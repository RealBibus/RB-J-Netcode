package snakenet.audio;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;





public final class Sfx {

    public enum Sound { CLICK, EAT, DIE, COUNT, START, WIN, JOIN, LEAVE }

    private static final int RATE = 44100;
    private static volatile boolean enabled = true;
    private static volatile SourceDataLine line;
    private static volatile boolean audioBroken;

    private static final ExecutorService POOL = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "snake-sfx");
        t.setDaemon(true);
        return t;
    });

    private static final byte[][] CACHE = new byte[Sound.values().length][];

    private Sfx() {
    }

    public static void setEnabled(boolean on) {
        enabled = on;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static void play(Sound s) {
        if (!enabled || audioBroken) return;
        POOL.submit(() -> {
            try {
                byte[] data = CACHE[s.ordinal()];
                if (data == null) {
                    data = generate(s);
                    CACHE[s.ordinal()] = data;
                }
                SourceDataLine l = line();
                if (l == null) return;
                l.write(data, 0, data.length);
            } catch (Throwable t) {
                audioBroken = true;
            }
        });
    }

    private static SourceDataLine line() {
        if (audioBroken) return null;
        if (line == null) {
            synchronized (Sfx.class) {
                if (line == null) {
                    try {
                        AudioFormat fmt = new AudioFormat(RATE, 16, 1, true, false);
                        SourceDataLine l = AudioSystem.getSourceDataLine(fmt);
                        l.open(fmt, RATE);
                        l.start();
                        line = l;
                    } catch (Throwable t) {
                        audioBroken = true;
                        return null;
                    }
                }
            }
        }
        return line;
    }

    private static byte[] generate(Sound s) {
        switch (s) {
            case CLICK:
                return sweep(900, 520, 0.055, 0.25, 0.6);
            case EAT:
                return sweep(620, 980, 0.09, 0.32, 0.35);
            case DIE:
                return sweep(420, 70, 0.45, 0.4, 0.15);
            case COUNT:
                return sweep(520, 520, 0.10, 0.3, 0.3);
            case START:
                return concat(sweep(440, 440, 0.10, 0.28, 0.2), sweep(660, 660, 0.10, 0.28, 0.2),
                        sweep(880, 880, 0.22, 0.3, 0.25));
            case WIN:
                return concat(sweep(523, 523, 0.12, 0.3, 0.2), sweep(659, 659, 0.12, 0.3, 0.2),
                        sweep(784, 784, 0.12, 0.3, 0.2), sweep(1047, 1047, 0.30, 0.32, 0.3));
            case JOIN:
                return sweep(380, 720, 0.12, 0.25, 0.3);
            case LEAVE:
                return sweep(600, 260, 0.16, 0.25, 0.3);
            default:
                return new byte[0];
        }
    }

    
    private static byte[] sweep(double f0, double f1, double dur, double volume, double squareMix) {
        int n = (int) (RATE * dur);
        byte[] out = new byte[n * 2];
        double phase = 0;
        for (int i = 0; i < n; i++) {
            double p = i / (double) n;
            double f = f0 + (f1 - f0) * p;
            phase += 2 * Math.PI * f / RATE;
            double env = Math.exp(-3.2 * p) * (1 - p * 0.15);
            double sine = Math.sin(phase);
            double square = Math.signum(Math.sin(phase)) * 0.35;
            double v = (sine * (1 - squareMix) + square * squareMix) * env * volume;
            int sample = (int) (Math.max(-1, Math.min(1, v)) * Short.MAX_VALUE);
            out[i * 2] = (byte) (sample & 0xff);
            out[i * 2 + 1] = (byte) ((sample >> 8) & 0xff);
        }
        return out;
    }

    private static byte[] concat(byte[]... parts) {
        int total = 0;
        for (byte[] p : parts) total += p.length;
        byte[] out = new byte[total];
        int pos = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, pos, p.length);
            pos += p.length;
        }
        return out;
    }
}
