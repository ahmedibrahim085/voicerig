// Our own tiny microphone injector for testing the voice app on a REAL Android phone (no root, no third-party code).
// Run as the adb SHELL user through app_process (like scrcpy does), over the USB adb connection:
//   adb shell CLASSPATH=/data/local/tmp/voicerig.dex app_process / VoiceRig [options]
// It registers a loopback AudioPolicy (system API, allowed to the shell user: MODIFY_AUDIO_ROUTING) with an INJECTOR mix, so that
// AudioRecord sessions that match the rules read the audio we write instead of the microphone. The mechanism is the one documented
// by Android's AudioMixingRule/AudioPolicy; the VirtuMic project (MIT, audited by us 2026-11-25) was read as a reference, no code copied.
//
// Options: --uid N          only recordings of this uid get the injected audio (default: all matching recordings)
//          --presets a,b    capture presets matched (default 1,5,6,7,9,10: MIC, CAMCORDER, VOICE_RECOGNITION, VOICE_COMMUNICATION, UNPROCESSED, VOICE_PERFORMANCE)
//          --rate N         sample rate of the injected stream, mono 16-bit (default 48000)
//          --cmd PATH       control file (default /data/local/tmp/inject.cmd): when it exists, its first line is the path of a raw
//                           PCM s16le mono file at --rate to play; the file is deleted once read. Silence is written between utterances.
//          --run SECONDS    stop after this time (default 600)
//          --selftest       also record with AudioRecord in this process and report the level (proves the pipeline)
//          --capture-uid N  ALSO capture what the app with this uid PLAYS (the bot's voice, any usage incl. VOICE_COMMUNICATION) into
//                           --capture-out (default /data/local/tmp/capture.pcm, raw s16le mono at --rate). That app's playback is diverted
//                           from the speaker while the policy runs (so a test has no speaker echo).
//          --monitor MODE   make the test AUDIBLE on the phone's speaker: bot = replay what the app plays (capture diverts it from the
//                           speaker otherwise), user = also play the injected utterance out loud, both, or off (default off).
//                           Needs --capture-uid for bot. The monitor is played by this process (not the app), so it never feeds back.
//          --selftest-capture  play a tone from this process, capture it with the capture mix and report the level
// Machine-readable events on stdout, one per line, epoch milliseconds: EVT <name> <ms> [detail]
import android.content.Context;
import android.content.ContextWrapper;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.os.Looper;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public final class VoiceRig {
    static final String SHELL_PKG = "com.android.shell";

    /** The shell's package identity for the policy (the system context has none an app_process could use). */
    static final class ShellContext extends ContextWrapper {
        ShellContext(Context base) { super(base); }
        @Override public String getPackageName() { return SHELL_PKG; }
        @Override public String getOpPackageName() { return SHELL_PKG; }
        @Override public Context getApplicationContext() { return this; }
    }

    static Context systemContext() throws Exception {
        Looper.prepareMainLooper();
        Class<?> at = Class.forName("android.app.ActivityThread");
        Constructor<?> c = at.getDeclaredConstructor();
        c.setAccessible(true);
        Object thread = c.newInstance();
        Field cur = at.getDeclaredField("sCurrentActivityThread");
        cur.setAccessible(true);
        cur.set(null, thread);
        Field sys = at.getDeclaredField("mSystemThread");
        sys.setAccessible(true);
        sys.setBoolean(thread, true);
        // Android 14+ keeps the configuration in a ConfigurationController that a bare ActivityThread does not have yet
        try {
            Class<?> cc = Class.forName("android.app.ConfigurationController");
            Class<?> internal = Class.forName("android.app.ActivityThreadInternal");
            Constructor<?> cctor = cc.getDeclaredConstructor(internal);
            cctor.setAccessible(true);
            Field f = at.getDeclaredField("mConfigurationController");
            f.setAccessible(true);
            f.set(thread, cctor.newInstance(thread));
        } catch (ClassNotFoundException | NoSuchFieldException | NoSuchMethodException ignored) { }
        Method get = at.getDeclaredMethod("getSystemContext");
        return new ShellContext((Context) get.invoke(thread));
    }

    static int staticInt(Class<?> k, String name) throws Exception { return k.getField(name).getInt(null); }

    public static void main(String[] args) throws Exception {
        int uid = -1, rate = 48000, runSeconds = 600;
        String presets = "1,5,6,7,9,10", cmd = "/data/local/tmp/inject.cmd";
        boolean selftest = false, selftestCapture = false;
        int captureUid = -1;
        String monitor = "off";
        String captureOut = "/data/local/tmp/capture.pcm";
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--uid": uid = Integer.parseInt(args[++i]); break;
                case "--presets": presets = args[++i]; break;
                case "--rate": rate = Integer.parseInt(args[++i]); break;
                case "--cmd": cmd = args[++i]; break;
                case "--run": runSeconds = Integer.parseInt(args[++i]); break;
                case "--selftest": selftest = true; break;
                case "--capture-uid": captureUid = Integer.parseInt(args[++i]); break;
                case "--capture-out": captureOut = args[++i]; break;
                case "--monitor": monitor = args[++i]; break;
                case "--selftest-capture": selftestCapture = true; captureUid = android.os.Process.myUid(); break;
                default: System.err.println("unknown option " + args[i]); System.exit(2);
            }
        }
        Context ctx = systemContext();
        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);

        Class<?> ruleB = Class.forName("android.media.audiopolicy.AudioMixingRule$Builder");
        Class<?> rule = Class.forName("android.media.audiopolicy.AudioMixingRule");
        Class<?> mixB = Class.forName("android.media.audiopolicy.AudioMix$Builder");
        Class<?> mix = Class.forName("android.media.audiopolicy.AudioMix");
        Class<?> polB = Class.forName("android.media.audiopolicy.AudioPolicy$Builder");
        Class<?> pol = Class.forName("android.media.audiopolicy.AudioPolicy");

        Object rb = ruleB.getConstructor().newInstance();
        ruleB.getMethod("setTargetMixRole", int.class).invoke(rb, staticInt(rule, "MIX_ROLE_INJECTOR"));
        Method addRule = ruleB.getMethod("addMixRule", int.class, Object.class);
        if (uid >= 0) {
            addRule.invoke(rb, staticInt(rule, "RULE_MATCH_UID"), Integer.valueOf(uid));
        }
        int rulePreset = staticInt(rule, "RULE_MATCH_ATTRIBUTE_CAPTURE_PRESET");
        for (String p : presets.split(",")) {
            AudioAttributes.Builder ab = new AudioAttributes.Builder();
            AudioAttributes.Builder.class.getMethod("setCapturePreset", int.class).invoke(ab, Integer.parseInt(p.trim()));
            AudioAttributes attr = ab.build();
            addRule.invoke(rb, rulePreset, attr);
        }
        Object theRule = ruleB.getMethod("build").invoke(rb);

        AudioFormat format = new AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build();
        Object mb = mixB.getConstructor(rule).newInstance(theRule);
        mixB.getMethod("setFormat", AudioFormat.class).invoke(mb, format);
        mixB.getMethod("setRouteFlags", int.class).invoke(mb, staticInt(mix, "ROUTE_FLAG_LOOP_BACK"));
        Object theMix = mixB.getMethod("build").invoke(mb);

        Object captureMix = null;
        if (captureUid >= 0) {
            Object crb = ruleB.getConstructor().newInstance();
            ruleB.getMethod("setTargetMixRole", int.class).invoke(crb, staticInt(rule, "MIX_ROLE_PLAYERS"));
            ruleB.getMethod("addMixRule", int.class, Object.class).invoke(crb, staticInt(rule, "RULE_MATCH_UID"), Integer.valueOf(captureUid));
            AudioFormat capFormat = new AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build();
            Object cmb = mixB.getConstructor(rule).newInstance(ruleB.getMethod("build").invoke(crb));
            mixB.getMethod("setFormat", AudioFormat.class).invoke(cmb, capFormat);
            mixB.getMethod("setRouteFlags", int.class).invoke(cmb, staticInt(mix, "ROUTE_FLAG_LOOP_BACK"));
            captureMix = mixB.getMethod("build").invoke(cmb);
        }

        Object pb = polB.getConstructor(Context.class).newInstance(ctx);
        polB.getMethod("setLooper", Looper.class).invoke(pb, Looper.getMainLooper());
        polB.getMethod("addMix", mix).invoke(pb, theMix);
        if (captureMix != null) polB.getMethod("addMix", mix).invoke(pb, captureMix);
        Object policy = polB.getMethod("build").invoke(pb);

        Method register = AudioManager.class.getMethod("registerAudioPolicy", pol);
        int result = (Integer) register.invoke(am, policy);
        if (result != 0) { System.err.println("registerAudioPolicy failed: " + result); System.exit(3); }
        System.out.println("POLICY registered (uid=" + uid + ", presets=" + presets + ", rate=" + rate + ")");

        final boolean[] stopCapture = {false};
        final double[] captureLevel = {0};
        Thread captureThread = null;
        if (captureMix != null) {
            final AudioRecord sink = (AudioRecord) pol.getMethod("createAudioRecordSink", mix).invoke(policy, captureMix);
            final String outPath = captureOut;
            final int r = rate;
            final AudioTrack botMonitor = (monitor.equals("bot") || monitor.equals("both")) ? monitorTrack(rate) : null;
            captureThread = new Thread(() -> {
                try (FileOutputStream out = new FileOutputStream(outPath)) {
                    sink.startRecording();
                    System.out.println("EVT capture_started " + System.currentTimeMillis() + " uid=" + android.os.Process.myUid());
                    short[] buf = new short[r / 50]; // 20 ms
                    byte[] bytes = new byte[buf.length * 2];
                    boolean sound = false; double sum = 0; long n = 0;
                    while (!stopCapture[0]) {
                        int got = sink.read(buf, 0, buf.length);
                        if (got <= 0) continue;
                        double e = 0;
                        for (int i = 0; i < got; i++) { bytes[2 * i] = (byte) (buf[i] & 0xff); bytes[2 * i + 1] = (byte) ((buf[i] >> 8) & 0xff); e += (double) buf[i] * buf[i]; }
                        out.write(bytes, 0, got * 2);
                        if (botMonitor != null) botMonitor.write(bytes, 0, got * 2);
                        double rms = Math.sqrt(e / got);
                        sum += e; n += got;
                        boolean now = rms > 300; // simple energy gate: the bot speaking
                        if (now != sound) { sound = now; System.out.println("EVT capture_" + (now ? "sound_start " : "sound_end ") + System.currentTimeMillis()); }
                    }
                    sink.stop(); sink.release();
                    if (botMonitor != null) { botMonitor.stop(); botMonitor.release(); }
                    captureLevel[0] = n == 0 ? 0 : Math.sqrt(sum / n);
                } catch (Throwable t) { System.out.println("CAPTURE failed: " + t); }
            });
            captureThread.start();
        }
        if (selftestCapture) {
            final int r = rate;
            new Thread(() -> {
                try {
                    Thread.sleep(500);
                    AudioTrack t = new AudioTrack.Builder()
                            .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                            .setAudioFormat(new AudioFormat.Builder().setSampleRate(r).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                            .setBufferSizeInBytes(r).build();
                    byte[] tone = sine(r, 880, 2000);
                    t.play();
                    System.out.println("EVT tone_start " + System.currentTimeMillis());
                    t.write(tone, 0, tone.length);
                    t.stop(); t.release();
                    System.out.println("EVT tone_end " + System.currentTimeMillis());
                } catch (Throwable e) { System.out.println("SELFTEST-CAPTURE tone failed: " + e); }
            }).start();
        }

        AudioTrack track = (AudioTrack) pol.getMethod("createAudioTrackSource", mix).invoke(policy, theMix);
        track.play();
        System.out.println("TRACK playing, state=" + track.getPlayState());

        Thread selfRecord = null;
        final double[] level = {0};
        if (selftest) {
            final int r = rate;
            selfRecord = new Thread(() -> {
                try {
                    int min = AudioRecord.getMinBufferSize(r, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
                    AudioRecord rec = new AudioRecord(android.media.MediaRecorder.AudioSource.MIC, r, AudioFormat.CHANNEL_IN_MONO,
                            AudioFormat.ENCODING_PCM_16BIT, Math.max(min, r / 5));
                    System.out.println("SELFTEST record state=" + rec.getState());
                    rec.startRecording();
                    short[] buf = new short[r / 10];
                    long end = System.currentTimeMillis() + 3000;
                    double sum = 0; long n = 0;
                    while (System.currentTimeMillis() < end) {
                        int got = rec.read(buf, 0, buf.length);
                        for (int i = 0; i < got; i++) { sum += (double) buf[i] * buf[i]; n++; }
                    }
                    rec.stop(); rec.release();
                    level[0] = n == 0 ? 0 : Math.sqrt(sum / n);
                    System.out.println("SELFTEST rms=" + level[0] + " samples=" + n);
                } catch (Throwable t) { System.out.println("SELFTEST failed: " + t); }
            });
            selfRecord.start();
        }

        final AudioTrack userMonitor = (monitor.equals("user") || monitor.equals("both")) ? monitorTrack(rate) : null;
        byte[] pending = null; int pos = 0;
        final int chunk = rate / 50 * 2; // 20 ms of 16-bit mono
        byte[] silence = new byte[chunk];
        long end = System.currentTimeMillis() + runSeconds * 1000L;
        long selftestUntil = selftest ? System.currentTimeMillis() + 4000 : (selftestCapture ? System.currentTimeMillis() + 4500 : Long.MAX_VALUE);
        File cmdFile = new File(cmd);
        while (System.currentTimeMillis() < Math.min(end, selftestUntil)) {
            if (pending == null && cmdFile.exists()) {
                String path = new String(Files.readAllBytes(cmdFile.toPath()), StandardCharsets.UTF_8).trim();
                cmdFile.delete();
                try (FileInputStream in = new FileInputStream(path)) { pending = in.readAllBytes(); pos = 0; }
                System.out.println("EVT play_start " + System.currentTimeMillis() + " " + path + " " + pending.length / 2 * 1000L / rate + "ms");
            }
            if (selftest && pending == null) { pending = sine(rate, 440, 3000); pos = 0; System.out.println("SELFTEST injecting 440 Hz"); }
            if (pending != null) {
                int n = Math.min(chunk, pending.length - pos);
                track.write(pending, pos, n);
                if (userMonitor != null) userMonitor.write(pending, pos, n);
                pos += n;
                if (pos >= pending.length) { pending = null; System.out.println("EVT play_end " + System.currentTimeMillis()); if (selftest) selftestUntil = 0; }
            } else {
                track.write(silence, 0, chunk);
            }
        }
        if (selfRecord != null) selfRecord.join(5000);
        stopCapture[0] = true;
        if (captureThread != null) captureThread.join(3000);
        if (captureMix != null) System.out.println("CAPTURE rms=" + captureLevel[0] + " file=" + captureOut);
        track.stop(); track.release();
        if (userMonitor != null) { userMonitor.stop(); userMonitor.release(); }
        am.getClass().getMethod("unregisterAudioPolicy", pol).invoke(am, policy);
        System.out.println("POLICY unregistered");
        System.exit((selftest && level[0] < 500) || (selftestCapture && captureLevel[0] < 300) ? 4 : 0);
    }

    /** A plain speaker track owned by this process (usage MEDIA): what the human in the room hears. */
    static AudioTrack monitorTrack(int rate) {
        AudioTrack t = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(new AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(rate) // about 0.5 s
                .build();
        t.play();
        return t;
    }

    static byte[] sine(int rate, double hz, int ms) {
        int n = rate * ms / 1000;
        byte[] b = new byte[n * 2];
        for (int i = 0; i < n; i++) {
            short s = (short) (12000 * Math.sin(2 * Math.PI * hz * i / rate));
            b[2 * i] = (byte) (s & 0xff);
            b[2 * i + 1] = (byte) ((s >> 8) & 0xff);
        }
        return b;
    }
}
