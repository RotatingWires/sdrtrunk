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

package io.github.dsheirer.channel.state;

import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.channel.state.DecoderStateEvent.Event;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.controller.channel.Channel.ChannelType;
import io.github.dsheirer.controller.channel.ChannelEvent;
import io.github.dsheirer.module.decode.config.DecodeConfiguration;
import io.github.dsheirer.module.decode.dmr.DecodeConfigDMR;
import io.github.dsheirer.module.decode.nbfm.DecodeConfigNBFM;
import io.github.dsheirer.module.decode.nxdn.DecodeConfigNXDN;
import io.github.dsheirer.module.decode.p25.phase1.DecodeConfigP25Phase1;
import io.github.dsheirer.module.decode.p25.phase2.DecodeConfigP25Phase2;
import io.github.dsheirer.source.config.SourceConfigTuner;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that a traffic channel that detects an encrypted call is released (ie a channel disable request is issued
 * so that its tuner channel is freed) when the decoder configuration ignores encrypted calls, and that it isn't
 * released in any other situation.
 */
public class EncryptedTrafficChannelReleaseTest
{
    private static final int TS1 = 1;
    private static final int TS2 = 2;

    /**
     * Channel state under test with captured channel disable requests.
     */
    private static class Harness
    {
        private final AbstractChannelState mState;
        private final List<ChannelEvent> mDisableRequests = new ArrayList<>();

        Harness(AbstractChannelState state)
        {
            mState = state;
            mState.setChannelEventListener(event -> {
                if(event.getEvent() == ChannelEvent.Event.REQUEST_DISABLE)
                {
                    mDisableRequests.add(event);
                }
            });
            mState.start();
        }

        void send(Event event, State state, int timeslot)
        {
            ((IDecoderStateEventListener)mState).getDecoderStateListener()
                    .receive(new DecoderStateEvent(this, event, state, timeslot));
        }

        int disableRequests()
        {
            return mDisableRequests.size();
        }
    }

    private static Channel channel(ChannelType type, DecodeConfiguration config)
    {
        Channel channel = new Channel("test", type);
        channel.setDecodeConfiguration(config);
        SourceConfigTuner sourceConfig = new SourceConfigTuner();
        sourceConfig.setFrequency(851_000_000L);
        channel.setSourceConfiguration(sourceConfig);
        return channel;
    }

    private static DecodeConfigP25Phase1 p25p1(boolean ignoreEncrypted)
    {
        DecodeConfigP25Phase1 config = new DecodeConfigP25Phase1();
        config.setIgnoreEncryptedCalls(ignoreEncrypted);
        return config;
    }

    private static DecodeConfigP25Phase2 p25p2(boolean ignoreEncrypted)
    {
        DecodeConfigP25Phase2 config = new DecodeConfigP25Phase2();
        config.setIgnoreEncryptedCalls(ignoreEncrypted);
        return config;
    }

    private static DecodeConfigDMR dmr(boolean ignoreEncrypted)
    {
        DecodeConfigDMR config = new DecodeConfigDMR();
        config.setIgnoreEncryptedCalls(ignoreEncrypted);
        return config;
    }

    private static DecodeConfigNXDN nxdn(boolean ignoreEncrypted)
    {
        DecodeConfigNXDN config = new DecodeConfigNXDN();
        config.setIgnoreEncryptedCalls(ignoreEncrypted);
        return config;
    }

    private static Harness single(ChannelType type, DecodeConfiguration config)
    {
        return new Harness(new SingleChannelState(channel(type, config), new AliasModel()));
    }

    private static Harness multi(ChannelType type, DecodeConfiguration config)
    {
        return new Harness(new MultiChannelState(channel(type, config), new AliasModel(), new int[]{TS1, TS2}));
    }

    @Test
    void singleTimeslotEncryptedCallReleasesTrafficChannel()
    {
        for(DecodeConfiguration config: List.of(p25p1(true), nxdn(true)))
        {
            Harness h = single(ChannelType.TRAFFIC, config);
            h.send(Event.START, State.ENCRYPTED, 0);
            assertEquals(1, h.disableRequests(), config.getDecoderType().name());
        }
    }

    @Test
    void singleTimeslotEncryptedCallIsKeptWhenOptionDisabled()
    {
        Harness h = single(ChannelType.TRAFFIC, p25p1(false));
        h.send(Event.START, State.ENCRYPTED, 0);
        h.send(Event.CONTINUATION, State.ENCRYPTED, 0);
        assertEquals(0, h.disableRequests());
    }

    @Test
    void singleTimeslotClearCallIsKept()
    {
        Harness h = single(ChannelType.TRAFFIC, p25p1(true));
        h.send(Event.START, State.CALL, 0);
        h.send(Event.CONTINUATION, State.CALL, 0);
        h.send(Event.CONTINUATION, State.DATA, 0);
        assertEquals(0, h.disableRequests());
    }

    @Test
    void clearCallThatTurnsOutEncryptedIsReleased()
    {
        //HDU without encryption followed by an LDU2 that identifies the call as encrypted
        Harness h = single(ChannelType.TRAFFIC, p25p1(true));
        h.send(Event.START, State.CALL, 0);
        assertEquals(0, h.disableRequests());
        h.send(Event.CONTINUATION, State.ENCRYPTED, 0);
        assertEquals(1, h.disableRequests());
    }

    @Test
    void repeatedEncryptedFramesRequestReleaseOnce()
    {
        Harness h = single(ChannelType.TRAFFIC, p25p1(true));
        for(int x = 0; x < 20; x++)
        {
            h.send(Event.CONTINUATION, State.ENCRYPTED, 0);
        }
        assertEquals(1, h.disableRequests());
    }

    @Test
    void reusedTrafficChannelCanBeReleasedAgain()
    {
        Harness h = single(ChannelType.TRAFFIC, p25p1(true));
        h.send(Event.START, State.ENCRYPTED, 0);
        assertEquals(1, h.disableRequests());

        //The processing chain is cached and reused for the next traffic channel grant
        h.mState.reset();
        h.mState.start();
        h.send(Event.START, State.ENCRYPTED, 0);
        assertEquals(2, h.disableRequests());
    }

    @Test
    void conventionalChannelIsNeverReleased()
    {
        Harness single = single(ChannelType.STANDARD, p25p1(true));
        single.send(Event.START, State.ENCRYPTED, 0);
        assertEquals(0, single.disableRequests());

        Harness multi = multi(ChannelType.STANDARD, dmr(true));
        multi.send(Event.START, State.ENCRYPTED, TS1);
        multi.send(Event.START, State.ENCRYPTED, TS2);
        assertEquals(0, multi.disableRequests());
    }

    @Test
    void decoderWithoutTheOptionIsNeverReleased()
    {
        Harness h = single(ChannelType.TRAFFIC, new DecodeConfigNBFM());
        h.send(Event.START, State.ENCRYPTED, 0);
        assertEquals(0, h.disableRequests());
    }

    @Test
    void twoTimeslotEncryptedCallWithIdleOtherTimeslotIsReleased()
    {
        for(DecodeConfiguration config: List.of(p25p2(true), dmr(true)))
        {
            Harness h = multi(ChannelType.TRAFFIC, config);
            h.send(Event.START, State.ENCRYPTED, TS1);
            assertEquals(1, h.disableRequests(), config.getDecoderType().name());
        }
    }

    @Test
    void twoTimeslotEncryptedCallWithActiveButNotCallingOtherTimeslotIsReleased()
    {
        //P25 Phase 2 idle/hangtime MAC messages put the other timeslot in an ACTIVE state without a call
        Harness h = multi(ChannelType.TRAFFIC, p25p2(true));
        h.send(Event.CONTINUATION, State.ACTIVE, TS2);
        h.send(Event.START, State.ENCRYPTED, TS1);
        assertEquals(1, h.disableRequests());
    }

    @Test
    void twoTimeslotEncryptedCallIsKeptWhileOtherTimeslotHasClearCall()
    {
        for(DecodeConfiguration config: List.of(p25p2(true), dmr(true)))
        {
            Harness h = multi(ChannelType.TRAFFIC, config);
            h.send(Event.START, State.CALL, TS2);
            h.send(Event.START, State.ENCRYPTED, TS1);
            h.send(Event.CONTINUATION, State.ENCRYPTED, TS1);
            h.send(Event.CONTINUATION, State.CALL, TS2);
            assertEquals(0, h.disableRequests(), config.getDecoderType().name());
        }
    }

    @Test
    void twoTimeslotEncryptedCallIsKeptWhileOtherTimeslotHasData()
    {
        Harness h = multi(ChannelType.TRAFFIC, dmr(true));
        h.send(Event.START, State.DATA, TS2);
        h.send(Event.START, State.ENCRYPTED, TS1);
        assertEquals(0, h.disableRequests());
    }

    @Test
    void twoTimeslotChannelIsReleasedWhenOtherTimeslotClearCallEnds()
    {
        //Clear call ends with a fade
        Harness fade = multi(ChannelType.TRAFFIC, dmr(true));
        fade.send(Event.START, State.CALL, TS2);
        fade.send(Event.START, State.ENCRYPTED, TS1);
        assertEquals(0, fade.disableRequests());
        fade.send(Event.END, State.FADE, TS2);
        assertEquals(1, fade.disableRequests());

        //Clear call ends and the timeslot goes to hangtime (ACTIVE)
        Harness hangtime = multi(ChannelType.TRAFFIC, p25p2(true));
        hangtime.send(Event.START, State.CALL, TS1);
        hangtime.send(Event.START, State.ENCRYPTED, TS2);
        assertEquals(0, hangtime.disableRequests());
        hangtime.send(Event.CONTINUATION, State.ACTIVE, TS1);
        assertEquals(1, hangtime.disableRequests());
    }

    @Test
    void twoTimeslotBothEncryptedRequestsReleaseOnce()
    {
        Harness h = multi(ChannelType.TRAFFIC, p25p2(true));
        h.send(Event.START, State.ENCRYPTED, TS1);
        h.send(Event.START, State.ENCRYPTED, TS2);
        h.send(Event.CONTINUATION, State.ENCRYPTED, TS1);
        assertEquals(1, h.disableRequests());
    }

    @Test
    void twoTimeslotEncryptedCallIsKeptWhenOptionDisabled()
    {
        Harness h = multi(ChannelType.TRAFFIC, dmr(false));
        h.send(Event.START, State.ENCRYPTED, TS1);
        h.send(Event.START, State.ENCRYPTED, TS2);
        assertEquals(0, h.disableRequests());
    }

    @Test
    void twoTimeslotClearCallsAreKept()
    {
        Harness h = multi(ChannelType.TRAFFIC, dmr(true));
        h.send(Event.START, State.CALL, TS1);
        h.send(Event.START, State.CALL, TS2);
        h.send(Event.END, State.FADE, TS1);
        assertEquals(0, h.disableRequests());
        assertTrue(h.mDisableRequests.isEmpty());
    }
}
