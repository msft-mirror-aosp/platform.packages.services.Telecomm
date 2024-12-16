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

import static android.Manifest.permission.CALL_PRIVILEGED;
import static android.telecom.CallAttributes.CALL_CAPABILITIES_KEY;
import static android.telecom.CallAttributes.DISPLAY_NAME_KEY;
import static android.telecom.CallException.CODE_CALL_NOT_PERMITTED_AT_PRESENT_TIME;

import static com.android.server.telecom.callsequencing.voip.VideoStateTranslation
        .TransactionalVideoStateToVideoProfileState;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.telecom.CallAttributes;
import android.telecom.TelecomManager;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.telecom.Call;
import com.android.server.telecom.CallsManager;
import com.android.server.telecom.LoggedHandlerExecutor;
import com.android.server.telecom.callsequencing.CallTransaction;
import com.android.server.telecom.callsequencing.CallTransactionResult;
import com.android.server.telecom.flags.FeatureFlags;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public class OutgoingCallTransaction extends CallTransaction {

    private static final String TAG = OutgoingCallTransaction.class.getSimpleName();
    private final String mCallId;
    private final Context mContext;
    private final String mCallingPackage;
    private final CallAttributes mCallAttributes;
    private final CallsManager mCallsManager;
    private final Bundle mExtras;
    private FeatureFlags mFeatureFlags;

    public void setFeatureFlags(FeatureFlags featureFlags) {
        mFeatureFlags = featureFlags;
    }

    public OutgoingCallTransaction(String callId, Context context, CallAttributes callAttributes,
            CallsManager callsManager, Bundle extras, FeatureFlags featureFlags) {
        super(callsManager.getLock());
        mCallId = callId;
        mContext = context;
        mCallAttributes = callAttributes;
        mCallsManager = callsManager;
        mExtras = extras;
        mCallingPackage = mContext.getOpPackageName();
        mFeatureFlags = featureFlags;
    }

    public OutgoingCallTransaction(String callId, Context context, CallAttributes callAttributes,
            CallsManager callsManager, FeatureFlags featureFlags) {
        this(callId, context, callAttributes, callsManager, new Bundle(), featureFlags);
    }

    @Override
    public CompletionStage<CallTransactionResult> processTransaction(Void v) {
        Log.d(TAG, "processTransaction");

        final boolean hasCallPrivilegedPermission = mContext.checkCallingPermission(
                CALL_PRIVILEGED) == PackageManager.PERMISSION_GRANTED;

        final Intent intent = new Intent(hasCallPrivilegedPermission ?
                Intent.ACTION_CALL_PRIVILEGED : Intent.ACTION_CALL, mCallAttributes.getAddress());

        if (mCallsManager.isOutgoingCallPermitted(mCallAttributes.getPhoneAccountHandle())) {
            Log.d(TAG, "processTransaction: outgoing call permitted");

            CompletableFuture<Call> callFuture =
                    mCallsManager.startOutgoingCall(mCallAttributes.getAddress(),
                            mCallAttributes.getPhoneAccountHandle(),
                            generateExtras(mCallId, mExtras, mCallAttributes, mFeatureFlags),
                            mCallAttributes.getPhoneAccountHandle().getUserHandle(),
                            intent,
                            mCallingPackage);

            if (callFuture == null) {
                return CompletableFuture.completedFuture(
                        new CallTransactionResult(
                                CODE_CALL_NOT_PERMITTED_AT_PRESENT_TIME,
                                "incoming call not permitted at the current time"));
            }

            return callFuture.thenComposeAsync(
                    (call) -> processOutgoingCallTransactionHelper(call, TAG,
                            mCallsManager, mFeatureFlags)
                    , new LoggedHandlerExecutor(mHandler, "OCT.pT", null));
        } else {
            return CompletableFuture.completedFuture(
                    new CallTransactionResult(
                            CODE_CALL_NOT_PERMITTED_AT_PRESENT_TIME,
                            "incoming call not permitted at the current time"));

        }
    }

    @VisibleForTesting
    public static Bundle generateExtras(String callId, Bundle extras,
            CallAttributes callAttributes, FeatureFlags featureFlags) {
        extras.setDefusable(true);
        extras.putString(TelecomManager.TRANSACTION_CALL_ID_KEY, callId);
        extras.putInt(CALL_CAPABILITIES_KEY, callAttributes.getCallCapabilities());
        if (featureFlags.transactionalVideoState()) {
            // Transactional calls need to remap the CallAttributes video state to the existing
            // VideoProfile for consistency.
            extras.putInt(TelecomManager.EXTRA_START_CALL_WITH_VIDEO_STATE,
                    TransactionalVideoStateToVideoProfileState(callAttributes.getCallType()));
        } else {
            extras.putInt(TelecomManager.EXTRA_START_CALL_WITH_VIDEO_STATE,
                    callAttributes.getCallType());
        }
        extras.putCharSequence(DISPLAY_NAME_KEY, callAttributes.getDisplayName());
        return extras;
    }

    public static CompletableFuture<CallTransactionResult> processOutgoingCallTransactionHelper(
            Call call, String tag, CallsManager callsManager, FeatureFlags featureFlags) {
        Log.d(tag, "processTransaction: completing future");

        if (call == null) {
            Log.d(tag, "processTransaction: call is null");
            return CompletableFuture.completedFuture(
                    new CallTransactionResult(
                            CODE_CALL_NOT_PERMITTED_AT_PRESENT_TIME,
                            "call could not be created at this time"));
        } else {
            Log.d(tag, "processTransaction: call done. id=" + call.getId());
        }

        if (featureFlags.disconnectSelfManagedStuckStartupCalls()) {
            // set to dialing so the CallAnomalyWatchdog gives the VoIP calls 1
            // minute to timeout rather than 5 seconds.
            callsManager.markCallAsDialing(call);
        }

        return CompletableFuture.completedFuture(
                new CallTransactionResult(
                        CallTransactionResult.RESULT_SUCCEED,
                        call, null, true));
    }
}
