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
 * limitations under the License
 */

package com.android.server.telecom.tests;

import static android.media.AudioPlaybackConfiguration.PLAYER_STATE_IDLE;
import static android.media.AudioPlaybackConfiguration.PLAYER_STATE_STARTED;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.annotation.NonNull;
import android.content.ComponentName;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioPlaybackConfiguration;
import android.media.AudioRecordingConfiguration;
import android.media.IPlayer;
import android.media.MediaRecorder;
import android.media.PlayerBase;
import android.telecom.PhoneAccountHandle;
import android.util.ArrayMap;

import com.android.server.telecom.Call;
import com.android.server.telecom.CallAudioWatchdog;
import com.android.server.telecom.ClockProxy;
import com.android.server.telecom.metrics.CallStats;
import com.android.server.telecom.metrics.TelecomMetricsController;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;

/**
 * Tests for {@link com.android.server.telecom.CallAudioWatchdog}.
 */
@RunWith(JUnit4.class)
public class CallAudioWatchdogTest extends TelecomTestCase {
    private static final String TEST_CALL_ID = "TC@90210";
    private static final int TEST_APP_1_UID = 10001;
    private static final int TEST_APP_2_UID = 10002;
    private static final PhoneAccountHandle TEST_APP_1_HANDLE = new PhoneAccountHandle(
            new ComponentName("com.app1.package", "class1"), "1");
    private static final ArrayMap<Integer, PhoneAccountHandle> TEST_UID_TO_PHAC = new ArrayMap<>();
    private CallAudioWatchdog.PhoneAccountRegistrarProxy mPhoneAccountRegistrarProxy =
            new CallAudioWatchdog.PhoneAccountRegistrarProxy() {
                @Override
                public boolean hasPhoneAccountForUid(int uid) {
                    return TEST_UID_TO_PHAC.containsKey(uid);
                }

                @Override
                public int getUidForPhoneAccountHandle(PhoneAccountHandle handle) {
                    Optional<Map.Entry<Integer, PhoneAccountHandle>> entry =
                            TEST_UID_TO_PHAC.entrySet().stream().filter(
                                    e -> e.getValue().equals(handle)).findFirst();
                    if (entry.isPresent()) {
                        return entry.get().getKey();
                    } else {
                        return -1;
                    }
                }
            };

    @Mock private ClockProxy mClockProxy;
    @Mock private TelecomMetricsController mMetricsController;
    @Mock private CallStats mCallStats;
    private CallAudioWatchdog mCallAudioWatchdog;

    @Override
    @Before
    public void setUp() throws Exception {
        super.setUp();
        when(mMetricsController.getCallStats()).thenReturn(mCallStats);
        when(mClockProxy.elapsedRealtime()).thenReturn(0L);
        TEST_UID_TO_PHAC.put(TEST_APP_1_UID, TEST_APP_1_HANDLE);
        mCallAudioWatchdog = new CallAudioWatchdog(mComponentContextFixture.getAudioManager(),
                mPhoneAccountRegistrarProxy, mClockProxy, null /* mHandler */, mMetricsController);
    }

    @Override
    @After
    public void tearDown() throws Exception {
        super.tearDown();
    }

    /**
     * Verifies that a new Telecom call added results in a session being added for that call.
     */
    @Test
    public void testAddTelecomCall() {
        Call mockCall = createMockCall();
        mCallAudioWatchdog.onCallAdded(mockCall);
        assertTrue(mCallAudioWatchdog.getCommunicationSessions().containsKey(TEST_APP_1_UID));
        CallAudioWatchdog.CommunicationSession session = mCallAudioWatchdog
                .getCommunicationSessions().get(TEST_APP_1_UID);
        assertFalse(session.hasMediaResources());
        assertEquals(TEST_CALL_ID, session.getTelecomCall().getId());
    }

    /**
     * Verifies tracking of multiple audio sessions.
     */
    @Test
    public void testTrackAudioPlayback() {
        var client1Idle = makeAudioPlaybackConfiguration(
                TEST_APP_1_UID, PLAYER_STATE_IDLE, 1);
        mCallAudioWatchdog.getWatchdogAudioPlayback().onPlaybackConfigChanged(
                Arrays.asList(client1Idle));
        assertFalse(mCallAudioWatchdog.getCommunicationSessions().containsKey(TEST_APP_1_UID));

        var client1Playing = makeAudioPlaybackConfiguration(
                TEST_APP_1_UID, PLAYER_STATE_STARTED, 1);
        mCallAudioWatchdog.getWatchdogAudioPlayback().onPlaybackConfigChanged(
                Arrays.asList(client1Playing));
        assertTrue(mCallAudioWatchdog.getCommunicationSessions().containsKey(TEST_APP_1_UID));

        var client2Playing = makeAudioPlaybackConfiguration(
                TEST_APP_1_UID, PLAYER_STATE_STARTED, 2);
        mCallAudioWatchdog.getWatchdogAudioPlayback().onPlaybackConfigChanged(
                Arrays.asList(client1Playing, client2Playing));
        assertTrue(mCallAudioWatchdog.getCommunicationSessions().containsKey(TEST_APP_1_UID));

        mCallAudioWatchdog.getWatchdogAudioPlayback().onPlaybackConfigChanged(
                Arrays.asList(client2Playing));
        assertTrue(mCallAudioWatchdog.getCommunicationSessions().containsKey(TEST_APP_1_UID));

        mCallAudioWatchdog.getWatchdogAudioPlayback().onPlaybackConfigChanged(
                Arrays.asList(makeAudioPlaybackConfiguration(
                        TEST_APP_1_UID, PLAYER_STATE_IDLE, 2)));
        assertFalse(mCallAudioWatchdog.getCommunicationSessions().containsKey(TEST_APP_1_UID));
    }

    /**
     * Verifies ability of the audio watchdog to handle changes to the audio record configs.
     */
    @Test
    public void testTrackAudioRecord() {
        var client1Recording = makeAudioRecordingConfiguration(TEST_APP_1_UID, 1);
        var theRecords = Arrays.asList(client1Recording);
        when(mComponentContextFixture.getAudioManager().getActiveRecordingConfigurations())
                .thenReturn(theRecords);
        mCallAudioWatchdog.getWatchdogAudioRecordCallack().onRecordingConfigChanged(theRecords);
        assertTrue(mCallAudioWatchdog.getCommunicationSessions().containsKey(TEST_APP_1_UID));

        var client2Recording = makeAudioRecordingConfiguration(TEST_APP_1_UID, 2);
        theRecords = Arrays.asList(client1Recording, client2Recording);
        when(mComponentContextFixture.getAudioManager().getActiveRecordingConfigurations())
                .thenReturn(theRecords);
        mCallAudioWatchdog.getWatchdogAudioRecordCallack().onRecordingConfigChanged(theRecords);
        assertTrue(mCallAudioWatchdog.getCommunicationSessions().containsKey(TEST_APP_1_UID));

        theRecords = Arrays.asList(client2Recording);
        when(mComponentContextFixture.getAudioManager().getActiveRecordingConfigurations())
                .thenReturn(theRecords);
        mCallAudioWatchdog.getWatchdogAudioRecordCallack().onRecordingConfigChanged(theRecords);
        assertTrue(mCallAudioWatchdog.getCommunicationSessions().containsKey(TEST_APP_1_UID));

        when(mComponentContextFixture.getAudioManager().getActiveRecordingConfigurations())
                .thenReturn(Collections.EMPTY_LIST);
        when(mClockProxy.elapsedRealtime()).thenReturn(1000L);
        mCallAudioWatchdog.getWatchdogAudioRecordCallack().onRecordingConfigChanged(
                Collections.EMPTY_LIST);
        assertFalse(mCallAudioWatchdog.getCommunicationSessions().containsKey(TEST_APP_1_UID));

        // Ensure that a call with telecom support but which did not use Telecom gets logged to
        // metrics as a non-telecom call.
        verify(mCallStats).onNonTelecomCallEnd(eq(true), eq(TEST_APP_1_UID), eq(1000L));
    }

    /**
     * Verifies ability of the audio watchdog to track non-telecom calls where there is no Telecom
     * integration.
     */
    @Test
    public void testNonTelecomCallMetricsTracking() {
        var client1Recording = makeAudioRecordingConfiguration(TEST_APP_2_UID, 1);
        var theRecords = Arrays.asList(client1Recording);
        when(mComponentContextFixture.getAudioManager().getActiveRecordingConfigurations())
                .thenReturn(theRecords);
        mCallAudioWatchdog.getWatchdogAudioRecordCallack().onRecordingConfigChanged(theRecords);
        assertTrue(mCallAudioWatchdog.getCommunicationSessions().containsKey(TEST_APP_2_UID));

        when(mComponentContextFixture.getAudioManager().getActiveRecordingConfigurations())
                .thenReturn(Collections.EMPTY_LIST);
        when(mClockProxy.elapsedRealtime()).thenReturn(1000L);
        mCallAudioWatchdog.getWatchdogAudioRecordCallack().onRecordingConfigChanged(
                Collections.EMPTY_LIST);
        assertFalse(mCallAudioWatchdog.getCommunicationSessions().containsKey(TEST_APP_2_UID));

        // This should log as a non-telecom call with no telecom support.
        verify(mCallStats).onNonTelecomCallEnd(eq(false), eq(TEST_APP_2_UID), eq(1000L));
    }

    /**
     * Verifies that if a call known to Telecom is added, that we don't try to track it in the
     * non-telecom metrics.
     */
    @Test
    public void testTelecomCallMetricsTracking() {
        var client1Recording = makeAudioRecordingConfiguration(TEST_APP_1_UID, 1);
        var theRecords = Arrays.asList(client1Recording);
        when(mComponentContextFixture.getAudioManager().getActiveRecordingConfigurations())
                .thenReturn(theRecords);
        mCallAudioWatchdog.getWatchdogAudioRecordCallack().onRecordingConfigChanged(theRecords);
        assertTrue(mCallAudioWatchdog.getCommunicationSessions().containsKey(TEST_APP_1_UID));

        Call mockCall = mock(Call.class);
        when(mockCall.isSelfManaged()).thenReturn(true);
        when(mockCall.isExternalCall()).thenReturn(false);
        when(mockCall.getTargetPhoneAccount()).thenReturn(TEST_APP_1_HANDLE);
        when(mockCall.getId()).thenReturn("90210");
        mCallAudioWatchdog.onCallAdded(mockCall);

        when(mComponentContextFixture.getAudioManager().getActiveRecordingConfigurations())
                .thenReturn(Collections.EMPTY_LIST);
        when(mClockProxy.elapsedRealtime()).thenReturn(1000L);
        mCallAudioWatchdog.getWatchdogAudioRecordCallack().onRecordingConfigChanged(
                Collections.EMPTY_LIST);
        assertTrue(mCallAudioWatchdog.getCommunicationSessions().containsKey(TEST_APP_1_UID));

        mCallAudioWatchdog.onCallRemoved(mockCall);
        assertFalse(mCallAudioWatchdog.getCommunicationSessions().containsKey(TEST_APP_1_UID));

        // We should not log a non-telecom call.  Note; we are purposely NOT trying to check if a
        // Telecom call metric is logged here since that is done elsewhere and this unit test is
        // only testing CallAudioWatchdog in isolation.
        verify(mCallStats, never()).onNonTelecomCallEnd(anyBoolean(), anyInt(), anyLong());

    }

    private AudioPlaybackConfiguration makeAudioPlaybackConfiguration(int clientUid,
            int playerState, int playerInterfaceId) {
        AudioAttributes attributes = new AudioAttributes.Builder()
                             .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                             .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                             .build();
        AudioPlaybackConfiguration configuration = mock(AudioPlaybackConfiguration.class);
        when(configuration.getAudioAttributes()).thenReturn(attributes);
        when(configuration.getClientUid()).thenReturn(clientUid);
        when(configuration.getPlayerState()).thenReturn(playerState);
        when(configuration.getPlayerInterfaceId()).thenReturn(playerInterfaceId);
        return configuration;
    }

    private AudioRecordingConfiguration makeAudioRecordingConfiguration(int clientUid,
            int clientAudioSessionId) {
        AudioRecordingConfiguration configuration = mock(AudioRecordingConfiguration.class);
        when(configuration.getClientUid()).thenReturn(clientUid);
        when(configuration.getClientAudioSource()).thenReturn(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION);
        when(configuration.getClientAudioSessionId()).thenReturn(clientAudioSessionId);
        return configuration;
    }

    private Call createMockCall() {
        Call mockCall = mock(Call.class);
        when(mockCall.getId()).thenReturn(TEST_CALL_ID);
        when(mockCall.isSelfManaged()).thenReturn(true);
        when(mockCall.getTargetPhoneAccount()).thenReturn(TEST_APP_1_HANDLE);
        return mockCall;
    }
}
