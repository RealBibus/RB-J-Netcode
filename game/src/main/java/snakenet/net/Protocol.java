package snakenet.net;

/** Имена сообщений протокола. Одна константа — одно место правки. */
public final class Protocol {

    private Protocol() {
    }

    // ---- клиент -> сервер ----
    public static final String HELLO = "HELLO";
    public static final String DIR = "DIR";
    /** Ввод с номером и тиком: {@code IN|d=1|t=105|s=0.42|n=17}. */
    public static final String IN = "IN";
    public static final String READY = "READY";
    public static final String CHAT = "CHAT";
    public static final String SET = "SET";
    public static final String BOT = "BOT";
    public static final String START = "START";
    public static final String AGAIN = "AGAIN";
    public static final String PING = "PING";

    // ---- сервер -> клиент ----
    public static final String WELCOME = "WELCOME";
    public static final String LOBBY = "LOBBY";
    public static final String COUNT = "COUNT";
    public static final String SNAP = "SNAP";
    public static final String OVER = "OVER";
    public static final String PONG = "PONG";
    public static final String ERR = "ERR";
    public static final String KICK = "KICK";

    // ---- строки данных внутри кадра ----
    public static final String PLAYER = "P";   // строка списка игроков
    public static final String SETTINGS = "SET"; // настройки лобби
    public static final String FOOD = "F";
    public static final String SNAKE = "S";
    public static final String EVENT = "E";
    public static final String RESULT = "R";

    // ---- события внутри тика ----
    public static final int EV_EAT = 0;
    public static final int EV_DIE = 1;

    // ---- фазы комнаты ----
    public static final int PHASE_LOBBY = 0;
    public static final int PHASE_COUNT = 1;
    public static final int PHASE_RUNNING = 2;
    public static final int PHASE_OVER = 3;
}
