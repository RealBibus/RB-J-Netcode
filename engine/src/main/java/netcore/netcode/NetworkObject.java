package netcore.netcode;

import netcore.net.Connection;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A replicated object. It owns its observers (the connections that may see its sync vars
 * and observer RPCs) and one or more {@link NetworkBehaviour}s.
 */
public final class NetworkObject {

    public static final MsgType SYNC = MsgType.declare("nc:sync");

    private final int id;
    private volatile Connection owner;
    private final Set<Connection> observers = ConcurrentHashMap.newKeySet();
    private final List<NetworkBehaviour> behaviours = new CopyOnWriteArrayList<>();
    private volatile boolean spawned;

    public NetworkObject(int id) {
        this.id = id;
    }

    public int id() {
        return id;
    }

    public boolean spawned() {
        return spawned;
    }

    public Connection owner() {
        return owner;
    }

    public void setOwner(Connection owner) {
        this.owner = owner;
        if (owner != null) observers.add(owner);
    }

    public void addBehaviour(NetworkBehaviour behaviour) {
        behaviours.add(behaviour);
    }

    public List<NetworkBehaviour> behaviours() {
        return Collections.unmodifiableList(behaviours);
    }

    public NetworkBehaviour behaviour(String name) {
        for (NetworkBehaviour b : behaviours) {
            if (b.name().equals(name)) return b;
        }
        return null;
    }

    public void addObserver(Connection connection) {
        if (connection != null) observers.add(connection);
    }

    public void removeObserver(Connection connection) {
        if (connection != null) observers.remove(connection);
    }

    public void setObservers(Collection<Connection> next) {
        observers.clear();
        for (Connection c : next) if (c != null) observers.add(c);
    }

    public Set<Connection> observers() {
        return Collections.unmodifiableSet(observers);
    }

    public int observerCount() {
        return observers.size();
    }

    public void spawn(Connection owner) {
        this.owner = owner;
        if (owner != null) observers.add(owner);
        spawned = true;
        for (NetworkBehaviour b : behaviours) b.onStartServer();
    }

    public void despawn() {
        spawned = false;
        for (NetworkBehaviour b : behaviours) b.onStopServer();
        observers.clear();
        owner = null;
    }

    public void markAllDirty() {
        for (NetworkBehaviour b : behaviours) {
            for (SyncVar<?> var : b.syncVars()) var.markDirty();
        }
    }

    /**
     * Returns one delta message, or {@code null} when nothing changed. The message contains
     * one field per dirty sync var keyed by its declared path.
     */
    public Msg drainSync() {
        Msg msg = Msg.of(SYNC);
        int count = 0;
        msg.put("o", id);
        for (NetworkBehaviour b : behaviours) {
            for (SyncVar<?> var : b.syncVars()) {
                if (!var.isDirty()) continue;
                msg.put(var.path(), var.encode());
                count++;
            }
        }
        return count == 0 ? null : msg;
    }

    public void applySync(Msg message) {
        if (message == null || !message.is(SYNC)) return;
        for (Map.Entry<String, String> e : message.rawFields().entrySet()) {
            if ("o".equals(e.getKey())) continue;
            for (NetworkBehaviour b : behaviours) {
                SyncVar<?> var = b.syncVarByPath(e.getKey());
                if (var != null) {
                    var.applyRaw(e.getValue());
                    break;
                }
            }
        }
    }

    public List<Msg> syncMessages() {
        Msg delta = drainSync();
        if (delta == null) return List.of();
        List<Msg> out = new ArrayList<>(1);
        out.add(delta);
        return out;
    }
}
