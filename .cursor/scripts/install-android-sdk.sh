#!/usr/bin/env bash
# Idempotent Android SDK bootstrap for Cursor cloud agents.
# Versions come from this repo: compileSdk 37 (Versions.kt) and AGP 9.4.0
# (gradle/libs.versions.toml gradlePlugin), whose default and minimum
# build-tools revision is 36.0.0.
# The published platform package for API 37 is platforms/android-37.0.
set -euo pipefail

SDK_ROOT="${ANDROID_SDK_ROOT:-/opt/android-sdk}"
CMD_REV="23.0"
CMD_URL="https://dl.google.com/android/repository/commandlinetools-linux-16111833_latest.zip"
CMD_SHA1="e025545c62a8e64c7559119566a569fb1dec5f60"
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}"
export ANDROID_HOME="$SDK_ROOT"
export ANDROID_SDK_ROOT="$SDK_ROOT"

if [ ! -x "${JAVA_HOME}/bin/java" ] || ! command -v curl >/dev/null 2>&1 || ! command -v unzip >/dev/null 2>&1; then
  sudo apt-get update
  sudo apt-get install -y --no-install-recommends openjdk-21-jdk-headless curl unzip ca-certificates
fi

if [ ! -d "$SDK_ROOT" ]; then
  if mkdir -p "$SDK_ROOT" 2>/dev/null; then
    :
  else
    sudo mkdir -p "$SDK_ROOT"
    sudo chown "$(id -u):$(id -g)" "$SDK_ROOT"
  fi
fi

PROPS="$SDK_ROOT/cmdline-tools/latest/source.properties"
if ! grep -q "^Pkg.Revision=${CMD_REV}$" "$PROPS" 2>/dev/null; then
  tmp="$(mktemp)"
  curl -fL --retry 3 --retry-delay 2 -o "$tmp" "$CMD_URL"
  echo "${CMD_SHA1}  ${tmp}" | sha1sum -c -
  rm -rf "$SDK_ROOT/cmdline-tools"
  mkdir -p "$SDK_ROOT/cmdline-tools"
  unzip -q "$tmp" -d "$SDK_ROOT/cmdline-tools/unpack"
  mv "$SDK_ROOT/cmdline-tools/unpack/cmdline-tools" "$SDK_ROOT/cmdline-tools/latest"
  rm -rf "$SDK_ROOT/cmdline-tools/unpack" "$tmp"
fi

SDKMANAGER="$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager"

# cmdline-tools 23 closes stdin immediately: --licenses is obsolete and `yes`
# then dies with SIGPIPE (141). Keep going and require the packages below.
set +e
yes | "$SDKMANAGER" --sdk_root="$SDK_ROOT" --licenses
set -e

set +e
yes | "$SDKMANAGER" --sdk_root="$SDK_ROOT" \
  "platform-tools" \
  "platforms/android-37.0" \
  "build-tools/36.0.0"
rc=$?
set -e
if [ "$rc" -ne 0 ] && [ "$rc" -ne 141 ]; then
  exit "$rc"
fi

test -x "$SDK_ROOT/platform-tools/adb"
test -f "$SDK_ROOT/platforms/android-37.0/source.properties"
test -f "$SDK_ROOT/build-tools/36.0.0/source.properties"

# Gradle reads sdk.dir even when the agent shell does not source profile.d.
# local.properties is gitignored at the repo root (/local.properties).
repo_root="$(pwd)"
lp="$repo_root/local.properties"
if [ -f "$lp" ] && grep -q '^sdk\.dir=' "$lp"; then
  sed -i "s|^sdk\\.dir=.*|sdk.dir=${SDK_ROOT}|" "$lp"
else
  printf 'sdk.dir=%s\n' "$SDK_ROOT" >> "$lp"
fi

profile=/etc/profile.d/android-sdk.sh
profile_body="export JAVA_HOME=${JAVA_HOME}
export ANDROID_HOME=${SDK_ROOT}
export ANDROID_SDK_ROOT=${SDK_ROOT}
export PATH=\"\$JAVA_HOME/bin:\$ANDROID_HOME/cmdline-tools/latest/bin:\$ANDROID_HOME/platform-tools:\$PATH\"
"
if [ -w /etc/profile.d ] || [ -w "$profile" ]; then
  printf '%s\n' "$profile_body" > "$profile"
else
  printf '%s\n' "$profile_body" | sudo tee "$profile" >/dev/null
fi

# Daemon heap for this VM user only. Gradle 9.7 resolves gradle.properties with
# GRADLE_USER_HOME ahead of the project file, so this replaces the repo's
# org.gradle.jvmargs (-Xmx8g -Xss1024m) without editing the shared file.
# maxParallelForks is not a gradle.properties key (it is hardcoded from
# availableProcessors() in buildSrc). The init script below is what forces 1.
gradle_home="${GRADLE_USER_HOME:-${HOME}/.gradle}"
mkdir -p "${gradle_home}/init.d"
user_props="${gradle_home}/gradle.properties"
jvm_line='org.gradle.jvmargs=-Xmx3g -XX:+UseParallelGC -XX:MaxMetaspaceSize=512m -Dfile.encoding=UTF-8'
if [ -f "$user_props" ] && grep -q '^org\.gradle\.jvmargs=' "$user_props"; then
  if ! grep -qxF "$jvm_line" "$user_props"; then
    tmp_props="$(mktemp)"
    awk -v line="$jvm_line" 'index($0, "org.gradle.jvmargs=")==1 {print line; next} {print}' "$user_props" > "$tmp_props"
    mv "$tmp_props" "$user_props"
  fi
else
  printf '%s\n' "$jvm_line" >> "$user_props"
fi

init_script="${gradle_home}/init.d/cursor-cloud-test-forks.gradle"
init_body='// Cloud-agent VM only. maxParallelForks is not a Gradle property.
// Registered after project evaluation so it wins over buildSrc configureEach.
gradle.projectsEvaluated {
    rootProject.allprojects { p ->
        p.tasks.withType(org.gradle.api.tasks.testing.Test).configureEach {
            maxParallelForks = 1
        }
    }
}
'
if [ ! -f "$init_script" ] || ! cmp -s <(printf '%s\n' "$init_body") "$init_script"; then
  printf '%s\n' "$init_body" > "$init_script"
fi

echo "Android SDK ready at ${SDK_ROOT} (cmdline-tools ${CMD_REV}, platform android-37.0, build-tools 36.0.0, platform-tools)"
echo "Gradle user memory: ${jvm_line} (${user_props}); test forks capped in ${init_script}"
