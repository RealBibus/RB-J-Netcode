package netcore.netcode;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One wire message. The type must be a declared {@link MsgType} (normally an RPC created
 * in game code); the engine never invents game message names.
 */
public final class Msg {

    public static final String END = "END";

    private final MsgType type;
    private final Map<String, String> fields = new LinkedHashMap<>();

    private Msg(MsgType type) {
        if (type == null) throw new IllegalArgumentException("type is required");
        this.type = type;
    }

    public static Msg of(MsgType type) {
        return new Msg(type);
    }

    public static Msg of(Rpc<?> rpc) {
        return new Msg(rpc.type());
    }

    public static Msg parse(String line) {
        if (line == null) return null;
        String[] parts = line.split("\\|", -1);
        if (parts.length == 0 || parts[0].isBlank()) return null;
        MsgType type = MsgType.find(parts[0].trim());
        if (type == null) return null;
        Msg m = new Msg(type);
        for (int i = 1; i < parts.length; i++) {
            int eq = parts[i].indexOf('=');
            if (eq <= 0) continue;
            m.fields.put(parts[i].substring(0, eq), parts[i].substring(eq + 1));
        }
        return m;
    }

    public MsgType type() {
        return type;
    }

    public String typeName() {
        return type.name();
    }

    public boolean is(MsgType t) {
        return type == t;
    }

    public boolean is(Rpc<?> rpc) {
        return rpc != null && type == rpc.type();
    }

    public Msg put(String k, Object v) {
        fields.put(k, String.valueOf(v));
        return this;
    }

    public Msg putStr(String k, String v) {
        fields.put(k, enc(v));
        return this;
    }

    public String raw(String k) {
        String v = fields.get(k);
        return v == null ? "" : v;
    }

    public String str(String k) {
        return dec(raw(k));
    }

    public int num(String k, int def) {
        try {
            return Integer.parseInt(raw(k));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public long numL(String k, long def) {
        try {
            return Long.parseLong(raw(k));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public double numD(String k, double def) {
        try {
            return Double.parseDouble(raw(k));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public boolean bool(String k) {
        String v = raw(k);
        return "1".equals(v) || "true".equals(v);
    }

    public boolean has(String k) {
        return fields.containsKey(k);
    }

    public Map<String, String> rawFields() {
        return Collections.unmodifiableMap(fields);
    }

    public int size() {
        return fields.size();
    }

    public Msg copy() {
        Msg m = new Msg(type);
        m.fields.putAll(fields);
        return m;
    }

    public String encode() {
        StringBuilder sb = new StringBuilder(type.name());
        for (Map.Entry<String, String> e : fields.entrySet()) {
            sb.append('|').append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return encode();
    }

    public static String enc(String s) {
        if (s == null || s.isEmpty()) return "";
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    public static String dec(String s) {
        if (s == null || s.isEmpty()) return "";
        try {
            return new String(Base64.getUrlDecoder().decode(s), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return "";
        }
    }
}
