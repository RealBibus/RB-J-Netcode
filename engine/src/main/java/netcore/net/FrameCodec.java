package netcore.net;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;

/**
 * Рамка поверх TCP: {@code [int длина][byte тип][int seq][payload]}.
 * TCP — поток, без длины кадры слипаются, поэтому длина обязательна.
 */
public final class FrameCodec {

    public static final int MAX_PAYLOAD = 256 * 1024;

    private FrameCodec() {
    }

    public static void write(DataOutputStream out, Packet packet) throws IOException {
        if (packet.payload.length > MAX_PAYLOAD) {
            throw new IOException("слишком большой пакет: " + packet.payload.length);
        }
        out.writeInt(5 + packet.payload.length);
        out.writeByte(packet.type);
        out.writeInt(packet.seq);
        out.write(packet.payload);
        out.flush();
    }

    public static Packet read(DataInputStream in) throws IOException {
        int length;
        try {
            length = in.readInt();
        } catch (EOFException e) {
            return null;
        }
        if (length < 5 || length > MAX_PAYLOAD + 5) {
            throw new IOException("битая длина кадра: " + length);
        }
        byte type = in.readByte();
        int seq = in.readInt();
        byte[] payload = new byte[length - 5];
        in.readFully(payload);
        return new Packet(type, seq, payload);
    }
}
