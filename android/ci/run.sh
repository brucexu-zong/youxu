#!/bin/bash
# 在模拟器上安装并实测（由 android-emulator-runner 调用，此时 adb 已连上模拟器）
cd "$(dirname "$0")"
OUT=out; mkdir -p $OUT
APK=../app/build/outputs/apk/debug/app-debug.apk
adb install -r "$APK" > $OUT/install.txt 2>&1
adb logcat -c
adb shell am start -n app.youxu.planner/.MainActivity
sleep 15
adb exec-out screencap -p > $OUT/01-launch.png
timeout 900 node test.js > $OUT/test.txt 2>&1; echo "exit $?" >> $OUT/test.txt
adb logcat -d -v brief 2>/dev/null | grep -E "chromium|AndroidRuntime|youxu|WebView|FATAL" | tail -400 > $OUT/logcat.txt
exit 0
