package snakenet.net;

import netcore.netcode.Codecs;
import netcore.netcode.Msg;
import netcore.netcode.Rpc;

/**
 * Every message this game can send is declared here. The engine has no protocol table and
 * does not know these names; adding a message is a one-line change in game code.
 *
 * <p>The RPC kind documents the allowed direction. {@code SERVER} messages travel
 * client -> server, the rest travel server -> client.</p>
 */
public final class Net {

    // ---- hub / server browser (server receives) ----
    public static final Rpc<Msg> HELLO = Rpc.server("snake/hello", Codecs.MSG);
    public static final Rpc<Msg> LOBBY_LIST = Rpc.server("snake/lobby/list", Codecs.MSG);
    public static final Rpc<Msg> LOBBY_CREATE = Rpc.server("snake/lobby/create", Codecs.MSG);
    public static final Rpc<Msg> LOBBY_JOIN = Rpc.server("snake/lobby/join", Codecs.MSG);
    public static final Rpc<Msg> LOBBY_LEAVE = Rpc.server("snake/lobby/leave", Codecs.MSG);

    // ---- lobby / match (server receives) ----
    public static final Rpc<Msg> IN = Rpc.server("snake/input", Codecs.MSG);
    public static final Rpc<Msg> READY = Rpc.server("snake/ready", Codecs.MSG);
    public static final Rpc<Msg> CHAT = Rpc.server("snake/chat", Codecs.MSG);
    public static final Rpc<Msg> SET = Rpc.server("snake/setting", Codecs.MSG);
    public static final Rpc<Msg> BOT = Rpc.server("snake/bot", Codecs.MSG);
    public static final Rpc<Msg> START = Rpc.server("snake/start", Codecs.MSG);
    public static final Rpc<Msg> AGAIN = Rpc.server("snake/again", Codecs.MSG);
    public static final Rpc<Msg> KICK_PLAYER = Rpc.server("snake/kick", Codecs.MSG);
    public static final Rpc<Msg> CHESS_MOVE = Rpc.server("chess/move", Codecs.MSG);

    // ---- hub / browser (server sends) ----
    public static final Rpc<Msg> HUB = Rpc.target("snake/hub", Codecs.MSG);
    public static final Rpc<Msg> ROOM = Rpc.observers("snake/room", Codecs.MSG);
    public static final Rpc<Msg> JOINED = Rpc.target("snake/lobby/joined", Codecs.MSG);
    public static final Rpc<Msg> LEFT = Rpc.target("snake/lobby/left", Codecs.MSG);

    // ---- game (server sends) ----
    public static final Rpc<Msg> STARTED = Rpc.observers("snake/started", Codecs.MSG);
    public static final Rpc<Msg> WELCOME = Rpc.target("snake/welcome", Codecs.MSG);
    public static final Rpc<Msg> LOBBY = Rpc.observers("snake/lobby", Codecs.MSG);
    public static final Rpc<Msg> PLAYER = Rpc.observers("snake/player", Codecs.MSG);
    public static final Rpc<Msg> COUNT = Rpc.observers("snake/count", Codecs.MSG);
    public static final Rpc<Msg> SNAP = Rpc.observers("snake/snapshot", Codecs.MSG);
    public static final Rpc<Msg> FOOD = Rpc.observers("snake/food", Codecs.MSG);
    public static final Rpc<Msg> SNAKE = Rpc.observers("snake/body", Codecs.MSG);
    public static final Rpc<Msg> EVENT = Rpc.observers("snake/event", Codecs.MSG);
    public static final Rpc<Msg> OVER = Rpc.observers("snake/over", Codecs.MSG);
    public static final Rpc<Msg> RESULT = Rpc.observers("snake/result", Codecs.MSG);
    public static final Rpc<Msg> ACK = Rpc.target("snake/ack", Codecs.MSG);
    public static final Rpc<Msg> CHAT_OUT = Rpc.observers("snake/chat/out", Codecs.MSG);
    public static final Rpc<Msg> ERR = Rpc.target("snake/error", Codecs.MSG);
    public static final Rpc<Msg> KICK = Rpc.target("snake/kicked", Codecs.MSG);
    public static final Rpc<Msg> CHESS_MOVE_OUT = Rpc.observers("chess/move/out", Codecs.MSG);
    public static final Rpc<Msg> CHESS_END = Rpc.observers("chess/end", Codecs.MSG);

    // ---- values used in fields ----
    public static final int PHASE_LOBBY = 0;
    public static final int PHASE_COUNT = 1;
    public static final int PHASE_RUNNING = 2;
    public static final int PHASE_OVER = 3;

    public static final int EV_EAT = 0;
    public static final int EV_DIE = 1;

    public static final int KIND_SNAKE = 0;
    public static final int KIND_CHESS = 1;

    private Net() {
    }

    public static String kindName(int kind) {
        return kind == KIND_CHESS ? "chess" : "snake";
    }

    public static int parseKind(String raw) {
        if (raw == null) return KIND_SNAKE;
        String value = raw.trim();
        return "chess".equalsIgnoreCase(value) || "1".equals(value) ? KIND_CHESS : KIND_SNAKE;
    }
}
