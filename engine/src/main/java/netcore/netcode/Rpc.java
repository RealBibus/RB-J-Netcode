package netcore.netcode;

/**
 * A declared RPC channel. RPCs are created in game code, which is what replaces the old
 * hard-coded protocol constants:
 *
 * <pre>{@code
 * static final Rpc<Msg> INPUT = Rpc.server("snake/input", Codecs.MSG);
 * static final Rpc<Msg> SNAPSHOT = Rpc.observers("snake/snapshot", Codecs.MSG);
 * }</pre>
 *
 * <p>{@link Kind#SERVER} is invoked by a client and executed on the server.
 * {@link Kind#OBSERVERS} is invoked by the server and executed on every observer of the
 * target object. {@link Kind#TARGET} is invoked by the server on one connection.</p>
 */
public final class Rpc<T> {

    public enum Kind {
        SERVER,
        OBSERVERS,
        TARGET
    }

    private final String name;
    private final MsgType type;
    private final Kind kind;
    private final Codec<T> codec;

    @SuppressWarnings("unchecked")
    private Rpc(String name, Kind kind, Codec<T> codec) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("rpc name is required");
        this.name = name;
        this.type = MsgType.declare(name);
        this.kind = kind;
        this.codec = codec == null ? (Codec<T>) Codecs.NONE : codec;
    }

    public static <T> Rpc<T> server(String name, Codec<T> codec) {
        return new Rpc<>(name, Kind.SERVER, codec);
    }

    public static <T> Rpc<T> observers(String name, Codec<T> codec) {
        return new Rpc<>(name, Kind.OBSERVERS, codec);
    }

    public static <T> Rpc<T> target(String name, Codec<T> codec) {
        return new Rpc<>(name, Kind.TARGET, codec);
    }

    public String name() {
        return name;
    }

    public MsgType type() {
        return type;
    }

    public Kind kind() {
        return kind;
    }

    public Codec<T> codec() {
        return codec;
    }

    public Msg newMsg() {
        return Msg.of(type);
    }

    public Msg write(T value) {
        Msg m = newMsg();
        codec.write(m, value);
        return m;
    }

    public void write(Msg m, T value) {
        codec.write(m, value);
    }

    public T read(Msg m) {
        return codec.read(m);
    }

    @Override
    public String toString() {
        return kind + ":" + name;
    }
}
