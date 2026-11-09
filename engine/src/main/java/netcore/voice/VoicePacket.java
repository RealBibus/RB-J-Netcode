package netcore.voice;

public final class VoicePacket {

    public static final int KIND_VOICE = 1;
    public static final int HEADER = 3;

    public record Frame(int seq, short[] pcm) {
    }

    private VoicePacket() {
    }

    public static byte[] encode(int seq, short[] pcm) {
        int n = pcm == null ? 0 : pcm.length;
        byte[] out = new byte[HEADER + n * 2];
        out[0] = KIND_VOICE;
        out[1] = (byte) (seq >> 8);
        out[2] = (byte) seq;
        for (int i = 0; i < n; i++) {
            short s = pcm[i];
            out[HEADER + i * 2] = (byte) s;
            out[HEADER + i * 2 + 1] = (byte) (s >> 8);
        }
        return out;
    }

    public static Frame decode(byte[] data, int maxPacketBytes) {
        if (data == null || data.length < HEADER + 2 || data.length > maxPacketBytes) return null;
        if (data[0] != KIND_VOICE) return null;
        int payload = data.length - HEADER;
        if ((payload & 1) != 0) return null;
        int seq = ((data[1] & 0xff) << 8) | (data[2] & 0xff);
        short[] pcm = readPcm(data, HEADER, payload);
        return new Frame(seq, pcm);
    }

    public static short[] readPcm(byte[] data, int offset, int length) {
        int n = Math.max(0, length / 2);
        short[] pcm = new short[n];
        for (int i = 0; i < n; i++) {
            pcm[i] = (short) ((data[offset + i * 2] & 0xff) | ((data[offset + i * 2 + 1] & 0xff) << 8));
        }
        return pcm;
    }

    public static int sequence(byte[] data, int offset) {
        return ((data[offset] & 0xff) << 8) | (data[offset + 1] & 0xff);
    }
}
