# iPhone pre-soak (Dexcom ONE+ / G7)

The iPhone runs the same pre-soak slot as Android: a second sensor warms up beside the one that
feeds the loop, the status card shows warming up → settling → ready, and Promote / Promote early /
Cancel use the shared screens. Readings from the pre-soak slot never reach the loop until
promotion. Promotion keeps the pre-soak start time, so sensor age and expiry follow the moment
the sensor was started, not the moment it was promoted.

## One scan, two connections

CoreBluetooth allows one `scanForPeripherals` per central. A second call replaces the first, and
`stopScan` ends it for every listener. The two slots therefore do not each own a scan.

`OnePlusIosScanBus` is the only caller of `startScan` / `stopScan` on the ONE+ central. Each
scanner records what it needs (nothing, an unfiltered screen scan, or the filtered FEBC wait).
The bus keeps the filtered scan while any slot is waiting on it, because a locked iPhone delivers
nothing to a scan that has no service filter. A screen scan does not replace that wait. When
nobody wants a scan, the radio stops. Stopping one slot's scan does not stop the other's.

Connections are separate. iOS allows several `connectPeripheral` calls at once. Each slot has its
own GATT client, both stay registered on `OnePlusIosRadio`, and each client ignores events for
the other peripheral. The production sensor is not cancelled when the pre-soak slot connects,
scans, or stops scanning.

Between readings both slots use the held connect (`OemDeviceProfile.holdsPendingConnect`, the
`APPLE` profile, `BleCentral.connect(id, startDelaySeconds)`). That path does not scan. iOS keeps
the pending connect while the app is suspended and wakes the app when the sensor advertises.
Nothing here has been tried with two real sensors on a locked phone.

## The switch

`DexcomOnePlusBooleanKey.IosPreSoak`, preference key `dexcom_oneplus_ios_presoak`,
title "Pre-soak sensor on this iPhone". Default **on**. It is shown only on iPhone. Android does
not show it and does not read it.

Off: `supportsStaging` is false, the second driver is not started, the status card and the slot
chooser are hidden, and the phone behaves as it did with one sensor. A pre-soak that was already
running is disconnected. Its stored identity and soak clock are kept, and come back if the switch
is turned on again. Cancel (the button) still wipes that store; the switch does not.

## The "Settling / 2049 readings" report

Checked in the shared state machine, which Android and iPhone both use.

- The readings counter incremented for every sample, including the backfill the driver delivers
  on every reconnect (up to three hours of history). The same moments were counted again each
  cycle. Twelve hours of a five-minute sensor is about 150 readings, not 2049. The counter now
  accepts a timestamp only when it is newer than the last counted one by at least four minutes
  (the same near-duplicate window as loop ingest). A stored total that is more than twice what
  the soak clock can explain, and that has no high-water mark yet, is brought back to that
  plausible count once.
- A stale last reading does **not** block Ready. Ready is "present, warm-up finished, soak at
  least 12 h, at least 6 distinct readings". The early ("not settled") button is the one that
  requires a reading younger than 20 minutes. The card used to recompute only when a reading
  arrived, so a sensor that went quiet before the 12 h mark stayed on Settling, the early button
  stayed hidden because the last reading was stale, and no Promote button appeared after the
  soak had in fact ended. The card is now recomputed every minute while a pre-soak sensor is
  present.
- The pre-soak slot had no reconnect watchdog. The production one only asks the production
  driver. If the pre-soak session thread exited, nothing asked again until the next plugin
  start. The pre-soak slot now has the same watchdog, and it never touches the production driver.

Ready does not look at the last reading when it chooses the label. Promotion does. Any
promotion, the button and the automatic switch, needs a staging reading younger than 30 minutes.
A card whose last reading is older hides Promote and says the sensor has to connect again. The
time of that reading stays on the card. The early button stays hidden while the last reading is
older than 20 minutes.

## Still to validate on a real iPhone with two sensors

- Both sensors stay connected overnight with the phone locked, and the production readings that
  feed the loop are not delayed or dropped because the pre-soak slot is also connected.
- A scan from the start screen, and stopping that scan, does not disconnect the production sensor.
- Promote keeps the pre-soak start date (age and expiry) and the pre-soak readings do not appear
  as loop glucose before that.
- Turning `dexcom_oneplus_ios_presoak` off returns to one sensor without losing the stored
  pre-soak, and turning it back on resumes it.
- The pre-soak card leaves Settling when the soak clock passes 12 h even if no new reading arrives,
  and the readings count stays near one per five minutes rather than climbing by the backfill.
