#!/usr/bin/env bash
set -euo pipefail

if ! command -v python3 >/dev/null 2>&1; then
  printf 'ONNX Runtime XCFramework check: required tool python3 was not found in PATH.\n' >&2
  exit 1
fi
SCRIPT_DIR="$(python3 -c 'import pathlib, sys; print(pathlib.Path(sys.argv[1]).resolve().parent)' "$0")"
ROOT="$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)"
XCFRAMEWORK="${1:-$ROOT/plugins/aps/build/onnxRuntime/onnxruntime-apple-framework/framework_out/onnxruntime.xcframework}"

python3 - "$XCFRAMEWORK" <<'PY'
import pathlib
import plistlib
import sys

xcframework = pathlib.Path(sys.argv[1]).resolve()
info_path = xcframework / "Info.plist"
if not info_path.is_file():
    raise SystemExit(f"ONNX Runtime XCFramework check: missing {info_path}")

with info_path.open("rb") as info_file:
    info = plistlib.load(info_file)

libraries = info.get("AvailableLibraries", [])
expected = {
    ("ios", None): "iphoneos arm64",
    ("ios", "simulator"): "iOS simulator arm64",
}
found = set()
for library in libraries:
    platform = library.get("SupportedPlatform")
    variant = library.get("SupportedPlatformVariant")
    architectures = set(library.get("SupportedArchitectures", []))
    key = (platform, variant)
    if key not in expected or "arm64" not in architectures:
        continue

    framework_path = xcframework / library["LibraryIdentifier"] / library["LibraryPath"]
    headers_path = framework_path / "Headers"
    if not framework_path.is_dir():
        raise SystemExit(
            f"ONNX Runtime XCFramework check: missing framework for {expected[key]}: "
            f"{framework_path}"
        )
    for header in (
        "onnxruntime_c_api.h",
        "onnxruntime_run_options_config_keys.h",
        "onnxruntime_session_options_config_keys.h",
    ):
        if not (headers_path / header).is_file():
            raise SystemExit(
                f"ONNX Runtime XCFramework check: missing {header} for "
                f"{expected[key]} at {headers_path}"
            )
    found.add(key)

missing = set(expected) - found
if missing:
    labels = ", ".join(expected[key] for key in sorted(missing, key=str))
    raise SystemExit(f"ONNX Runtime XCFramework check: missing required slice(s): {labels}")

print("ONNX Runtime XCFramework contains iphoneos arm64 and iOS simulator arm64 slices.")
print("Both slices include the C API and its run/session option key headers.")
PY
