package snakenet.chess.client;

import netcore.netcode.NetworkBehaviour;
import netcore.netcode.SyncVar;
import netcore.netcode.ValueCodecs;

/**
 * Client-side mirror of the {@code Room.RoomSync} behaviour. Field paths must match the
 * server-side declarations; the netcode layer dispatches by path, not by class.
 */
public final class RoomView extends NetworkBehaviour {

    public final SyncVar<Integer> phase = syncVar("phase", ValueCodecs.INT, 0);
    public final SyncVar<String> fen = syncVar("fen", ValueCodecs.STRING, "");
    public final SyncVar<Integer> turn = syncVar("turn", ValueCodecs.INT, 0);
    public final SyncVar<Integer> winner = syncVar("winner", ValueCodecs.INT, -1);
    public final SyncVar<String> reason = syncVar("reason", ValueCodecs.STRING, "");
    public final SyncVar<Integer> players = syncVar("players", ValueCodecs.INT, 0);
    public final SyncVar<Integer> host = syncVar("host", ValueCodecs.INT, -1);

    public RoomView() {
        super("room");
    }

    public void reset() {
        phase.set(0);
        fen.set("");
        turn.set(0);
        winner.set(-1);
        reason.set("");
        players.set(0);
        host.set(-1);
    }
}

