package snakenet.chess.net;

import netcore.netcode.Codecs;
import netcore.netcode.Msg;
import netcore.netcode.Rpc;

/**
 * Every message the chess game can send is declared here. The engine has no protocol table
 * and does not know these names; adding a message is a one-line change in game code.
 *
 * <p>The RPC kind documents the allowed direction. {@code SERVER} messages travel
 * client -> server, the rest travel server -> client.</p>
 */
public final class Net {

    // ---- hub / server browser (server receives) ----
    public static final Rpc<Msg> HELLO = Rpc.server("chess/hello", Codecs.MSG);
    public static final Rpc<Msg> LOBBY_LIST = Rpc.server("chess/lobby/list", Codecs.MSG);
    public static final Rpc<Msg> LOBBY_CREATE = Rpc.server("chess/lobby/create", Codecs.MSG);
    public static final Rpc<Msg> LOBBY_JOIN = Rpc.server("chess/lobby/join", Codecs.MSG);
    public static final Rpc<Msg> LOBBY_LEAVE = Rpc.server("chess/lobby/leave", Codecs.MSG);

    // ---- lobby / match (server receives) ----
    public static final Rpc<Msg> READY = Rpc.server("chess/ready", Codecs.MSG);
    public static final Rpc<Msg> CHAT = Rpc.server("chess/chat", Codecs.MSG);
    public static final Rpc<Msg> SET = Rpc.server("chess/setting", Codecs.MSG);
    public static final Rpc<Msg> BOT = Rpc.server("chess/bot", Codecs.MSG);
    public static final Rpc<Msg> START = Rpc.server("chess/start", Codecs.MSG);
    public static final Rpc<Msg> AGAIN = Rpc.server("chess/again", Codecs.MSG);
    public static final Rpc<Msg> KICK_PLAYER = Rpc.server("chess/kick", Codecs.MSG);
    public static final Rpc<Msg> CHESS_MOVE = Rpc.server("chess/move", Codecs.MSG);

    // ---- hub / browser (server sends) ----
    public static final Rpc<Msg> HUB = Rpc.target("chess/hub", Codecs.MSG);
    public static final Rpc<Msg> ROOM = Rpc.observers("chess/room", Codecs.MSG);
    public static final Rpc<Msg> JOINED = Rpc.target("chess/lobby/joined", Codecs.MSG);
    public static final Rpc<Msg> LEFT = Rpc.target("chess/lobby/left", Codecs.MSG);

    // ---- game (server sends) ----
    public static final Rpc<Msg> STARTED = Rpc.observers("chess/started", Codecs.MSG);
    public static final Rpc<Msg> WELCOME = Rpc.target("chess/welcome", Codecs.MSG);
    public static final Rpc<Msg> LOBBY = Rpc.observers("chess/lobby", Codecs.MSG);
    public static final Rpc<Msg> PLAYER = Rpc.observers("chess/player", Codecs.MSG);
    public static final Rpc<Msg> COUNT = Rpc.observers("chess/count", Codecs.MSG);
    public static final Rpc<Msg> CHAT_OUT = Rpc.observers("chess/chat/out", Codecs.MSG);
    public static final Rpc<Msg> ERR = Rpc.target("chess/error", Codecs.MSG);
    public static final Rpc<Msg> KICK = Rpc.target("chess/kicked", Codecs.MSG);
    public static final Rpc<Msg> CHESS_MOVE_OUT = Rpc.observers("chess/move/out", Codecs.MSG);
    public static final Rpc<Msg> CHESS_END = Rpc.observers("chess/end", Codecs.MSG);

    // ---- values used in fields ----
    public static final int PHASE_LOBBY = 0;
    public static final int PHASE_COUNT = 1;
    public static final int PHASE_RUNNING = 2;
    public static final int PHASE_OVER = 3;

    public static final int KIND_CHESS = 1;

    private Net() {
    }
}
