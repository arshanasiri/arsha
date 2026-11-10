#!/usr/bin/env bash
# اجرا داخل شبیه‌ساز اندروید (reactivecircus/android-emulator-runner)
set -u
PKG=com.example.callrecorder
OUT=ci-out
mkdir -p "$OUT/shots" "$OUT/reports"
FAIL=0
note() { echo "$*" | tee -a "$OUT/summary.txt"; }
fail() { note "FAIL: $*"; FAIL=1; }
pass() { note "PASS: $*"; }

note "device: $(adb shell getprop ro.product.model | tr -d '\r') api=$(adb shell getprop ro.build.version.sdk | tr -d '\r')"

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
for p in RECORD_AUDIO READ_PHONE_STATE POST_NOTIFICATIONS; do
  adb shell pm grant "$PKG" "android.permission.$p" > /dev/null 2>&1
done
adb shell cmd uimode night no > /dev/null 2>&1

adb shell am start -n "$PKG/.MainActivity" --ez arm true --ez skipIntro true > /dev/null
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
if grep -q "CallRecorder: event ok=" "$OUT/logcat-call.txt"; then
  grep "CallRecorder: event" "$OUT/logcat-call.txt" | tail -3 | tee -a "$OUT/summary.txt"
  if grep "CallRecorder: event" "$OUT/logcat-call.txt" | grep -q "ذخیره شد"; then
    pass "call auto-started and saved a recording"
  else
    fail "call finished but no recording was saved"
  fi
else
  fail "no recording event after the simulated call (auto-start did not happen)"
fi
adb shell "ls -la /sdcard/Android/data/$PKG/files/Recordings/" 2>&1 | tee -a "$OUT/summary.txt" || true

# ---------------------------------------------------------------- 3) عکس از برنامه پس از تماس
adb shell am start -n "$PKG/.MainActivity" > /dev/null
sleep 3
adb exec-out screencap -p > "$OUT/shots/call_3_after_call.png"

note "=== done, FAIL=$FAIL ==="
exit $FAIL
