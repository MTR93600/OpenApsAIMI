#!/usr/bin/env bash
# Idempotent Android SDK bootstrap for Cursor cloud agent VMs.
# Versions come from this repo: compileSdk 37 (Versions.kt) and AGP 9.4.0
# (gradle/libs.versions.toml gradlePlugin), whose default and minimum
# build-tools revision is 36.0.0.
# The published platform package for API 37 is platforms/android-37.0.
#
# Do not run on a developer machine. The script writes ~/.gradle (jvmargs and
# an init script that applies to every Gradle build of that user), uses sudo,
# and writes /etc/profile.d. It refuses to continue unless this process is in
# the Cursor cloud agent cgroup, or AAPS_ALLOW_LOCAL_SDK_INSTALL=1 is set.
set -euo pipefail

# Cloud signal, checked on this VM:
#   agent shell:  0::/system.slice/pod-…/cursor-agent/workload
#   exec-daemon:  0::/system.slice/pod-…/cursor-agent/daemon
# exec-daemon is what runs environment.json install, and it does not have
# CURSOR_AGENT=1, so the cgroup substring is the signal. A cgroup namespace
# without that segment (sudo unshare --cgroup → 0::/) is refused.
if [ "${AAPS_ALLOW_LOCAL_SDK_INSTALL:-}" != "1" ]; then
  cgroup="$(cat /proc/self/cgroup 2>/dev/null || true)"
  case "$cgroup" in
    *cursor-agent*) ;;
    *)
      echo "Refusing to install the Android SDK outside a Cursor cloud agent VM." >&2
      echo "This script writes ~/.gradle (org.gradle.jvmargs and init.d/cursor-cloud-test-forks.gradle, which applies to every Gradle build of this user), uses sudo, and writes /etc/profile.d." >&2
      echo "Expected signal: /proc/self/cgroup contains 'cursor-agent' (agent workload or exec-daemon). Got: ${cgroup:-<unreadable>}" >&2
      echo "To run on a machine you control, set AAPS_ALLOW_LOCAL_SDK_INSTALL=1." >&2
      exit 1
      ;;
  esac
fi

SDK_ROOT="${ANDROID_SDK_ROOT:-/opt/android-sdk}"
CMD_REV="23.0"
CMD_URL="https://dl.google.com/android/repository/commandlinetools-linux-16111833_latest.zip"
CMD_SHA1="e025545c62a8e64c7559119566a569fb1dec5f60"
export ANDROID_HOME="$SDK_ROOT"
export ANDROID_SDK_ROOT="$SDK_ROOT"

# JDK 21, without pinning the CPU architecture. Debian/Ubuntu ships
# /usr/lib/jvm/java-21-openjdk-<arch>. Other layouts (Homebrew, SDKMAN) are
# not searched; set JAVA_HOME to a JDK 21 in that case.
java_home_is_21() {
  local home="$1"
  [ -n "$home" ] && [ -x "$home/bin/java" ] || return 1
  local line
  line="$("$home/bin/java" -version 2>&1 | head -n 1)"
  case "$line" in
    *'version "21'*|*'version "1.21'*) return 0 ;;
    *) return 1 ;;
  esac
}

resolve_java_home() {
  if java_home_is_21 "${JAVA_HOME:-}"; then
    export JAVA_HOME
    return 0
  fi
  local d resolved bin
  for d in /usr/lib/jvm/java-21-openjdk-* /usr/lib/jvm/java-1.21.0-openjdk-*; do
    [ -e "$d" ] || continue
    if java_home_is_21 "$d"; then
      resolved="$(readlink -f "$d")"
      JAVA_HOME="$resolved"
      export JAVA_HOME
      return 0
    fi
  done
  if command -v java >/dev/null 2>&1; then
    bin="$(readlink -f "$(command -v java)")"
    resolved="$(dirname "$(dirname "$bin")")"
    if java_home_is_21 "$resolved"; then
      JAVA_HOME="$resolved"
      export JAVA_HOME
      return 0
    fi
  fi
  return 1
}

if ! resolve_java_home || ! command -v curl >/dev/null 2>&1 || ! command -v unzip >/dev/null 2>&1; then
  sudo apt-get update
  sudo apt-get install -y --no-install-recommends openjdk-21-jdk-headless curl unzip ca-certificates
fi
if ! resolve_java_home; then
  echo "JDK 21 not found. Looked at JAVA_HOME, /usr/lib/jvm/java-21-openjdk-* (any arch), and java on PATH. Other install layouts are not detected; set JAVA_HOME to a JDK 21 and retry." >&2
  exit 1
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
# org.gradle.jvmargs without editing the shared file. Only -Xmx changes
# (-Xmx8g → -Xmx3g); -XX:+UseParallelGC and -Xss1024m stay as in the repo.
# maxParallelForks is not a gradle.properties key (it is hardcoded from
# availableProcessors() in buildSrc). The init script below is what forces 1.
gradle_home="${GRADLE_USER_HOME:-${HOME}/.gradle}"
mkdir -p "${gradle_home}/init.d"
user_props="${gradle_home}/gradle.properties"
jvm_line='org.gradle.jvmargs=-Xmx3g -XX:+UseParallelGC -Xss1024m'
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

echo "Android SDK ready at ${SDK_ROOT} (cmdline-tools ${CMD_REV}, platform android-37.0, build-tools 36.0.0, platform-tools, JAVA_HOME=${JAVA_HOME})"
echo "Gradle user memory: ${jvm_line} (${user_props}); test forks capped in ${init_script}"
