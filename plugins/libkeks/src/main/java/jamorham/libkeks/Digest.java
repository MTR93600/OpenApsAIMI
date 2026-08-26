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
package jamorham.libkeks;


import lombok.RequiredArgsConstructor;
import lombok.val;

/**
 * JamOrHam
 */

@RequiredArgsConstructor
public class Digest {

    private byte[] store = new byte[0];
    private final byte[] destination;

    public void update(byte[] data) {
        val n = new byte[store.length + data.length];
        System.arraycopy(data, 0, n,  store.length, data.length);
        System.arraycopy(store, 0, n, 0, store.length);
        store = n;
    }

    public void doFinal() {
        val result = SHA256.hash(store);
        System.arraycopy(result, 0, destination, 0, destination.length);
    }
}
