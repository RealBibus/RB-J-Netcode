package netcore.netcode;

/** Encodes one value into a single text field for a {@link SyncVar}. */
public interface ValueCodec<T> {

    String write(T value);

    T read(String raw);
}
