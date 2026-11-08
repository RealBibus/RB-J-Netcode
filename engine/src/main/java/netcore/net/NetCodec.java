package netcore.net;

import java.io.ByteArrayOutputStream;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

public final class NetCodec {

    public static final int MIN_SIZE = 160;
    public static final int MAX_RAW = 1024 * 1024;

    private static final ThreadLocal<Deflater> DEFLATER = ThreadLocal.withInitial(() -> new Deflater(Deflater.BEST_SPEED));
    private static final ThreadLocal<Inflater> INFLATER = ThreadLocal.withInitial(Inflater::new);

    private NetCodec() {
    }

    public static byte[] compress(byte[] data) {
        if (data == null || data.length < MIN_SIZE) return null;
        Deflater deflater = DEFLATER.get();
        deflater.reset();
        deflater.setInput(data);
        deflater.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, data.length / 2));
        byte[] buffer = new byte[8192];
        while (!deflater.finished()) {
            int n = deflater.deflate(buffer);
            if (n <= 0) break;
            out.write(buffer, 0, n);
        }
        byte[] packed = out.toByteArray();
        if (packed.length + 4 >= data.length) return null;
        byte[] framed = new byte[packed.length + 4];
        framed[0] = (byte) (data.length >>> 24);
        framed[1] = (byte) (data.length >>> 16);
        framed[2] = (byte) (data.length >>> 8);
        framed[3] = (byte) data.length;
        System.arraycopy(packed, 0, framed, 4, packed.length);
        return framed;
    }

    public static byte[] decompress(byte[] packed, int offset, int length) {
        if (packed == null || length < 4) return null;
        int rawLength = ((packed[offset] & 0xff) << 24)
                | ((packed[offset + 1] & 0xff) << 16)
                | ((packed[offset + 2] & 0xff) << 8)
                | (packed[offset + 3] & 0xff);
        if (rawLength <= 0 || rawLength > MAX_RAW) return null;
        Inflater inflater = INFLATER.get();
        inflater.reset();
        inflater.setInput(packed, offset + 4, length - 4);
        byte[] raw = new byte[rawLength];
        try {
            int total = 0;
            while (total < rawLength && !inflater.finished()) {
                int n = inflater.inflate(raw, total, rawLength - total);
                if (n == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) return null;
                    break;
                }
                total += n;
            }
            return total == rawLength ? raw : null;
        } catch (DataFormatException e) {
            return null;
        } finally {
            inflater.reset();
        }
    }

    public static byte[] decompress(byte[] packed) {
        return packed == null ? null : decompress(packed, 0, packed.length);
    }
}
