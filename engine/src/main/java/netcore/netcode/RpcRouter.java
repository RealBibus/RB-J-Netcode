package netcore.netcode;

import netcore.net.Connection;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * Dispatches declared RPCs to handlers. The same class is used on the server and on the
 * client; a side only registers the directions it is allowed to execute.
 */
public final class RpcRouter {

    public interface Handler {
        void handle(Connection from, Msg message);
    }

    private final Map<MsgType, Handler> handlers = new ConcurrentHashMap<>();
    private final boolean serverSide;

    public RpcRouter(boolean serverSide) {
        this.serverSide = serverSide;
    }

    public boolean serverSide() {
        return serverSide;
    }

    /**
     * Registers a handler. On the server only {@link Rpc.Kind#SERVER} is executable; on the
     * client only {@link Rpc.Kind#OBSERVERS} and {@link Rpc.Kind#TARGET}.
     */
    public <T> RpcRouter on(Rpc<T> rpc, BiConsumer<Connection, T> handler) {
        if (serverSide && rpc.kind() != Rpc.Kind.SERVER) {
            throw new IllegalArgumentException("server cannot register " + rpc.kind() + " rpc " + rpc);
        }
        if (!serverSide && rpc.kind() == Rpc.Kind.SERVER) {
            throw new IllegalArgumentException("client cannot register server rpc " + rpc);
        }
        handlers.put(rpc.type(), (from, message) -> handler.accept(from, rpc.read(message)));
        return this;
    }

    public boolean handles(Msg message) {
        return handlers.containsKey(message.type());
    }

    public boolean dispatch(Connection from, Msg message) {
        Handler handler = handlers.get(message.type());
        if (handler == null) return false;
        handler.handle(from, message);
        return true;
    }

    public int count() {
        return handlers.size();
    }
}
