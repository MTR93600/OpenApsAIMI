# OpenAPS AI MI: Maintenance & Monitoring Guide

This guide provides instructions for developers and maintainers on how to monitor, debug, and update the AI MI module.

## 📊 Log Monitoring

The system uses **Structured Logging** via [AimiLogger.kt]. Logs are tagged with specific identifiers to filter decisions.

### Useful Log Filters:
- `🤖 [advisor:Decision]`: High-level AI decision summary.
- `🤖 [performance:execution]`: Latency metrics for calculations.
- `🤖 [safety:enforce]`: Decisions modified or blocked by safety guards.

### Traceability Analysis:
Each log includes a metadata block `{...}`. When a decision is questioned, check:
1. `bgVelocity`: Did the trend justify the dose?
2. `estimatedRa`: Was a hidden meal detected?
3. `shieldBlocking`: Was the dose capped per safety rules?

## 🚀 Performance Metrics

AIMI strictly enforces a `<100ms` latency loop. 
- **Monitoring**: Check `performance:execution` logs for spikes.
- **Profiling**: If latency exceeds 50ms consistently, run the `EngineBenchmarks.kt` suite to identify the bottleneck (usually PKPD or ML inference).

## 🛠️ Maintenance Tasks

### 1. Model Updates (ML)
When updating on-device ML models (TensorFlow Lite):
1. Replace the `.tflite` file in `src/main/assets`.
2. Update versioning in `Constants.kt`.
3. Run `AutoDrivePipelineTest.kt` to ensure the new model doesn't regression into aggressive behavior.

### 2. Sensitivity Refactoring
The `estimatedSI` and `estimatedRa` factors are core to the MPC. If you modify these:
- **Mandatory**: Validate against the "Exercise" and "Meal" use cases in the benchmark suite.

## 🚑 Troubleshooting

| Issue | Potential Cause | Resolution |
|-------|-----------------|------------|
| Loop Latency > 200ms | CPU Throttling or Large History | Check `tddCalculator` lookback range. |
| Zero SMB despite High BG | `HypoBlocked` flag active | Check `SafetyReport` logs for trajectory warnings. |
| Double Dosing | Clock skew or Duplicate Events | Verify `currentEpochMs` consistency in `AutodriveEngine`. |
