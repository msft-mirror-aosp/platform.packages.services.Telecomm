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

package com.android.server.telecom.callsequencing.voip;

import static android.telecom.CallException.CODE_CALL_NOT_PERMITTED_AT_PRESENT_TIME;

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

public class OutgoingCallTransactionSequencing extends CallTransaction {

    private static final String TAG = OutgoingCallTransactionSequencing.class.getSimpleName();
    private final CompletableFuture<Call> mCallFuture;
    private final CallsManager mCallsManager;
    private final boolean mCallNotPermitted;
    private FeatureFlags mFeatureFlags;

    public OutgoingCallTransactionSequencing(CallsManager callsManager,
            CompletableFuture<Call> callFuture, boolean callNotPermitted,
            FeatureFlags featureFlags) {
        super(callsManager.getLock());
        mCallsManager = callsManager;
        mCallFuture = callFuture;
        mCallNotPermitted = callNotPermitted;
        mFeatureFlags = featureFlags;
    }

    @Override
    public CompletionStage<CallTransactionResult> processTransaction(Void v) {
        Log.d(TAG, "processTransaction");
        if (mCallNotPermitted) {
            return CompletableFuture.completedFuture(
                    new CallTransactionResult(
                            CODE_CALL_NOT_PERMITTED_AT_PRESENT_TIME,
                            "outgoing call not permitted at the current time"));
        }

        return mCallFuture.thenComposeAsync(
                (call) -> OutgoingCallTransaction.processOutgoingCallTransactionHelper(call, TAG,
                        mCallsManager, mFeatureFlags)
                , new LoggedHandlerExecutor(mHandler, "OCT.pT", null));
    }

    @VisibleForTesting
    public boolean getCallNotPermitted() {
        return mCallNotPermitted;
    }
}
