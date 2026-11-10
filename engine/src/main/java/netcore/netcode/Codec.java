package netcore.netcode;

/**
 * Maps a typed RPC payload to and from message fields. A game can use the generic
 * {@link Codecs#MSG} codec for map-shaped payloads or implement a dedicated codec.
 */
public interface Codec<T> {

    void write(Msg message, T value);

    T read(Msg message);
}
