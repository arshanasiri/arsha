#!/usr/bin/env bash
# اجرا داخل شبیه‌ساز اندروید (reactivecircus/android-emulator-runner)
set -u
PKG=ir.nasirzadeh.callrecorder
NS=com.example.callrecorder
OUT=ci-out
mkdir -p "$OUT/shots" "$OUT/reports"
FAIL=0
note() { echo "$*" | tee -a "$OUT/summary.txt"; }
fail() { note "FAIL: $*"; FAIL=1; }
pass() { note "PASS: $*"; }

note "device: $(adb shell getprop ro.product.model | tr -d '\r') api=$(adb shell getprop ro.build.version.sdk | tr -d '\r')"

# جلوگیری از پنجره‌های خطای سیستمی (مثلاً ANR لانچر روی شبیه‌ساز کند) که روی عکس‌ها می‌افتند
adb shell settings put global hide_error_dialogs 1 > /dev/null 2>&1

# ---------------------------------------------------------------- 1) تست‌های ابزاردار
adb logcat -c
if gradle :app:connectedDebugAndroidTest --no-daemon --stacktrace > "$OUT/gradle-instrumented.log" 2>&1; then
  pass "instrumented tests (launch, tabs, recording, armed mode, enhance)"
else
  fail "instrumented tests"
  grep -E "FAILED|AssertionError|Exception|Tests? .*failed" "$OUT/gradle-instrumented.log" | head -40 | tee -a "$OUT/summary.txt"
fi
adb pull /data/local/tmp/shots/. "$OUT/shots/" > /dev/null 2>&1 || note "no screenshots pulled"
cp -r app/build/outputs/androidTest-results "$OUT/reports/" 2>/dev/null || true
cp -r app/build/reports/androidTests "$OUT/reports/" 2>/dev/null || true
adb logcat -d -v time > "$OUT/logcat-instrumented.txt" 2>&1

# ---------------------------------------------------------------- 2) سناریوی واقعی: آماده‌باش + پس‌زمینه + تماس
adb logcat -c
if ! gradle :app:installDebug --no-daemon -q > "$OUT/gradle-install.log" 2>&1; then
  fail "installDebug"
  cat "$OUT/gradle-install.log" | tail -20
  exit 1
fi
for p in RECORD_AUDIO READ_PHONE_STATE READ_CALL_LOG POST_NOTIFICATIONS; do
  adb shell pm grant "$PKG" "android.permission.$p" > /dev/null 2>&1
done
adb shell cmd uimode night no > /dev/null 2>&1

adb shell am start -n "$PKG/$NS.MainActivity" --ez arm true --ez skipIntro true --ez fake true > /dev/null
sleep 6
adb exec-out screencap -p > "$OUT/shots/call_1_armed.png"
if adb shell dumpsys activity services "$PKG" | grep -q "RecorderService"; then
  pass "armed service is running"
else
  fail "armed service not running"
fi

adb shell input keyevent KEYCODE_HOME
sleep 2
adb emu gsm call 5551234 > /dev/null 2>&1
sleep 2
adb emu gsm accept 5551234 > /dev/null 2>&1
sleep 16
adb exec-out screencap -p > "$OUT/shots/call_2_in_call_background.png"
adb shell dumpsys activity services "$PKG" > "$OUT/services-in-call.txt" 2>&1
adb logcat -d -v time > "$OUT/logcat-call-mid.txt" 2>&1

adb emu gsm cancel 5551234 > /dev/null 2>&1
sleep 5
adb logcat -d -v time > "$OUT/logcat-call.txt" 2>&1

if adb shell pidof "$PKG" > /dev/null 2>&1 && [ -n "$(adb shell pidof "$PKG" | tr -d '\r')" ]; then
  pass "app process survived the call"
else
  fail "app process died during call"
fi
if grep -q "FATAL EXCEPTION" "$OUT/logcat-call.txt"; then
  fail "FATAL EXCEPTION during call scenario"
  grep -A 25 "FATAL EXCEPTION" "$OUT/logcat-call.txt" | head -60 | tee -a "$OUT/summary.txt"
else
  pass "no fatal exception during call scenario"
fi
if grep -aq "CallRecorder.*event ok=" "$OUT/logcat-call.txt"; then
  grep -a "CallRecorder.*event ok=" "$OUT/logcat-call.txt" | tail -3 | cut -c1-300 | tee -a "$OUT/summary.txt"
  if grep -a "CallRecorder.*event ok=true" "$OUT/logcat-call.txt" | grep -aq "ذخیره شد"; then
    pass "call auto-started and saved a recording"
  else
    fail "call finished but no recording was saved"
  fi
else
  fail "no recording event after the simulated call (auto-start did not happen)"
fi
adb shell "ls -la /sdcard/Android/data/$PKG/files/Recordings/" 2>&1 | tee -a "$OUT/summary.txt" || true

# ---------------------------------------------------------------- 3) عکس از برنامه پس از تماس
adb shell am start -n "$PKG/$NS.MainActivity" > /dev/null
sleep 3
adb exec-out screencap -p > "$OUT/shots/call_3_after_call.png"

# ---------------------------------------------------------------- 4) آزمایش: میکروفون واقعی در تماس، با و بدون سرویس دسترسی
run_call_experiment() {
  local label="$1"
  adb shell am force-stop "$PKG"
  adb logcat -c
  adb shell am start -n "$PKG/$NS.MainActivity" --ez arm true --ez skipIntro true --ez nofake true > /dev/null
  sleep 5
  adb shell input keyevent KEYCODE_HOME
  sleep 2
  adb emu gsm call 5551234 > /dev/null 2>&1
  sleep 2
  adb emu gsm accept 5551234 > /dev/null 2>&1
  sleep 14
  adb emu gsm cancel 5551234 > /dev/null 2>&1
  sleep 5
  adb logcat -d -v time > "$OUT/logcat-experiment-$label.txt" 2>&1
  note "EXPERIMENT [$label]:"
  grep -a "CallRecorder.*event ok=" "$OUT/logcat-experiment-$label.txt" | tail -1 | cut -c1-700 | tee -a "$OUT/summary.txt"
}
adb shell settings put secure enabled_accessibility_services "" > /dev/null 2>&1
run_call_experiment "real-mic_a11y-OFF"
adb shell settings put secure enabled_accessibility_services "$PKG/$NS.CallAccessibilityService" > /dev/null 2>&1
adb shell settings put secure accessibility_enabled 1 > /dev/null 2>&1
sleep 3
run_call_experiment "real-mic_a11y-ON"
adb shell settings put secure enabled_accessibility_services "" > /dev/null 2>&1

note "=== done, FAIL=$FAIL ==="

# ---------------------------------------------------------------- 5) نسخه‌ی انتشار (R8 + امضا) روی دستگاه
REL=app/build/outputs/apk/release/app-release.apk
if [ -f "$REL" ]; then
  adb uninstall "$PKG" > /dev/null 2>&1
  adb logcat -c
  if adb install -r "$REL" > "$OUT/release-install.txt" 2>&1; then
    for p in RECORD_AUDIO READ_PHONE_STATE READ_CALL_LOG POST_NOTIFICATIONS; do
      adb shell pm grant "$PKG" "android.permission.$p" > /dev/null 2>&1
    done
    adb shell am start -n "$PKG/$NS.MainActivity" > /dev/null
    sleep 6
    adb shell screencap -p /data/local/tmp/shots/release_launch.png && adb pull /data/local/tmp/shots/release_launch.png "$OUT/shots/" > /dev/null 2>&1
    adb shell am startservice -n "$PKG/$NS.RecorderService" -a "$NS.ARM" > /dev/null 2>&1
    sleep 3
    adb logcat -d -v time > "$OUT/logcat-release.txt" 2>&1
    PID="$(adb shell pidof "$PKG" | tr -d '\r')"
    if [ -n "$PID" ] && ! grep -q "FATAL EXCEPTION" "$OUT/logcat-release.txt"; then
      pass "release APK (R8, signed) launches and stays alive"
    else
      fail "release APK crashed or died"
      grep -B2 -A12 "FATAL EXCEPTION" "$OUT/logcat-release.txt" | head -40 | tee -a "$OUT/summary.txt"
    fi
  else
    fail "release APK install: $(cat "$OUT/release-install.txt")"
  fi
else
  note "release APK not present; skipped"
fi

exit $FAIL
