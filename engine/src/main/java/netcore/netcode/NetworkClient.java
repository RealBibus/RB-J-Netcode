package netcore.netcode;

/**
 * Client-side mirror of a replicated object. Feed every received message through
 * {@link #apply(Msg)}; sync vars update their hooks immediately.
 */
public final class NetworkClient {

    private final NetworkObject local;

    public NetworkClient() {
        this.local = new NetworkObject(0);
    }

    public NetworkClient(int objectId) {
        this.local = new NetworkObject(objectId);
    }

    public NetworkObject object() {
        return local;
    }

    public void addBehaviour(NetworkBehaviour behaviour) {
        local.addBehaviour(behaviour);
    }

    public boolean apply(Msg message) {
        if (message == null || !message.is(NetworkObject.SYNC)) return false;
        local.applySync(message);
        return true;
    }

    public void reset() {
        for (NetworkBehaviour b : local.behaviours()) b.onStopClient();
    }

    public void start() {
        for (NetworkBehaviour b : local.behaviours()) b.onStartClient();
    }
}
