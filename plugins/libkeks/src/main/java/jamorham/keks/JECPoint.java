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

import static org.bouncycastle.util.BigIntegers.fromUnsignedByteArray;
import static jamorham.keks.util.Util.arrayAppend;

import org.bouncycastle.math.ec.ECPoint;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * JamOrHam
 */

@RequiredArgsConstructor
public class JECPoint {

    @Getter
    private final ECPoint point;

    static ECPoint pointFromBytes(final byte[] xBytes, final byte[] yBytes) {
        return (Curve.curve.createPoint(fromUnsignedByteArray(xBytes), fromUnsignedByteArray(yBytes)));
    }

    byte[] toBytes() {
        return arrayAppend(point.getXCoord().getEncoded(), point.getYCoord().getEncoded());
    }
}
