# AIMI Remote Control PIN (AAPS Client <-> AAPS AIMI)

## Purpose

Secure AIMI remote context commands sent through Nightscout by requiring a PIN configured in AIMI preferences on the receiving AAPS instance.

## What changed historically

- `c8334cc88c` added:
  - preference key `aimi_remote_control_pin` (`AimiStringKey.RemoteControlPin`)
  - legacy UI section for Remote Control PIN
- `a472329011` removed the legacy Remote Control preference screen wiring
  - the key remained in code, but no longer visible in plugin settings

## Current protocol

### Context note format (Nightscout TherapyEvent NOTE)

- **New secure format**:
  - `AIMI_CONTEXT:<intentId>:PIN:<pin>:<intentJson>`
- **Legacy format (kept for parsing compatibility)**:
  - `AIMI_CONTEXT:<intentId>:<intentJson>`

## Validation rules on receiver

When receiving `AIMI_CONTEXT` from Nightscout:

1. Read configured PIN from AIMI preferences (`aimi_remote_control_pin`).
2. If configured PIN is empty: reject remote context injection.
3. If incoming PIN is missing or different: reject remote context injection.
4. Only inject context when PIN matches.

## Relevant files

- `plugins/aps/.../openAPSAIMI/OpenAPSAIMIPlugin.kt`
  - exposes `RemoteControlPin` again in Compose preferences
- `plugins/sync/.../RemoteControlFragment.kt`
  - propagates entered PIN when sending context modes
- `plugins/aps/.../context/ContextManager.kt`
  - writes secure `AIMI_CONTEXT` note and validates incoming PIN
- `plugins/sync/.../NsIncomingDataProcessor.kt`
  - parses both secure and legacy formats
- `plugins/sync/.../NSClientAddUpdateWorker.kt`
  - parses both secure and legacy formats

## Notes

- MEAL/CONTROL commands already carry PIN in the `AIMI:<pin> ...` command string.
- ACTIVITY/PHYSIO contexts now carry PIN too via the secure `AIMI_CONTEXT` format.
