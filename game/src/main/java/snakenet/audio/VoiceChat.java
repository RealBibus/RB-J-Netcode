package snakenet.audio;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.TargetDataLine;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;









public final class VoiceChat {

    public static final float RATE = 16000f;
    public static final int FRAME_SAMPLES = 320;   

    private static final AudioFormat FORMAT = new AudioFormat(RATE, 16, 1, true, false);

    private final Map<Integer, Deque<short[]>> jitter = new ConcurrentHashMap<>();
    private final Map<Integer, Long> lastHeard = new ConcurrentHashMap<>();

    private volatile Consumer<byte[]> sender;
    private TargetDataLine mic;
    private SourceDataLine speaker;
    private Thread captureThread;
    private Thread mixThread;
    private volatile boolean running;
    private volatile boolean talking;
    private volatile boolean micOk;
    private volatile boolean speakerOk;
    private volatile float level;
    private volatile int sequence;

    public VoiceChat(Consumer<byte[]> sender) {
        this.sender = sender;
    }

    public boolean start() {
        if (running) return micOk;
        running = true;
        try {
            DataLine.Info info = new DataLine.Info(TargetDataLine.class, FORMAT);
            mic = (TargetDataLine) AudioSystem.getLine(info);
            mic.open(FORMAT);
            mic.start();
            micOk = true;
        } catch (Throwable e) {
            micOk = false;
        }
        try {
            DataLine.Info info = new DataLine.Info(SourceDataLine.class, FORMAT);
            speaker = (SourceDataLine) AudioSystem.getLine(info);
            speaker.open(FORMAT, FRAME_SAMPLES * 8);
            speaker.start();
            speakerOk = true;
        } catch (Throwable e) {
            speakerOk = false;
        }
        if (micOk) {
            captureThread = new Thread(this::captureLoop, "voice-capture");
            captureThread.setDaemon(true);
            captureThread.start();
        }
        if (speakerOk) {
            mixThread = new Thread(this::mixLoop, "voice-mix");
            mixThread.setDaemon(true);
            mixThread.start();
        }
        return micOk || speakerOk;
    }

    private void captureLoop() {
        byte[] buffer = new byte[FRAME_SAMPLES * 2];
        while (running) {
            int read = mic.read(buffer, 0, buffer.length);
            if (read <= 0) continue;
            if (!talking) {
                level = 0;
                continue;
            }
            float rms = 0;
            for (int i = 0; i + 1 < read; i += 2) {
                short s = (short) ((buffer[i] & 0xff) | (buffer[i + 1] << 8));
                rms += (float) s * s;
            }
            rms = (float) Math.sqrt(rms / Math.max(1, read / 2)) / 32768f;
            level = Math.min(1f, rms * 4f);

            Consumer<byte[]> s = sender;
            if (s != null) {
                sequence = (sequence + 1) & 0xffff;
                byte[] frame = new byte[3 + read];
                frame[0] = 1;
                frame[1] = (byte) (sequence >> 8);
                frame[2] = (byte) sequence;
                System.arraycopy(buffer, 0, frame, 3, read);
                s.accept(frame);
            }
        }
    }

    private void mixLoop() {
        byte[] out = new byte[FRAME_SAMPLES * 2];
        long next = System.nanoTime();
        while (running) {
            next += 20_000_000L;
            long sleep = next - System.nanoTime();
            if (sleep > 0) {
                try {
                    Thread.sleep(sleep / 1_000_000L, (int) (sleep % 1_000_000L));
                } catch (InterruptedException e) {
                    return;
                }
            }
            Arrays.fill(out, (byte) 0);
            long now = System.currentTimeMillis();
            for (Iterator<Map.Entry<Integer, Deque<short[]>>> it = jitter.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<Integer, Deque<short[]>> e = it.next();
                Long heard = lastHeard.get(e.getKey());
                if (heard == null || now - heard > 700) {
                    it.remove();
                    lastHeard.remove(e.getKey());
                    continue;
                }
                short[] frame = e.getValue().pollFirst();
                if (frame == null) continue;
                for (int i = 0; i < FRAME_SAMPLES; i++) {
                    short sample = i < frame.length ? frame[i] : 0;
                    int mixed = (short) ((out[i * 2] & 0xff) | (out[i * 2 + 1] << 8)) + sample;
                    mixed = Math.max(-32768, Math.min(32767, mixed));
                    out[i * 2] = (byte) mixed;
                    out[i * 2 + 1] = (byte) (mixed >> 8);
                }
            }
            speaker.write(out, 0, out.length);
        }
    }

    public void onVoice(int playerId, short[] pcm) {
        if (!speakerOk) return;
        Deque<short[]> queue = jitter.computeIfAbsent(playerId, k -> new ArrayDeque<>());
        synchronized (queue) {
            if (queue.size() > 12) queue.pollFirst();   
            queue.addLast(pcm);
        }
        lastHeard.put(playerId, System.currentTimeMillis());
    }

    public void setTalking(boolean on) {
        talking = on && micOk;
        if (!on) level = 0;
    }

    public boolean talking() {
        return talking;
    }

    public boolean micOk() {
        return micOk;
    }

    public boolean speakerOk() {
        return speakerOk;
    }

    public float level() {
        return level;
    }

    
    public int speakingCount() {
        long now = System.currentTimeMillis();
        int n = 0;
        for (Long t : lastHeard.values()) {
            if (now - t < 350) n++;
        }
        return n;
    }

    public void stop() {
        running = false;
        if (captureThread != null) captureThread.interrupt();
        if (mixThread != null) mixThread.interrupt();
        try {
            if (mic != null) {
                mic.stop();
                mic.close();
            }
        } catch (Throwable ignored) {
        }
        try {
            if (speaker != null) {
                speaker.stop();
                speaker.close();
            }
        } catch (Throwable ignored) {
        }
        mic = null;
        speaker = null;
        jitter.clear();
        lastHeard.clear();
        micOk = false;
        speakerOk = false;
    }
}
