#!/data/data/com.termux/files/usr/bin/env bash
# opencode-android build script — pure Android SDK, no network, no external deps.
# Copied from proven gloomvault template.
set -euo pipefail

SDK="$HOME/android-sdk"
BT="$SDK/build-tools/34.0.4"
AAPT2="$BT/aapt2"
D8="$BT/d8"
ZIPALIGN="$BT/zipalign"
APKSIGNER="$BT/apksigner"
ANDROID_JAR="$SDK/platforms/android-35/android.jar"
LINK_JAR="$SDK/platforms/android-33/android.jar"

ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"
rm -rf build/out build/resc build/dex build/classes
mkdir -p build/out build/resc build/dex build/classes

echo "==[1/6] resources =="
"$AAPT2" compile --dir res -o build/resc/res.zip

echo "==[2/6] link manifest+resources =="
"$AAPT2" link -o build/out/base.apk \
  -I "$LINK_JAR" \
  --manifest AndroidManifest.xml \
  -R build/resc/res.zip \
  --auto-add-overlay \
  --min-sdk-version 26 \
  --target-sdk-version 35

echo "==[3/6] compile java =="
find src/java -name "*.java" > build/sources.txt
javac --release 17 -nowarn -classpath "$ANDROID_JAR" -d build/classes @build/sources.txt 2> build/javac-warnings.log || {
  echo "javac failed"; cat build/javac-warnings.log; exit 1; }

echo "==[4/6] dex =="
"$D8" --lib "$ANDROID_JAR" --release --min-api 26 --output build/dex \
  $(find build/classes -name "*.class") > /dev/null

echo "==[5/6] package =="
cp build/out/base.apk build/out/pack.apk
( cd build/dex && jar uf ../out/pack.apk classes.dex )
"$ZIPALIGN" -f 4 build/out/pack.apk build/out/aligned.apk

echo "==[6/6] sign =="
if [ ! -f debug.keystore ]; then
  keytool -genkeypair -v -keystore debug.keystore -alias androiddebugkey \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass android -keypass android -dname "CN=Android Debug,O=OpenCode,C=CN"
fi
"$APKSIGNER" sign --ks debug.keystore --ks-key-alias androiddebugkey \
  --ks-pass pass:android --key-pass pass:android \
  --out "$ROOT/OpenCodeClient.apk" build/out/aligned.apk

echo "OK -> $ROOT/OpenCodeClient.apk ($(du -h "$ROOT/OpenCodeClient.apk" | cut -f1))"
