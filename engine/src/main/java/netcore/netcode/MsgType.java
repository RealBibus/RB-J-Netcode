package netcore.netcode;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A declared message type. The engine does not know any game message: every message is
 * declared by the game (or by the netcode layer itself) before it is used on the wire.
 *
 * <p>Declarations are idempotent, so both the client and the server can declare the same
 * set of messages in static initialisers without coordination.</p>
 */
public final class MsgType {

    private static final Map<String, MsgType> REGISTRY = new ConcurrentHashMap<>();

    private final String name;
    private final int hash;

    private MsgType(String name) {
        this.name = name;
        this.hash = fnv1a(name);
    }

    public static MsgType declare(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("message name is required");
        }
        return REGISTRY.computeIfAbsent(name, MsgType::new);
    }

    public static MsgType find(String name) {
        return name == null ? null : REGISTRY.get(name);
    }

    public String name() {
        return name;
    }

    /**
     * Stable 32-bit id. Useful for compact wire formats and for logging; the text wire
     * format keeps the readable name so captures stay debuggable.
     */
    public int hash() {
        return hash;
    }

    @Override
    public String toString() {
        return name;
    }

    private static int fnv1a(String text) {
        int h = 0x811C9DC5;
        for (int i = 0; i < text.length(); i++) {
            h ^= text.charAt(i);
            h *= 0x01000193;
        }
        return h;
    }
}
