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

package io.github.dsheirer.module.decode.dmr;

import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.Subscribe;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.controller.channel.ChannelEvent;
import io.github.dsheirer.controller.channel.event.ChannelStartProcessingRequest;
import io.github.dsheirer.identifier.MutableIdentifierCollection;
import io.github.dsheirer.module.decode.DecoderFactory;
import io.github.dsheirer.module.decode.dmr.channel.DMRAbsoluteChannel;
import io.github.dsheirer.module.decode.dmr.channel.DMRChannel;
import io.github.dsheirer.module.decode.dmr.event.DMRDecodeEvent;
import io.github.dsheirer.module.decode.dmr.identifier.DMRRadio;
import io.github.dsheirer.module.decode.dmr.identifier.DMRTalkgroup;
import io.github.dsheirer.module.decode.dmr.message.data.csbk.Opcode;
import io.github.dsheirer.module.decode.event.DecodeEventType;
import io.github.dsheirer.module.decode.event.IDecodeEvent;
import io.github.dsheirer.source.config.SourceConfigTuner;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the ignore encrypted calls option of the DMR traffic channel manager: encrypted channel grants don't tune
 * a traffic channel, and a traffic channel that was released because it detected an encrypted call isn't tuned again
 * by the channel grant updates that continue for the call.
 */
public class DMRTrafficChannelManagerEncryptedTest
{
    private static final long FREQUENCY_1 = 452_000_000L;
    private static final long FREQUENCY_2 = 452_500_000L;
    private static final int TALKGROUP_A = 100;
    private static final int TALKGROUP_B = 200;
    private static final Opcode GRANT = Opcode.STANDARD_TALKGROUP_VOICE_CHANNEL_GRANT;

    /**
     * Captures traffic channel start requests and decode events.
     */
    private static class Harness
    {
        private final DMRTrafficChannelManager mManager;
        private final List<Long> mStartedFrequencies = new ArrayList<>();
        private final List<Channel> mStartedChannels = new ArrayList<>();
        private final List<IDecodeEvent> mEvents = new ArrayList<>();

        Harness(boolean ignoreEncrypted)
        {
            DecodeConfigDMR config = new DecodeConfigDMR();
            config.setIgnoreEncryptedCalls(ignoreEncrypted);
            Channel parent = new Channel("control");
            parent.setDecodeConfiguration(config);
            mManager = new DMRTrafficChannelManager(parent);
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

        void grant(long frequency, int timeslot, int talkgroup, int radio, boolean encrypted, long timestamp)
        {
            mManager.processChannelGrant(channel(frequency, timeslot), ic(talkgroup, radio), GRANT, timestamp, encrypted);
        }

        /**
         * Traffic channel reports that the call on the timeslot is encrypted (ie voice link control service options)
         */
        void trafficEncryptedCall(long frequency, int timeslot, int talkgroup, int radio, long timestamp)
        {
            IDecodeEvent event = DMRDecodeEvent.builder(DecodeEventType.CALL_GROUP_ENCRYPTED, timestamp)
                    .channel(channel(frequency, timeslot))
                    .identifiers(ic(talkgroup, radio))
                    .timeslot(timeslot)
                    .build();
            mManager.receiveTrafficChannelEvent(event);
        }

        void trafficClearCall(long frequency, int timeslot, int talkgroup, int radio, long timestamp)
        {
            IDecodeEvent event = DMRDecodeEvent.builder(DecodeEventType.CALL_GROUP, timestamp)
                    .channel(channel(frequency, timeslot))
                    .identifiers(ic(talkgroup, radio))
                    .timeslot(timeslot)
                    .build();
            mManager.receiveTrafficChannelEvent(event);
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

    private static DMRChannel channel(long frequency, int timeslot)
    {
        return new DMRAbsoluteChannel(1, timeslot, frequency, 0);
    }

    private static MutableIdentifierCollection ic(int talkgroup, int radio)
    {
        MutableIdentifierCollection mic = new MutableIdentifierCollection();
        mic.update(DMRTalkgroup.create(talkgroup));
        mic.update(DMRRadio.createFrom(radio));
        return mic;
    }

    @Test
    void encryptedGrantIsIgnoredIncludingRepeatedGrants()
    {
        Harness h = new Harness(true);
        h.grant(FREQUENCY_1, 1, TALKGROUP_A, 1, true, 1000);
        h.grant(FREQUENCY_1, 1, TALKGROUP_A, 1, true, 1500);
        h.grant(FREQUENCY_1, 1, TALKGROUP_A, 1, true, 2000);
        assertTrue(h.mStartedFrequencies.isEmpty());
        assertTrue(h.lastDetails().startsWith(DMRTrafficChannelManager.ENCRYPTED_CALL_IGNORED), h.lastDetails());
    }

    @Test
    void encryptedGrantIsTunedWhenOptionDisabled()
    {
        Harness h = new Harness(false);
        h.grant(FREQUENCY_1, 1, TALKGROUP_A, 1, true, 1000);
        assertEquals(List.of(FREQUENCY_1), h.mStartedFrequencies);
    }

    @Test
    void clearGrantIsTuned()
    {
        Harness h = new Harness(true);
        h.grant(FREQUENCY_1, 1, TALKGROUP_A, 1, false, 1000);
        assertEquals(List.of(FREQUENCY_1), h.mStartedFrequencies);
    }

    @Test
    void clearCallAfterIgnoredEncryptedCallFromDifferentTalkerIsTuned()
    {
        Harness h = new Harness(true);
        h.grant(FREQUENCY_1, 1, TALKGROUP_A, 1, true, 1000);
        h.grant(FREQUENCY_1, 1, TALKGROUP_A, 2, false, 1500);
        assertEquals(List.of(FREQUENCY_1), h.mStartedFrequencies);
    }

    @Test
    void releasedEncryptedCallIsNotTunedAgainByContinuingGrants()
    {
        //The grant doesn't identify the call as encrypted, but the traffic channel does
        Harness h = new Harness(true);
        h.grant(FREQUENCY_1, 1, TALKGROUP_A, 1, false, 1000);
        h.trafficEncryptedCall(FREQUENCY_1, 1, TALKGROUP_A, 1, 1200);
        h.stopLastTrafficChannel();

        //Capacity Max and Tier III control channels keep sending channel updates/grants for the call
        for(long ts = 2000; ts <= 20000; ts += 1000)
        {
            h.grant(FREQUENCY_1, 1, TALKGROUP_A, 1, false, ts);
        }

        assertEquals(1, h.mStartedFrequencies.size(), "released encrypted call must not be tuned again");
        assertTrue(h.lastDetails().startsWith(DMRTrafficChannelManager.ENCRYPTED_CALL_IGNORED), h.lastDetails());
    }

    @Test
    void releasedEncryptedCallIsForgottenOnceGrantsStop()
    {
        Harness h = new Harness(true);
        h.grant(FREQUENCY_1, 1, TALKGROUP_A, 1, false, 1000);
        h.trafficEncryptedCall(FREQUENCY_1, 1, TALKGROUP_A, 1, 1200);
        h.stopLastTrafficChannel();
        h.grant(FREQUENCY_1, 1, TALKGROUP_A, 1, false, 2000);
        assertEquals(1, h.mStartedFrequencies.size());

        //A later clear call on the same talkgroup and channel is tuned
        h.grant(FREQUENCY_1, 1, TALKGROUP_A, 1, false, 60_000);
        assertEquals(2, h.mStartedFrequencies.size());
    }

    @Test
    void releasedEncryptedCallDoesNotBlockOtherCalls()
    {
        Harness h = new Harness(true);
        h.grant(FREQUENCY_1, 1, TALKGROUP_A, 1, false, 1000);
        h.trafficEncryptedCall(FREQUENCY_1, 1, TALKGROUP_A, 1, 1200);
        h.stopLastTrafficChannel();

        //Different talkgroup on the same timeslot, the other timeslot, and another frequency
        h.grant(FREQUENCY_1, 1, TALKGROUP_B, 2, false, 2000);
        h.stopLastTrafficChannel();
        h.grant(FREQUENCY_1, 2, TALKGROUP_A, 3, false, 2500);
        h.stopLastTrafficChannel();
        h.grant(FREQUENCY_2, 1, TALKGROUP_B, 4, false, 3000);
        assertEquals(List.of(FREQUENCY_1, FREQUENCY_1, FREQUENCY_1, FREQUENCY_2), h.mStartedFrequencies);
    }

    @Test
    void encryptedGrantOnAllocatedFrequencyIsHandledByRunningTrafficChannel()
    {
        //Timeslot 2 clear call tunes the frequency and the running traffic channel processes both timeslots
        Harness h = new Harness(true);
        h.grant(FREQUENCY_1, 2, TALKGROUP_B, 2, false, 1000);
        h.grant(FREQUENCY_1, 1, TALKGROUP_A, 1, true, 1100);
        assertEquals(List.of(FREQUENCY_1), h.mStartedFrequencies);
    }

    @Test
    void stoppedClearCallIsTunedAgainForContinuingGrants()
    {
        //Unchanged behavior: the call events for a stopped clear call are removed
        Harness h = new Harness(true);
        h.grant(FREQUENCY_1, 1, TALKGROUP_A, 1, false, 1000);
        h.trafficClearCall(FREQUENCY_1, 1, TALKGROUP_A, 1, 1200);
        h.stopLastTrafficChannel();
        h.grant(FREQUENCY_1, 1, TALKGROUP_A, 1, false, 2000);
        assertEquals(2, h.mStartedFrequencies.size());
    }

    @Test
    void encryptedTrafficCallIsTunedAgainWhenOptionDisabled()
    {
        Harness h = new Harness(false);
        h.grant(FREQUENCY_1, 1, TALKGROUP_A, 1, false, 1000);
        h.trafficEncryptedCall(FREQUENCY_1, 1, TALKGROUP_A, 1, 1200);
        h.stopLastTrafficChannel();
        h.grant(FREQUENCY_1, 1, TALKGROUP_A, 1, false, 2000);
        assertEquals(2, h.mStartedFrequencies.size());
    }

    @Test
    void trafficChannelsShareTheParentSetting()
    {
        Harness h = new Harness(true);
        h.grant(FREQUENCY_1, 1, TALKGROUP_A, 1, false, 1000);
        assertTrue(((DecodeConfigDMR)h.mStartedChannels.get(0).getDecodeConfiguration()).getIgnoreEncryptedCalls());
    }

    @Test
    void copyKeepsIgnoreEncryptedCalls()
    {
        DecodeConfigDMR original = new DecodeConfigDMR();
        assertFalse(original.getIgnoreEncryptedCalls(), "default is disabled");
        original.setIgnoreEncryptedCalls(true);
        assertTrue(((DecodeConfigDMR)DecoderFactory.copy(original)).getIgnoreEncryptedCalls());
    }
}
