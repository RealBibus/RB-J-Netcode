package netcore.net;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;

public final class FrameCodec {

    public static final int MAX_PAYLOAD = 256 * 1024;
    private static final int FLAG_Z = 0x80;

    private FrameCodec() {
    }

    public static void write(DataOutputStream out, Packet packet) throws IOException {
        write(out, packet, true);
    }

    public static void write(DataOutputStream out, Packet packet, boolean compression) throws IOException {
        byte[] body = packet.payload;
        boolean compressed = false;
        if (compression) {
            byte[] packed = NetCodec.compress(body);
            if (packed != null) {
                body = packed;
                compressed = true;
            }
        }
        if (body.length > MAX_PAYLOAD) {
            throw new IOException("packet too large: " + body.length);
        }
        out.writeInt(5 + body.length);
        out.writeByte(packet.type | (compressed ? FLAG_Z : 0));
        out.writeInt(packet.seq);
        out.write(body);
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
            throw new IOException("bad frame length: " + length);
        }
        int raw = in.readByte() & 0xff;
        boolean compressed = (raw & FLAG_Z) != 0;
        byte type = (byte) (raw & ~FLAG_Z);
        int seq = in.readInt();
        byte[] payload = new byte[length - 5];
        in.readFully(payload);
        if (compressed) {
            payload = NetCodec.decompress(payload);
            if (payload == null) throw new IOException("corrupt compressed frame");
        }
        return new Packet(type, seq, payload);
    }
}
