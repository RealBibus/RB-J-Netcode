package snakenet.chess.ui;

import netcore.netcode.Msg;
import snakenet.chess.Chess;
import snakenet.chess.ChessBoardView;
import snakenet.chess.client.RoomView;
import snakenet.chess.core.GameConfig;
import snakenet.chess.net.Net;

import javax.swing.AbstractAction;
import javax.swing.JComponent;
import javax.swing.KeyStroke;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Shape;
import java.awt.event.ActionEvent;
import java.awt.event.MouseEvent;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Chess match screen. It only presents the board the server owns and sends moves.
 * There is no snake code here: this file belongs entirely to the chess game.
 */
public final class GameScreen extends Screen {

    private static final double AREA_X = 4;
    private static final double AREA_Y = 44;
    private static final double AREA_W = 860;
    private static final double AREA_H = 700;

    private static final double INFO_X = 900;
    private static final double INFO_Y = 76;
    private static final double INFO_W = 330;
    private static final double INFO_H = 470;

    private final ChessBoardView board = new ChessBoardView();
    private final RoomView roomView;
    private final Map<Integer, PlayerInfo> names = new HashMap<>();

    private GameConfig cfg = new GameConfig();
    private Chess chess = new Chess();
    private int chessTurn = Chess.WHITE;
    private int myColor = Chess.WHITE;
    private int winner = -1;
    private String reason = "";
    private int phase = Net.PHASE_LOBBY;
    private int countdown;
    private double countdownAnim;
    private double runTime;
    private boolean help;
    private boolean pauseMenu;
    private double pauseAnim;

    private double boardX = 40;
    private double boardY = 60;
    private double boardW = 820;
    private double boardH = 690;

    private final NeonButton bHelp;
    private final NeonButton bLobby;
    private final NeonButton bResume;
    private final NeonButton bQuit;

    private static final class PlayerInfo {
        String name = "";
        int color;
        boolean bot;
    }

    public GameScreen(App app) {
        super(app);
        this.roomView = app.roomView();

        bHelp = add(new NeonButton("? HOW TO PLAY", NeonButton.Style.CHIP, 1000, 566, 220, 34, this::toggleHelp));
        bLobby = add(new NeonButton("LOBBY", NeonButton.Style.SECONDARY, 1000, 614, 220, 48, app::leaveLobby));
        bResume = add(new NeonButton("RESUME", NeonButton.Style.PRIMARY, 500, 388, 280, 52, () -> pauseMenu = false));
        bQuit = add(new NeonButton("LEAVE TO MENU", NeonButton.Style.DANGER, 500, 456, 280, 52, app::leaveToHome));

        bHelp.visible = true;
        bLobby.visible = false;
        bResume.visible = false;
        bQuit.visible = false;

        getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke("pressed H"), "chesshelp");
        getActionMap().put("chesshelp", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                toggleHelp();
            }
        });
        getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke("ESCAPE"), "escape");
        getActionMap().put("escape", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (help) {
                    help = false;
                } else {
                    pauseMenu = !pauseMenu;
                }
            }
        });
    }

    private void toggleHelp() {
        help = !help;
        if (help) board.clearSelection();
    }

    @Override
    public void mousePressed(MouseEvent e) {
        if (phase == Net.PHASE_RUNNING && !help && !pauseMenu) {
            if (e.getButton() == MouseEvent.BUTTON3) {
                board.clearSelection();
                super.mousePressed(e);
                return;
            }
            double vx = (e.getX() - ox) / Math.max(0.001, scale);
            double vy = (e.getY() - oy) / Math.max(0.001, scale);
            Chess.Move move = board.click(vx, vy, boardX, boardY, boardW, boardH);
            if (move != null) sendMove(move);
        }
        super.mousePressed(e);
    }

    @Override
    public void onShow() {
        requestFocusInWindow();
        pauseMenu = false;
        help = false;
    }

    @Override
    public void onHide() {
        pauseMenu = false;
        help = false;
    }

    private void sendMove(Chess.Move move) {
        app.send(Msg.of(Net.CHESS_MOVE).put("p", app.myId())
                .put("f", move.from).put("t", move.to).put("pr", move.promotion));
    }

    @Override
    public void onFrame(List<Msg> frame) {
        for (Msg m : frame) {
            if (m.is(Net.LOBBY)) {
                readRoster(frame);
            } else if (m.is(Net.STARTED)) {
                applyStart(m);
            } else if (m.is(Net.COUNT)) {
                countdown = m.num("n", countdown);
                countdownAnim = 1;
            } else if (m.is(Net.CHESS_MOVE_OUT)) {
                applyMove(m);
            } else if (m.is(Net.CHESS_END)) {
                phase = Net.PHASE_OVER;
                winner = m.num("w", -1);
                reason = m.str("r");
                board.clearSelection();
                app.log("chess finished: " + reason);
            } else if (m.is(Net.KICK)) {
                app.toastError(m.str("msg"));
            } else if (m.is(Net.ERR)) {
                app.toastError(m.str("msg"));
            }
        }
        updateButtons();
    }

    private void readRoster(List<Msg> frame) {
        for (Msg m : frame) {
            if (!m.is(Net.PLAYER)) continue;
            PlayerInfo info = new PlayerInfo();
            info.name = m.str("name");
            info.color = m.num("c", 0);
            info.bot = m.bool("bot");
            names.put(m.num("id", -1), info);
        }
    }

    private void applyStart(Msg m) {
        cfg = GameConfig.read(m, cfg);
        phase = m.num("phase", Net.PHASE_COUNT);
        countdown = m.num("n", 3);
        countdownAnim = 1;
        runTime = 0;
        winner = -1;
        reason = "";
        myColor = app.myChessColor();
        chess = new Chess();
        chessTurn = Chess.WHITE;
        board.reset(chess, chessTurn, myColor);
        app.log("chess match started — you play " + (myColor == Chess.WHITE ? "white" : "black"));
    }

    private void applyMove(Msg m) {
        int from = m.num("f", -1);
        int to = m.num("t", -1);
        int promo = m.num("pr", 0);
        Chess before = chess;
        Chess next = null;
        if (!m.raw("fen").isEmpty()) {
            next = Chess.fromFen(m.str("fen"));
        } else {
            for (Chess.Move candidate : chess.legalMoves()) {
                if (candidate.from == from && candidate.to == to && candidate.promotion == promo) {
                    next = chess.apply(candidate);
                    break;
                }
            }
        }
        if (next != null) {
            chess = next;
            chessTurn = next.turn();
            board.playMove(from, to, before, next, chessTurn);
        }
    }

    @Override
    protected void onTick(double dt) {
        board.update(dt);
        countdownAnim = Math.max(0, countdownAnim - dt * 1.6);
        pauseAnim += ((pauseMenu ? 1 : 0) - pauseAnim) * Math.min(1, dt * 14);
        if (phase == Net.PHASE_RUNNING) runTime += dt;

        int syncPhase = roomView.phase.get();
        if (syncPhase == Net.PHASE_RUNNING && phase == Net.PHASE_COUNT) {
            phase = Net.PHASE_RUNNING;
            countdown = 0;
        }
        String fen = roomView.fen.get();
        if (fen != null && !fen.isBlank() && !fen.equals(chess.toFen())) {
            chess = Chess.fromFen(fen);
            chessTurn = roomView.turn.get();
            board.sync(chess, chessTurn);
        } else {
            chessTurn = roomView.turn.get();
            board.sync(chess, chessTurn);
        }
        if (roomView.phase.get() == Net.PHASE_OVER && phase != Net.PHASE_OVER) {
            phase = Net.PHASE_OVER;
        }
        updateButtons();
    }

    private void updateButtons() {
        bHelp.visible = !pauseMenu;
        bHelp.label = help ? "CLOSE HELP" : "? HOW TO PLAY";
        bHelp.selected = help;
        bLobby.visible = phase == Net.PHASE_OVER && !pauseMenu;
        bResume.visible = pauseMenu;
        bQuit.visible = pauseMenu;
    }

    @Override
    protected void paintScreen(Graphics2D g) {
        computeBoard();
        paintTopBar(g);
        board.paint(g, boardX, boardY, boardW, boardH);
        paintInfo(g);
        paintControls(g);
        paintOverlays(g);
    }

    private void computeBoard() {
        boardW = AREA_W - 20;
        boardH = AREA_H - 20;
        boardX = AREA_X + (AREA_W - boardW) / 2;
        boardY = AREA_Y + (AREA_H - boardH) / 2;
    }

    private void paintTopBar(Graphics2D g) {
        g.setColor(new Color(0x08, 0x0A, 0x0C, 225));
        g.fill(new Rectangle2D.Double(0, 0, VW, 42));
        g.setColor(Theme.LINE);
        g.draw(new Line2D.Double(0, 42.5, VW, 42.5));

        Brush.text(g, "CHESS NET", 16, 27, Theme.display(15), Theme.TEXT);
        g.setColor(Theme.GOLD);
        g.fill(new Ellipse2D.Double(119, 17, 6, 6));
        Brush.text(g, "CHESS  ·  " + (chessTurn == Chess.WHITE ? "white" : "black") + " to move"
                        + "  ·  " + hint(),
                136, 27, Theme.font(11), Theme.MUTED);

        int ping = app.client() == null ? -1 : app.client().rttMs();
        String right = "PLAYERS " + Math.max(0, roomView.players.get())
                + "   ·   " + timeString(runTime)
                + "   ·   FPS " + win.fps()
                + "   ·   PING " + (ping < 0 ? "—" : ping + " ms");
        Brush.right(g, right, VW - 16, 27, Theme.font(11), Theme.MUTED);
    }

    private String timeString(double seconds) {
        int s = (int) seconds;
        return String.format(Locale.ROOT, "%02d:%02d", s / 60, s % 60);
    }

    private String hint() {
        if (phase == Net.PHASE_OVER) return "game over · returning to lobby";
        if (phase != Net.PHASE_RUNNING) return "get ready";
        if (chessTurn != myColor) return "opponent is thinking";
        if (chess.isCheck()) return "your king is in check!";
        if (board.selected() >= 0) return "click a highlighted square";
        return "select one of your pieces";
    }

    private void paintInfo(Graphics2D g) {
        Brush.panel(g, INFO_X, INFO_Y, INFO_W, INFO_H, 22);
        g.setColor(Theme.alpha(Theme.GOLD, 160));
        g.fill(new Rectangle2D.Double(INFO_X + 18, INFO_Y + 18, 3, INFO_H - 36));

        Brush.text(g, "MATCH", INFO_X + 34, INFO_Y + 42, Theme.semibold(10), Theme.MUTED);
        Brush.text(g, "You play " + (myColor == Chess.WHITE ? "WHITE" : "BLACK"),
                INFO_X + 34, INFO_Y + 74, Theme.semibold(18), Theme.TEXT);
        Brush.text(g, myColor == Chess.WHITE ? "Pieces at the bottom" : "Pieces at the top",
                INFO_X + 34, INFO_Y + 98, Theme.font(12), Theme.DIM);

        Brush.text(g, "TURN", INFO_X + 34, INFO_Y + 140, Theme.semibold(10), Theme.MUTED);
        boolean mine = chessTurn == myColor;
        Color turnColor = mine ? Theme.ACCENT : Theme.GOLD;
        Brush.text(g, chessTurn == Chess.WHITE ? "White" : "Black",
                INFO_X + 34, INFO_Y + 172, Theme.semibold(20), turnColor);
        Brush.text(g, mine ? "Your move" : "Opponent's move",
                INFO_X + 34, INFO_Y + 196, Theme.font(12), mine ? Theme.ACCENT : Theme.DIM);

        Brush.text(g, "STATUS", INFO_X + 34, INFO_Y + 238, Theme.semibold(10), Theme.MUTED);
        String status = phase == Net.PHASE_OVER
                ? (reason.isBlank() ? "Finished" : reason)
                : chess.isCheck() ? "Check!" : mine ? "Choose a piece" : "Waiting…";
        Brush.text(g, status, INFO_X + 34, INFO_Y + 270, Theme.semibold(16),
                chess.isCheck() && phase != Net.PHASE_OVER ? Theme.DANGER : Theme.TEXT);

        Brush.text(g, "HINTS", INFO_X + 34, INFO_Y + 312, Theme.semibold(10), Theme.MUTED);
        Brush.text(g, "Click a piece, then a dot.", INFO_X + 34, INFO_Y + 338, Theme.font(12), Theme.DIM);
        Brush.text(g, "Red ring = capture.", INFO_X + 34, INFO_Y + 358, Theme.font(12), Theme.DIM);
        Brush.text(g, "Right-click cancels.", INFO_X + 34, INFO_Y + 378, Theme.font(12), Theme.DIM);
        Brush.text(g, "H opens the full rules.", INFO_X + 34, INFO_Y + 398, Theme.font(12), Theme.DIM);

        if (phase == Net.PHASE_OVER) {
            Brush.text(g, "Returning to the lobby…", INFO_X + 34, INFO_Y + 438,
                    Theme.font(13), Theme.ACCENT2);
        }
    }

    private void paintControls(Graphics2D g) {
        if (!pauseMenu) {
            Brush.text(g, "CLICK PIECE → DOT TO MOVE    RIGHT-CLICK CANCEL    H — HELP    ESC — MENU",
                    16, VH - 10, Theme.font(9), Theme.MUTED);
        }
    }

    private void paintOverlays(Graphics2D g) {
        if (phase == Net.PHASE_COUNT && countdown > 0) {
            g.setColor(new Color(0, 0, 0, 115));
            g.fill(Brush.round(boardX, boardY, boardW, boardH, 8));
            double cx = boardX + boardW / 2;
            double cy = boardY + boardH / 2;
            Brush.centered(g, "GET READY", cx, cy - 96, Theme.semibold(18), Theme.DIM);
            float size = (float) (128 + countdownAnim * 40);
            int alpha = (int) (120 + 135 * countdownAnim);
            Brush.glowText(g, String.valueOf(countdown), cx, cy + 40, Theme.display(size),
                    Theme.alpha(Theme.TEXT, alpha), Theme.GOLD, 4);
        }

        if (phase == Net.PHASE_OVER) {
            g.setColor(new Color(0, 0, 0, 165));
            g.fillRect(0, 0, VW, VH);
            Brush.panel(g, 460, 268, 360, 224, 24);
            String title = reason.isEmpty() ? "GAME OVER" : reason.toUpperCase(Locale.ROOT);
            Brush.centered(g, title, 640, 316, Theme.semibold(13), Theme.MUTED);
            String outcome = winner < 0 ? "Draw" : winner == app.myId() ? "You win" : "You lose";
            Brush.glowText(g, outcome, 640, 374, Theme.display(40), Theme.TEXT, Theme.GOLD, 4);
            Brush.centered(g, "Returning to the lobby…", 640, 430, Theme.font(14), Theme.DIM);
        }

        if (help) {
            g.setColor(new Color(0, 0, 0, 195));
            g.fillRect(0, 0, VW, VH);
            Brush.panel(g, 292, 120, 696, 576, 24);
            Brush.centered(g, "HOW TO PLAY CHESS", 640, 168, Theme.display(26), Theme.TEXT);
            String[] lines = {
                "You play " + (myColor == Chess.WHITE ? "WHITE" : "BLACK")
                        + " — the pieces at the bottom of the board.",
                "",
                "1.  Click one of your pieces. It becomes highlighted.",
                "2.  Small dots show legal moves; a red ring means a capture.",
                "3.  Click a highlighted square to move there.",
                "4.  Right-click (or click the piece again) to cancel.",
                "",
                "The game checks the rules for you: you cannot leave",
                "your king in check, and pawns promote automatically.",
                "Castling and en passant are supported.",
                "",
                "The top bar and the side card tell you whose turn it is",
                "and warn you when your king is in check.",
                "",
                "After checkmate or a draw the match returns to the",
                "lobby automatically — no need to press anything."
            };
            double y = 218;
            for (String line : lines) {
                if (line.isEmpty()) {
                    y += 12;
                    continue;
                }
                Brush.text(g, line, 344, y, Theme.font(14), Theme.TEXT);
                y += 25;
            }
            Brush.centered(g, "Press H or CLOSE HELP to return to the game", 640, 668,
                    Theme.font(12), Theme.MUTED);
        }

        if (pauseAnim > 0.01) {
            g.setColor(new Color(0, 0, 0, (int) (170 * pauseAnim)));
            g.fillRect(0, 0, VW, VH);
            Brush.panel(g, 440, 270, 400, 260, 24);
            Brush.centered(g, "Leave the match?", 640, 330, Theme.display(26), Theme.TEXT);
            Brush.centered(g, app.isHost() ? "You are the host — the server closes for everyone"
                            : "The match will continue without you",
                    640, 358, Theme.font(13), Theme.MUTED);
        }
    }

    private static Shape round(double x, double y, double w, double h, double r) {
        return new RoundRectangle2D.Double(x, y, w, h, r, r);
    }
}
