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

import static android.telecom.TelecomManager.TELECOM_TRANSACTION_SUCCESS;
import static android.telecom.CallException.CODE_OPERATION_TIMED_OUT;

import android.os.Bundle;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.telecom.CallAttributes;
import android.telecom.DisconnectCause;
import android.util.Log;

import com.android.internal.telecom.ICallEventCallback;
import com.android.server.telecom.TelecomSystem;
import com.android.server.telecom.TransactionalServiceWrapper;
import com.android.server.telecom.callsequencing.CallTransaction;
import com.android.server.telecom.callsequencing.CallTransactionResult;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * SRP: using the ICallEventCallback binder, reach out to the client for the pending call event and
 * get an acknowledgement that the call event can be completed.
 */
public class CallEventCallbackAckTransaction extends CallTransaction {
    private static final String TAG = CallEventCallbackAckTransaction.class.getSimpleName();
    private final ICallEventCallback mICallEventCallback;
    private final String mAction;
    private final String mCallId;
    // optional values
    private int mVideoState = CallAttributes.AUDIO_CALL;
    private DisconnectCause mDisconnectCause = null;

    private final CallTransactionResult TRANSACTION_FAILED = new CallTransactionResult(
            CODE_OPERATION_TIMED_OUT, "failed to complete the operation before timeout");

    private static class AckResultReceiver extends ResultReceiver {
        CompletableFuture<Boolean> mCompletableFuture;

        public AckResultReceiver(CompletableFuture<Boolean> future) {
            super(null);
            mCompletableFuture = future;
        }

        @Override
        protected void onReceiveResult(int resultCode, Bundle resultData) {
            if (resultCode == TELECOM_TRANSACTION_SUCCESS) {
                mCompletableFuture.complete(true);
            }
        }
    }

    public CallEventCallbackAckTransaction(ICallEventCallback service, String action,
            String callId, TelecomSystem.SyncRoot lock) {
        super(lock);
        mICallEventCallback = service;
        mAction = action;
        mCallId = callId;
    }


    public CallEventCallbackAckTransaction(ICallEventCallback service, String action, String callId,
            int videoState, TelecomSystem.SyncRoot lock) {
        super(lock);
        mICallEventCallback = service;
        mAction = action;
        mCallId = callId;
        mVideoState = videoState;
    }

    public CallEventCallbackAckTransaction(ICallEventCallback service, String action, String callId,
            DisconnectCause cause, TelecomSystem.SyncRoot lock) {
        super(lock);
        mICallEventCallback = service;
        mAction = action;
        mCallId = callId;
        mDisconnectCause = cause;
    }


    @Override
    public CompletionStage<CallTransactionResult> processTransaction(Void v) {
        Log.d(TAG, "processTransaction: action [" + mAction + "]");
        CompletableFuture<Boolean> future = new CompletableFuture<Boolean>()
                .completeOnTimeout(false, mTransactionTimeoutMs, TimeUnit.MILLISECONDS);
        ResultReceiver receiver = new AckResultReceiver(future);

        try {
            switch (mAction) {
                case TransactionalServiceWrapper.ON_SET_INACTIVE:
                    mICallEventCallback.onSetInactive(mCallId, receiver);
                    break;
                case TransactionalServiceWrapper.ON_DISCONNECT:
                    mICallEventCallback.onDisconnect(mCallId, mDisconnectCause, receiver);
                    break;
                case TransactionalServiceWrapper.ON_SET_ACTIVE:
                    mICallEventCallback.onSetActive(mCallId, receiver);
                    break;
                case TransactionalServiceWrapper.ON_ANSWER:
                    mICallEventCallback.onAnswer(mCallId, mVideoState, receiver);
                    break;
                case TransactionalServiceWrapper.ON_STREAMING_STARTED:
                    mICallEventCallback.onCallStreamingStarted(mCallId, receiver);
                    break;
            }
        } catch (RemoteException remoteException) {
            return CompletableFuture.completedFuture(TRANSACTION_FAILED);
        }

        return future.thenCompose((success) -> {
            if (!success) {
                // client send onError and failed to complete transaction
                Log.i(TAG, String.format("CallEventCallbackAckTransaction:"
                        + " client failed to complete the [%s] transaction", mAction));
                return CompletableFuture.completedFuture(TRANSACTION_FAILED);
            } else {
                // success
                return CompletableFuture.completedFuture(
                        new CallTransactionResult(CallTransactionResult.RESULT_SUCCEED,
                                "success"));
            }
        });
    }
}
