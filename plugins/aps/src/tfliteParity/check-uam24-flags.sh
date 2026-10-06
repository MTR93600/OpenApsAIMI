#!/bin/bash
# Fails unless the simulator and device TensorFlow Lite 2.4.0 builds recorded
# the same parity flags and the same Apple clang version. The SDK name and
# the minimum-version flag are the only allowed differences, and the version
# number on that flag must still be 12.0.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../../../.." && pwd)"
SIM="$ROOT/plugins/aps/build/tflite-24-sim-arm64"
DEV="$ROOT/plugins/aps/build/tflite-24-device-arm64"

for dir in "$SIM" "$DEV"; do
  for name in libtensorflow-lite.a flags.txt compile-line.txt clang-version.txt; do
    if [[ ! -f "$dir/$name" ]]; then
      echo "missing $dir/$name"
      exit 1
    fi
  done
done

if ! cmp -s "$SIM/flags.txt" "$DEV/flags.txt"; then
  echo "flags.txt differs"
  diff -u "$SIM/flags.txt" "$DEV/flags.txt" || true
  exit 1
fi
if ! cmp -s "$SIM/clang-version.txt" "$DEV/clang-version.txt"; then
  echo "clang version differs"
  diff -u "$SIM/clang-version.txt" "$DEV/clang-version.txt" || true
  exit 1
fi
if ! grep -q "Apple clang" "$SIM/clang-version.txt"; then
  echo "compiler is not Apple clang"
  cat "$SIM/clang-version.txt"
  exit 1
fi

python3 - "$SIM/compile-line.txt" "$DEV/compile-line.txt" "$SIM/flags.txt" << 'PY'
import pathlib, sys
sim = pathlib.Path(sys.argv[1]).read_text().strip()
dev = pathlib.Path(sys.argv[2]).read_text().strip()
flags = pathlib.Path(sys.argv[3]).read_text().split()
required = [
    "-DNDEBUG",
    "-DTFLITE_WITHOUT_XNNPACK",
    "-DTFLITE_WITH_RUY",
    "-O3",
    "-ffp-contract=on",
]
if flags != required:
    raise SystemExit(f"flags.txt is {flags}, expected {required}")
forbidden = (
    "-fembed-bitcode",
    "Accelerate",
    "TF_LITE_USE_CBLAS",
    "GEMMLOWP_ALLOW_SLOW_SCALAR_FALLBACK",
    "-DTFLITE_WITH_XNNPACK",
)

def tokens(line):
    parts = line.split()
    drop_next = False
    kept = []
    for part in parts:
        if drop_next:
            drop_next = False
            continue
        if part in {"-isysroot", "-arch", "-I", "-o", "-c"}:
            drop_next = True
            continue
        if part.startswith("-mios-simulator-version-min="):
            kept.append("-mios-version-min=" + part.split("=", 1)[1])
            continue
        if part.startswith("-miphoneos-version-min="):
            kept.append("-mios-version-min=" + part.split("=", 1)[1])
            continue
        if part in {"xcrun", "clang", "clang++", "-sdk", "iphonesimulator", "iphoneos"}:
            continue
        if part.endswith(".cc") or part.endswith(".c") or part.endswith(".cpp"):
            continue
        kept.append(part)
    return kept

sim_set = set(tokens(sim))
dev_set = set(tokens(dev))
if "iphonesimulator" not in sim or "-mios-simulator-version-min=12.0" not in sim:
    raise SystemExit("simulator compile line lost its SDK or min version")
if "iphoneos" not in dev or "-miphoneos-version-min=12.0" not in dev:
    raise SystemExit("device compile line lost its SDK or min version")
if "iphoneos" in sim.split() and "iphonesimulator" not in sim:
    raise SystemExit("simulator compile line uses the device SDK")
for flag in required:
    if flag not in sim_set or flag not in dev_set:
        raise SystemExit(f"{flag} missing from a compile line")
for flag in forbidden:
    if flag in sim or flag in dev:
        raise SystemExit(f"forbidden flag {flag}")
if sim_set != dev_set:
    print("normalized compile flags differ")
    print("simulator only", sorted(sim_set - dev_set))
    print("device only", sorted(dev_set - sim_set))
    raise SystemExit(1)
print("simulator and device compile flags match")
print(" ".join(required))
print(pathlib.Path(sys.argv[3]).read_text().strip())
PY
echo "Apple clang: $(cat "$SIM/clang-version.txt")"
