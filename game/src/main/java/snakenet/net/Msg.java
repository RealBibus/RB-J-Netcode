package snakenet.net;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;







public final class Msg {

    
    public static final String END = "END";

    private final String type;
    private final Map<String, String> fields = new LinkedHashMap<>();

    private Msg(String type) {
        this.type = type;
    }

    public static Msg of(String type) {
        return new Msg(type);
    }

    public static Msg parse(String line) {
        if (line == null) return null;
        String[] parts = line.split("\\|", -1);
        if (parts.length == 0 || parts[0].isBlank()) return null;
        Msg m = new Msg(parts[0].trim());
        for (int i = 1; i < parts.length; i++) {
            int eq = parts[i].indexOf('=');
            if (eq <= 0) continue;
            m.fields.put(parts[i].substring(0, eq), parts[i].substring(eq + 1));
        }
        return m;
    }

    public String type() {
        return type;
    }

    public boolean is(String t) {
        return type.equals(t);
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

    public boolean bool(String k) {
        String v = raw(k);
        return "1".equals(v) || "true".equals(v);
    }

    public boolean has(String k) {
        return fields.containsKey(k);
    }

    public String encode() {
        StringBuilder sb = new StringBuilder(type);
        for (Map.Entry<String, String> e : fields.entrySet()) {
            sb.append('|').append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return encode();
    }

    static String enc(String s) {
        if (s == null || s.isEmpty()) return "";
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    static String dec(String s) {
        if (s == null || s.isEmpty()) return "";
        try {
            return new String(Base64.getUrlDecoder().decode(s), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return "";
        }
    }
}
