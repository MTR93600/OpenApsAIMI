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
package jamorham.keks.message;

import jamorham.keks.Plugin;
import lombok.val;

/**
 * JamOrHam
 */

public class CertInfoTxMessage extends BaseMessage {

    public static final byte opcode = 0x0b;

    public static byte[] expectMyCert(final Plugin plugin, final int which) {
        val p = new CertInfoTxMessage();
        val c = plugin.getContext();
        p.init(opcode, 6);
        p.data.put((byte) which);
        p.data.putInt(which == 0 ? c.getPartA().length : c.getPartB().length);
        return p.getByteSequence();
    }

    public static byte[] expectMyCert1(final Plugin plugin) {
        return expectMyCert(plugin, 0);
    }

    public static byte[] expectMyCert2(final Plugin plugin) {
        return expectMyCert(plugin, 1);
    }
}