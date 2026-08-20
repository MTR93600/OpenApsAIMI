# Libre 3 and Libre 3 Plus native — user guide

**Status: not confirmed on a real sensor yet.** Read the last section before you try it.

This is the native driver: AAPS talks to the sensor over Bluetooth by itself. No other app is
needed once it works.

Until you have confirmed it on your own sensor, **Juggluco or xDrip stays the path you rely on**.

## What you need

- A Libre 3 or a Libre 3 Plus sensor. The same driver handles both. The sensor says which one it is.
- A phone with NFC. NFC is only needed to start a sensor. A phone without NFC can still reconnect
  to a sensor that was already started.
- The engineering marker file `Documents/AAPS/extra/engineering_libre3`. Without it the plugin is
  not offered anywhere in AAPS, on purpose.

## Starting a sensor

1. Put the sensor on your arm as usual.
2. **Close the Abbott app and Juggluco.** A sensor answers one app at a time, and the last one to
   scan takes it over. This matters: if you scan with AAPS, the Abbott app will not get the sensor
   back without another scan of its own.
3. In AAPS open the Libre 3 plugin settings and choose **Start sensor**.
4. Hold the top of your phone flat on the sensor until the screen changes. Do not move it.
5. Allow Bluetooth if you are asked.
6. Warm-up takes about 60 minutes for a new sensor. A sensor that is already running can send
   glucose sooner, because its warm-up is already behind it.

You can leave the screen. The countdown also shows on the dashboard.

## Libre 3 or Libre 3 Plus

The sensor tells AAPS which family it is during the NFC scan, so there is nothing to choose. The
only real difference is how long it runs: about 14 days for a Libre 3 and about 15 for a Libre 3
Plus. The status screen shows the family it read.

## When something goes wrong

- **"That is not a Libre 3 sensor"** — the phone touched something else. Hold it on the sensor.
- **"The sensor did not accept the scan"** — wait a moment and scan again.
- **"Hold the phone still"** — the phone moved before the scan finished.
- **Nothing arrives after warm-up** — start the sensor again from the Start screen. The driver
  never falls back to a full pairing on a running sensor, because a running sensor refuses it, so
  a fresh scan is the way back.

## What this driver will never do

- It never sends the shutdown command to your sensor. Stopping the plugin, switching phones off, or
  a crash can only drop the Bluetooth link. Your sensor keeps running.
- It never sends glucose to the loop while the sensor is warming up, when the sensor says the
  reading itself is bad, or when the sensor was in a bad state while it made the reading.
- If the sensor reports a fault of its own, that is written to the log and shown as an error, but
  readings that still pass the checks above keep flowing. This matches the reference project. It
  does mean that "no error on screen" is not the same as "the sensor is healthy".
- It never guesses a countdown. If the sensor has not said how long is left, you see a dash.

## What is not finished yet

At the time of writing, the parts that talk to the sensor are written and tested, but three pieces
of the pairing are **not** in the app: the block maker that protects the pairing messages, the key
schedule behind it, and the key pair that a brand new sensor demands. Their table files are not in
this build either.

Until they are, the plugin will say so on the status screen and no sensor can be paired. Nothing
is guessed or worked around in their place, because a wrong pairing would look like a sensor fault
rather than a missing piece of this app.

## Before you trust it

This driver has not been confirmed on a real sensor. Until you have done that yourself:

- Keep Juggluco or xDrip as the source AAPS actually uses.
- Do not remove your usual alarm path. AAPS is the only thing that will alarm while the Abbott app
  is not holding the sensor.
- Treat the first sensor you try as a test, not as the one you depend on.

## Where the protocol comes from

The driver is a port of two open projects, both under the MIT licence: LibreCRKit for the protocol
and LibreLoop for the live rules. Details and the licence reasoning are in
[LIBRE3_NATIVE_LICENCE_MEMO.md](LIBRE3_NATIVE_LICENCE_MEMO.md) and `plugins/libre3/NOTICE`.
