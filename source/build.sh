#!/usr/bin/env bash
# Builds Pure Home with plain SDK tools (no Gradle): aapt → javac → dx → aapt → zipalign → apksigner
set -euo pipefail
cd "$(dirname "$0")"
SDK_JAR=${ANDROID_JAR:-/usr/lib/android-sdk/platforms/android-23/android.jar}
rm -rf build && mkdir -p build/gen build/classes build/dex
aapt package -f -m -J build/gen -M AndroidManifest.xml -S res -I "$SDK_JAR"
javac -nowarn -Xlint:-options --release 8 -encoding UTF-8 -classpath "$SDK_JAR" -d build/classes \
  $(find src build/gen -name '*.java')
dalvik-exchange --dex --output=build/dex/classes.dex build/classes
aapt package -f -M AndroidManifest.xml -S res -I "$SDK_JAR" -F build/unaligned.apk build/dex
zipalign -f -p 4 build/unaligned.apk build/aligned.apk
[ -f release.keystore ] || keytool -genkeypair -keystore release.keystore -storepass purehome -keypass purehome \
  -alias pure -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Pure Home" >/dev/null 2>&1
apksigner sign --ks release.keystore --ks-pass pass:purehome --key-pass pass:purehome --out build/PureHome.apk build/aligned.apk
apksigner verify build/PureHome.apk && ls -la build/PureHome.apk
