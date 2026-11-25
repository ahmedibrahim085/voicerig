# voicerig

Feed audio to the **microphone of a real Android phone** from your computer, to test voice apps (voice assistants, WebRTC calls, speech
input) with **push to talk, streaming and barge-in**, **without root**, over a plain **USB adb** connection, with a few hundred lines of
Java you can read in five minutes. No app to install, no third-party library, no network code.

## How it works
`voicerig` runs as the adb **shell** user through `app_process` (the way scrcpy runs its server). It registers a loopback
`AudioPolicy` with an `INJECTOR` mix, so that recordings that match the rules (a given app uid, and/or capture presets such as
MIC and VOICE_COMMUNICATION) read the PCM you write instead of the real microphone. Silence is written between utterances, so the
app sees a live, continuous microphone.

## Quick start
```
export ANDROID_HOME=~/Library/Android/sdk          # your SDK
./build.sh                                          # javac + d8 -> out/voicerig.dex (about 10 KB)
adb push out/voicerig.dex /data/local/tmp/
adb shell CLASSPATH=/data/local/tmp/voicerig.dex app_process / VoiceRig --selftest        # proves it works on your phone
UID_=$(adb shell pm list packages -U com.example.yourapp | sed 's/.*uid:\([0-9]*\).*/\1/')
adb shell CLASSPATH=/data/local/tmp/voicerig.dex app_process / VoiceRig --uid $UID_ --run 600 &
host/say.sh "how much money do I have in my wallet"   # macOS: speech -> PCM -> phone; one call per utterance
```
`--selftest` injects a 440 Hz tone and records it back with an `AudioRecord` in the same process: exit code 0 and an RMS near 8400 mean the whole pipeline works.
Options: `--uid N` (only this app hears the injected audio), `--presets 1,7,...`, `--rate 48000`, `--cmd /data/local/tmp/inject.cmd`, `--run SECONDS`, `--capture-uid N --capture-out PATH`, `--selftest-capture`.
Utterances are raw PCM s16le mono at `--rate`; write the file path into the control file (`say.sh` does it) and the injector plays it.

## What it does
- **Inject** speech into a chosen app's microphone: push to talk, open mic / streaming (silence is written between utterances), barge-in (inject while the app speaks).
- **Capture** what the app plays back, including voice-call streams that Android's normal playback capture refuses (`--capture-uid`; the app is silent on the speaker while it runs, so a test has no echo).
- **Measure**: machine-readable `EVT` lines with epoch milliseconds (`play_start`, `play_end`, `capture_sound_start`, `capture_sound_end`) give the **time to first audio** of the app's reply and the reply length.
- **Scenarios**: `host/voicerig_run.py scenario.json` runs a script of utterances, holds, taps and waits, and writes a JSON report (stdlib only; see examples/).

## Tested
| Phone | Android | Result |
|---|---|---|
| Samsung Galaxy S22 (SM-S901E) | 16 (SDK 36) | `--selftest` OK (RMS 8424, expected 8485); scoped to another uid the recorder heard only the real mic (RMS 205); `--selftest-capture` OK: a VOICE_COMMUNICATION tone was captured (RMS 4913), first sound seen 49 ms after the tone started |
Everything else: UNVERIFIED. Reports welcome (model, Android version, result of `--selftest`).

## Prior art and why this exists
- The mechanism (a shell-user program that registers an AudioPolicy mixing rule to build a virtual microphone) was already shown in a scrcpy discussion, tested only in two apps: https://github.com/Genymobile/scrcpy/issues/3880 . `voicerig` makes it a tested tool with scoping, capture, timing and scenarios.
- scrcpy forwards a phone's audio to the computer (REMOTE_SUBMIX, `--audio-source=playback|mic|output`) but does not inject into the phone's microphone: https://blog.rom1v.com/2023/03/scrcpy-2-0-with-audio/ .
- VirtuMic (MIT) provides a virtual microphone app on Android 13+ over wireless ADB: https://github.com/IndiTheo/VirtuMic . voicerig is a CLI test tool with no app and no extra privileged libraries.
- Voice-agent evaluation frameworks test the BOT side (simulated callers, WER, latency, barge-in), e.g. https://github.com/ServiceNow/eva and https://deepeval.com/blog/top-5-open-source-voice-ai-evaluation-tools ; they do not drive a real phone's microphone. voicerig is the missing last metre: the real device, real audio stack, real app.
- Useful latency reference points from the evaluation literature: time to first audio (TTFA) under 800 ms, P50 end-to-end under 1.5 s, P95 under 5 s (https://hamming.ai/resources/how-to-evaluate-voice-agents-2026, vendor guidance, not a standard).

## Roadmap
Background-noise mixing at a given SNR; optional local ASR hook to score what the app answered (WER); Linux host helpers (espeak-ng/piper instead of `say`); a CI job that builds the dex; a compatibility table (phones, Android versions); open-mic stress scenarios (long monologues, double talk).

## Safety
- Without `--uid`, every app that records with the matched presets hears the injected audio while it runs. Use `--uid`.
- It runs with the shell user's privileges only for the time of the command; it unregisters the policy when it ends.
- Needs USB debugging on a phone you own. Do not use it to impersonate anyone or to feed audio into apps you do not control.
- It does not capture or record anything from other apps.

## Limits
Hidden/system APIs are used through reflection and can change between Android versions. The policy should exist before the app starts recording
(if the app keeps an older microphone session, restart its recording). Synthetic speech (`say`) is not a real voice: it tests the pipeline, not recognition quality in noise or speaker echo.

## License
Apache-2.0 (see LICENSE and NOTICE).
