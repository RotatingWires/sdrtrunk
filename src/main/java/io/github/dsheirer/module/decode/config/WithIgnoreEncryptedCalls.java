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

/**
 * Trait of trunked decoder configurations that support ignoring encrypted calls.  When enabled, the traffic channel
 * manager doesn't allocate (ie tune) a traffic channel for a channel grant that identifies the call as encrypted, and
 * a traffic channel that detects an encrypted call after it is tuned is released so that its tuner channel is
 * available for other calls.
 */
public interface WithIgnoreEncryptedCalls
{
    /**
     * @return true to ignore encrypted calls
     */
    boolean getIgnoreEncryptedCalls();
}
