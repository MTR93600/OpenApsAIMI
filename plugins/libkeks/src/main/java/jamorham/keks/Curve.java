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


import static java.math.BigInteger.ONE;

import org.bouncycastle.jce.ECNamedCurveTable;
import org.bouncycastle.jce.spec.ECNamedCurveParameterSpec;
import org.bouncycastle.math.ec.ECCurve;
import org.bouncycastle.math.ec.ECPoint;
import org.bouncycastle.util.BigIntegers;

import java.math.BigInteger;
import java.security.SecureRandom;

/**
 * JamOrHam
 *
 * KEKS Elliptic Curve
 */

public class Curve {

    private static final SecureRandom random = new SecureRandom();
    public static final String name = "secp256r1";
    public static final ECNamedCurveParameterSpec curveSpec = ECNamedCurveTable.getParameterSpec(name);
    public static final ECPoint G = curveSpec.getG();
    public static final ECCurve curve = curveSpec.getCurve();
    public static final BigInteger Q = curve.getOrder();
    public static final BigInteger QM1 = Q.subtract(ONE);
    public static final int CURVE_BITS = curve.getFieldSize();
    public static final int FIELD_SIZE = (CURVE_BITS + 7) / 8;
    public static final int PACKET_SIZE = FIELD_SIZE * 5;

    public static BigInteger getExponent() {
        return BigIntegers.createRandomInRange(ONE, QM1, random);
    }

}
