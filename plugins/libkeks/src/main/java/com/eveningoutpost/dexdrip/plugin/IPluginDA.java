/*
 * KEKS J-PAKE / ExtraData authentication for Dexcom G7 / ONE+.
 *
 * Vendored from NightscoutFoundation/xDrip
 *   https://github.com/NightscoutFoundation/xDrip
 *   pin commit 1e86d9a2a52577ed2c30fbf7b69d75fd56e6918f (tag 2026.07.15)
 * Authorship per upstream comments: JamOrHam / jamorham. Upstream carries no
 * formal per-file "Copyright (C) <year> <name>" line; none is asserted or
 * invented here. See plugins/libkeks/NOTICE.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Part of OpenApsAIMI (AGPL-3.0-or-later); these KEKS sources retain their
 * upstream GPL-3.0 terms. Free software, distributed WITHOUT ANY WARRANTY.
 * See https://www.gnu.org/licenses/gpl-3.0.html
 */
package com.eveningoutpost.dexdrip.plugin;

import androidx.annotation.Keep;

/**
 * JamOrHam
 *
 * Simple plugin data exchange interface
 */

@Keep
public interface IPluginDA {

    byte[][] aNext();

    byte[][] bNext();

    byte[][] cNext();

    void amConnected();

    boolean bondNow(final byte[] data);

    boolean receivedResponse(final byte[] data);

    boolean receivedResponse2(final byte[] data);

    boolean receivedResponse3(final byte[] data);

    boolean receivedData(final byte[] data);

    boolean receivedData2(final byte[] data);

    boolean receivedData3(final byte[] data);

    byte[] getPersistence(final int channel);

    boolean setPersistence(final int channel, final byte[] data);

    String getStatus();

    String getName();

}
