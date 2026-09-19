# Dexcom ONE+ — sending a calibration to the sensor (0x34)

Study, 2026-09-18. Updated 2026-09-19, when this was built. Read it together with the software
calibration (`plugins/calibration`, `LinearCalibrationPlugin`).

**Status: built, switched off by default.** The whole path exists — packet, queue, BLE write, reply,
preference, routing from the calibration dialog. It does nothing until the user turns on
`DexcomOnePlusBooleanKey.SendCalibrationToSensor`, which is engineering-only.

**The one thing that did not survive contact with the evidence:** a Dexcom ONE+ does **not** answer
`0x35`. See §2c. The app therefore cannot tell a user whether their fingerstick was taken, and says
so rather than guessing.

## 1. The question

Today a fingerstick entered in AAPS never reaches the ONE+ sensor. It is stored as a `CAL` row, and
`LinearCalibrationPlugin` fits a line (`slope·value + offset`) that AAPS applies **on top of** the
value the sensor sends. The sensor's own algorithm knows nothing about it.

xDrip+ does the opposite for a Dexcom in native mode: it writes the fingerstick to the transmitter
(`CalibrateTxMessage`, opcode `0x34`), the sensor's algorithm recalibrates itself, and xDrip adds no
software fit on top. Dexcom's own apps work the same way, and the G7 / ONE+ user guide is explicit
that a calibration is stored **in the sensor**: it says to calibrate in the app *or* in the receiver,
never both, "because the sensor sends calibration information between them".

So: should the ONE+ driver send `0x34` instead of correcting in software?

## 2. What already exists in this repo

Everything needed to send a Control command is in place; a calibration message would be one more of
the same shape.

| Piece | Where | Note |
|---|---|---|
| Control write | `OnePlusGattClient.writeControl(payload)` | used for `0x26`, `0x59`, `0x4e` |
| Reply read | `OnePlusGattClient.awaitControlNotify(timeoutMs)` | Control is INDICATE, NOTIFY fallback |
| CRC | `OnePlusFastCrc16.calculate` | same trailer as every TX message |
| Transmitter clock | `OnePlusEgvSession.lastDexTimeSeconds` | needed: the packet carries dex time |
| Message pattern | `parse/OnePlusSessionStartTx.kt` + `OnePlusSessionStartRx.kt` | opcode, LE buffer, CRC, typed reply |
| Sensor answer | `OnePlusCalibrationState` | already decodes `CalibrationSent (0xC3)`, `NeedsCalibration (0x07)`, `NeedsFirstCalibration (0x04)`, `NeedsSecondCalibration (0x05)` |

The missing parts are two small files (`OnePlusCalibrateTx` / `OnePlusCalibrateRx`), one call site in
`OnePlusEgvSession`, and the routing decision in §4.

**Packet shape in xDrip** (`CalibrateTxMessage`): opcode `0x34`, glucose as `uint16` (mg/dL), the
fingerstick time as `uint32` dex time, then CRC16. The reply is `0x35` with a status byte:
`0x00` accepted, `0x06` "second calibration needed", `0x0D` duplicate; `0x08` rejected, `0x0B`
stopped, `0x0E` not ready.

**xDrip really does send this to a G7, read in its source 2026-09-18.** The chain is
`AddCalibration` → `NativeCalibrationPipe.addCalibration` → `Ob1G5StateMachine.addCalibration` →
`enqueueCommand(new CalibrateTxMessage(glucose, dexTime))`, i.e. opcode `0x34`. Nothing on that path
excludes a G7: the gate is `acceptCommands()` = `DexCollectionType.hasDexcomRaw()` (true for the
`DexcomG5` collector, which is the one that runs G5, G6 **and** G7 — a G7 is recognised by
`shortTxId()`) plus the preference `ob1_g5_use_transmitter_alg`.

Better still, `AddCalibration.java:215` shows xDrip treating the G7 family as a **send-only** case:
when the transmitter is "raw incapable" (G7 / Firefly) it creates **no** local calibration record at
all, says "Sending Blood Test to Transmitter", and only pushes the value down the native pipe. That
is exactly the design this note proposes.

**What is proven and what is not — read this before quoting the section above.**

- Proven: the ONE+ **sensor** accepts a calibration. The official Dexcom ONE+ app offers it, and the
  user guide says the calibration is held in the sensor and shared between the app and the receiver.
- Proven: our driver already decodes `CalibrationSent (0xC3)`, `NeedsFirstCalibration (0x04)`,
  `NeedsSecondCalibration (0x05)` and `NeedsCalibration (0x07)` in the EGV state byte, so those
  states exist on this sensor family.
- Proven: xDrip sends `0x34` to G7-family transmitters, with no G7 exclusion anywhere on that path
  (see above). Our own driver is a port of those same sources, and it already speaks the other
  Control opcodes of that family (`0x26`, `0x4e`, `0x59`) successfully.
- **Settled 2026-09-19, and settled against the assumption: a Dexcom ONE+ does not answer `0x35`.**
  See §2c. The narrow gap named here is closed; what replaced it is a different and smaller one.
- One design gap on our side, unrelated to the protocol: our EGV loop is a blocking cycle with no
  general pending-command slot. It has exactly one, the boolean `requestNewSensorStart` used for
  `0x26`. A calibration would need a real small queue (value + time + "answer to report back"), or it
  would only ever be sendable at the start of a cycle.


## 2c. What a ONE+ actually answers (2026-09-19)

The only public capture of a ONE+ being calibrated (xDrip discussion #4034) shows the sensor
answering with **four bytes that echo the request opcode**, not the five-byte `0x35` of the G5/G6
family:

```
Queuing Calibration for transmitter: 7.4 mmol/l   ->   Got unknown packet rx: 34000100
Queuing Calibration for transmitter: 6.3 mmol/l   ->   Got unknown packet rx: 34000200
```

Checked here: none of `crc(34)`, `crc(34 00)`, `crc(34 00 01)` matches the trailer of those packets,
so they do not carry this family's CRC either. xDrip's own `CalibrateRxMessage` rejects them, which
is why xDrip never logs "calibration accepted" on a ONE+ — the maintainer confirms as much.

Two readings of the third byte are possible and nothing published chooses between them:

- a private status from a sensor that took the value; or
- the firmware refusing an opcode it does not support, echoing it back.

The second is not far-fetched: every other Control command this driver uses answers with **opcode+1**
(`0x24`→`0x25`, `0x26`→`0x27`, `0x28`→`0x29`), never an echo. Against it: on a G7 the observed effect
of a calibration is real ("the next reading snaps to very near the value you send").

**What we do about it.** `OnePlusCalibrateRx` reads both shapes and reports the echo as
`Outcome.UNKNOWN`, never as an acceptance, carrying the raw bytes into the message so a field report
can quote them. Telling a user their calibration was taken, on a guess, would be worse than telling
them we cannot say — the sensor keeps what it accepts, for good.

## 2d. A second ceiling, on the sensor side

xDrip's maintainer reports that a ONE+ **ignores corrections larger than about 20 %** of the reading,
and one field report has three calibrations over 24 h leaving a 2 mmol/L gap untouched. This is not
compatible with the 60 → 150 case in §2b, and one of the two is mis-characterised; both are
second-hand. It matters because the case that motivated this work — blood 209 against a sensor
reading 309 — is a **32 %** correction, above that reported ceiling. The sensor route may simply not
answer this user's problem, which is why the software route was fixed in the same pass.

## 2b. Field evidence, 2026-09: sensor 60, fingerstick 150

A user hit a ONE+ reading **60 mg/dL while the blood was 150** — the sensor was 90 mg/dL low, which
is not drift, it is wrong. The official Dexcom app **accepted that calibration**, gap and all.

Two things follow, and they point in opposite directions.

**It strengthens the case for the sensor route.** The sensor's own algorithm takes a correction of
this size and re-bases itself. So a ONE+ does accept a big correction — the limit is not the size of
the gap, it is where the correction is applied.

**It shows the software route cannot serve this case at all.** Run those numbers through
`LinearCalibrationPlugin` as it stands after the 2026-09-18 changes:

| Check | Value for 60 → 150 | Verdict |
|---|---|---|
| Entries needed for a slope | 1 stick | offset only, offset = +90 |
| Correction at 100 mg/dL | +90 | over `CORRECTION_AT_CENTER_MAX` (30) → **refused** |
| Lift at 40 mg/dL | +90 | over `CORRECTION_AT_LOW_MAX` (20) → **refused** |

The fit is dropped and the loop keeps seeing 60. **That is the right call**, not a bug to loosen: a
line that adds 90 mg/dL everywhere would turn a real 55 into 145 and hide every hypo of the rest of
the session. A sensor 90 off does not need a correction bolted on top of it in the phone — it needs
its own algorithm re-based, or it needs to be replaced. Only the sensor route can do the first.

**A gap the driver has today.** `OnePlusCalibrationState.usableGlucose()` returns true for
`NeedsCalibration (0x07)` as well as `Ok`, so when the sensor asks for a calibration we keep feeding
its glucose to the loop and have no way to answer the request. Worse in theory:
`NeedsFirstCalibration (0x04)` and `NeedsSecondCalibration (0x05)` are **not** usable, so the glucose
stops — and with no way to send a calibration, a sensor in that state cannot be brought back from
AAPS at all; the user has to go to the official app. Whether a ONE+ ever really enters 0x04 / 0x05 is
not established here: the states are decoded, I have no field log showing one.

## 3. Why it is worth doing

1. **One truth instead of two.** The sensor's algorithm keeps its own lag model, its noise handling
   and its own limits. Our line does not: it is fitted on a handful of points and then applied to
   every value, including far outside the range those points cover. The audit of 2026-09-18 found
   exactly that failure — two sticks could lift a real 55 mg/dL to 104 for the loop.
2. **The sensor can say no.** `0x35` carries an explicit refusal, and "not ready" / "stopped" are
   states our software fit cannot even see. Today a fingerstick typed during warm-up or on a failing
   sensor is simply fitted.
3. **It matches what the user was told.** The ONE+ guide describes calibration as something the
   sensor holds. A user who calibrates in AAPS today and then looks at the Dexcom app sees two
   different numbers, which is the confusing case the guide warns about.
4. **No retroactive rewrite.** Our fit re-computes the whole session's history on every new entry;
   the sensor's own correction only moves forward.

## 4. What must be decided before writing any code

- **Never both.** If a calibration is sent to the sensor, `LinearCalibrationPlugin` must not also
  correct that sensor, or the correction is applied twice. xDrip enforces this by turning the display
  plugin off in native mode (`setG6Defaults`). Proposed rule: when the active BG source is the native
  ONE+ **and** sending is switched on, calibration entries go to the sensor and the software fit is
  identity for that session. This needs a visible state in the UI, not a silent preference.
- **Where the value enters.** `CalibrationDialogViewModel` already routes to
  `activeCalibration.addEntry` (+ xDrip when that source is active). A sensor-bound path would be a
  third route, and the dialog must show which one was taken and what the sensor answered.
- **Same gates as Dexcom.** 40–400 mg/dL, not during warm-up, fingerstick within 5 min, stable
  glucose, at most one per hour. We already have most of this in `checkPreconditionsAt`.
- **Failure is loud.** A refused `0x35` must reach the user as clearly as an accepted one; a silent
  refusal would leave them believing they calibrated.
- **The pre-soak.** Two sensors can run at once. A calibration must go to the sensor that feeds the
  loop, never to the pre-soak one; the slot is already carried by the driver instance.

## 5. Risks

- **Unverified on a ONE+.** The opcode is taken from xDrip's G6/G7 path. A wrong packet on the
  Control characteristic is not a read-only mistake: the same characteristic starts and stops a
  session. Work has to start on a spare sensor, behind an engineering switch, default off.
- **A calibration cannot be taken back.** The Dexcom guide says a stored calibration cannot be
  edited or deleted. A bad fingerstick sent to the sensor stays in it; a bad software fit can be
  removed by invalidating the entry. This is the strongest argument for keeping the gates strict.
- **It changes what the sensor reports to everyone**, including the Dexcom app if the user also runs
  it. That is by design, but it must be said plainly in the UI.
- **A ONE+ is factory calibrated.** Dexcom calls calibration optional, and the honest default stays
  "do not calibrate at all". Any of this is for the user who has a real, repeated offset.

## 6. Suggested order, if we go ahead

0. **First, evidence that the transport is right.** What the 2026-09 field case proves is that the
   SENSOR takes a large calibration, not that `0x34` on the Control characteristic is how the app
   delivers it. Still needed: a capture of the official app calibrating a ONE+, or a xDrip report of
   an accepted calibration on a ONE+ (not a G6, not a G7). Without it, step 1 is guesswork dressed as
   code, on the one characteristic that also starts and stops a session.
   A cheap first step that needs no capture: surface `NeedsCalibration` in the UI, so a user who sees
   it knows to calibrate in the official app rather than wonder why the numbers are wrong.
1. `OnePlusCalibrateTx` / `OnePlusCalibrateRx` + unit tests on the bytes only (no BLE). Cheap, and it
   is the part that can be reviewed against xDrip's source line by line.
2. A shadow mode: build the packet, log it, send nothing. Confirms the dex time and the gates on real
   traces.
3. Engineering switch, default off, one spare sensor, and the `0x35` answer surfaced in the Status
   screen.
4. Only then the routing rule of §4, and the software fit disabled for that session.

## 7. Sources

- xDrip+ `models/Calibration.java`, `calibrations/*`, `g5model/CalibrateTxMessage.java`,
  `g5model/CalibrateRxMessage.java`, `g5model/Ob1G5StateMachine.java` —
  <https://github.com/NightscoutFoundation/xDrip>
- xDrip native algorithm notes — <https://navid200.github.io/xDrip/docs/Native-Algorithm.html>
- Dexcom ONE+ user guide, "Accuracy and calibration" —
  <https://s3.us-west-2.amazonaws.com/dexcompdf/Dexcom_ONE_Plus/AW-1000027-05_UG_D1Plus_OUS_en_MMOL.pdf>
- Dexcom G7 user guide —
  <https://s3.us-west-2.amazonaws.com/dexcompdf/G7/AW00046-05_UG_G7_OUS_en_MMOL.pdf>
- Dexcom "Calibration not used" FAQ —
  <https://www.dexcom.com/en-us/faqs/what-does-calibration-not-used-mean>

This is a draft for review by the maintainers before any of it is built or relied upon.
