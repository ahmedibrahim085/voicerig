#!/bin/bash
# Builds out/voicerig.dex with javac + d8 from your Android SDK. Nothing is downloaded.
# Needs: JDK 17+ (JAVA_HOME or `java` on PATH), ANDROID_HOME (SDK with platforms;android-34+ and build-tools).
set -euo pipefail
: "${ANDROID_HOME:?set ANDROID_HOME to your Android SDK}"
JAVAC=${JAVA_HOME:+$JAVA_HOME/bin/}javac; JAR=${JAVA_HOME:+$JAVA_HOME/bin/}jar
BT=$(ls -d "$ANDROID_HOME"/build-tools/* | sort -V | tail -1)
AJ=$(ls -d "$ANDROID_HOME"/platforms/android-* | sort -V | tail -1)/android.jar
cd "$(dirname "$0")"; rm -rf out; mkdir -p out/classes
"$JAVAC" -source 17 -target 17 -cp "$AJ" -d out/classes src/VoiceRig.java
"$JAR" cf out/voicerig.jar -C out/classes .
"$BT/d8" --min-api 31 --lib "$AJ" --output out out/voicerig.jar
mv out/classes.dex out/voicerig.dex
echo "built out/voicerig.dex"
