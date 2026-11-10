package netcore.netcode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Base class for replicated game state. Game code declares its fields here, for example:
 *
 * <pre>{@code
 * final class ChessView extends NetworkBehaviour {
 *     final SyncVar<String> fen = syncVar("fen", ValueCodecs.STRING, "");
 *     final SyncVar<Integer> turn = syncVar("turn", ValueCodecs.INT, 0);
 * }
 * }</pre>
 */
public abstract class NetworkBehaviour {

    private final String name;
    private final List<SyncVar<?>> syncVars = new ArrayList<>();

    protected NetworkBehaviour(String name) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("behaviour name is required");
        this.name = name;
    }

    public String name() {
        return name;
    }

    protected final <T> SyncVar<T> syncVar(String field, ValueCodec<T> codec, T initial) {
        if (field == null || field.isBlank()) throw new IllegalArgumentException("field name is required");
        SyncVar<T> var = new SyncVar<>(name + "." + field, codec, initial);
        syncVars.add(var);
        return var;
    }

    public List<SyncVar<?>> syncVars() {
        return Collections.unmodifiableList(syncVars);
    }

    SyncVar<?> syncVarByPath(String path) {
        for (SyncVar<?> var : syncVars) {
            if (var.path().equals(path)) return var;
        }
        return null;
    }

    public void onStartServer() {
    }

    public void onStartClient() {
    }

    public void onStopServer() {
    }

    public void onStopClient() {
    }
}
