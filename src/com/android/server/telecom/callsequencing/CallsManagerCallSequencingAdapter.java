/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.server.telecom.callsequencing;

import android.os.Bundle;
import android.os.Handler;
import android.os.OutcomeReceiver;
import android.telecom.CallAttributes;
import android.telecom.CallException;
import android.telecom.Log;

import com.android.server.telecom.Call;
import com.android.server.telecom.CallsManager;
import com.android.server.telecom.LoggedHandlerExecutor;
import com.android.server.telecom.callsequencing.voip.OutgoingCallTransaction;
import com.android.server.telecom.flags.FeatureFlags;

import java.util.concurrent.CompletableFuture;

/**
 * Abstraction layer for CallsManager to perform call sequencing operations through CallsManager
 * or CallSequencingController, which is controlled by {@link FeatureFlags#enableCallSequencing()}.
 */
public class CallsManagerCallSequencingAdapter {

    private final CallsManager mCallsManager;
    private final CallSequencingController mSequencingController;
    private final Handler mHandler;
    private final FeatureFlags mFeatureFlags;
    private final boolean mIsCallSequencingEnabled;

    public CallsManagerCallSequencingAdapter(CallsManager callsManager,
            CallSequencingController sequencingController,
            FeatureFlags featureFlags) {
        mCallsManager = callsManager;
        mSequencingController = sequencingController;
        mHandler = sequencingController.getHandler();
        mFeatureFlags = featureFlags;
        mIsCallSequencingEnabled = featureFlags.enableCallSequencing();
    }

    /**
     * Helps create the transaction representing the outgoing transactional call. For outgoing
     * calls, there can be more than one transaction that will need to complete when
     * mIsCallSequencingEnabled is true. Otherwise, rely on the old behavior of creating an
     * {@link OutgoingCallTransaction}.
     * @param callAttributes The call attributes associated with the call.
     * @param extras The extras that are associated with the call.
     * @param callingPackage The calling package representing where the request was invoked from.
     * @return The {@link CompletableFuture<CallTransaction>} that encompasses the request to
     *         place/receive the transactional call.
     */
    public CompletableFuture<CallTransaction> createTransactionalOutgoingCall(String callId,
            CallAttributes callAttributes, Bundle extras, String callingPackage) {
        return mIsCallSequencingEnabled
                ? mSequencingController.createTransactionalOutgoingCall(callId,
                        callAttributes, extras, callingPackage)
                : CompletableFuture.completedFuture(new OutgoingCallTransaction(callId,
                        mCallsManager.getContext(), callAttributes, mCallsManager, extras,
                        mFeatureFlags));
    }

    /**
     * Conditionally try to answer the call depending on whether call sequencing
     * (mIsCallSequencingEnabled) is enabled.
     * @param incomingCall The incoming call that should be answered.
     * @param videoState The video state configuration associated with the call.
     */
    public void answerCall(Call incomingCall, int videoState) {
        if (mIsCallSequencingEnabled && !incomingCall.isTransactionalCall()) {
            mSequencingController.answerCall(incomingCall, videoState);
        } else {
            mCallsManager.answerCallOld(incomingCall, videoState);
        }
    }

    /**
     * Conditionally attempt to unhold the provided call depending on whether call sequencing
     * (mIsCallSequencingEnabled) is enabled.
     * @param call The call to unhold.
     */
    public void unholdCall(Call call) {
        if (mIsCallSequencingEnabled) {
            mSequencingController.unholdCall(call);
        } else {
            mCallsManager.unholdCallOld(call);
        }
    }

    /**
     * Conditionally attempt to hold the provided call depending on whether call sequencing
     * (mIsCallSequencingEnabled) is enabled.
     * @param call The call to hold.
     */
    public void holdCall(Call call) {
        // Sequencing already taken care of for CSW/TSW in Call class.
        CompletableFuture<Boolean> holdFuture = call.hold();
        if (mIsCallSequencingEnabled) {
            logFutureResultTransaction(holdFuture, "holdCall", "CMCSA.hC",
                    "hold call transaction succeeded.", "hold call transaction failed.");
        }
    }

    /**
     * Conditionally disconnect the provided call depending on whether call sequencing
     * (mIsCallSequencingEnabled) is enabled. The sequencing functionality ensures that we wait for
     * the call to be disconnected as signalled by CSW/TSW as to ensure that subsequent call
     * operations don't overlap with this one.
     * @param call The call to disconnect.
     */
    public void disconnectCall(Call call) {
        int previousState = call.getState();
        if (mIsCallSequencingEnabled) {
            mSequencingController.disconnectCall(call, previousState);
        } else {
            mCallsManager.disconnectCallOld(call, previousState);
        }
    }

    /**
     * Conditionally make room for the outgoing call depending on whether call sequencing
     * (mIsCallSequencingEnabled) is enabled.
     * @param isEmergency Indicator of whether the call is an emergency call.
     * @param call The call to potentially make room for.
     * @return {@link CompletableFuture} which will contain the result of the transaction if room
     *         was able to made for the call.
     */
    public CompletableFuture<Boolean> makeRoomForOutgoingCall(boolean isEmergency, Call call) {
        if (mIsCallSequencingEnabled) {
            return mSequencingController.makeRoomForOutgoingCall(isEmergency, call);
        } else {
            return isEmergency
                    ? CompletableFuture.completedFuture(
                            mCallsManager.makeRoomForOutgoingEmergencyCall(call))
                    : CompletableFuture.completedFuture(
                            mCallsManager.makeRoomForOutgoingCall(call));
        }
    }

    /**
     * Attempts to mark the self-managed call as active by first holding the active call and then
     * requesting call focus for the self-managed call.
     * @param call The self-managed call to set active
     */
    public void markCallAsActiveSelfManagedCall(Call call) {
        if (mIsCallSequencingEnabled) {
            mSequencingController.handleSetSelfManagedCallActive(call);
        } else {
            mCallsManager.holdActiveCallForNewCall(call);
            mCallsManager.requestActionSetActiveCall(call,
                    "active set explicitly for self-managed");
        }
    }

    /**
     * Attempts to hold the active call for transactional call cases with call sequencing support
     * if mIsCallSequencingEnabled is true.
     * @param newCall The new (transactional) call that's waiting to go active.
     * @param activeCall The currently active call.
     * @param callback The callback to report the result of the aforementioned hold transaction.
     * @return {@code CompletableFuture} indicating the result of holding the active call.
     */
    public void transactionHoldPotentialActiveCallForNewCall(Call newCall,
            Call activeCall, OutcomeReceiver<Boolean, CallException> callback) {
        if (mIsCallSequencingEnabled) {
            mSequencingController.transactionHoldPotentialActiveCallForNewCallSequencing(
                    newCall, callback);
        } else {
            mCallsManager.transactionHoldPotentialActiveCallForNewCallOld(newCall,
                    activeCall, callback);
        }
    }

    /**
     * Generic helper to log the result of the {@link CompletableFuture} containing the transactions
     * that are being processed in the context of call sequencing.
     * @param future The {@link CompletableFuture} encompassing the transaction that's being
     *               computed.
     * @param methodName The method name to describe the type of transaction being processed.
     * @param sessionName The session name to identify the log.
     * @param successMsg The message to be logged if the transaction succeeds.
     * @param failureMsg The message to be logged if the transaction fails.
     */
    public void logFutureResultTransaction(CompletableFuture<Boolean> future, String methodName,
            String sessionName, String successMsg, String failureMsg) {
        future.thenApplyAsync((result) -> {
            StringBuilder msg = new StringBuilder(methodName).append(": ");
            msg.append(result ? successMsg : failureMsg);
            Log.i(this, String.valueOf(msg));
            return CompletableFuture.completedFuture(result);
        }, new LoggedHandlerExecutor(mHandler, sessionName, mCallsManager.getLock()));
    }

    public Handler getHandler() {
        return mHandler;
    }
}
