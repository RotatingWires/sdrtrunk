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

package io.github.dsheirer.module.decode.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.dataformat.xml.JacksonXmlModule;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.module.decode.dmr.DecodeConfigDMR;
import io.github.dsheirer.module.decode.nxdn.DecodeConfigNXDN;
import io.github.dsheirer.module.decode.p25.phase1.DecodeConfigP25Phase1;
import io.github.dsheirer.module.decode.p25.phase2.DecodeConfigP25Phase2;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that the ignore encrypted calls option is saved to and loaded from the playlist for each decoder that
 * supports it, and that playlists saved without the option load with the option disabled.
 */
public class IgnoreEncryptedCallsPersistenceTest
{
    private static XmlMapper mapper()
    {
        JacksonXmlModule module = new JacksonXmlModule();
        module.setDefaultUseWrapper(false);
        XmlMapper mapper = new XmlMapper(module);
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        return mapper;
    }

    private record Case(DecodeConfiguration config, Consumer<Boolean> setter) {}

    private static List<Case> cases()
    {
        DecodeConfigP25Phase1 p25p1 = new DecodeConfigP25Phase1();
        DecodeConfigP25Phase2 p25p2 = new DecodeConfigP25Phase2();
        DecodeConfigDMR dmr = new DecodeConfigDMR();
        DecodeConfigNXDN nxdn = new DecodeConfigNXDN();
        return List.of(new Case(p25p1, p25p1::setIgnoreEncryptedCalls), new Case(p25p2, p25p2::setIgnoreEncryptedCalls),
                new Case(dmr, dmr::setIgnoreEncryptedCalls), new Case(nxdn, nxdn::setIgnoreEncryptedCalls));
    }

    private static boolean ignoreEncrypted(Channel channel)
    {
        assertInstanceOf(WithIgnoreEncryptedCalls.class, channel.getDecodeConfiguration());
        return ((WithIgnoreEncryptedCalls)channel.getDecodeConfiguration()).getIgnoreEncryptedCalls();
    }

    @Test
    void optionRoundTripsThroughThePlaylist() throws Exception
    {
        XmlMapper mapper = mapper();

        for(Case c: cases())
        {
            String name = c.config().getDecoderType().name();

            c.setter().accept(true);
            Channel channel = new Channel(name);
            channel.setDecodeConfiguration(c.config());
            String xml = mapper.writeValueAsString(channel);
            assertTrue(xml.contains("ignore_encrypted_calls=\"true\""), name + ": " + xml);
            assertTrue(ignoreEncrypted(mapper.readValue(xml, Channel.class)), name);

            c.setter().accept(false);
            assertFalse(ignoreEncrypted(mapper.readValue(mapper.writeValueAsString(channel), Channel.class)), name);
        }
    }

    @Test
    void playlistWithoutTheOptionLoadsDisabled() throws Exception
    {
        XmlMapper mapper = mapper();

        for(Case c: cases())
        {
            Channel channel = new Channel(c.config().getDecoderType().name());
            channel.setDecodeConfiguration(c.config());
            String legacy = mapper.writeValueAsString(channel).replace(" ignore_encrypted_calls=\"false\"", "");
            assertFalse(legacy.contains("ignore_encrypted_calls"), legacy);
            assertFalse(ignoreEncrypted(mapper.readValue(legacy, Channel.class)), legacy);
        }
    }
}
