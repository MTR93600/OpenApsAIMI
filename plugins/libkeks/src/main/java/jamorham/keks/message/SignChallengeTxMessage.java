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

import static jamorham.keks.util.Util.getRandomKey;

import java.nio.ByteBuffer;

import lombok.Getter;

/**
 * JamOrHam
 */

public class SignChallengeTxMessage extends BaseMessage {

    byte opcode = 0x0c;
    @Getter
    byte[] challengeHash;

    public SignChallengeTxMessage(byte[] challenge) {
        challengeHash = challenge;
        data = ByteBuffer.allocate(17);
        data.put(opcode);
        data.put(challengeHash);
        byteSequence = data.array();
    }

    public SignChallengeTxMessage() {
        this(getRandomKey());
    }

}
