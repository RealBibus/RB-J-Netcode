package snakenet.net;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Превращение кадра сообщений в байты для движка и обратно. */
public final class Wire {

    private Wire() {
    }

    public static byte[] encode(List<Msg> frame) {
        StringBuilder sb = new StringBuilder(256);
        for (Msg m : frame) sb.append(m.encode()).append('\n');
        sb.append(Msg.END).append('\n');
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    public static byte[] encode(Msg message) {
        return encode(List.of(message));
    }

    public static List<Msg> decode(byte[] data) {
        String text = new String(data, StandardCharsets.UTF_8);
        List<Msg> out = new ArrayList<>(4);
        for (String line : text.split("\n")) {
            if (line.isEmpty() || line.equals(Msg.END)) continue;
            Msg m = Msg.parse(line);
            if (m != null) out.add(m);
        }
        return out;
    }
}
