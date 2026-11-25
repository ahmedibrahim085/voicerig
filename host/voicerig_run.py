#!/usr/bin/env python3
"""voicerig scenario runner (stdlib only): drive a voice app on a real Android phone with injected speech and measure it.

usage: voicerig_run.py scenario.json [--serial SERIAL] [--out report.json]
Needs: adb, the built out/voicerig.dex, and for `say` steps macOS `say` + ffmpeg (or give a ready PCM file: {"pcm": "x.pcm"}).

Scenario JSON: {"app_uid": 10303, "rate": 48000, "monitor": "both", "steps": [ ... ]}   ("monitor": off | user | bot | both = also play
the injected speech and the app's replies out loud on the phone so a human in the room hears the conversation)   steps:
  {"say": "how much money do I have", "voice": "Samantha", "hold": {"x": 938, "y": 2046}}   inject speech; with "hold" the
        runner also holds that screen point (push to talk) for the utterance length plus a margin
  {"say": "..."}                                   same, without holding anything (open mic / streaming: the mic is already open)
  {"wait_reply": 15}                               wait up to N s for the app to start speaking (capture sound start) and then to finish
  {"tap": {"x": 540, "y": 1600}}                   a screen tap
  {"sleep": 2}
Report: per turn, the milliseconds from the end of your utterance to the first sound the app plays (TTFA) and the length of its reply.
The app's playback is captured by the injector (--capture-uid) into a PCM file that is pulled to --out's folder (reply.pcm).
"""
import json, os, subprocess, sys, tempfile, threading, time

def parse_events(lines):
    """EVT <name> <epoch_ms> [detail...] -> [(name, ms, detail)]"""
    out = []
    for ln in lines:
        p = ln.strip().split(None, 3)
        if len(p) >= 3 and p[0] == 'EVT':
            try: out.append((p[1], int(p[2]), p[3] if len(p) > 3 else ''))
            except ValueError: pass
    return out

def turn_metrics(events):
    """For each play_end, the first capture_sound_start after it, and how long that sound lasted (until capture_sound_end)."""
    turns = []
    ends = [e for e in events if e[0] == 'play_end']
    starts = [e for e in events if e[0] == 'capture_sound_start']
    stops = [e for e in events if e[0] == 'capture_sound_end']
    for i, e in enumerate(ends):
        nxt = ends[i + 1][1] if i + 1 < len(ends) else 1 << 62
        s = next((x for x in starts if e[1] <= x[1] < nxt), None)
        if s is None:
            turns.append({'utterance_end_ms': e[1], 'ttfa_ms': None, 'reply_ms': None}); continue
        t = next((x for x in stops if x[1] > s[1]), None)
        turns.append({'utterance_end_ms': e[1], 'ttfa_ms': s[1] - e[1], 'reply_ms': (t[1] - s[1]) if t else None})
    return turns

class Phone:
    def __init__(self, serial=None, adb='adb'):
        self.base = [adb] + (['-s', serial] if serial else [])
    def run(self, *a, **kw): return subprocess.run(self.base + list(a), capture_output=True, text=True, **kw)
    def shell(self, cmd): return self.run('shell', cmd)

def speech_pcm(text, voice, rate, tmp):
    aiff, pcm = os.path.join(tmp, 's.aiff'), os.path.join(tmp, 's.pcm')
    subprocess.run(['say', '-v', voice or 'Samantha', '-o', aiff, text], check=True)
    subprocess.run(['ffmpeg', '-loglevel', 'error', '-y', '-i', aiff, '-ar', str(rate), '-ac', '1', '-f', 's16le', pcm], check=True)
    return pcm

def main(argv):
    scen = json.load(open(argv[1]))
    serial = argv[argv.index('--serial') + 1] if '--serial' in argv else None
    outp = argv[argv.index('--out') + 1] if '--out' in argv else 'report.json'
    ph = Phone(serial); rate = scen.get('rate', 48000); uid = scen['app_uid']
    dex = scen.get('dex', os.path.join(os.path.dirname(__file__), '..', 'out', 'voicerig.dex'))
    ph.run('push', dex, '/data/local/tmp/voicerig.dex')
    cmd = ph.base + ['shell', f'CLASSPATH=/data/local/tmp/voicerig.dex app_process / VoiceRig --uid {uid} --capture-uid {uid} --rate {rate} --monitor {scen.get("monitor", "off")} --run {scen.get("run", 900)}']
    inj = subprocess.Popen(cmd, stdout=subprocess.PIPE, text=True, bufsize=1)
    lines = []
    threading.Thread(target=lambda: [lines.append(l) for l in inj.stdout], daemon=True).start()
    time.sleep(1.5)
    tmp = tempfile.mkdtemp()
    try:
        for st in scen['steps']:
            if 'say' in st:
                pcm = st.get('pcm') or speech_pcm(st['say'], st.get('voice'), rate, tmp)
                ms = os.path.getsize(pcm) // 2 * 1000 // rate
                ph.run('push', pcm, '/data/local/tmp/utterance.pcm')
                hold = None
                if 'hold' in st:
                    h = st['hold']
                    hold = threading.Thread(target=lambda: ph.shell(f'input swipe {h["x"]} {h["y"]} {h["x"]} {h["y"]} {ms + 600}'))
                    hold.start()
                ph.shell('echo /data/local/tmp/utterance.pcm > /data/local/tmp/inject.cmd')
                time.sleep(ms / 1000 + 0.8)
                if hold: hold.join()
            elif 'wait_reply' in st:
                end = time.time() + st['wait_reply']; seen = 0
                while time.time() < end:
                    ev = parse_events(lines)
                    started = len([e for e in ev if e[0] == 'capture_sound_start'])
                    ended = len([e for e in ev if e[0] == 'capture_sound_end'])
                    if started > seen: seen = started
                    if started and ended >= started and time.time() - ev[-1][1] / 1000 > 1.5: break
                    time.sleep(0.2)
            elif 'tap' in st:
                ph.shell(f'input tap {st["tap"]["x"]} {st["tap"]["y"]}')
            elif 'sleep' in st:
                time.sleep(st['sleep'])
    finally:
        inj.terminate()
        time.sleep(0.5)
    ev = parse_events(lines)
    report = {'turns': turn_metrics(ev), 'events': [list(e) for e in ev]}
    ph.run('pull', '/data/local/tmp/capture.pcm', os.path.join(os.path.dirname(os.path.abspath(outp)), 'reply.pcm'))
    json.dump(report, open(outp, 'w'), indent=1)
    for i, t in enumerate(report['turns'], 1): print(f'turn {i}: TTFA {t["ttfa_ms"]} ms, reply {t["reply_ms"]} ms')
    return 0

if __name__ == '__main__':
    sys.exit(main(sys.argv))
