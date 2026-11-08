package snakenet.net;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;







public final class Wire {

    public static final byte TEXT = 0;
    public static final byte VOICE = 1;

    private Wire() {
    }

    public static byte[] encode(List<Msg> frame) {
        StringBuilder sb = new StringBuilder(256);
        for (Msg m : frame) sb.append(m.encode()).append('\n');
        sb.append(Msg.END).append('\n');
        byte[] body = sb.toString().getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[body.length + 1];
        out[0] = TEXT;
        System.arraycopy(body, 0, out, 1, body.length);
        return out;
    }

    public static byte[] encode(Msg message) {
        return encode(List.of(message));
    }

    public static List<Msg> decode(byte[] data) {
        int offset = (data.length > 0 && data[0] == TEXT) ? 1 : 0;
        String text = new String(data, offset, data.length - offset, StandardCharsets.UTF_8);
        List<Msg> out = new ArrayList<>(4);
        for (String line : text.split("\n")) {
            if (line.isEmpty() || line.equals(Msg.END)) continue;
            Msg m = Msg.parse(line);
            if (m != null) out.add(m);
        }
        return out;
    }

    public static boolean isVoice(byte[] data) {
        return data.length > 3 && data[0] == VOICE;
    }

    
    public static byte[] voice(int seq, byte[] pcm) {
        byte[] out = new byte[pcm.length + 3];
        out[0] = VOICE;
        out[1] = (byte) (seq >> 8);
        out[2] = (byte) seq;
        System.arraycopy(pcm, 0, out, 3, pcm.length);
        return out;
    }

    
    public static byte[] voiceFrom(int pid, int seq, byte[] pcm) {
        byte[] out = new byte[pcm.length + 5];
        out[0] = VOICE;
        out[1] = (byte) (pid >> 8);
        out[2] = (byte) pid;
        out[3] = (byte) (seq >> 8);
        out[4] = (byte) seq;
        System.arraycopy(pcm, 0, out, 5, pcm.length);
        return out;
    }
}
