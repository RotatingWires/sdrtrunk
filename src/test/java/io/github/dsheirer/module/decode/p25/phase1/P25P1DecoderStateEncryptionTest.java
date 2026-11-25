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
package io.github.dsheirer.module.decode.p25.phase1;

import io.github.dsheirer.bits.BinaryMessage;
import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.channel.state.State;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.identifier.MutableIdentifierCollection;
import io.github.dsheirer.module.decode.p25.P25TrafficChannelManager;
import io.github.dsheirer.module.decode.p25.phase1.message.ldu.EncryptionSyncParameters;
import io.github.dsheirer.module.decode.p25.phase1.message.ldu.LDU2Message;
import io.github.dsheirer.module.decode.p25.phase1.message.tdu.TDUMessage;
import io.github.dsheirer.module.decode.p25.reference.Encryption;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Tests that a single LDU2 that reports encryption during a clear call (ie a corrupted frame that the Reed-Solomon
 * decoder mis-corrected) doesn't put the call in the ENCRYPTED state, which releases the traffic channel when ignoring
 * encrypted calls, while a call that is encrypted still enters the ENCRYPTED state.
 */
public class P25P1DecoderStateEncryptionTest
{
    private static final int NAC = 0x293;
    private static final int UNKNOWN_ALGORITHM = 215; //Reported by a mis-corrected LDU2 during a clear call
    private static final int KEY = 6458;
    private P25P1DecoderState mDecoderState;
    private final MutableIdentifierCollection mIdentifiers = new MutableIdentifierCollection();
    private final List<State> mStates = new ArrayList<>();
    private long mTimestamp = 1_700_000_000_000L;

    @BeforeEach
    public void setup()
    {
        Channel channel = new Channel("Test");
        channel.setDecodeConfiguration(new DecodeConfigP25Phase1());
        P25TrafficChannelManager trafficChannelManager = new P25TrafficChannelManager(channel);
        mDecoderState = new P25P1DecoderState(channel, trafficChannelManager);
        mDecoderState.setDecoderStateListener(event -> mStates.add(event.getState()));
        mDecoderState.setIdentifierUpdateListener(mIdentifiers);
        mDecoderState.start();
    }

    /**
     * LDU2 carrying the encryption sync parameters for the algorithm and key.
     */
    private void ldu2(int algorithm, int key)
    {
        BinaryMessage message = new BinaryMessage(96);
        message.load(0, 72, 0x123456789ABCDEL); //Message indicator
        message.load(72, 8, algorithm);
        message.load(80, 16, key);
        EncryptionSyncParameters esp = new EncryptionSyncParameters(message);

        mDecoderState.receive(new LDU2Message(new CorrectedBinaryMessage(1568), NAC, mTimestamp)
        {
            @Override
            public EncryptionSyncParameters getEncryptionSyncParameters()
            {
                return esp;
            }
        });

        mTimestamp += 360;
    }

    private void clearLDU2()
    {
        ldu2(Encryption.UNENCRYPTED.getValue(), 0);
    }

    private void endCall()
    {
        mDecoderState.receive(new TDUMessage(new CorrectedBinaryMessage(28), NAC, mTimestamp));
        mTimestamp += 500;
    }

    /**
     * State of the last decoder state event, or null when there were none since the last call.
     */
    private State lastState()
    {
        State state = mStates.isEmpty() ? null : mStates.getLast();
        mStates.clear();
        return state;
    }

    @Test
    public void singleEncryptedLDU2DuringClearCallIsIgnored()
    {
        clearLDU2();
        assertEquals(State.CALL, lastState());

        ldu2(UNKNOWN_ALGORITHM, KEY);
        assertNull(lastState(), "unconfirmed encryption changes the state");
        assertNull(mIdentifiers.getEncryptionIdentifier(), "unconfirmed encryption key added to the call");

        clearLDU2();
        assertEquals(State.CALL, lastState());

        //A single mis-corrected LDU2 later in the call doesn't confirm the earlier one
        ldu2(UNKNOWN_ALGORITHM, KEY);
        assertNull(lastState());
        clearLDU2();
        assertFalse(mStates.contains(State.ENCRYPTED));
    }

    @Test
    public void confirmedEncryptionDuringClearCallEntersEncrypted()
    {
        clearLDU2();
        assertEquals(State.CALL, lastState());

        ldu2(Encryption.AES_256.getValue(), KEY);
        assertNull(lastState());

        ldu2(Encryption.AES_256.getValue(), KEY);
        assertEquals(State.ENCRYPTED, lastState());
        assertNotNull(mIdentifiers.getEncryptionIdentifier());
    }

    @Test
    public void differentEncryptionKeysDontConfirmEachOther()
    {
        clearLDU2();
        lastState();

        ldu2(UNKNOWN_ALGORITHM, KEY);
        ldu2(Encryption.AES_256.getValue(), KEY + 1);
        assertNull(lastState(), "two different mis-corrected LDU2s confirmed encryption");

        ldu2(Encryption.AES_256.getValue(), KEY + 1);
        assertEquals(State.ENCRYPTED, lastState());
    }

    @Test
    public void encryptedCallEntersEncryptedAtTheFirstLDU2()
    {
        //Late entry: the first LDU2 of the call establishes the encryption state
        ldu2(Encryption.AES_256.getValue(), KEY);
        assertEquals(State.ENCRYPTED, lastState());

        //The next call starts clear, so it doesn't need confirmation either way after the call ends
        endCall();
        lastState();
        clearLDU2();
        assertEquals(State.CALL, lastState());
        endCall();
        lastState();
        ldu2(Encryption.AES_256.getValue(), KEY);
        assertEquals(State.ENCRYPTED, lastState());
    }

    @Test
    public void unconfirmedEncryptionDoesntCarryOverToTheNextCall()
    {
        clearLDU2();
        ldu2(Encryption.AES_256.getValue(), KEY);
        endCall();

        //The next call is clear: its first LDU2 is clear and a single encrypted LDU2 still needs confirmation
        clearLDU2();
        lastState();
        ldu2(Encryption.AES_256.getValue(), KEY);
        assertNull(lastState(), "unconfirmed encryption carried over from the previous call");
        assertNull(mIdentifiers.getEncryptionIdentifier());
    }
}
