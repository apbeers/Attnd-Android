#!/usr/bin/env bash
# Capture raw Play Store screenshots of the debug app on a headless emulator (ubuntu-latest).
#
#   scripts/ci/screenshots.sh [app-debug.apk]
#
# The APK must be a DEBUG build (screenshot mode is debug-only) with BuildConfig.ATTND_SERVER and
# ATTND_API_KEY pointing at a server that has the DEMO event, and MAPS_API_KEY for the map tiles.
# For each screen in store/en-US/captions.txt it clears the app's data, launches
#   am start ... --es attnd_screenshot <screen>
# (the app joins DEMO through the normal lookup/join path and shows that screen), waits, and saves
# build/screenshots/raw/<screen>.png. compose_screenshots.py turns those into listing images.
#
# Env (all optional):
#   SYSTEM_IMAGE     default system-images;android-37.0;google_apis;x86_64 (targetSdk 37; stable
#                    channel, 4 KB pages, needs emulator >= 37.2.11; sdkmanager installs the latest)
#   DEVICE_PROFILES  avdmanager profiles to try in order (first one available wins). cmdline-tools
#                    23.0 (Nov 2026) knows up to pixel_9_pro_xl; pixel_10_pro_xl is tried first
#                    for when a newer avdmanager adds it.
#   SCREENS          space-separated screens (default: the keys in store/en-US/captions.txt)
#   SETTLE_SECONDS   wait after launch before capturing (default 25; the map needs tiles)
#   OUT              output directory (default build/screenshots/raw)
set -euo pipefail

APK=${1:-app/build/outputs/apk/debug/app-debug.apk}
OUT=${OUT:-build/screenshots/raw}
LOG_DIR=$(dirname "$OUT")
SYSTEM_IMAGE=${SYSTEM_IMAGE:-"system-images;android-37.0;google_apis;x86_64"}
DEVICE_PROFILES=${DEVICE_PROFILES:-"pixel_10_pro_xl pixel_9_pro_xl pixel_8_pro pixel_7_pro"}
SETTLE_SECONDS=${SETTLE_SECONDS:-25}
CAPTIONS=${CAPTIONS:-store/en-US/captions.txt}
AVD_NAME=attnd_store
PACKAGE=${SCREENSHOT_PACKAGE:-com.arcu.attnd.debug}  # com.arcu.attnd for -PstoreScreenshots builds
ACTIVITY=com.arcu.attnd.MainActivity

: "${ANDROID_HOME:?ANDROID_HOME must point at the Android SDK}"
SDKMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
AVDMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager"
EMULATOR="$ANDROID_HOME/emulator/emulator"
ADB="$ANDROID_HOME/platform-tools/adb"

[ -f "$APK" ] || { echo "No APK at $APK (build it with ./gradlew assembleDebug)" >&2; exit 1; }
if [ -z "${SCREENS:-}" ]; then
  SCREENS=$(grep -v '^[[:space:]]*#' "$CAPTIONS" | grep '=' | cut -d= -f1 | tr -d ' ' | tr '\n' ' ')
fi
mkdir -p "$OUT"

log() { echo "::group::$*" 2>/dev/null || true; }
endlog() { echo "::endgroup::" 2>/dev/null || true; }

# ── KVM (GitHub's Linux runners expose /dev/kvm, but only to root by default) ────────────
if [ -n "${CI:-}" ] && [ -e /dev/kvm ]; then
  echo 'KERNEL=="kvm", GROUP="kvm", MODE="0666", OPTIONS+="static_node=kvm"' \
    | sudo tee /etc/udev/rules.d/99-kvm4all.rules > /dev/null
  sudo udevadm control --reload-rules
  sudo udevadm trigger --name-match=kvm
  sudo udevadm settle || true
  # The udev trigger can lag behind; set the mode directly as well.
  sudo chmod 666 /dev/kvm || true
fi
ls -l /dev/kvm 2> /dev/null || echo "no /dev/kvm on this runner" >&2
if ! { [ -r /dev/kvm ] && [ -w /dev/kvm ]; }; then
  echo "::error::/dev/kvm is not usable: an x86_64 API 37 emulator will not boot in time without KVM" >&2
  exit 1
fi

# Shared libraries the emulator links against (even headless with -no-audio) that newer
# ubuntu-latest images no longer ship, e.g. libpulse.so.0.
if [ -n "${CI:-}" ] && command -v apt-get > /dev/null; then
  log "Emulator system libraries"
  sudo apt-get update -qq > /dev/null || true
  for pkg in libpulse0 libnss3 libgl1 libxkbfile1 libxcursor1 libxdamage1 libxcomposite1 libxi6 \
             libxtst6 libxrandr2 libasound2t64; do
    sudo apt-get install -y -qq --no-install-recommends "$pkg" > /dev/null || echo "warning: could not install $pkg" >&2
  done
  endlog
fi

# ── SDK packages ─────────────────────────────────────────────────────────────────────────
log "Install emulator and $SYSTEM_IMAGE"
yes 2> /dev/null | "$SDKMANAGER" --licenses > /dev/null || true
"$SDKMANAGER" --install emulator platform-tools "$SYSTEM_IMAGE" > /dev/null
QEMU="$(dirname "$EMULATOR")/qemu/linux-x86_64/qemu-system-x86_64"
if [ -x "$QEMU" ]; then
  missing=$(LD_LIBRARY_PATH="$(dirname "$EMULATOR")/lib64:$(dirname "$EMULATOR")/lib64/qt/lib:${LD_LIBRARY_PATH:-}" \
    ldd "$QEMU" | awk '/not found/ {print $1}' | sort -u | tr '\n' ' ')
  [ -z "$missing" ] || { echo "::error::The emulator is missing system libraries: $missing" >&2; exit 1; }
fi
"$EMULATOR" -version | head -n 1
endlog

# ── AVD: the newest large Pixel profile this avdmanager knows ────────────────────────────
AVAILABLE=$("$AVDMANAGER" list device -c)
PROFILE=""
for candidate in $DEVICE_PROFILES; do
  if grep -qx "$candidate" <<< "$AVAILABLE"; then PROFILE=$candidate; break; fi
done
[ -n "$PROFILE" ] || { echo "None of [$DEVICE_PROFILES] in: $AVAILABLE" >&2; exit 1; }
echo "Device profile: $PROFILE"
# Pin the AVD location for both avdmanager and the emulator (newer tools otherwise pick their own
# default, e.g. under ~/.config, and the two can disagree).
export ANDROID_AVD_HOME="${ANDROID_AVD_HOME:-$HOME/.android/avd}"
mkdir -p "$ANDROID_AVD_HOME"
echo no | "$AVDMANAGER" create avd --force -n "$AVD_NAME" -k "$SYSTEM_IMAGE" -d "$PROFILE" -p "$ANDROID_AVD_HOME/$AVD_NAME.avd"
AVD_DIR="$ANDROID_AVD_HOME/$AVD_NAME.avd"
[ -f "$AVD_DIR/config.ini" ] || { echo "::error::avdmanager did not create $AVD_DIR/config.ini"; ls -la "$ANDROID_AVD_HOME" "$HOME/.android" 2>&1 || true; exit 1; }
# Replace (not append) each key: avdmanager already writes some of them, e.g. hw.ramSize.
set_avd() {
  sed -i "/^$1[[:space:]]*=/d" "$AVD_DIR/config.ini"
  echo "$1=$2" >> "$AVD_DIR/config.ini"
}
set_avd hw.ramSize 4096
set_avd disk.dataPartition.size 6G
# No hardware keyboard: with one, Android leaves touch mode and focuses the first text field
# (the schedule search), which brings up the on-screen keyboard.
set_avd hw.keyboard no
set_avd hw.camera.back none
set_avd hw.camera.front none
set_avd showDeviceFrame no

# ── Boot headless ────────────────────────────────────────────────────────────────────────
"$ADB" start-server > /dev/null
"$EMULATOR" -avd "$AVD_NAME" -no-window -no-audio -no-boot-anim -no-snapshot \
  -gpu swiftshader_indirect -camera-back none -camera-front none \
  > "$LOG_DIR/emulator.log" 2>&1 &
EMULATOR_PID=$!
cleanup() {
  "$ADB" emu kill > /dev/null 2>&1 || true
  sleep 2
  kill "$EMULATOR_PID" 2> /dev/null || true
}
trap cleanup EXIT

echo "Waiting for the emulator to boot…"
timeout 120 "$ADB" wait-for-device
BOOTED=""
for _ in $(seq 1 120); do
  if [ "$("$ADB" shell getprop sys.boot_completed 2> /dev/null | tr -d '\r')" = "1" ]; then BOOTED=1; break; fi
  if ! kill -0 "$EMULATOR_PID" 2> /dev/null; then break; fi
  sleep 5
done
if [ -z "$BOOTED" ]; then
  echo "Emulator did not boot. Last log lines:" >&2
  tail -n 50 "$LOG_DIR/emulator.log" >&2
  exit 1
fi
echo "Booted: $("$ADB" shell getprop ro.build.version.release | tr -d '\r') (API $("$ADB" shell getprop ro.build.version.sdk | tr -d '\r'))"
sleep 20  # let the launcher and Play services settle after boot

# ── Device state: light theme, no animations, screen on, clean demo-mode status bar ──────
sh_adb() { "$ADB" shell "$@" > /dev/null; }
sh_adb settings put global window_animation_scale 0
sh_adb settings put global transition_animation_scale 0
sh_adb settings put global animator_duration_scale 0
sh_adb settings put secure show_ime_with_hard_keyboard 0
sh_adb settings put secure immersive_mode_confirmations confirmed
sh_adb settings put system screen_off_timeout 1800000
sh_adb cmd uimode night no
sh_adb svc power stayon true
sh_adb input keyevent KEYCODE_WAKEUP
sh_adb wm dismiss-keyguard

demo() { sh_adb am broadcast -a com.android.systemui.demo -e command "$@"; }
sh_adb settings put global sysui_demo_allowed 1
demo enter
demo clock -e hhmm 0941
demo battery -e level 100 -e plugged false -e powersave false
demo network -e wifi show -e level 4 -e fully true
demo network -e mobile hide
demo network -e airplane hide
demo notifications -e visible false
demo status -e volume hide -e bluetooth hide -e location hide -e alarm hide -e sync hide -e mute hide

# ── App ──────────────────────────────────────────────────────────────────────────────────
log "Install $APK"
"$ADB" install -r "$APK"
endlog

ui_dump() {
  "$ADB" shell uiautomator dump /sdcard/attnd_ui.xml > /dev/null 2>&1 && "$ADB" shell cat /sdcard/attnd_ui.xml 2> /dev/null || true
}

capture() {
  local screen=$1
  sh_adb am force-stop "$PACKAGE"
  sh_adb pm clear "$PACKAGE"  # fresh start: no saved event, no dismissed banners
  sh_adb am start -W -n "$PACKAGE/$ACTIVITY" --es attnd_screenshot "$screen"
  sleep "$SETTLE_SECONDS"
  demo clock -e hhmm 0941  # re-assert in case SystemUI restarted

  local focus
  focus=$("$ADB" shell dumpsys window | grep -E 'mCurrentFocus|mFocusedApp' | head -n 2 || true)
  if ! grep -q "$PACKAGE" <<< "$focus"; then
    echo "  $screen: the app is not in front ($focus)" >&2
    return 1
  fi
  # A focused text field can still raise the keyboard: BACK hides it (and does nothing else while it's up).
  if "$ADB" shell dumpsys input_method | grep -q 'mInputShown=true'; then
    sh_adb input keyevent KEYCODE_BACK
    sleep 1
  fi
  if [ "$screen" != launch ] && grep -q 'Welcome to Attnd' <<< "$(ui_dump)"; then
    echo "  $screen: still on the launch screen; joining the event failed (API key, server or DEMO event?)" >&2
    return 1
  fi
  "$ADB" exec-out screencap -p > "$OUT/$screen.png"
  echo "  $OUT/$screen.png ($(stat -c %s "$OUT/$screen.png") bytes)"
}

echo "Screens: $SCREENS"
for screen in $SCREENS; do
  # One retry: a system dialog (e.g. "isn't responding") or a slow first map load on CI.
  capture "$screen" || { sh_adb input keyevent KEYCODE_BACK; sleep 5; capture "$screen"; }
done
"$ADB" logcat -d > "$LOG_DIR/logcat.txt" 2>/dev/null || true
# A blank map is almost always the Maps key's app restriction rejecting this debug build.
if grep -qiE 'Authorization failure|API key not found|Ensure that the "Maps SDK for Android"' "$LOG_DIR/logcat.txt"; then
  echo "::warning::Google Maps rejected the key in the screenshot build (blank map). The key must allow package $PACKAGE with this build's signing certificate (store-screenshots.yml signs with the upload key). Details:"
  grep -iE -A3 'Authorization failure|API key not found' "$LOG_DIR/logcat.txt" | head -n 20
fi
"$ADB" exec-out screencap -p > /dev/null  # keep adb happy before the emulator is killed
echo "Raw screenshots in $OUT"
