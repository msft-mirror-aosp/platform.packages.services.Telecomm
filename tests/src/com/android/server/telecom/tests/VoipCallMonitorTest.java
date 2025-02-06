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

package com.android.server.telecom.tests;

import static android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA;
import static android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE;
import static android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
import static android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.ActivityManagerInternal;
import android.app.ForegroundServiceDelegationOptions;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.Person;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.UserHandle;
import android.service.notification.StatusBarNotification;
import android.telecom.PhoneAccountHandle;

import androidx.test.filters.SmallTest;

import com.android.server.telecom.Call;
import com.android.server.telecom.CallState;
import com.android.server.telecom.TelecomSystem;
import com.android.server.telecom.callsequencing.voip.VoipCallMonitor;

import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@RunWith(JUnit4.class)
public class VoipCallMonitorTest extends TelecomTestCase {
    private VoipCallMonitor mMonitor;
    private static final String NAME = "John Smith";
    private static final String PKG_NAME_1 = "telecom.voip.test1";
    private static final String PKG_NAME_2 = "telecom.voip.test2";
    private static final String CLS_NAME = "VoipActivity";
    private static final String ID_1 = "id1";
    public static final String CHANNEL_ID = "TelecomVoipAppChannelId";
    private static final UserHandle USER_HANDLE_1 = new UserHandle(1);
    private static final long TIMEOUT = 6000L;

    @Mock private Handler mHandler;
    @Mock private TelecomSystem.SyncRoot mLock;
    @Mock private ActivityManagerInternal mActivityManagerInternal;
    @Mock private IBinder mServiceConnection;

    private final PhoneAccountHandle mHandle1User1 = new PhoneAccountHandle(
            new ComponentName(PKG_NAME_1, CLS_NAME), ID_1, USER_HANDLE_1);
    private final PhoneAccountHandle mHandle2User1 = new PhoneAccountHandle(
            new ComponentName(PKG_NAME_2, CLS_NAME), ID_1, USER_HANDLE_1);

    @Override
    @Before
    public void setUp() throws Exception {
        super.setUp();
        mHandler = mock(Handler.class);
        mMonitor = new VoipCallMonitor(mContext, mHandler, mLock);
        mActivityManagerInternal = mock(ActivityManagerInternal.class);
        mMonitor.setActivityManagerInternal(mActivityManagerInternal);
        mMonitor.registerNotificationListener();
        when(mActivityManagerInternal.startForegroundServiceDelegate(any(
                ForegroundServiceDelegationOptions.class), any(ServiceConnection.class)))
                .thenReturn(true);
    }

    @Override
    @After
    public void tearDown() throws Exception {
        mMonitor.unregisterNotificationListener();
        super.tearDown();
    }

    /**
     * This test ensures VoipCallMonitor is passing the correct foregroundServiceTypes when starting
     * foreground service delegation on behalf of a client.
     */
    @SmallTest
    @Test
    public void testVerifyForegroundServiceTypesBeingPassedToActivityManager() {
        Call call = createTestCall("testCall", mHandle1User1);
        ArgumentCaptor<ForegroundServiceDelegationOptions> optionsCaptor =
                ArgumentCaptor.forClass(ForegroundServiceDelegationOptions.class);

        mMonitor.onCallAdded(call);

        verify(mActivityManagerInternal, timeout(TIMEOUT)).startForegroundServiceDelegate(
                optionsCaptor.capture(), any(ServiceConnection.class));

        assertEquals(FOREGROUND_SERVICE_TYPE_PHONE_CALL |
                        FOREGROUND_SERVICE_TYPE_MICROPHONE |
                        FOREGROUND_SERVICE_TYPE_CAMERA |
                        FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
                optionsCaptor.getValue().mForegroundServiceTypes);

        mMonitor.onCallRemoved(call);
    }

    /**
     * Tests that {@link VoipCallMonitor#stopFGSDelegation} does not throw a NullPointerException
     * when called on a transactional call that has not been tracked by the account to calls
     * mapping, and that no calls are made to ActivityManagerInternal.stopForegroundServiceDelegate.
     */
    @SmallTest
    @Test
    public void testStopFgsDelegationWithoutAnyTrackedCalls() {
        //GIVEN: a transactional call that has NOT been added to the monitor tracking
        Call call = createTestCall("testCall", mHandle1User1);
        ConcurrentHashMap<PhoneAccountHandle, Set<Call>> m = mMonitor.getAccountToCallsMapping();
        assertEquals(0, m.size());
        assertNull(m.get(mHandle1User1));

        // WHEN: stop is called on the transactional call
        mMonitor.stopFGSDelegation(call, mHandle1User1);

        // THEN: a NullPointerException should not be thrown at runtime
        verify(mActivityManagerInternal, times(0))
                .stopForegroundServiceDelegate(any(ServiceConnection.class));
        assertEquals(0, m.size());
        assertNull(m.get(mHandle1User1));
    }

    @SmallTest
    @Test
    public void testStartMonitorForOneCall() {
        // GIVEN - a single call and notification for a voip app
        Call call = createTestCall("testCall", mHandle1User1);
        StatusBarNotification sbn = createStatusBarNotificationFromHandle(mHandle1User1, 1);

        // WHEN - the Voip call is added and a notification is posted, verify FGS is gained
        addCallAndVerifyFgsIsGained(call);
        mMonitor.postNotification(sbn);
        assertNotificationTimeoutTriggered();
        assertFalse(mMonitor.getNewCallsMissingCallStyleNotificationQueue().contains(call));

        // THEN - when the Voip call is removed, verify that FGS is revoked for the app
        mMonitor.onCallRemoved(call);
        mMonitor.removeNotification(sbn);
        verify(mActivityManagerInternal, times(1))
                .stopForegroundServiceDelegate(any(ServiceConnection.class));
    }

    /**
     * Verify FGS is not lost if another call is ongoing for a Voip app
     */
    @SmallTest
    @Test
    public void testStopDelegation_SameApp() {
        // GIVEN - 2 consecutive calls for a single Voip app
        Call call1 = createTestCall("testCall1", mHandle1User1);
        StatusBarNotification sbn1 = createStatusBarNotificationFromHandle(mHandle1User1, 1);
        Call call2 = createTestCall("testCall2", mHandle1User1);
        StatusBarNotification sbn2 = createStatusBarNotificationFromHandle(mHandle1User1, 2);

        // WHEN - the second call is added and the first is disconnected
        // -- add the first all and post the corresponding notification
        addCallAndVerifyFgsIsGained(call1);
        assertTrue(mMonitor.getNewCallsMissingCallStyleNotificationQueue().contains(call1));
        mMonitor.postNotification(sbn1);
        assertNotificationTimeoutTriggered();
        assertFalse(mMonitor.getNewCallsMissingCallStyleNotificationQueue().contains(call1));
        // -- add the second call and post the corresponding notification
        mMonitor.onCallAdded(call2);
        assertTrue(mMonitor.getNewCallsMissingCallStyleNotificationQueue().contains(call2));
        mMonitor.postNotification(sbn2);
        assertNotificationTimeoutTriggered();
        assertFalse(mMonitor.getNewCallsMissingCallStyleNotificationQueue().contains(call2));

        // THEN - assert FGS is maintained for the process since there is still an ongoing call
        mMonitor.onCallRemoved(call1);
        mMonitor.removeNotification(sbn1);
        assertNotificationTimeoutTriggered();
        verify(mActivityManagerInternal, times(0))
                .stopForegroundServiceDelegate(any(ServiceConnection.class));
        // once all calls are removed, verify FGS is stopped
        mMonitor.onCallRemoved(call2);
        mMonitor.removeNotification(sbn2);
        verify(mActivityManagerInternal, times(1))
                .stopForegroundServiceDelegate(any(ServiceConnection.class));
    }

    @SmallTest
    @Test
    public void testMonitorForTwoCallsOnDifferentHandle() {
        Call call1 = createTestCall("testCall1", mHandle1User1);
        Call call2 = createTestCall("testCall2", mHandle2User1);
        IBinder service = mock(IBinder.class);

        ArgumentCaptor<ServiceConnection> connCaptor1 = ArgumentCaptor.forClass(
                ServiceConnection.class);
        ArgumentCaptor<ForegroundServiceDelegationOptions> optionsCaptor1 =
                ArgumentCaptor.forClass(ForegroundServiceDelegationOptions.class);
        mMonitor.onCallAdded(call1);
        verify(mActivityManagerInternal, timeout(TIMEOUT).times(1))
                .startForegroundServiceDelegate(optionsCaptor1.capture(), connCaptor1.capture());
        ForegroundServiceDelegationOptions options1 = optionsCaptor1.getValue();
        ServiceConnection conn1 = connCaptor1.getValue();
        conn1.onServiceConnected(mHandle1User1.getComponentName(), service);
        assertEquals(PKG_NAME_1, options1.getComponentName().getPackageName());

        ArgumentCaptor<ServiceConnection> connCaptor2 = ArgumentCaptor.forClass(
                ServiceConnection.class);
        ArgumentCaptor<ForegroundServiceDelegationOptions> optionsCaptor2 =
                ArgumentCaptor.forClass(ForegroundServiceDelegationOptions.class);
        mMonitor.onCallAdded(call2);
        verify(mActivityManagerInternal, timeout(TIMEOUT).times(2))
                .startForegroundServiceDelegate(optionsCaptor2.capture(), connCaptor2.capture());
        ForegroundServiceDelegationOptions options2 = optionsCaptor2.getValue();
        ServiceConnection conn2 = connCaptor2.getValue();
        conn2.onServiceConnected(mHandle2User1.getComponentName(), service);
        assertEquals(PKG_NAME_2, options2.getComponentName().getPackageName());

        mMonitor.onCallRemoved(call2);
        verify(mActivityManagerInternal).stopForegroundServiceDelegate(eq(conn2));
        mMonitor.onCallRemoved(call1);
        verify(mActivityManagerInternal).stopForegroundServiceDelegate(eq(conn1));
    }

    /**
     * Ensure an app loses foreground service delegation if the user dismisses the call style
     * notification or the app removes the notification.
     * Note: post the notification AFTER foreground service delegation is gained
     */
    @SmallTest
    @Test
    @Ignore("b/383403913") // when b/383403913 is fixed, remove the @Ignore
    public void testStopFgsIfCallNotificationIsRemoved_PostedAfterFgsIsGained() {
        // GIVEN
        StatusBarNotification sbn = createStatusBarNotificationFromHandle(mHandle1User1, 1);

        // WHEN
        // FGS is gained after the call is added to VoipCallMonitor
        ServiceConnection c = addCallAndVerifyFgsIsGained(createTestCall("1", mHandle1User1));
        // simulate an app posting a call style notification after FGS is gained
        mMonitor.postNotification(sbn);

        // THEN
        // shortly after posting the notification, simulate the user dismissing it
        mMonitor.removeNotification(sbn);
        // FGS should be removed once the notification is removed
        assertNotificationTimeoutTriggered();
        verify(mActivityManagerInternal, times(1)).stopForegroundServiceDelegate(c);
    }


    /**
     * Tests the behavior of foreground service (FGS) delegation for a VoIP app during a scenario
     * with two consecutive calls.  In this scenario, the first call is disconnected shortly after
     * being created but the second call continues.  The apps foreground service should be
     * maintained.
     *
     * GIVEN: Two calls (call1 and call2) are created for the same VoIP app.
     * WHEN:
     *  - call1 is added, starting the FGS.
     *  - call2 is added immediately after.
     *  - call1 is removed.
     *  - call1 notification is finally posted (late)
     *  - call1 notification is removed shortly after since the call was disconnected
     * THEN:
     *  - Verifies that the FGS is NOT stopped while call2 is still active.
     *  - Verifies that the FGS IS stopped after call2 is removed and its notification is gone.
     */
    @SmallTest
    @Test
    public void test2CallsInQuickSuccession() {
        // GIVEN - 2 consecutive calls for a single Voip app
        Call call1 = createTestCall("testCall1", mHandle1User1);
        StatusBarNotification sbn1 = createStatusBarNotificationFromHandle(mHandle1User1, 1);
        Call call2 = createTestCall("testCall2", mHandle1User1);
        StatusBarNotification sbn2 = createStatusBarNotificationFromHandle(mHandle1User1, 2);

        // WHEN - add the calls to the VoipCallMonitor class
        addCallAndVerifyFgsIsGained(call1);
        mMonitor.onCallAdded(call2);
        assertTrue(mMonitor.getNewCallsMissingCallStyleNotificationQueue().contains(call1));
        assertTrue(mMonitor.getNewCallsMissingCallStyleNotificationQueue().contains(call2));
        // -- mock the app disconnecting the first
        mMonitor.onCallRemoved(call1);
        // Shortly after, simulate the notification updates coming in to the class
        // -- post and remove the first call-style notification
        mMonitor.postNotification(sbn1);
        assertFalse(mMonitor.getNewCallsMissingCallStyleNotificationQueue().contains(call1));
        mMonitor.removeNotification(sbn1);
        assertNotificationTimeoutTriggered();

        // -- keep the second notification up since the call will continue
        mMonitor.postNotification(sbn2);
        assertFalse(mMonitor.getNewCallsMissingCallStyleNotificationQueue().contains(call2));

        // THEN - assert FGS is maintained for the process since there is still an ongoing call
        assertNotificationTimeoutTriggered();
        verify(mActivityManagerInternal, times(0))
                .stopForegroundServiceDelegate(any(ServiceConnection.class));

        // once all calls are removed, verify FGS is stopped
        mMonitor.onCallRemoved(call2);
        mMonitor.removeNotification(sbn2);
        verify(mActivityManagerInternal, timeout(TIMEOUT).times(1))
                .stopForegroundServiceDelegate(any(ServiceConnection.class));
    }

    /**
     * Helpers for testing
     */

    private Call createTestCall(String id, PhoneAccountHandle handle) {
        Call call = mock(Call.class);
        when(call.getTargetPhoneAccount()).thenReturn(handle);
        when(call.isTransactionalCall()).thenReturn(true);
        when(call.getExtras()).thenReturn(new Bundle());
        when(call.getId()).thenReturn(id);
        when(call.getCallingPackageIdentity()).thenReturn(new Call.CallingPackageIdentity());
        when(call.getState()).thenReturn(CallState.ACTIVE);
        return call;
    }

    private Notification createCallStyleNotification() {
        PendingIntent pendingOngoingIntent = PendingIntent.getActivity(mContext, 0,
                new Intent(""), PendingIntent.FLAG_IMMUTABLE);

        return new Notification.Builder(mContext,
                CHANNEL_ID)
                .setStyle(Notification.CallStyle.forOngoingCall(
                        new Person.Builder().setName(NAME).setImportant(true).build(),
                        pendingOngoingIntent)
                )
                .setFullScreenIntent(pendingOngoingIntent, true)
                .build();
    }

    private StatusBarNotification createStatusBarNotificationFromHandle(
            PhoneAccountHandle handle, int id) {
        return new StatusBarNotification(
                handle.getComponentName().getPackageName(), "", id, "", 0, 0,
                createCallStyleNotification(), handle.getUserHandle(), "", 0);
    }

    private ServiceConnection addCallAndVerifyFgsIsGained(Call call) {
        ArgumentCaptor<ServiceConnection> captor = ArgumentCaptor.forClass(ServiceConnection.class);
        // add the call to the VoipCallMonitor under test which will start FGS
        mMonitor.onCallAdded(call);
        // FGS should be granted within the timeout
        verify(mActivityManagerInternal, timeout(TIMEOUT))
                .startForegroundServiceDelegate(any(
                                ForegroundServiceDelegationOptions.class),
                        captor.capture());
        // onServiceConnected must be called in order for VoipCallMonitor to start monitoring for
        // a notification before the timeout expires
        ServiceConnection serviceConnection = captor.getValue();
        serviceConnection.onServiceConnected(
                call.getTargetPhoneAccount().getComponentName(),
                mServiceConnection);
        return serviceConnection;
    }

    /**
     * Verifies that a delayed runnable is posted to the handler to handle the notification timeout.
     * This also executes the captured runnable to simulate the timeout occurring.
     */
    private void assertNotificationTimeoutTriggered() {
        ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(mHandler, atLeastOnce()).postDelayed(
                runnableCaptor.capture(),
                eq(VoipCallMonitor.NOTIFICATION_NOT_POSTED_IN_TIME_TIMEOUT));
        Runnable capturedRunnable = runnableCaptor.getValue();
        capturedRunnable.run();
    }
}
