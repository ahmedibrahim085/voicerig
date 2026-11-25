#!/bin/bash
# Make speech with macOS `say`, convert to raw PCM s16le mono 48 kHz, push it to the phone and tell the running injector to play it.
# usage (on the Mac that has adb and the phone): say.sh "how much money do I have in my wallet" [voice] ; env SER = adb serial if several devices
# The injector must be running:  adb shell CLASSPATH=/data/local/tmp/voicerig.dex app_process / VoiceRig --uid <app uid> --run 600
set -e
A=${ADB:-adb}; D=${SER:+-s $SER}; V=${2:-Samantha}
T=$(mktemp -d); say -v "$V" -o "$T/s.aiff" "$1"
ffmpeg -loglevel error -y -i "$T/s.aiff" -ar 48000 -ac 1 -f s16le "$T/s.pcm"
$A $D push "$T/s.pcm" /data/local/tmp/utterance.pcm >/dev/null
$A $D shell "echo /data/local/tmp/utterance.pcm > /data/local/tmp/inject.cmd"
python3 -c "import os;print('utterance ms =', os.path.getsize('$T/s.pcm')//2*1000//48000)"
rm -rf "$T"
