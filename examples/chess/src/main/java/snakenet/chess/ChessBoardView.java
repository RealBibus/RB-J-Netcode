package snakenet.chess;

import snakenet.chess.ui.Theme;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Shape;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.util.List;

/**
 * Chess board rendering, input and animation. This is game code, not engine code.
 * The server owns the position; this class only presents it and turns clicks into moves.
 */
public final class ChessBoardView {

    private static final String GLYPHS = "\u265A\u265B\u265C\u265D\u265E\u265F";
    private static final double MOVE_SECONDS = 0.20;

    private Chess chess = new Chess();
    private int turn = Chess.WHITE;
    private int myColor = Chess.WHITE;
    private int selected = -1;

    private int lastFrom = -1;
    private int lastTo = -1;
    private double selectPulse;
    private double clock;

    private int animFrom = -1;
    private int animTo = -1;
    private int animPiece;
    private int animCaptureSquare = -1;
    private int animCapturePiece;
    private double anim = 1;

    public void reset(Chess position, int turn, int myColor) {
        this.chess = position == null ? new Chess() : position;
        this.turn = turn;
        this.myColor = myColor;
        this.selected = -1;
        this.lastFrom = -1;
        this.lastTo = -1;
        this.anim = 1;
        this.animFrom = -1;
        this.animTo = -1;
        this.animCaptureSquare = -1;
        this.animCapturePiece = 0;
    }

    public void sync(Chess position, int turn) {
        if (position == null) return;
        boolean changed = !position.toFen().equals(chess.toFen());
        this.chess = position;
        this.turn = turn;
        if (changed && anim >= 1) {
            selected = -1;
        }
    }

    public void playMove(int from, int to, Chess before, Chess next, int turn) {
        this.lastFrom = from;
        this.lastTo = to;
        this.turn = turn;
        this.selected = -1;

        Chess old = before == null ? this.chess : before;
        int piece = old.at(from);
        int captured = old.at(to);
        int captureSquare = captured != 0 ? to : -1;
        if (captured == 0 && piece != 0 && (piece == 1 || piece == 7) && to == old.enPassantSquare()) {
            captureSquare = to + (piece == 1 ? -8 : 8);
            if (captureSquare >= 0 && captureSquare < 64) captured = old.at(captureSquare);
        }

        this.animFrom = from;
        this.animTo = to;
        this.animPiece = piece;
        this.animCaptureSquare = captureSquare;
        this.animCapturePiece = captured;
        this.anim = 0;
        this.chess = next == null ? old : next;
    }

    public void update(double dt) {
        clock += dt;
        selectPulse += dt * 3.4;
        if (anim < 1) anim = Math.min(1, anim + dt / MOVE_SECONDS);
    }

    public void clearSelection() {
        selected = -1;
    }

    public int selected() {
        return selected;
    }

    public int myColor() {
        return myColor;
    }

    public int turn() {
        return turn;
    }

    public Chess position() {
        return chess;
    }

    /** Returns the move the player wants to send, or null when this click only changed selection. */
    public Chess.Move click(double vx, double vy, double boardX, double boardY,
                            double boardW, double boardH) {
        double cs = cellSize(boardW, boardH);
        double bx0 = originX(boardX, boardW, cs);
        double by0 = originY(boardY, boardH, cs);
        int bx = (int) Math.floor((vx - bx0) / cs);
        int by = (int) Math.floor((vy - by0) / cs);
        if (bx < 0 || bx >= 8 || by < 0 || by >= 8) return null;

        boolean flip = myColor == Chess.BLACK;
        int file = flip ? 7 - bx : bx;
        int rank = flip ? by : 7 - by;
        int sq = rank * 8 + file;

        int piece = chess.at(sq);
        boolean mine = piece != 0 && (piece >= 7) == (myColor == Chess.BLACK);
        boolean myTurn = turn == myColor;

        if (selected < 0) {
            if (mine && myTurn) selected = sq;
            return null;
        }
        if (mine && sq != selected) {
            selected = sq;
            return null;
        }
        if (sq == selected) {
            selected = -1;
            return null;
        }
        if (!myTurn) return null;

        for (Chess.Move move : chess.legalMoves()) {
            if (move.from == selected && move.to == sq) {
                selected = -1;
                return move;
            }
        }
        return null;
    }

    public void paint(Graphics2D g, double x, double y, double w, double h) {
        double cs = cellSize(w, h);
        double bx0 = originX(x, w, cs);
        double by0 = originY(y, h, cs);
        boolean flip = myColor == Chess.BLACK;
        boolean myTurn = turn == myColor;

        Shape frame = new RoundRectangle2D.Double(x - 10, y - 10, w + 20, h + 20, 18, 18);
        g.setColor(new Color(0x07, 0x09, 0x0D, 235));
        g.fill(frame);
        g.setColor(Theme.alpha(myTurn ? Theme.GOLD : Theme.LINE, myTurn ? 105 : 190));
        g.setStroke(new BasicStroke(myTurn ? 1.6f : 1.2f));
        g.draw(frame);

        List<Chess.Move> targets = selected >= 0 && myTurn ? chess.legalMoves() : List.of();
        int checkSquare = chess.isCheck() ? kingSquare(turn) : -1;

        for (int r = 0; r < 8; r++) {
            for (int f = 0; f < 8; f++) {
                int rank = flip ? r : 7 - r;
                int file = flip ? 7 - f : f;
                int sq = rank * 8 + file;
                double sx = bx0 + f * cs;
                double sy = by0 + r * cs;

                Color light = new Color(0x3A, 0x49, 0x5B);
                Color dark = new Color(0x25, 0x30, 0x3E);
                Color bg = ((r + f) % 2 == 0) ? light : dark;
                if (sq == lastFrom || sq == lastTo) bg = Theme.mix(bg, Theme.GOLD, 0.22f);
                g.setColor(bg);
                g.fill(new Rectangle2D.Double(sx, sy, cs, cs));

                if (sq == checkSquare) {
                    float pulse = (float) (0.45 + 0.25 * Math.sin(clock * 5));
                    g.setColor(new Color(0xE8, 0x4A, 0x3D, Math.round(190 * pulse)));
                    g.fill(new Rectangle2D.Double(sx, sy, cs, cs));
                    g.setColor(Theme.alpha(Theme.DANGER, 220));
                    g.setStroke(new BasicStroke(2f));
                    g.draw(new Rectangle2D.Double(sx + 1.5, sy + 1.5, cs - 3, cs - 3));
                }

                if (sq == selected) {
                    double glow = 0.45 + 0.20 * Math.sin(selectPulse);
                    g.setColor(Theme.alpha(Theme.GOLD, (int) (90 + 80 * glow)));
                    g.fill(new Rectangle2D.Double(sx, sy, cs, cs));
                    g.setColor(Theme.alpha(Theme.GOLD, 235));
                    g.setStroke(new BasicStroke(2.2f));
                    g.draw(new Rectangle2D.Double(sx + 2, sy + 2, cs - 4, cs - 4));
                }

                for (Chess.Move move : targets) {
                    if (move.to != sq) continue;
                    int occupant = chess.at(sq);
                    if (occupant == 0) {
                        double rr = cs * (0.095 + 0.012 * Math.sin(clock * 4 + f + r));
                        g.setColor(Theme.alpha(Theme.ACCENT, 175));
                        g.fill(new Ellipse2D.Double(sx + cs / 2 - rr, sy + cs / 2 - rr, rr * 2, rr * 2));
                    } else {
                        g.setColor(Theme.alpha(Theme.DANGER, 195));
                        g.setStroke(new BasicStroke(2.4f));
                        double pad = cs * 0.10;
                        g.draw(new Ellipse2D.Double(sx + pad, sy + pad, cs - pad * 2, cs - pad * 2));
                    }
                    break;
                }

                int piece = chess.at(sq);
                if (piece != 0 && !(animTo == sq && anim < 1)) {
                    paintPiece(g, piece, sx + cs / 2, sy + cs / 2, cs);
                }
            }
        }

        paintCoordinates(g, bx0, by0, cs, flip);
        paintAnimation(g, bx0, by0, cs, flip);
    }

    private void paintAnimation(Graphics2D g, double bx0, double by0, double cs, boolean flip) {
        if (anim >= 1 || animFrom < 0 || animTo < 0) return;

        double t = smooth(anim);
        double fx = cellX(animFrom, flip);
        double fy = cellY(animFrom, flip);
        double tx = cellX(animTo, flip);
        double ty = cellY(animTo, flip);
        double cx = bx0 + (fx + (tx - fx) * t + 0.5) * cs;
        double cy = by0 + (fy + (ty - fy) * t + 0.5) * cs;

        if (animCapturePiece != 0 && animCaptureSquare >= 0) {
            double ax = cellX(animCaptureSquare, flip);
            double ay = cellY(animCaptureSquare, flip);
            float fade = (float) Math.max(0, 1 - anim * 1.4);
            if (fade > 0.02f) {
                Graphics2D ghost = (Graphics2D) g.create();
                ghost.setComposite(java.awt.AlphaComposite.getInstance(
                        java.awt.AlphaComposite.SRC_OVER, fade * 0.6f));
                paintPiece(ghost, animCapturePiece, bx0 + (ax + 0.5) * cs, by0 + (ay + 0.5) * cs, cs);
                ghost.dispose();
            }
        }

        double lift = Math.sin(Math.PI * t) * cs * 0.05;
        paintPiece(g, animPiece, cx, cy - lift, cs * (1.0 + 0.08 * Math.sin(Math.PI * t)));
    }

    private void paintCoordinates(Graphics2D g, double bx0, double by0, double cs, boolean flip) {
        g.setFont(Theme.semibold((float) Math.max(9, cs * 0.13)));
        java.awt.FontMetrics fm = g.getFontMetrics();
        int ascent = fm.getAscent();
        for (int i = 0; i < 8; i++) {
            int file = flip ? 7 - i : i;
            int rank = flip ? i : 7 - i;
            String fileName = String.valueOf((char) ('a' + file));
            String rankName = String.valueOf(rank + 1);
            g.setColor(Theme.alpha(Theme.MUTED, 215));
            g.drawString(rankName, (float) (bx0 - 8 - fm.stringWidth(rankName)),
                    (float) (by0 + i * cs + cs / 2 + ascent / 2.0 - 1));
            g.drawString(fileName,
                    (float) (bx0 + i * cs + cs / 2 - fm.stringWidth(fileName) / 2.0),
                    (float) (by0 + 8 * cs + ascent + 4));
        }
    }

    private static double cellSize(double w, double h) {
        return Math.min(w, Math.max(1, h - 18)) / 8.0;
    }

    private static double originX(double x, double w, double cs) {
        return x + (w - cs * 8) / 2;
    }

    private static double originY(double y, double h, double cs) {
        return y + (Math.max(1, h - 18) - cs * 8) / 2;
    }

    private void paintPiece(Graphics2D g, int piece, double cx, double cy, double cell) {
        if (piece == 0) return;
        int type = (piece - 1) % 6;
        boolean black = piece >= 7;
        String glyph = String.valueOf(GLYPHS.charAt(type));
        Font font = new Font("Segoe UI Symbol", Font.PLAIN, (int) Math.max(18, cell * 0.74));
        FontRenderContext frc = g.getFontRenderContext();
        GlyphVector gv = font.createGlyphVector(frc, glyph);
        Rectangle2D b = gv.getVisualBounds();
        double gx = cx - b.getWidth() / 2 - b.getX();
        double gy = cy - b.getHeight() / 2 - b.getY();
        Shape shape = gv.getOutline((float) gx, (float) gy);

        Graphics2D shadow = (Graphics2D) g.create();
        shadow.translate(0, cell * 0.045);
        shadow.setColor(new Color(0, 0, 0, 120));
        shadow.fill(shape);
        shadow.dispose();

        Color fill = black ? new Color(0x2A, 0x22, 0x1C) : new Color(0xEC, 0xF1, 0xF7);
        Color edge = black ? new Color(0xD8, 0xA0, 0x60) : new Color(0x40, 0x33, 0x2A);
        g.setColor(fill);
        g.fill(shape);
        g.setColor(edge);
        g.setStroke(new BasicStroke((float) Math.max(0.9, cell * 0.018)));
        g.draw(shape);
    }

    private int kingSquare(int color) {
        int king = color == Chess.WHITE ? 1 : 7;
        for (int sq = 0; sq < 64; sq++) {
            if (chess.at(sq) == king) return sq;
        }
        return -1;
    }

    private static double cellX(int sq, boolean flip) {
        int file = sq % 8;
        return flip ? 7 - file : file;
    }

    private static double cellY(int sq, boolean flip) {
        int rank = sq / 8;
        return flip ? rank : 7 - rank;
    }

    private static double smooth(double t) {
        return t * t * (3 - 2 * t);
    }
}
