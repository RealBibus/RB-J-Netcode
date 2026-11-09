package netcore.voice;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.TargetDataLine;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;

public final class VoiceEngine {

    public interface Sender {
        void send(byte[] frame);
    }

    private final VoiceConfig config;
    private final Map<Integer, VoiceJitterBuffer> jitter = new ConcurrentHashMap<>();
    private final Map<Integer, Long> lastHeard = new ConcurrentHashMap<>();
    private final AudioFormat format;

    private volatile Sender sender;
    private volatile WebRtcVoiceAdapter webRtc;
    private volatile boolean webRtcActive;

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
    private volatile long captureFrames;
    private volatile long sentFrames;
    private volatile long mixedFrames;

    public VoiceEngine(VoiceConfig config, Sender sender) {
        this.config = config == null ? VoiceConfig.DEFAULT : config;
        this.sender = sender;
        this.format = new AudioFormat(this.config.sampleRate(), 16, 1, true, false);
    }

    public VoiceConfig config() {
        return config;
    }

    public void setSender(Sender sender) {
        this.sender = sender;
    }

    public VoiceMode mode() {
        return config.mode();
    }

    public String backend() {
        if (webRtcActive) return "WebRTC";
        return WebRtcSupport.backendName(config.mode());
    }

    public boolean webRtcActive() {
        return webRtcActive;
    }

    public boolean webRtcAvailable() {
        return WebRtcSupport.available();
    }

    public boolean start() {
        if (running) return micOk || speakerOk || webRtcActive;
        running = true;
        if (config.mode() == VoiceMode.WEBRTC) {
            if (openWebRtc()) return true;
        }
        try {
            DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);
            mic = (TargetDataLine) AudioSystem.getLine(info);
            mic.open(format);
            mic.start();
            micOk = true;
        } catch (Throwable e) {
            micOk = false;
        }
        try {
            DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
            speaker = (SourceDataLine) AudioSystem.getLine(info);
            speaker.open(format, config.frameSamples() * 8);
            speaker.start();
            speakerOk = true;
        } catch (Throwable e) {
            speakerOk = false;
        }
        if (micOk) {
            captureThread = new Thread(this::captureLoop, "netcore-voice-capture");
            captureThread.setDaemon(true);
            captureThread.setPriority(Thread.NORM_PRIORITY + 1);
            captureThread.start();
        }
        if (speakerOk) {
            mixThread = new Thread(this::mixLoop, "netcore-voice-mix");
            mixThread.setDaemon(true);
            mixThread.setPriority(Thread.NORM_PRIORITY + 1);
            mixThread.start();
        }
        return micOk || speakerOk;
    }

    private boolean openWebRtc() {
        if (!WebRtcSupport.corePresent()) return false;
        try {
            Iterator<WebRtcVoiceAdapter> it = ServiceLoader.load(WebRtcVoiceAdapter.class).iterator();
            if (!it.hasNext()) return false;
            WebRtcVoiceAdapter adapter = it.next();
            adapter.open(config, frame -> {
                Sender s = sender;
                if (s != null) s.send(frame);
            });
            if (!adapter.active()) {
                adapter.close();
                return false;
            }
            webRtc = adapter;
            webRtcActive = true;
            micOk = true;
            speakerOk = true;
            return true;
        } catch (Throwable e) {
            webRtcActive = false;
            return false;
        }
    }

    private void captureLoop() {
        int bytes = config.frameSamples() * 2;
        byte[] buffer = new byte[bytes];
        while (running) {
            int read;
            try {
                read = mic.read(buffer, 0, buffer.length);
            } catch (Throwable e) {
                return;
            }
            if (read <= 0) continue;
            captureFrames++;
            short[] pcm = VoicePacket.readPcm(buffer, 0, read);
            double rms = VoiceGuard.rms(pcm);
            level = (float) Math.min(1.0, rms * 4.0);
            if (!talking) continue;
            if (config.voiceActivity() && rms < config.vadThreshold()) continue;
            sequence = (sequence + 1) & 0xffff;
            sentFrames++;
            byte[] frame = VoicePacket.encode(sequence, pcm);
            Sender s = sender;
            if (s != null) s.send(frame);
        }
    }

    private void mixLoop() {
        int samples = config.frameSamples();
        short[] out = new short[samples];
        byte[] bytes = new byte[samples * 2];
        long next = System.nanoTime();
        while (running) {
            next += config.frameNanos();
            long sleep = next - System.nanoTime();
            if (sleep > 0) {
                try {
                    Thread.sleep(sleep / 1_000_000L, (int) (sleep % 1_000_000L));
                } catch (InterruptedException e) {
                    return;
                }
            }
            Arrays.fill(out, (short) 0);
            long now = System.nanoTime();
            boolean any = false;
            for (Iterator<Map.Entry<Integer, VoiceJitterBuffer>> it = jitter.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<Integer, VoiceJitterBuffer> e = it.next();
                VoiceJitterBuffer buffer = e.getValue();
                if (!buffer.active(now)) {
                    it.remove();
                    lastHeard.remove(e.getKey());
                    continue;
                }
                short[] frame = buffer.poll(now);
                if (frame == null) continue;
                any = true;
                int n = Math.min(samples, frame.length);
                for (int i = 0; i < n; i++) {
                    int mixed = out[i] + frame[i];
                    out[i] = (short) Math.max(-32768, Math.min(32767, mixed));
                }
            }
            if (!any && speakerOk) {
                boolean others = !jitter.isEmpty();
                if (!others) {
                    mixedFrames++;
                    continue;
                }
            }
            for (int i = 0; i < samples; i++) {
                short s = out[i];
                bytes[i * 2] = (byte) s;
                bytes[i * 2 + 1] = (byte) (s >> 8);
            }
            try {
                speaker.write(bytes, 0, bytes.length);
                mixedFrames++;
            } catch (Throwable e) {
                return;
            }
        }
    }

    public void onVoice(int playerId, int seq, short[] pcm) {
        if (webRtcActive) {
            WebRtcVoiceAdapter adapter = webRtc;
            if (adapter != null) adapter.onRemote(playerId, seq, pcm);
            return;
        }
        if (!speakerOk) return;
        VoiceJitterBuffer buffer = jitter.computeIfAbsent(playerId, k -> new VoiceJitterBuffer(config));
        long now = System.nanoTime();
        if (buffer.push(seq, pcm, now)) {
            lastHeard.put(playerId, now);
        }
    }

    public void onVoiceFrame(int playerId, int seq, byte[] packet) {
        VoicePacket.Frame frame = VoicePacket.decode(packet, config.maxPacketBytes());
        if (frame == null) return;
        onVoice(playerId, seq, frame.pcm());
    }

    public void setTalking(boolean on) {
        talking = on && (micOk || webRtcActive);
        if (!on) level = 0;
        WebRtcVoiceAdapter adapter = webRtc;
        if (adapter != null) adapter.setTalking(talking);
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
        long now = System.nanoTime();
        int n = 0;
        for (Long t : lastHeard.values()) {
            if (t != null && now - t < 350_000_000L) n++;
        }
        return n;
    }

    public int jitterQueued() {
        int n = 0;
        for (VoiceJitterBuffer b : jitter.values()) n += b.queued();
        return n;
    }

    public int jitterTarget() {
        int n = 0;
        int count = 0;
        for (VoiceJitterBuffer b : jitter.values()) {
            n += b.targetFrames();
            count++;
        }
        return count == 0 ? config.jitterTargetFrames() : n / count;
    }

    public long captureFrames() {
        return captureFrames;
    }

    public long sentFrames() {
        return sentFrames;
    }

    public long mixedFrames() {
        return mixedFrames;
    }

    public void stop() {
        running = false;
        WebRtcVoiceAdapter adapter = webRtc;
        if (adapter != null) {
            try {
                adapter.close();
            } catch (Throwable ignored) {
            }
        }
        webRtc = null;
        webRtcActive = false;
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
        level = 0;
        talking = false;
    }
}
