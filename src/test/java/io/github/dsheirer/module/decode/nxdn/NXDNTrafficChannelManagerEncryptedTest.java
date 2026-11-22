/*
 * *****************************************************************************
 * Copyright (C) 2014-2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 * ****************************************************************************
 */

package io.github.dsheirer.module.decode.nxdn;

import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.Subscribe;
import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.controller.channel.ChannelEvent;
import io.github.dsheirer.controller.channel.event.ChannelStartProcessingRequest;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.encryption.EncryptionKeyIdentifier;
import io.github.dsheirer.module.decode.DecoderFactory;
import io.github.dsheirer.module.decode.event.IDecodeEvent;
import io.github.dsheirer.module.decode.nxdn.channel.ChannelFrequency;
import io.github.dsheirer.module.decode.nxdn.channel.NXDNChannel;
import io.github.dsheirer.module.decode.nxdn.channel.NXDNChannelLookup;
import io.github.dsheirer.module.decode.nxdn.identifier.NXDNEncryptionKey;
import io.github.dsheirer.module.decode.nxdn.identifier.NXDNRadioIdentifier;
import io.github.dsheirer.module.decode.nxdn.identifier.NXDNTalkgroupIdentifier;
import io.github.dsheirer.module.decode.nxdn.layer3.NXDNMessageType;
import io.github.dsheirer.module.decode.nxdn.layer3.call.VoiceCall;
import io.github.dsheirer.module.decode.nxdn.layer3.call.VoiceCallAssignment;
import io.github.dsheirer.module.decode.nxdn.layer3.type.CallType;
import io.github.dsheirer.protocol.Protocol;
import io.github.dsheirer.source.config.SourceConfigTuner;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the ignore encrypted calls option of the NXDN traffic channel manager: encrypted voice call assignments
 * don't tune a traffic channel, and a traffic channel that was released because it detected an encrypted call isn't
 * tuned again by the voice call assignments that continue for the call.
 */
public class NXDNTrafficChannelManagerEncryptedTest
{
    private static final int CHANNEL_NUMBER = 10;
    private static final long FREQUENCY = 451_000_000L;
    private static final int TALKGROUP = 100;
    private static final int AES = 3;

    /**
     * Captures traffic channel start requests and decode events.
     */
    private static class Harness
    {
        private final NXDNTrafficChannelManager mManager;
        private final List<Long> mStartedFrequencies = new ArrayList<>();
        private final List<Channel> mStartedChannels = new ArrayList<>();
        private final List<IDecodeEvent> mEvents = new ArrayList<>();

        Harness(boolean ignoreEncrypted)
        {
            DecodeConfigNXDN config = new DecodeConfigNXDN();
            config.setIgnoreEncryptedCalls(ignoreEncrypted);
            Channel parent = new Channel("control");
            parent.setDecodeConfiguration(config);
            SourceConfigTuner sourceConfig = new SourceConfigTuner();
            sourceConfig.setFrequency(450_000_000L);
            parent.setSourceConfiguration(sourceConfig);
            mManager = new NXDNTrafficChannelManager(parent);
            EventBus eventBus = new EventBus();
            eventBus.register(this);
            mManager.setInterModuleEventBus(eventBus);
            mManager.addDecodeEventListener(mEvents::add);
        }

        @Subscribe
        public void start(ChannelStartProcessingRequest request)
        {
            mStartedFrequencies.add(request.getChannel().getSourceConfiguration() instanceof
                    SourceConfigTuner sct ? sct.getFrequency() : -1L);
            mStartedChannels.add(request.getChannel());
        }

        /**
         * Control channel voice call assignment
         */
        void assignment(int cipher, long timestamp)
        {
            mManager.processVoiceCallAssignment(new TestVoiceCallAssignment(cipher, timestamp));
        }

        void assignmentsEverySecond(int cipher, long start, long end)
        {
            for(long ts = start; ts <= end; ts += 1000)
            {
                assignment(cipher, ts);
            }
        }

        /**
         * Traffic channel voice call message and call audio
         */
        void trafficVoiceCall(int cipher, long timestamp)
        {
            mManager.processVoiceCall(new TestVoiceCall(cipher, timestamp), channel());
            mManager.processCallProgressUpdate(channel(), timestamp + 100);
        }

        void stopLastTrafficChannel()
        {
            Channel channel = mStartedChannels.get(mStartedChannels.size() - 1);
            mManager.getChannelEventListener().receive(new ChannelEvent(channel, ChannelEvent.Event.NOTIFICATION_PROCESSING_STOP));
        }

        String lastDetails()
        {
            return mEvents.get(mEvents.size() - 1).getDetails();
        }
    }

    private static NXDNChannel channel()
    {
        NXDNChannelLookup channel = new NXDNChannelLookup(CHANNEL_NUMBER);
        channel.receive(null, Map.of(CHANNEL_NUMBER, new ChannelFrequency(CHANNEL_NUMBER, FREQUENCY, FREQUENCY)));
        return channel;
    }

    private static EncryptionKeyIdentifier encryption(int cipher)
    {
        return EncryptionKeyIdentifier.create(Protocol.NXDN, NXDNEncryptionKey.create(cipher, cipher == 0 ? 0 : 1));
    }

    private static List<Identifier> identifiers(int cipher)
    {
        return List.of(NXDNRadioIdentifier.createFrom(1), NXDNTalkgroupIdentifier.createTo(TALKGROUP), encryption(cipher));
    }

    /**
     * Voice call assignment with the call details provided directly instead of decoded from the message bits.
     */
    private static class TestVoiceCallAssignment extends VoiceCallAssignment
    {
        private final int mCipher;

        TestVoiceCallAssignment(int cipher, long timestamp)
        {
            super(new CorrectedBinaryMessage(200), timestamp, NXDNMessageType.CONTROL_OUT_04_CC_VOICE_CALL_ASSIGNMENT, 0, null);
            mCipher = cipher;
        }

        @Override public NXDNChannel getChannel() {return channel();}
        @Override public EncryptionKeyIdentifier getEncryptionKeyIdentifier() {return encryption(mCipher);}
        @Override public CallType getCallType() {return CallType.GROUP_CONFERENCE;}
        @Override public List<Identifier> getIdentifiers() {return identifiers(mCipher);}
    }

    /**
     * Traffic channel voice call with the call details provided directly instead of decoded from the message bits.
     */
    private static class TestVoiceCall extends VoiceCall
    {
        private final int mCipher;

        TestVoiceCall(int cipher, long timestamp)
        {
            super(new CorrectedBinaryMessage(200), timestamp, NXDNMessageType.TRAFFIC_OUT_01_CC_VOICE_CALL, 0, null);
            mCipher = cipher;
        }

        @Override public EncryptionKeyIdentifier getEncryptionKeyIdentifier() {return encryption(mCipher);}
        @Override public CallType getCallType() {return CallType.GROUP_CONFERENCE;}
        @Override public List<Identifier> getIdentifiers() {return identifiers(mCipher);}
    }

    @Test
    void encryptedAssignmentIsIgnored()
    {
        Harness h = new Harness(true);
        h.assignmentsEverySecond(AES, 1000, 5000);
        assertTrue(h.mStartedFrequencies.isEmpty());
        assertTrue(h.lastDetails().startsWith(NXDNTrafficChannelManager.IGNORED_ENCRYPTED), h.lastDetails());
    }

    @Test
    void encryptedAssignmentIsTunedWhenOptionDisabled()
    {
        Harness h = new Harness(false);
        h.assignment(AES, 1000);
        assertEquals(List.of(FREQUENCY), h.mStartedFrequencies);
    }

    @Test
    void clearAssignmentIsTuned()
    {
        Harness h = new Harness(true);
        h.assignment(0, 1000);
        assertEquals(List.of(FREQUENCY), h.mStartedFrequencies);
    }

    @Test
    void releasedEncryptedCallIsNotTunedAgainByContinuingAssignments()
    {
        //The assignment doesn't identify the call as encrypted, but the traffic channel voice call message does
        Harness h = new Harness(true);
        h.assignment(0, 1000);
        h.trafficVoiceCall(AES, 1200);
        h.stopLastTrafficChannel();
        assertTrue(h.lastDetails().startsWith(NXDNTrafficChannelManager.IGNORED_ENCRYPTED), h.lastDetails());

        h.assignmentsEverySecond(0, 2000, 15000);
        assertEquals(1, h.mStartedFrequencies.size(), "released encrypted call must not be tuned again");
    }

    @Test
    void encryptionFromInitializationVectorIsRemembered()
    {
        //Late entry: the traffic channel missed the voice call message and detects encryption from the IV
        Harness h = new Harness(true);
        h.assignment(0, 1000);
        h.mManager.processCallProgressUpdate(channel(), 1200);
        h.mManager.processEncryptedCall(channel(), 1300);
        h.stopLastTrafficChannel();
        h.assignmentsEverySecond(0, 2000, 10000);
        assertEquals(1, h.mStartedFrequencies.size());
    }

    @Test
    void releasedEncryptedCallIsForgottenOnceAssignmentsStop()
    {
        Harness h = new Harness(true);
        h.assignment(0, 1000);
        h.trafficVoiceCall(AES, 1200);
        h.stopLastTrafficChannel();
        h.assignment(0, 2000);
        h.assignment(0, 60_000);
        assertEquals(2, h.mStartedFrequencies.size());
    }

    @Test
    void stoppedClearCallIsTunedAgainAfterTrackerGoesStale()
    {
        //Unchanged behavior: a clear call that continues after its traffic channel stopped is tuned again
        Harness h = new Harness(true);
        h.assignment(0, 1000);
        h.trafficVoiceCall(0, 1200);
        h.stopLastTrafficChannel();
        h.assignmentsEverySecond(0, 2000, 6000);
        assertEquals(2, h.mStartedFrequencies.size());
    }

    @Test
    void encryptedTrafficCallIsTunedAgainWhenOptionDisabled()
    {
        Harness h = new Harness(false);
        h.assignment(0, 1000);
        h.trafficVoiceCall(AES, 1200);
        h.stopLastTrafficChannel();
        h.assignmentsEverySecond(0, 2000, 6000);
        assertEquals(2, h.mStartedFrequencies.size());
    }

    @Test
    void copyKeepsIgnoreEncryptedCalls()
    {
        DecodeConfigNXDN original = new DecodeConfigNXDN();
        assertFalse(original.getIgnoreEncryptedCalls(), "default is disabled");
        original.setIgnoreEncryptedCalls(true);
        assertTrue(((DecodeConfigNXDN)DecoderFactory.copy(original)).getIgnoreEncryptedCalls());
    }
}
