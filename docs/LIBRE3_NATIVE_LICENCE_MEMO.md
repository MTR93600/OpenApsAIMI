# Licence and provenance memo — Libre 3 / Libre 3 Plus native CGM (lot A1)

**Date:** 2026-08-19
**Mission file:** `docs/LIBRE3_NATIVE_AGENT_PLAN.md` (lot A1)
**Scope:** Libre 3 and Libre 3 Plus only. Docs and NOTICE only in this lot — no Kotlin.
**Status:** not user-confirmed. Native Libre 3 is not the production path (see plan section 10).
**Disclaimer:** this is not legal advice. The maintainer signs off, and should ask a lawyer before
any distribution outside this fork's normal source offer.

---

## 1. Verdict

| Question | Answer |
|----------|--------|
| Are both protocol sources MIT? | **Yes, both confirmed by reading their `LICENSE` files.** |
| Can MIT code go into this AGPL-3 fork? | **Yes.** MIT is permissive and one-way compatible with AGPL-3. |
| Is a NOTICE needed? | **Yes.** MIT requires the copyright and permission notice to travel with the code. Done: `plugins/libre3/NOTICE`. |
| Is the port allowed to start? | **Yes for lots A2-A12**, with the residual risk in section 4 accepted by the maintainer. |

---

## 2. Upstream sources and pins

| Field | LibreCRKit | LibreLoop |
|-------|------------|-----------|
| Repository | https://github.com/airedev326/LibreCRKit | https://github.com/LoopKit/LibreLoop |
| Licence | MIT (`LICENSE` at root) | MIT (`LICENSE` at root) |
| Copyright line | `Copyright (c) 2026 LibreCRKit contributors` | `Copyright (c) 2026 loopkitdev` |
| Pin used by this port | `a86b92f9e0807b2a6e12a896360833aeef49f5ba` | `e4a4642dde228ef705e20548b391bec6bcfbbf9a` |
| Role | protocol owner; the Swift parsers are wire truth | live handshake rules, CCCD set, key storage |

Notes on the LibreCRKit pin:

- The plan records `66920c6` as the pin seen in the Loop `Package.resolved`. That commit exists
  (2026-07-11) and the diff up to `a86b92f` is small and additive: scanner, sensor session,
  `PatchStatus`, `PairingFlow` and one data-plane test.
- Upstream `main` has since moved to `9efa81e`. The port stays on `a86b92f` so reviews by agent P
  have a stable target. Move the pin only in a lot that says so, and update the NOTICE with it.
- `protocol.md` in LibreCRKit is an overview. Where it disagrees with the Swift parsers, the Swift
  parsers win. The known example is the first-pair fallback to `0x01` on an active sensor, which is
  wrong; LibreLoop's live code is followed instead.

---

## 3. Licence effect on this fork

| Work | Licence |
|------|---------|
| OpenApsAIMI / AndroidAPS tree (`LICENSE.txt`) | GNU Affero GPL v3 |
| LibreCRKit | MIT |
| LibreLoop | MIT |

1. **Direction of flow is fine.** MIT code can be taken into an AGPL-3 work. The combined APK stays
   AGPL-3. Nothing here forces a licence change on the rest of AAPS.
2. **What we owe upstream.** Keep both copyright and permission notices. They are reproduced in full
   in `plugins/libre3/NOTICE`. Nothing else is required by MIT — no share-alike duty on our Kotlin.
3. **What we still owe our users.** AGPL-3 source duties are unchanged. Our Kotlin port is AGPL-3
   like the rest of the tree.
4. **No relicensing games.** Putting the driver in `:plugins:libre3` does not create a licence
   sandbox. The APK is one combined work. Module isolation helps maintenance and attribution only.
5. **Attribution style.** Ported files carry a short header comment naming the upstream Swift file
   and the pin. Do not invent copyright holders; both upstreams state theirs plainly.

---

## 4. The real residual risk: the LibAES tables and the phone certificate

This is the part that a maintainer must read.

The Libre 3 handshake cannot be done with standard crypto alone. Two artifacts come from the
LibreCRKit tree at `Sources/LibreCRKit/Resources/RuntimeTables`:

- `libaes_*.bin` — table banks for the white-box block primitive used by the Phase 5 wire message.
- `phone_cert_162b.bin` — the 162-byte phone certificate with family prefix `03 03`. The sibling
  file `phone_cert_firstpair.bin` has prefix `03 00` and is rejected by live sensors.

The upstream `RuntimeTables/README.md` says these are "distilled runtime artifacts from the
clean-room research corpus", "extracted from static program regions", and "fully determined by
Abbott's lib". So:

- **What MIT covers:** the upstream authors' own work — their Swift code, their ports, their
  research write-up. That grant is clear and we rely on it.
- **What MIT cannot cover:** rights the upstream authors never held. If a court saw the tables or
  the certificate as material of Abbott's, an MIT header on them would not change that.
- **What we do not do:** we ship no Abbott `.so`, no Abbott APK content, and we load nothing from an
  Abbott app at runtime. The plan bans that outright (plan section 2, bans 2 and 3).
- **Practical read:** this is the same class of risk that every open CGM driver in this space
  carries (xDrip, Juggluco, Loop plugins). It is an interoperability reverse-engineering question,
  not a licence-compatibility question, and it is a maintainer call.

**Recommendation:** accept for a fork-only, engineering-gated feature. Keep the plugin hidden behind
the `engineering_libre3` marker and keep the stub driver as the default, exactly as the plan
requires. Revisit if this feature is ever proposed for a wide release or upstream AAPS.

---

## 5. Hard bans that come from this memo

Carried from the plan, repeated here so the licence story stays true:

1. No Abbott shared object, APK content, or runtime load from an Abbott app.
2. No Juggluco source copy — no Java, no C++, no tables, no certificate loaders. Juggluco appears in
   user-facing text only as an app to close before connecting.
3. No Libre 2 / Libre 2 Plus code or UUIDs. Existing follower support (`LIBRE_2`, `LIBRE_2_NATIVE`,
   `LIBRE_3`, Glimp, Tomato) is not touched or removed.
4. The phone certificate must be the MIT 162-byte `03 03` blob. Do not generate one.
5. No claim that the native driver works until the user confirms it on a real sensor.

---

## 6. What lands where

| Item | Path |
|------|------|
| Module NOTICE (MIT texts, pins, port list) | `plugins/libre3/NOTICE` |
| This memo | `docs/LIBRE3_NATIVE_LICENCE_MEMO.md` |
| Merge constraint (seeded in A2, frozen in A11) | `docs/MERGE_CONSTRAINT_LIBRE3.md` |
| User guide (A11) | `docs/LIBRE3_NATIVE_USER_GUIDE.md` |

Update the NOTICE port list in its section 3 at the end of each of A4, A5, A6 and A7.
