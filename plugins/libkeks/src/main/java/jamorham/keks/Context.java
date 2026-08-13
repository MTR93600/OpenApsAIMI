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
package jamorham.keks;

import static java.util.Arrays.fill;
import static jamorham.keks.Config.Get.PREFIX;
import static jamorham.keks.util.Util.arrayAppend;

import org.bouncycastle.util.BigIntegers;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;

import jamorham.keks.util.Log;
import lombok.Getter;
import lombok.Setter;

/**
 * JamOrHam
 * <p>
 * KEKS context
 */

public class Context {

    public volatile KeyPair keyA;
    public volatile KeyPair KeyB;
    public volatile String password;
    public volatile byte[] passwordBytes;
    public volatile byte[] alice;
    public volatile byte[] bob;
    public volatile byte[] challenge;
    public volatile byte[] savedKey;
    public volatile Packet[] packet = new Packet[4];
    public volatile int sequence;

    @Getter @Setter
    private volatile byte[] partA;
    @Getter @Setter
    private volatile byte[] partB;
    @Getter @Setter
    private volatile byte[] partC;

    public boolean validateParts() {
        return partA != null && partB != null && partC != null && partA.length > 100 && partB.length > 100 && partC.length > 100;
    }

    public void reset() {
        savedKey = null;
        fill(packet, null);
    }

    public void resetIfNotReady() {
        sequence = 0;
        if (savedKey == null && getRound3Packet() == null) {
            reset();
        }
    }

    public byte[] getPasswordBytes() {
        if (password == null) {
            Log.l("Context password not set");
            throw new RuntimeException();
        }
        if (passwordBytes == null) {
            passwordBytes = password.getBytes(StandardCharsets.UTF_8);
            if (password.length() == 6) {
                passwordBytes = arrayAppend(PREFIX.bytes, passwordBytes);
            }
        }
        return passwordBytes;
    }

    public BigInteger getPasswordBigInteger() {
        return BigIntegers.fromUnsignedByteArray(getPasswordBytes());
    }

    public Packet getRound1Packet() {
        return packet[1];
    }

    public Packet getRound2Packet() {
        return packet[2];
    }

    public Packet getRound3Packet() {
        return packet[3];
    }

}
