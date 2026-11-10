package netcore.netcode;

import java.util.Map;

/** Built-in RPC codecs. */
public final class Codecs {

    /** Copies every field; use for messages whose schema is defined by the game. */
    public static final Codec<Msg> MSG = new Codec<>() {
        @Override
        public void write(Msg message, Msg value) {
            if (value == null) return;
            for (Map.Entry<String, String> e : value.rawFields().entrySet()) {
                message.put(e.getKey(), e.getValue());
            }
        }

        @Override
        public Msg read(Msg message) {
            return message.copy();
        }
    };

    public static final Codec<Void> NONE = new Codec<>() {
        @Override
        public void write(Msg message, Void value) {
        }

        @Override
        public Void read(Msg message) {
            return null;
        }
    };

    private Codecs() {
    }
}
