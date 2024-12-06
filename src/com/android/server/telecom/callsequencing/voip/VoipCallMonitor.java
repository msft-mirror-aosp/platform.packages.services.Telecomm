/*
 * Copyright (C) 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.telecom.callsequencing.voip;

import static android.app.ForegroundServiceDelegationOptions.DELEGATION_SERVICE_PHONE_CALL;
import static android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA;
import static android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE;
import static android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
import static android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL;

import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.app.ForegroundServiceDelegationOptions;
import android.app.Notification;
import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.telecom.Log;
import android.telecom.PhoneAccountHandle;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.LocalServices;
import com.android.server.telecom.Call;
import com.android.server.telecom.CallsManagerListenerBase;
import com.android.server.telecom.LogUtils;
import com.android.server.telecom.TelecomSystem;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class VoipCallMonitor extends CallsManagerListenerBase {
    private static final long NOTIFICATION_NOT_POSTED_IN_TIME_TIMEOUT = 5000L;
    private static final long NOTIFICATION_REMOVED_BUT_CALL_IS_STILL_ONGOING_TIMEOUT = 5000L;
    private static final String DElIMITER = "#";
    // This list caches calls that are added to the VoipCallMonitor and need an accompanying
    // Call-Style Notification!
    private final List<Call> mNewCallsMissingCallStyleNotification;
    private final ConcurrentHashMap<String, Call> mNotificationIdToCall;
    private final ConcurrentHashMap<PhoneAccountHandle, Set<Call>> mAccountHandleToCallMap;
    private final ConcurrentHashMap<PhoneAccountHandle, ServiceConnection> mServices;
    private ActivityManagerInternal mActivityManagerInternal;
    private final NotificationListenerService mNotificationListener;
    private final Handler mHandlerForClass;
    private final Context mContext;
    private final TelecomSystem.SyncRoot mSyncRoot;

    public VoipCallMonitor(Context context, TelecomSystem.SyncRoot lock) {
        mSyncRoot = lock;
        mContext = context;
        mHandlerForClass = new Handler(Looper.getMainLooper());
        mNewCallsMissingCallStyleNotification = new ArrayList<>();
        mNotificationIdToCall = new ConcurrentHashMap<>();
        mServices = new ConcurrentHashMap<>();
        mAccountHandleToCallMap = new ConcurrentHashMap<>();
        mActivityManagerInternal = LocalServices.getService(ActivityManagerInternal.class);
        mNotificationListener = new NotificationListenerService() {
            @Override
            public void onNotificationPosted(StatusBarNotification sbn) {
                if (isCallStyleNotification(sbn)) {
                    Log.i(this, "onNotificationPosted: sbn=[%s]", sbn);
                    boolean foundCallForNotification = false;
                    // Case 1: Call added to this class (via onCallAdded) BEFORE Call-Style
                    //         Notification is posted by the app (only supported scenario)
                    // --> remove the newly added call from
                    //     mNewCallsMissingCallStyleNotification so FGS is not revoked.
                    for (Call call : new ArrayList<>(mNewCallsMissingCallStyleNotification)) {
                        if (isNotificationForCall(sbn, call)) {
                            Log.i(this, "onNotificationPosted: found a pending "
                                    + "call=[%s] for sbn.id=[%s]", call, sbn.getId());
                            mNotificationIdToCall.put(
                                    getNotificationIdToCallKey(sbn),
                                    call);
                            removeFromNotificationTracking(call);
                            foundCallForNotification = true;
                            break;
                        }
                    }
                    // Case 2: Call-Style Notification was posted BEFORE the Call was added
                    // --> Currently do not support this
                    // Case 3: Call-Style Notification was updated (ex. incoming -> ongoing)
                    // --> do nothing
                    if (!foundCallForNotification) {
                        Log.i(this, "onNotificationPosted: could not find a call for the"
                                + " sbn.id=[%s]. This could mean the notification posted"
                                + " BEFORE the call is added (error) or it's an update from"
                                + " incoming to ongoing (ok).", sbn.getId());
                    }
                }
            }

            @Override
            public void onNotificationRemoved(StatusBarNotification sbn) {
                if (!isCallStyleNotification(sbn)) {
                    return;
                }
                Log.i(this, "onNotificationRemoved: Call-Style notification=[%s] removed", sbn);
                Call call = getCallFromStatusBarNotificationId(sbn);
                if (call != null) {
                    PhoneAccountHandle handle = getTargetPhoneAccount(call);
                    if (!isCallDisconnected(call)) {
                        mHandlerForClass.postDelayed(() -> {
                            if (isCallStillBeingTracked(call)) {
                                stopFGSDelegation(call, handle);
                            }
                        }, NOTIFICATION_REMOVED_BUT_CALL_IS_STILL_ONGOING_TIMEOUT);
                    }
                    mNotificationIdToCall.remove(getNotificationIdToCallKey(sbn));
                }
            }

            // TODO:: b/383403913 fix gap in matching notifications
            private boolean isNotificationForCall(StatusBarNotification sbn, Call call) {
                PhoneAccountHandle callHandle = getTargetPhoneAccount(call);
                if (callHandle == null) {
                    return false;
                }
                String callPackageName = VoipCallMonitor.this.getPackageName(call);
                return Objects.equals(sbn.getUser(), callHandle.getUserHandle()) &&
                        Objects.equals(sbn.getPackageName(), callPackageName);
            }

            private Call getCallFromStatusBarNotificationId(StatusBarNotification sbn) {
                if (mNotificationIdToCall.size() == 0) {
                    return null;
                }
                String targetKey = getNotificationIdToCallKey(sbn);
                for (Map.Entry<String, Call> entry : mNotificationIdToCall.entrySet()) {
                    if (targetKey.equals(entry.getKey())) {
                        return entry.getValue();
                    }
                }
                return null;
            }

            private String getNotificationIdToCallKey(StatusBarNotification sbn) {
                return sbn.getPackageName() + DElIMITER + sbn.getId();
            }

            private boolean isCallStyleNotification(StatusBarNotification sbn) {
                return sbn.getNotification().isStyle(Notification.CallStyle.class);
            }

            private boolean isCallStillBeingTracked(Call call) {
                PhoneAccountHandle handle = getTargetPhoneAccount(call);
                if (call == null || handle == null) {
                    return false;
                }
                return mAccountHandleToCallMap
                        .computeIfAbsent(handle, k -> new HashSet<>())
                        .contains(call);
            }
        };

    }

    public void registerNotificationListener() {
        try {
            mNotificationListener.registerAsSystemService(mContext,
                    new ComponentName(this.getClass().getPackageName(),
                            this.getClass().getCanonicalName()), ActivityManager.getCurrentUser());
        } catch (RemoteException e) {
            Log.e(this, e, "Cannot register notification listener");
        }
    }

    public void unregisterNotificationListener() {
        try {
            mNotificationListener.unregisterAsSystemService();
        } catch (RemoteException e) {
            Log.e(this, e, "Cannot unregister notification listener");
        }
    }

    @Override
    public void onCallAdded(Call call) {
        PhoneAccountHandle handle = getTargetPhoneAccount(call);
        if (!isTransactional(call) || handle == null) {
            return;
        }
        int callingPid = getCallingPackagePid(call);
        int callingUid = getCallingPackageUid(call);
        mAccountHandleToCallMap
                .computeIfAbsent(handle, k -> new HashSet<>())
                .add(call);
        maybeStartFGSDelegation(callingPid, callingUid, handle, call);
    }

    @Override
    public void onCallRemoved(Call call) {
        PhoneAccountHandle handle = getTargetPhoneAccount(call);
        if (!isTransactional(call) || handle == null) {
            return;
        }
        removeFromNotificationTracking(call);
        Set<Call> ongoingCalls = mAccountHandleToCallMap
                .computeIfAbsent(handle, k -> new HashSet<>());
        ongoingCalls.remove(call);
        Log.d(this, "onCallRemoved: callList.size=[%d]", ongoingCalls.size());
        if (ongoingCalls.isEmpty()) {
            stopFGSDelegation(call, handle);
        } else {
            Log.addEvent(call, LogUtils.Events.MAINTAINING_FGS_DELEGATION);
        }
    }

    private void maybeStartFGSDelegation(int pid, int uid, PhoneAccountHandle handle, Call call) {
        Log.i(this, "maybeStartFGSDelegation for call=[%s]", call);
        if (mActivityManagerInternal != null) {
            if (mServices.containsKey(handle)) {
                Log.addEvent(call, LogUtils.Events.ALREADY_HAS_FGS_DELEGATION);
                startMonitoringNotification(call, handle);
                return;
            }
            ForegroundServiceDelegationOptions options = new ForegroundServiceDelegationOptions(pid,
                    uid, handle.getComponentName().getPackageName(), null /* clientAppThread */,
                    false /* isSticky */, String.valueOf(handle.hashCode()),
                    FOREGROUND_SERVICE_TYPE_PHONE_CALL |
                            FOREGROUND_SERVICE_TYPE_MICROPHONE |
                            FOREGROUND_SERVICE_TYPE_CAMERA |
                            FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE /* foregroundServiceTypes */,
                    DELEGATION_SERVICE_PHONE_CALL /* delegationService */);
            ServiceConnection fgsConnection = new ServiceConnection() {
                @Override
                public void onServiceConnected(ComponentName name, IBinder service) {
                    Log.addEvent(call, LogUtils.Events.GAINED_FGS_DELEGATION);
                    mServices.put(handle, this);
                    startMonitoringNotification(call, handle);
                }

                @Override
                public void onServiceDisconnected(ComponentName name) {
                    Log.addEvent(call, LogUtils.Events.LOST_FGS_DELEGATION);
                    mServices.remove(handle);
                }
            };
            try {
                if (mActivityManagerInternal
                        .startForegroundServiceDelegate(options, fgsConnection)) {
                    Log.i(this, "maybeStartFGSDelegation: startForegroundServiceDelegate success");
                } else {
                    Log.addEvent(call, LogUtils.Events.GAIN_FGS_DELEGATION_FAILED);
                }
            } catch (Exception e) {
                Log.i(this, "startForegroundServiceDelegate failed due to: " + e);
            }
        }
    }

    @VisibleForTesting
    public void stopFGSDelegation(Call call, PhoneAccountHandle handle) {
        Log.i(this, "stopFGSDelegation of call=[%s]", call);
        if (handle == null) {
            return;
        }
        // In the event this class is waiting for any new calls to post a notification, cleanup
        for (Call ongoingCall :  new ArrayList<>(mAccountHandleToCallMap.get(handle))) {
            removeFromNotificationTracking(ongoingCall);
        }
        if (mActivityManagerInternal != null) {
            ServiceConnection fgsConnection = mServices.get(handle);
            if (fgsConnection != null) {
                Log.i(this, "stopFGSDelegation: requesting stopForegroundServiceDelegate");
                mActivityManagerInternal.stopForegroundServiceDelegate(fgsConnection);
            }
        }
        mAccountHandleToCallMap.remove(handle);
    }

    private void startMonitoringNotification(Call call, PhoneAccountHandle handle) {
        String packageName = getPackageName(call);
        String callId = getCallId(call);
        // Wait 5 seconds for a CallStyle notification to be posted for the call.
        // If the Call-Style Notification is not posted, FGS delegation needs to be revoked!
        Log.i(this, "startMonitoringNotification: starting timeout for call.id=[%s]", callId);
        addToNotificationTracking(call);
        // If no notification is posted, stop foreground service delegation!
        mHandlerForClass.postDelayed(() -> {
            if (isStillMissingNotification(call)) {
                Log.i(this, "startMonitoringNotification: A Call-Style-Notification"
                        + " for voip-call=[%s] hasn't posted in time,"
                        + " stopping delegation for app=[%s].", call, packageName);
                stopFGSDelegation(call, handle);
            } else {
                Log.i(this, "startMonitoringNotification: found a call-style"
                        + " notification for call.id[%s] at timeout", callId);
            }
        }, NOTIFICATION_NOT_POSTED_IN_TIME_TIMEOUT);
    }

    /**
     * Helpers
     */

    private void addToNotificationTracking(Call call) {
        synchronized (mNewCallsMissingCallStyleNotification) {
            mNewCallsMissingCallStyleNotification.add(call);
        }
    }

    private boolean isStillMissingNotification(Call call) {
        synchronized (mNewCallsMissingCallStyleNotification) {
           return mNewCallsMissingCallStyleNotification.contains(call);
        }
    }

    private void removeFromNotificationTracking(Call call) {
        synchronized (mNewCallsMissingCallStyleNotification) {
            mNewCallsMissingCallStyleNotification.remove(call);
        }
    }

    private PhoneAccountHandle getTargetPhoneAccount(Call call) {
        synchronized (mSyncRoot) {
            if (call == null) {
                return null;
            } else {
                return call.getTargetPhoneAccount();
            }
        }
    }

    private int getCallingPackageUid(Call call) {
        synchronized (mSyncRoot) {
            if (call == null) {
                return -1;
            } else {
                return call.getCallingPackageIdentity().mCallingPackageUid;
            }
        }
    }

    private int getCallingPackagePid(Call call) {
        synchronized (mSyncRoot) {
            if (call == null) {
                return -1;
            } else {
                return call.getCallingPackageIdentity().mCallingPackagePid;
            }
        }
    }

    private String getCallId(Call call) {
        synchronized (mSyncRoot) {
            if (call == null) {
                return "";
            } else {
                return call.getId();
            }
        }
    }

    private boolean isCallDisconnected(Call call) {
        synchronized (mSyncRoot) {
            if (call == null) {
                return true;
            } else {
                return call.isDisconnected();
            }
        }
    }

    private boolean isTransactional(Call call) {
        synchronized (mSyncRoot) {
            if (call == null) {
                return false;
            } else {
                return call.isTransactionalCall();
            }
        }
    }

    private String getPackageName(Call call) {
        String pn = "";
        try {
            pn = getTargetPhoneAccount(call).getComponentName().getPackageName();
        } catch (Exception e) {
            // fall through
        }
        return pn;
    }

    @VisibleForTesting
    public void setActivityManagerInternal(ActivityManagerInternal ami) {
        mActivityManagerInternal = ami;
    }

    @VisibleForTesting
    public void postNotification(StatusBarNotification statusBarNotification) {
        mNotificationListener.onNotificationPosted(statusBarNotification);
    }

    @VisibleForTesting
    public void removeNotification(StatusBarNotification statusBarNotification) {
        mNotificationListener.onNotificationRemoved(statusBarNotification);
    }

    public boolean hasForegroundServiceDelegation(PhoneAccountHandle handle) {
        boolean hasFgs = mServices.containsKey(handle);
        Log.i(this, "hasForegroundServiceDelegation: handle=[%s], hasFgs=[%b]", handle, hasFgs);
        return hasFgs;
    }
}
