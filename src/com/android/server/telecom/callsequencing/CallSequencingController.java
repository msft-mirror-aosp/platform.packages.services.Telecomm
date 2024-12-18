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

import static android.Manifest.permission.CALL_PRIVILEGED;

import static com.android.server.telecom.CallsManager.LIVE_CALL_STUCK_CONNECTING_EMERGENCY_ERROR_MSG;
import static com.android.server.telecom.CallsManager.LIVE_CALL_STUCK_CONNECTING_EMERGENCY_ERROR_UUID;
import static com.android.server.telecom.CallsManager.OUTGOING_CALL_STATES;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.OutcomeReceiver;
import android.telecom.CallAttributes;
import android.telecom.CallException;
import android.telecom.DisconnectCause;
import android.telecom.Log;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telephony.AnomalyReporter;

import com.android.server.telecom.Call;
import com.android.server.telecom.CallState;
import com.android.server.telecom.CallsManager;
import com.android.server.telecom.LogUtils;
import com.android.server.telecom.LoggedHandlerExecutor;
import com.android.server.telecom.callsequencing.voip.OutgoingCallTransaction;
import com.android.server.telecom.callsequencing.voip.OutgoingCallTransactionSequencing;
import com.android.server.telecom.flags.FeatureFlags;
import com.android.server.telecom.stats.CallFailureCause;

import java.util.concurrent.CompletableFuture;

/**
 * Controls the sequencing between calls when moving between the user ACTIVE (RINGING/ACTIVE) and
 * user INACTIVE (INCOMING/HOLD/DISCONNECTED) states. This controller is gated by the
 * {@link FeatureFlags#enableCallSequencing()} flag. Call state changes are verified on a
 * transactional basis where each operation is verified step by step for cross-phone account calls
 * or just for the focus call in the case of processing calls on the same phone account.
 */
public class CallSequencingController {
    private final CallsManager mCallsManager;
    private final Handler mHandler;
    private final Context mContext;
    private final FeatureFlags mFeatureFlags;
    private boolean mProcessingCallSequencing;

    public CallSequencingController(CallsManager callsManager, Context context,
            FeatureFlags featureFlags) {
        mCallsManager = callsManager;
        HandlerThread handlerThread = new HandlerThread(this.toString());
        handlerThread.start();
        mHandler = new Handler(handlerThread.getLooper());
        mProcessingCallSequencing = false;
        mFeatureFlags = featureFlags;
        mContext = context;
    }

    /**
     * Creates the outgoing call transaction given that call sequencing is enabled. Two separate
     * transactions are being tracked here; one is if room needs to be made for the outgoing call
     * and another to verify that the new call was placed. We need to ensure that the transaction
     * to make room for the outgoing call is processed beforehand (i.e. see
     * {@link OutgoingCallTransaction}.
     * @param callAttributes The call attributes associated with the call.
     * @param extras The extras that are associated with the call.
     * @param callingPackage The calling package representing where the request was invoked from.
     * @return The {@link CompletableFuture<CallTransaction>} that encompasses the request to
     *         place/receive the transactional call.
     */
    public CompletableFuture<CallTransaction> createTransactionalOutgoingCall(String callId,
            CallAttributes callAttributes, Bundle extras, String callingPackage) {
        PhoneAccountHandle requestedAccountHandle = callAttributes.getPhoneAccountHandle();
        Uri address = callAttributes.getAddress();
        if (mCallsManager.isOutgoingCallPermitted(requestedAccountHandle)) {
            Log.d(this, "createTransactionalOutgoingCall: outgoing call permitted");
            final boolean hasCallPrivilegedPermission = mContext.checkCallingPermission(
                    CALL_PRIVILEGED) == PackageManager.PERMISSION_GRANTED;

            final Intent intent = new Intent(hasCallPrivilegedPermission ?
                    Intent.ACTION_CALL_PRIVILEGED : Intent.ACTION_CALL, address);
            Bundle updatedExtras = OutgoingCallTransaction.generateExtras(callId, extras,
                    callAttributes, mFeatureFlags);
            // Note that this may start a potential transaction to make room for the outgoing call
            // so we want to ensure that transaction is queued up first and then create another
            // transaction to complete the call future.
            CompletableFuture<Call> callFuture = mCallsManager.startOutgoingCall(address,
                    requestedAccountHandle, updatedExtras, requestedAccountHandle.getUserHandle(),
                    intent, callingPackage);
            // The second transaction is represented below which will contain the result of whether
            // the new outgoing call was placed or not. To simplify the logic, we will wait on the
            // result of the outgoing call future before adding the transaction so that we can wait
            // for the make room future to complete first.
            if (callFuture == null) {
                Log.d(this, "createTransactionalOutgoingCall: Outgoing call not permitted at the "
                        + "current time.");
                return CompletableFuture.completedFuture(null);
            }
            return callFuture.thenComposeAsync((call) -> CompletableFuture.completedFuture(
                    new OutgoingCallTransactionSequencing(mCallsManager, callFuture,
                            mFeatureFlags)),
                    new LoggedHandlerExecutor(mHandler, "CSC.aC", mCallsManager.getLock()));
        } else {
            Log.d(this, "createTransactionalOutgoingCall: outgoing call not permitted at the "
                    + "current time.");
            return CompletableFuture.completedFuture(null);
        }
    }

    /**
     * Processes the answer call request from the app and verifies the call state changes with
     * sequencing provided that the calls that are being manipulated are across phone accounts.
     * @param incomingCall The incoming call to be answered.
     * @param videoState The video state configuration for the provided call.
     */
    public void answerCall(Call incomingCall, int videoState) {
        Log.i(this, "answerCall: Beginning call sequencing transaction for answering "
                + "incoming call.");
        // Retrieve the CompletableFuture which processes the steps to make room to answer the
        // incoming call.
        CompletableFuture<Boolean> holdActiveForNewCallFutureHandler =
                holdActiveCallForNewCallWithSequencing(incomingCall);
        // If we're performing call sequencing across phone accounts, then ensure that we only
        // proceed if the future above has completed successfully.
        if (isProcessingCallSequencing()) {
            holdActiveForNewCallFutureHandler.thenComposeAsync((result) -> {
                if (result) {
                    mCallsManager.requestFocusActionAnswerCall(incomingCall, videoState);
                } else {
                    Log.i(this, "answerCall: Hold active call transaction failed. Aborting "
                            + "request to answer the incoming call.");
                }
                return CompletableFuture.completedFuture(result);
            }, new LoggedHandlerExecutor(mHandler, "CSC.aC",
                    mCallsManager.getLock()));
        } else {
            mCallsManager.requestFocusActionAnswerCall(incomingCall, videoState);
        }
        resetProcessingCallSequencing();
    }

    /**
     * Handles the case of setting a self-managed call active with call sequencing support.
     * @param call The self-managed call that's waiting to go active.
     */
    public void handleSetSelfManagedCallActive(Call call) {
        CompletableFuture<Boolean> holdActiveCallFuture =
                holdActiveCallForNewCallWithSequencing(call);
        if (isProcessingCallSequencing()) {
            holdActiveCallFuture.thenComposeAsync((result) -> {
                if (result) {
                    Log.i(this, "markCallAsActive: requesting focus for self managed call "
                            + "before setting active.");
                    mCallsManager.requestActionSetActiveCall(call,
                            "active set explicitly for self-managed");
                } else {
                    Log.i(this, "markCallAsActive: Unable to hold active call. "
                            + "Aborting transaction to set self managed call active.");
                }
                return CompletableFuture.completedFuture(result);
            }, new LoggedHandlerExecutor(mHandler,
                    "CM.mCAA", mCallsManager.getLock()));
        } else {
            mCallsManager.requestActionSetActiveCall(call,
                    "active set explicitly for self-managed");
        }
        resetProcessingCallSequencing();
    }

    /**
     * This applies to transactional calls which request to hold the active call with call
     * sequencing support. The resulting future is an indication of whether the hold request
     * succeeded which is then used to create additional transactions to request call focus for the
     * new call.
     * @param newCall The new transactional call that's waiting to go active.
     * @param callback The callback used to report the result of holding the active call and if
     *                 the new call can go active.
     * @return The {@code CompletableFuture} indicating the result of holding the active call
     *         (if applicable).
     */
    public void transactionHoldPotentialActiveCallForNewCallSequencing(
            Call newCall, OutcomeReceiver<Boolean, CallException> callback) {
        CompletableFuture<Boolean> holdActiveCallFuture =
                holdActiveCallForNewCallWithSequencing(newCall).thenComposeAsync((result) -> {
                    if (result) {
                        // Either we were able to hold the active call or the active call was
                        // disconnected in favor of the new call.
                        callback.onResult(true);
                    } else {
                        Log.i(this, "transactionHoldPotentialActiveCallForNewCallSequencing: "
                                + "active call could not be held or disconnected");
                        callback.onError(
                                new CallException("activeCall could not be held or disconnected",
                                CallException.CODE_CANNOT_HOLD_CURRENT_ACTIVE_CALL));
                    }
                    return CompletableFuture.completedFuture(result);
                }, new LoggedHandlerExecutor(mHandler, "CM.mCAA", mCallsManager.getLock()));
        resetProcessingCallSequencing();
    }

    /**
     * Attempts to hold the active call so that the provided call can go active. This is done via
     * call sequencing and the resulting future is an indication of whether that request
     * has succeeded.
     * @param call The call that's waiting to go active.
     * @return The {@code CompletableFuture} indicating the result of whether the active call was
     *         able to be held (if applicable).
     */
    CompletableFuture<Boolean> holdActiveCallForNewCallWithSequencing(Call call) {
        Call activeCall = (Call) mCallsManager.getConnectionServiceFocusManager()
                .getCurrentFocusCall();
        Log.i(this, "holdActiveCallForNewCallWithSequencing, newCall: %s, "
                        + "activeCall: %s", call.getId(),
                (activeCall == null ? "<none>" : activeCall.getId()));
        if (activeCall != null && activeCall != call) {
            processCallSequencing(call, activeCall);
            if (mCallsManager.canHold(activeCall)) {
                return activeCall.hold("swap to " + call.getId());
            } else if (mCallsManager.supportsHold(activeCall)) {
                // Handle the case where active call supports hold but can't currently be held.
                // In this case, we'll look for the currently held call to disconnect prior to
                // holding the active call.
                // E.g.
                // Call A - Held   (Supports hold, can't hold)
                // Call B - Active (Supports hold, can't hold)
                // Call C - Incoming
                // Here we need to disconnect A prior to holding B so that C can be answered.
                // This case is driven by telephony requirements ultimately.
                //
                // These cases can further be broken down at the phone account level:
                // E.g. All cases not outlined below...
                // (1)                              (2)
                // Call A (Held) - PA1              Call A (Held) - PA1
                // Call B (Active) - PA2            Call B (Active) - PA2
                // Call C (Incoming) - PA1          Call C (Incoming) - PA2
                // We should ensure that only operations across phone accounts require sequencing.
                // Otherwise, we can send the requests up til the focus call state in question.
                Call heldCall = mCallsManager.getFirstCallWithState(CallState.ON_HOLD);
                CompletableFuture<Boolean> disconnectFutureHandler = null;
                // Assume default case (no sequencing required).
                boolean areIncomingHeldFromSameSource;

                if (heldCall != null) {
                    processCallSequencing(heldCall, activeCall);
                    processCallSequencing(call, heldCall);
                    areIncomingHeldFromSameSource = CallsManager.areFromSameSource(call, heldCall);

                    // If the calls are from the same source or the incoming call isn't a VOIP call
                    // and the held call is a carrier call, then disconnect the held call. The
                    // idea is that if we have a held carrier call and the incoming call is a
                    // VOIP call, we don't want to force the carrier call to auto-disconnect).
                    if (areIncomingHeldFromSameSource || !(call.isSelfManaged()
                            && !heldCall.isSelfManaged())) {
                        disconnectFutureHandler = heldCall.disconnect();
                        Log.i(this, "holdActiveCallForNewCallWithSequencing: "
                                        + "Disconnect held call %s before holding active call %s.",
                                heldCall.getId(), activeCall.getId());
                    } else {
                        // Otherwise, fail the transaction.
                        return CompletableFuture.completedFuture(false);
                    }
                }
                Log.i(this, "holdActiveCallForNewCallWithSequencing: Holding active "
                        + "%s before making %s active.", activeCall.getId(), call.getId());

                CompletableFuture<Boolean> holdFutureHandler;
                if (isProcessingCallSequencing() && disconnectFutureHandler != null) {
                    holdFutureHandler = disconnectFutureHandler
                            .thenComposeAsync((result) -> {
                                if (result) {
                                    return activeCall.hold();
                                }
                                return CompletableFuture.completedFuture(false);
                            }, new LoggedHandlerExecutor(mHandler,
                                    "CSC.hACFNCWS", mCallsManager.getLock()));
                } else {
                    holdFutureHandler = activeCall.hold();
                }
                call.increaseHeldByThisCallCount();
                return holdFutureHandler;
            } else {
                // This call does not support hold. If it is from a different connection
                // service or connection manager, then disconnect it, otherwise allow the connection
                // service or connection manager to figure out the right states.
                if (isProcessingCallSequencing()) {
                    Log.i(this, "holdActiveCallForNewCallWithSequencing: disconnecting %s "
                            + "so that %s can be made active.", activeCall.getId(), call.getId());
                    if (!activeCall.isEmergencyCall()) {
                        // We don't want to allow VOIP apps to disconnect carrier calls. We are
                        // purposely completing the future with false so that the call isn't
                        // answered.
                        if (call.isSelfManaged() && !activeCall.isSelfManaged()) {
                            Log.w(this, "holdActiveCallForNewCallWithSequencing: ignore "
                                    + "disconnecting carrier call for making VOIP call active");
                            return CompletableFuture.completedFuture(false);
                        } else {
                            return activeCall.disconnect();
                        }
                    } else {
                        // It's not possible to hold the active call, and it's an emergency call so
                        // we will silently reject the incoming call instead of answering it.
                        Log.w(this, "holdActiveCallForNewCallWithSequencing: rejecting incoming "
                                + "call %s as the active call is an emergency call and "
                                + "it cannot be held.", call.getId());
                        return call.reject(false /* rejectWithMessage */, "" /* message */,
                                "active emergency call can't be held");
                    }
                } else {
                    // Same source case: if the active call cannot be held, then the user has
                    // willingly chosen to accept the incoming call knowing that the active call
                    // will be disconnected.
                    return activeCall.disconnect("Active call disconnected in favor of accepting "
                            + "incoming call.");
                }
            }
        }
        return CompletableFuture.completedFuture(true);
    }

    /**
     * Processes the unhold call request sent by the app with call sequencing support.
     * @param call The call to be unheld.
     */
    public void unholdCall(Call call) {
        // Cases: set active call on hold and then set this call to active
        // Calls could be made on different phone accounts, in which case, we need to verify state
        // change for each call.
        CompletableFuture<Boolean> unholdCallFutureHandler = null;
        Call activeCall = (Call) mCallsManager.getConnectionServiceFocusManager()
                .getCurrentFocusCall();
        String activeCallId = null;
        if (activeCall != null && !activeCall.isLocallyDisconnecting()) {
            activeCallId = activeCall.getId();
            // Determine whether the calls are placed on different phone accounts.
            boolean areFromSameSource = CallsManager.areFromSameSource(activeCall, call);
            processCallSequencing(activeCall, call);
            boolean canHoldActiveCall = mCallsManager.canHold(activeCall);

            // If the active + held call are from different phone accounts, ensure that the call
            // sequencing states are verified at each step.
            if (canHoldActiveCall) {
                unholdCallFutureHandler = activeCall.hold("Swap to " + call.getId());
                Log.addEvent(activeCall, LogUtils.Events.SWAP, "To " + call.getId());
                Log.addEvent(call, LogUtils.Events.SWAP, "From " + activeCallId);
            } else {
                if (!areFromSameSource) {
                    // Don't unhold the call as requested if the active and held call are on
                    // different phone accounts - consider the WhatsApp (held) and PSTN (active)
                    // case. We also don't want to drop an emergency call.
                    if (!activeCall.isEmergencyCall()) {
                        Log.w(this, "unholdCall: % and %s are using different phone accounts. "
                                        + "Aborting swap to %s", activeCallId, call.getId(),
                                call.getId());
                    } else {
                        Log.w(this, "unholdCall: % is an emergency call, aborting swap to %s",
                                activeCallId, call.getId());
                    }
                    return;
                } else {
                    activeCall.hold("Swap to " + call.getId());
                }
            }
        }

        // Verify call state was changed to ACTIVE state
        if (isProcessingCallSequencing() && unholdCallFutureHandler != null) {
            String fixedActiveCallId = activeCallId;
            // Only attempt to unhold call if previous request to hold/disconnect call (on different
            // phone account) succeeded.
            unholdCallFutureHandler.thenComposeAsync((result) -> {
                if (result) {
                    Log.i(this, "unholdCall: Request to hold active call transaction succeeded.");
                    mCallsManager.requestActionUnholdCall(call, fixedActiveCallId);
                } else {
                    Log.i(this, "unholdCall: Request to hold active call transaction failed. "
                            + "Aborting unhold transaction.");
                }
                return CompletableFuture.completedFuture(result);
            }, new LoggedHandlerExecutor(mHandler, "CSC.uC",
                    mCallsManager.getLock()));
        } else {
            // Otherwise, we should verify call unhold succeeded for focus call.
            mCallsManager.requestActionUnholdCall(call, activeCallId);
        }
        resetProcessingCallSequencing();
    }

    public CompletableFuture<Boolean> makeRoomForOutgoingCall(boolean isEmergency, Call call) {
        CompletableFuture<Boolean> makeRoomForOutgoingCallFuture = isEmergency
                ? makeRoomForOutgoingEmergencyCall(call)
                : makeRoomForOutgoingCall(call);
        resetProcessingCallSequencing();
        return makeRoomForOutgoingCallFuture;
    }

    /**
     * This function tries to make room for the new emergency outgoing call via call sequencing.
     * The resulting future is an indication of whether room was able to be made for the emergency
     * call if needed.
     * @param emergencyCall The outgoing emergency call to be placed.
     * @return The {@code CompletableFuture} indicating the result of whether room was able to be
     *         made for the emergency call.
     */
    private CompletableFuture<Boolean> makeRoomForOutgoingEmergencyCall(Call emergencyCall) {
        // Always disconnect any ringing/incoming calls when an emergency call is placed to minimize
        // distraction. This does not affect live call count.
        CompletableFuture<Boolean> ringingCallFuture = null;
        Call ringingCall = null;
        if (mCallsManager.hasRingingOrSimulatedRingingCall()) {
            ringingCall = mCallsManager.getRingingOrSimulatedRingingCall();
            processCallSequencing(ringingCall, emergencyCall);
            ringingCall.getAnalytics().setCallIsAdditional(true);
            ringingCall.getAnalytics().setCallIsInterrupted(true);
            if (ringingCall.getState() == CallState.SIMULATED_RINGING) {
                if (!ringingCall.hasGoneActiveBefore()) {
                    // If this is an incoming call that is currently in SIMULATED_RINGING only
                    // after a call screen, disconnect to make room and mark as missed, since
                    // the user didn't get a chance to accept/reject.
                    ringingCallFuture = ringingCall.disconnect("emergency call dialed during "
                            + "simulated ringing after screen.");
                } else {
                    // If this is a simulated ringing call after being active and put in
                    // AUDIO_PROCESSING state again, disconnect normally.
                    ringingCallFuture = ringingCall.reject(false, null, "emergency call dialed "
                            + "during simulated ringing.");
                }
            } else { // normal incoming ringing call.
                // Hang up the ringing call to make room for the emergency call and mark as missed,
                // since the user did not reject.
                ringingCall.setOverrideDisconnectCauseCode(
                        new DisconnectCause(DisconnectCause.MISSED));
                ringingCallFuture = ringingCall.reject(false, null, "emergency call dialed "
                        + "during ringing.");
            }
        }

        // There is already room!
        if (!mCallsManager.hasMaximumLiveCalls(emergencyCall)) {
            return CompletableFuture.completedFuture(true);
        }

        Call liveCall = mCallsManager.getFirstCallWithLiveState();
        Log.i(this, "makeRoomForOutgoingEmergencyCall: call = " + emergencyCall
                + " livecall = " + liveCall);

        if (emergencyCall == liveCall) {
            // Not likely, but a good correctness check.
            return CompletableFuture.completedFuture(true);
        }

        if (mCallsManager.hasMaximumOutgoingCalls(emergencyCall)) {
            Call outgoingCall = mCallsManager.getFirstCallWithState(OUTGOING_CALL_STATES);
            String disconnectReason = null;
            if (!outgoingCall.isEmergencyCall()) {
                emergencyCall.getAnalytics().setCallIsAdditional(true);
                outgoingCall.getAnalytics().setCallIsInterrupted(true);
                disconnectReason = "Disconnecting dialing call in favor of new dialing"
                        + " emergency call.";
            }
            if (outgoingCall.getState() == CallState.SELECT_PHONE_ACCOUNT) {
                // Correctness check: if there is an orphaned emergency call in the
                // {@link CallState#SELECT_PHONE_ACCOUNT} state, just disconnect it since the user
                // has explicitly started a new call.
                emergencyCall.getAnalytics().setCallIsAdditional(true);
                outgoingCall.getAnalytics().setCallIsInterrupted(true);
                disconnectReason = "Disconnecting call in SELECT_PHONE_ACCOUNT in favor"
                        + " of new outgoing call.";
            }
            if (disconnectReason != null) {
                processCallSequencing(outgoingCall, emergencyCall);
                if (ringingCallFuture != null && isProcessingCallSequencing()) {
                    String finalDisconnectReason = disconnectReason;
                    return ringingCallFuture.thenComposeAsync((result) -> {
                        if (result) {
                            Log.i(this, "makeRoomForOutgoingEmergencyCall: Request to disconnect"
                                    + " ringing call succeeded. Attempting to disconnect "
                                    + "outgoing call.");
                            return outgoingCall.disconnect(finalDisconnectReason);
                        } else {
                            Log.i(this, "makeRoomForOutgoingEmergencyCall: Request to disconnect"
                                    + "ringing call failed. Aborting attempt to disconnect "
                                    + "outgoing call");
                            return CompletableFuture.completedFuture(false);
                        }
                    }, new LoggedHandlerExecutor(mHandler, "CSC.mRFOEC",
                            mCallsManager.getLock()));
                } else {
                    return outgoingCall.disconnect(disconnectReason);
                }
            }
            //  If the user tries to make two outgoing calls to different emergency call numbers,
            //  we will try to connect the first outgoing call and reject the second.
            emergencyCall.setStartFailCause(CallFailureCause.IN_EMERGENCY_CALL);
            return CompletableFuture.completedFuture(false);
        }

        processCallSequencing(liveCall, emergencyCall);
        if (ringingCall != null) {
            processCallSequencing(ringingCall, liveCall);
        }
        if (liveCall.getState() == CallState.AUDIO_PROCESSING) {
            emergencyCall.getAnalytics().setCallIsAdditional(true);
            liveCall.getAnalytics().setCallIsInterrupted(true);
            final String disconnectReason = "disconnecting audio processing call for emergency";
            if (ringingCallFuture != null && isProcessingCallSequencing()) {
                return ringingCallFuture.thenComposeAsync((result) -> {
                    if (result) {
                        Log.i(this, "makeRoomForOutgoingEmergencyCall: Request to disconnect "
                                + "ringing call succeeded. Attempting to disconnect live call.");
                        return liveCall.disconnect(disconnectReason);
                    } else {
                        Log.i(this, "makeRoomForOutgoingEmergencyCall: Request to disconnect "
                                + "ringing call failed. Aborting attempt to disconnect live call.");
                        return CompletableFuture.completedFuture(false);
                    }
                }, new LoggedHandlerExecutor(mHandler, "CSC.mRFOEC",
                        mCallsManager.getLock()));
            } else {
                return liveCall.disconnect(disconnectReason);
            }
        }

        // If the live call is stuck in a connecting state, prompt the user to generate a bugreport.
        if (liveCall.getState() == CallState.CONNECTING) {
            AnomalyReporter.reportAnomaly(LIVE_CALL_STUCK_CONNECTING_EMERGENCY_ERROR_UUID,
                    LIVE_CALL_STUCK_CONNECTING_EMERGENCY_ERROR_MSG);
        }

        // If we have the max number of held managed calls and we're placing an emergency call,
        // we'll disconnect the ongoing call if it cannot be held.
        if (mCallsManager.hasMaximumManagedHoldingCalls(emergencyCall)
                && !mCallsManager.canHold(liveCall)) {
            emergencyCall.getAnalytics().setCallIsAdditional(true);
            liveCall.getAnalytics().setCallIsInterrupted(true);
            // Disconnect the active call instead of the holding call because it is historically
            // easier to do, rather than disconnect a held call.
            final String disconnectReason = "disconnecting to make room for emergency call "
                    + emergencyCall.getId();
            if (ringingCallFuture != null && isProcessingCallSequencing()) {
                return ringingCallFuture.thenComposeAsync((result) -> {
                    if (result) {
                        Log.i(this, "makeRoomForOutgoingEmergencyCall: Request to disconnect "
                                + "ringing call succeeded. Attempting to disconnect live call.");
                        return liveCall.disconnect(disconnectReason);
                    } else {
                        Log.i(this, "makeRoomForOutgoingEmergencyCall: Request to disconnect "
                                + "ringing call failed. Aborting attempt to disconnect live call.");
                        return CompletableFuture.completedFuture(false);
                    }
                }, new LoggedHandlerExecutor(mHandler, "CSC.mRFOEC",
                        mCallsManager.getLock()));
            } else {
                return liveCall.disconnect(disconnectReason);
            }
        }

        // TODO: Remove once b/23035408 has been corrected.
        // If the live call is a conference, it will not have a target phone account set.  This
        // means the check to see if the live call has the same target phone account as the new
        // call will not cause us to bail early.  As a result, we'll end up holding the
        // ongoing conference call.  However, the ConnectionService is already doing that.  This
        // has caused problems with some carriers.  As a workaround until b/23035408 is
        // corrected, we will try and get the target phone account for one of the conference's
        // children and use that instead.
        PhoneAccountHandle liveCallPhoneAccount = liveCall.getTargetPhoneAccount();
        if (liveCallPhoneAccount == null && liveCall.isConference() &&
                !liveCall.getChildCalls().isEmpty()) {
            liveCallPhoneAccount = mCallsManager.getFirstChildPhoneAccount(liveCall);
            Log.i(this, "makeRoomForOutgoingEmergencyCall: using child call PhoneAccount = " +
                    liveCallPhoneAccount);
        }

        // We may not know which PhoneAccount the emergency call will be placed on yet, but if
        // the liveCall PhoneAccount does not support placing emergency calls, then we know it
        // will not be that one and we do not want multiple PhoneAccounts active during an
        // emergency call if possible. Disconnect the active call in favor of the emergency call
        // instead of trying to hold.
        if (liveCall.getTargetPhoneAccount() != null) {
            PhoneAccount pa = mCallsManager.getPhoneAccountRegistrar().getPhoneAccountUnchecked(
                    liveCall.getTargetPhoneAccount());
            if((pa.getCapabilities() & PhoneAccount.CAPABILITY_PLACE_EMERGENCY_CALLS) == 0) {
                liveCall.setOverrideDisconnectCauseCode(new DisconnectCause(
                        DisconnectCause.LOCAL, DisconnectCause.REASON_EMERGENCY_CALL_PLACED));
                final String disconnectReason = "outgoing call does not support emergency calls, "
                        + "disconnecting.";
                if (ringingCallFuture != null && isProcessingCallSequencing()) {
                    return ringingCallFuture.thenComposeAsync((result) -> {
                        if (result) {
                            Log.i(this, "makeRoomForOutgoingEmergencyCall: Request to disconnect "
                                    + "ringing call succeeded. "
                                    + "Attempting to disconnect live call.");
                            return liveCall.disconnect(disconnectReason);
                        } else {
                            Log.i(this, "makeRoomForOutgoingEmergencyCall: Request to disconnect "
                                    + "ringing call failed. "
                                    + "Aborting attempt to disconnect live call.");
                            return CompletableFuture.completedFuture(false);
                        }
                    }, new LoggedHandlerExecutor(mHandler, "CSC.mRFOEC",
                            mCallsManager.getLock()));
                } else {
                    return liveCall.disconnect(disconnectReason);
                }
            } else {
                return CompletableFuture.completedFuture(true);
            }
        }

        // First thing, if we are trying to make an emergency call with the same package name as
        // the live call, then allow it so that the connection service can make its own decision
        // about how to handle the new call relative to the current one.
        // By default, for telephony, it will try to hold the existing call before placing the new
        // emergency call except for if the carrier does not support holding calls for emergency.
        // In this case, telephony will disconnect the call.
        if (PhoneAccountHandle.areFromSamePackage(liveCallPhoneAccount,
                emergencyCall.getTargetPhoneAccount())) {
            Log.i(this, "makeRoomForOutgoingEmergencyCall: phoneAccount matches.");
            emergencyCall.getAnalytics().setCallIsAdditional(true);
            liveCall.getAnalytics().setCallIsInterrupted(true);
            return CompletableFuture.completedFuture(true);
        } else if (emergencyCall.getTargetPhoneAccount() == null) {
            // Without a phone account, we can't say reliably that the call will fail.
            // If the user chooses the same phone account as the live call, then it's
            // still possible that the call can be made (like with CDMA calls not supporting
            // hold but they still support adding a call by going immediately into conference
            // mode). Return true here and we'll run this code again after user chooses an
            // account.
            return CompletableFuture.completedFuture(true);
        }

        // Hold the live call if possible before attempting the new outgoing emergency call.
        if (mCallsManager.canHold(liveCall)) {
            Log.i(this, "makeRoomForOutgoingEmergencyCall: holding live call.");
            emergencyCall.getAnalytics().setCallIsAdditional(true);
            emergencyCall.increaseHeldByThisCallCount();
            liveCall.getAnalytics().setCallIsInterrupted(true);
            final String holdReason = "calling " + emergencyCall.getId();
            if (ringingCallFuture != null && isProcessingCallSequencing()) {
                return ringingCallFuture.thenComposeAsync((result) -> {
                    if (result) {
                        Log.i(this, "makeRoomForOutgoingEmergencyCall: Request to disconnect "
                                + "ringing call succeeded. Attempting to hold live call.");
                        return liveCall.hold(holdReason);
                    } else {
                        Log.i(this, "makeRoomForOutgoingEmergencyCall: Request to disconnect "
                                + "ringing call failed. Aborting attempt to hold live call.");
                        return CompletableFuture.completedFuture(false);
                    }
                }, new LoggedHandlerExecutor(mHandler, "CSC.mRFOEC",
                        mCallsManager.getLock()));
            } else {
                return liveCall.hold(holdReason);
            }
        }

        // The live call cannot be held so we're out of luck here.  There's no room.
        emergencyCall.setStartFailCause(CallFailureCause.CANNOT_HOLD_CALL);
        return CompletableFuture.completedFuture(false);
    }

    /**
     * This function tries to make room for the new outgoing call via call sequencing. The
     * resulting future is an indication of whether room was able to be made for the call if
     * needed.
     * @param call The outgoing call to make room for.
     * @return The {@code CompletableFuture} indicating the result of whether room was able to be
     *         made for the outgoing call.
     */
    private CompletableFuture<Boolean> makeRoomForOutgoingCall(Call call) {
        // Already room!
        if (!mCallsManager.hasMaximumLiveCalls(call)) {
            return CompletableFuture.completedFuture(true);
        }

        // NOTE: If the amount of live calls changes beyond 1, this logic will probably
        // have to change.
        Call liveCall = mCallsManager.getFirstCallWithLiveState();
        Log.i(this, "makeRoomForOutgoingCall call = " + call + " livecall = " +
                liveCall);

        if (call == liveCall) {
            // If the call is already the foreground call, then we are golden.
            // This can happen after the user selects an account in the SELECT_PHONE_ACCOUNT
            // state since the call was already populated into the list.
            return CompletableFuture.completedFuture(true);
        }

        CompletableFuture<Boolean> disconnectFuture = mCallsManager
                .maybeDisconnectExistingCallForNewOutgoingCall(call, liveCall);
        if (disconnectFuture != null) {
            return disconnectFuture;
        }

        // TODO: Remove once b/23035408 has been corrected.
        // If the live call is a conference, it will not have a target phone account set.  This
        // means the check to see if the live call has the same target phone account as the new
        // call will not cause us to bail early.  As a result, we'll end up holding the
        // ongoing conference call.  However, the ConnectionService is already doing that.  This
        // has caused problems with some carriers.  As a workaround until b/23035408 is
        // corrected, we will try and get the target phone account for one of the conference's
        // children and use that instead.
        PhoneAccountHandle liveCallPhoneAccount = liveCall.getTargetPhoneAccount();
        if (liveCallPhoneAccount == null && liveCall.isConference() &&
                !liveCall.getChildCalls().isEmpty()) {
            liveCallPhoneAccount = mCallsManager.getFirstChildPhoneAccount(liveCall);
            Log.i(this, "makeRoomForOutgoingCall: using child call PhoneAccount = " +
                    liveCallPhoneAccount);
        }

        // First thing, for managed calls, if we are trying to make a call with the same phone
        // account as the live call, then allow it so that the connection service can make its own
        // decision about how to handle the new call relative to the current one.
        // Note: This behavior is primarily in place because Telephony historically manages the
        // state of the calls it tracks by itself, holding and unholding as needed.  Self-managed
        // calls, even though from the same package are normally held/unheld automatically by
        // Telecom.  Calls within a single ConnectionService get held/unheld automatically during
        // "swap" operations by CallsManager#holdActiveCallForNewCall.  There is, however, a quirk
        // in that if an app declares TWO different ConnectionServices, holdActiveCallForNewCall
        // would not work correctly because focus switches between ConnectionServices, yet we
        // tended to assume that if the calls are from the same package that the hold/unhold should
        // be done by the app.  That was a bad assumption as it meant that we could have two active
        // calls.
        // TODO(b/280826075): We need to come back and revisit all this logic in a holistic manner.
        if (PhoneAccountHandle.areFromSamePackage(liveCallPhoneAccount,
                call.getTargetPhoneAccount())
                && !call.isSelfManaged()
                && !liveCall.isSelfManaged()) {
            Log.i(this, "makeRoomForOutgoingCall: managed phoneAccount matches");
            call.getAnalytics().setCallIsAdditional(true);
            liveCall.getAnalytics().setCallIsInterrupted(true);
            return CompletableFuture.completedFuture(true);
        } else if (call.getTargetPhoneAccount() == null) {
            // Without a phone account, we can't say reliably that the call will fail.
            // If the user chooses the same phone account as the live call, then it's
            // still possible that the call can be made (like with CDMA calls not supporting
            // hold but they still support adding a call by going immediately into conference
            // mode). Return true here and we'll run this code again after user chooses an
            // account.
            return CompletableFuture.completedFuture(true);
        }

        // Try to hold the live call before attempting the new outgoing call.
        if (mCallsManager.canHold(liveCall)) {
            Log.i(this, "makeRoomForOutgoingCall: holding live call.");
            call.getAnalytics().setCallIsAdditional(true);
            liveCall.getAnalytics().setCallIsInterrupted(true);
            return liveCall.hold("calling " + call.getId());
        }

        // The live call cannot be held so we're out of luck here.  There's no room.
        call.setStartFailCause(CallFailureCause.CANNOT_HOLD_CALL);
        return CompletableFuture.completedFuture(false);
    }

    /**
     * Processes the request from the app to disconnect a call. This is done via call sequencing
     * so that Telecom properly cleans up the call locally provided that the call has been
     * properly disconnected on the connection side.
     * @param call The call to disconnect.
     * @param previousState The previous state of the call before disconnecting.
     */
    public void disconnectCall(Call call, int previousState) {
        CompletableFuture<Boolean> disconnectFuture = call.disconnect();
        disconnectFuture.thenComposeAsync((result) -> {
            if (result) {
                Log.i(this, "disconnectCall: Disconnect call transaction succeeded. "
                        + "Processing associated cleanup.");
                mCallsManager.processDisconnectCallAndCleanup(call, previousState);
            } else {
                Log.i(this, "disconnectCall: Disconnect call transaction failed. "
                        + "Aborting associated cleanup.");
            }
            return CompletableFuture.completedFuture(false);
        }, new LoggedHandlerExecutor(mHandler, "CSC.dC",
                mCallsManager.getLock()));
    }

    private void resetProcessingCallSequencing() {
        setProcessingCallSequencing(false);
    }

    private void setProcessingCallSequencing(boolean processingCallSequencing) {
        mProcessingCallSequencing = processingCallSequencing;
    }

    /**
     * Checks if the 2 calls provided are from the same source and sets the
     * mProcessingCallSequencing field if they aren't in order to signal that sequencing is
     * required to verify the call state changes.
     */
    private void processCallSequencing(Call call1, Call call2) {
        boolean areCallsFromSameSource = CallsManager.areFromSameSource(call1, call2);
        if (!areCallsFromSameSource) {
            setProcessingCallSequencing(true);
        }
    }

    public boolean isProcessingCallSequencing() {
        return mProcessingCallSequencing;
    }

    public Handler getHandler() {
        return mHandler;
    }
}
