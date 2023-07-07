/*
 * Copyright 2014, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.telecom;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.media.Ringtone;
import android.media.VolumeShaper;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.telecom.Log;
import android.telecom.Logging.Session;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.os.SomeArgs;
import com.android.internal.util.Preconditions;

import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * Plays the default ringtone. Uses {@link Ringtone} in a separate thread so that this class can be
 * used from the main thread.
 */
@VisibleForTesting
public class AsyncRingtonePlayer {
    // Message codes used with the ringtone thread.
    private static final int EVENT_PLAY = 1;
    private static final int EVENT_STOP = 2;

    /** Handler running on the ringtone thread. */
    private Handler mHandler;

    /** The current ringtone. Only used by the ringtone thread. */
    private Ringtone mRingtone;

    public AsyncRingtonePlayer() {
        // Empty
    }

    /**
     * Plays the appropriate ringtone for the specified call.
     * If {@link VolumeShaper.Configuration} is specified, it is applied to the ringtone to change
     * the volume of the ringtone as it plays.
     *
     * @param ringtoneSupplier The {@link Ringtone} factory.
     * @param ringtoneConsumer The {@link Ringtone} post-creation callback (to start the vibration).
     */
    public void play(@NonNull Supplier<Ringtone> ringtoneSupplier,
            BiConsumer<Ringtone, Boolean> ringtoneConsumer) {
        Log.d(this, "Posting play.");
        SomeArgs args = SomeArgs.obtain();
        args.arg1 = ringtoneSupplier;
        args.arg2 = ringtoneConsumer;
        args.arg3 = Log.createSubsession();
        postMessage(EVENT_PLAY, true /* shouldCreateHandler */, args);
    }

    /** Stops playing the ringtone. */
    public void stop() {
        Log.d(this, "Posting stop.");
        postMessage(EVENT_STOP, false /* shouldCreateHandler */, null);
    }

    /**
     * Posts a message to the ringtone-thread handler. Creates the handler if specified by the
     * parameter shouldCreateHandler.
     *
     * @param messageCode The message to post.
     * @param shouldCreateHandler True when a handler should be created to handle this message.
     */
    private void postMessage(int messageCode, boolean shouldCreateHandler, SomeArgs args) {
        synchronized(this) {
            if (mHandler == null && shouldCreateHandler) {
                mHandler = getNewHandler();
            }

            if (mHandler == null) {
                Log.d(this, "Message %d skipped because there is no handler.", messageCode);
            } else {
                mHandler.obtainMessage(messageCode, args).sendToTarget();
            }
        }
    }

    /**
     * Creates a new ringtone Handler running in its own thread.
     */
    private Handler getNewHandler() {
        Preconditions.checkState(mHandler == null);

        HandlerThread thread = new HandlerThread("ringtone-player");
        thread.start();

        return new Handler(thread.getLooper(), null /*callback*/, true /*async*/) {
            @Override
            public void handleMessage(Message msg) {
                switch(msg.what) {
                    case EVENT_PLAY:
                        handlePlay((SomeArgs) msg.obj);
                        break;
                    case EVENT_STOP:
                        handleStop();
                        break;
                }
            }
        };
    }

    /**
     * Starts the actual playback of the ringtone. Executes on ringtone-thread.
     */
    private void handlePlay(SomeArgs args) {
        Supplier<Ringtone> ringtoneSupplier = (Supplier<Ringtone>) args.arg1;
        BiConsumer<Ringtone, Boolean> ringtoneConsumer = (BiConsumer<Ringtone, Boolean>) args.arg2;
        Session session = (Session) args.arg3;
        args.recycle();

        Log.continueSession(session, "ARP.hP");
        try {
            // Don't bother with any of this if there is an EVENT_STOP waiting, but give the
            // consumer a chance to do anything no matter what.
            if (mHandler.hasMessages(EVENT_STOP)) {
                ringtoneConsumer.accept(null, /* stopped= */ true);
                return;
            }
            Ringtone ringtone = null;
            boolean hasStopped = false;
            try {
                ringtone = ringtoneSupplier.get();
                // Ringtone supply can be slow. Re-check for stop event.
                if (mHandler.hasMessages(EVENT_STOP)) {
                    hasStopped = true;
                    ringtone.stop();  // proactively release the ringtone.
                    return;
                }
                // setRingtone even if null - it also stops any current ringtone to be consistent
                // with the overall state.
                setRingtone(ringtone);
                if (mRingtone == null) {
                    // The ringtoneConsumer can still vibrate at this stage.
                    Log.w(this, "No ringtone was found bail out from playing.");
                    return;
                }
                Uri uri = mRingtone.getUri();
                String uriString = (uri != null ? uri.toSafeString() : "");
                Log.i(this, "handlePlay: Play ringtone. Uri: " + uriString);
                mRingtone.setLooping(true);
                if (mRingtone.isPlaying()) {
                    Log.d(this, "Ringtone already playing.");
                    return;
                }
                mRingtone.play();
                Log.i(this, "Play ringtone, looping.");
            } finally {
                ringtoneConsumer.accept(ringtone, hasStopped);
            }
        } finally {
            Log.cancelSubsession(session);
        }
    }

    /**
     * Stops the playback of the ringtone. Executes on the ringtone-thread.
     */
    private void handleStop() {
        ThreadUtil.checkNotOnMainThread();
        Log.i(this, "Stop ringtone.");

        setRingtone(null);

        synchronized(this) {
            if (mHandler.hasMessages(EVENT_PLAY)) {
                Log.v(this, "Keeping alive ringtone thread for subsequent play request.");
            } else {
                mHandler.removeMessages(EVENT_STOP);
                mHandler.getLooper().quitSafely();
                mHandler = null;
                Log.v(this, "Handler cleared.");
            }
        }
    }

    public boolean isPlaying() {
        return mRingtone != null;
    }

    private void setRingtone(@Nullable Ringtone ringtone) {
        // Make sure that any previously created instance of Ringtone is stopped so the MediaPlayer
        // can be released, before replacing mRingtone with a new instance. This is always created
        // as a looping Ringtone, so if not stopped it will keep playing on the background.
        if (mRingtone != null) {
            Log.d(this, "Ringtone.stop() invoked.");
            mRingtone.stop();
        }
        mRingtone = ringtone;
    }
}
