package snakenet.chess;

import java.util.ArrayList;
import java.util.List;

public final class Chess {

    public static final int SIZE = 8;

    public static final int WHITE = 0;
    public static final int BLACK = 1;

    public static final int EMPTY = 0;
    public static final int WK = 1;
    public static final int WQ = 2;
    public static final int WR = 3;
    public static final int WB = 4;
    public static final int WN = 5;
    public static final int WP = 6;
    public static final int BK = 7;
    public static final int BQ = 8;
    public static final int BR = 9;
    public static final int BB = 10;
    public static final int BN = 11;
    public static final int BP = 12;

    public static final class Move {
        public final int from;
        public final int to;
        public final int piece;
        public final int captured;
        public final int promotion;
        public final boolean enPassant;

        public Move(int from, int to, int piece, int captured, int promotion, boolean enPassant) {
            this.from = from;
            this.to = to;
            this.piece = piece;
            this.captured = captured;
            this.promotion = promotion;
            this.enPassant = enPassant;
        }

        @Override
        public String toString() {
            return squareName(from) + "->" + squareName(to);
        }
    }

    private final int[] board = new int[SIZE * SIZE];
    private int turn = WHITE;
    private int castling = 0b1111;
    private int enPassant = -1;
    private int halfmove = 0;
    private int fullmove = 1;

    public Chess() {
        reset();
    }

    public static Chess start() {
        return new Chess();
    }

    public void reset() {
        java.util.Arrays.fill(board, EMPTY);
        board[4] = WK; board[3] = WQ; board[0] = WR; board[7] = WR; board[1] = WN; board[6] = WN; board[5] = WB; board[2] = WB;
        for (int f = 0; f < SIZE; f++) {
            board[8 + f] = WP;
            board[48 + f] = BP;
        }
        board[56] = BR; board[63] = BR; board[59] = BQ; board[60] = BK; board[57] = BN; board[62] = BN; board[58] = BB; board[61] = BB;
        turn = WHITE;
        castling = 0b1111;
        enPassant = -1;
        halfmove = 0;
        fullmove = 1;
    }

    public int[] board() {
        int[] out = new int[SIZE * SIZE];
        System.arraycopy(board, 0, out, 0, board.length);
        return out;
    }

    public int at(int sq) {
        int idx = Math.max(0, Math.min(SIZE * SIZE - 1, sq));
        return board[idx];
    }

    public int turn() {
        return turn;
    }

    public int fullmove() {
        return fullmove;
    }

    public int halfmove() {
        return halfmove;
    }

    public int castling() {
        return castling;
    }

    public int enPassantSquare() {
        return enPassant;
    }

    public static String squareName(int sq) {
        int file = sq % SIZE;
        int rank = sq / SIZE;
        return new String(new char[]{(char) ('a' + file), (char) ('1' + rank)});
    }

    public static int square(String name) {
        if (name == null || name.length() < 2) return -1;
        char f = name.charAt(0);
        char r = name.charAt(1);
        if (f < 'a' || f > 'h' || r < '1' || r > '8') return -1;
        int rank = r - '1';
        if (rank < 0 || rank > 7) return -1;
        return rank * SIZE + (f - 'a');
    }

    public List<Move> legalMoves() {
        List<Move> pseudo = new ArrayList<>();
        for (int sq = 0; sq < SIZE * SIZE; sq++) {
            int p = board[sq];
            if (p == EMPTY) continue;
            if (colorOf(p) != turn) continue;
            int type = typeOf(p);
            switch (type) {
                case WP:
                case BP: addPawnMoves(pseudo, sq, p); break;
                case WN:
                case BN: addKnightMoves(pseudo, sq, p); break;
                case WB:
                case BB: addSliderMoves(pseudo, sq, p, 1); break;
                case WR:
                case BR: addSliderMoves(pseudo, sq, p, 0); break;
                case WQ:
                case BQ: addSliderMoves(pseudo, sq, p, 2); break;
                case WK:
                case BK: addKingMoves(pseudo, sq, p, turn == WHITE ? BLACK : WHITE); break;
            }
        }
        List<Move> legal = new ArrayList<>();
        int mover = turn;
        for (Move m : pseudo) {
            Chess next = apply(m);
            int king = findKing(next, mover);
            if (king >= 0 && !isAttacked(next, king, mover != WHITE)) {
                legal.add(m);
            }
        }
        return legal;
    }

    private void addPawnMoves(List<Move> out, int sq, int p) {
        int color = colorOf(p);
        int dir = color == WHITE ? 1 : -1;
        int startRank = color == WHITE ? 1 : 6;
        int promoRank = color == WHITE ? SIZE - 1 : 0;
        int r = sq / SIZE;
        int f = sq % SIZE;
        int oneR = r + dir;
        if (oneR >= 0 && oneR < SIZE && board[oneR * SIZE + f] == EMPTY) {
            addPawnMove(out, sq, oneR * SIZE + f, p, color, promoRank, false);
            int twoR = r + 2 * dir;
            if (r == startRank && board[twoR * SIZE + f] == EMPTY) {
                addPawnMove(out, sq, twoR * SIZE + f, p, color, promoRank, false);
            }
        }
        for (int df = -1; df <= 1; df++) {
            if (df == 0) continue;
            int nf = f + df;
            if (nf < 0 || nf > 7) continue;
            int to = oneR * SIZE + nf;
            if (to < 0 || to >= SIZE * SIZE) continue;
            int target = board[to];
            if (target != EMPTY && colorOf(target) != color) {
                addPawnMove(out, sq, to, p, color, promoRank, false);
            } else if (to == enPassant) {
                addPawnMove(out, sq, to, p, color, promoRank, true);
            }
        }
    }

    private void addPawnMove(List<Move> out, int from, int to, int p, int color, int promoRank, boolean enPassant) {
        int promoType = (to / SIZE) == promoRank ? (color == WHITE ? WQ : BQ) : 0;
        int captured = 0;
        if (!enPassant) {
            int target = board[to];
            if (target != EMPTY && colorOf(target) != color) captured = target;
        }
        out.add(new Move(from, to, p, captured, promoType, enPassant));
    }

    private void addKnightMoves(List<Move> out, int sq, int p) {
        int[] drs = {-2, -2, -1, -1, 1, 1, 2, 2};
        int[] dfs = {-1, 1, -2, 2, -2, 2, -1, 1};
        for (int i = 0; i < drs.length; i++) {
            int tr = sq / SIZE + drs[i];
            int tf = sq % SIZE + dfs[i];
            if (tr < 0 || tr > 7 || tf < 0 || tf > 7) continue;
            int to = tr * SIZE + tf;
            int t = board[to];
            if (t == EMPTY) {
                out.add(new Move(sq, to, p, 0, 0, false));
            } else if (colorOf(t) != colorOf(p)) {
                out.add(new Move(sq, to, p, t, 0, false));
            }
        }
    }

    private void addSliderMoves(List<Move> out, int sq, int p, int kind) {
        int[][] dirs;
        switch (kind) {
            case 0:
                dirs = new int[][]{{0, -1}, {0, 1}, {-1, 0}, {1, 0}};
                break;
            case 1:
                dirs = new int[][]{{-1, -1}, {-1, 1}, {1, -1}, {1, 1}};
                break;
            default:
                dirs = new int[][]{{-1, -1}, {-1, 0}, {-1, 1}, {0, -1}, {0, 1}, {1, -1}, {1, 0}, {1, 1}};
                break;
        }
        for (int[] d : dirs) {
            int to = sq + d[0] * 8 + d[1];
            while (inBoard(sq, to)) {
                int target = board[to];
                if (target == EMPTY) {
                    out.add(new Move(sq, to, p, 0, 0, false));
                } else {
                    if (colorOf(target) != colorOf(p)) out.add(new Move(sq, to, p, target, 0, false));
                    break;
                }
                to += d[0] * 8 + d[1];
            }
        }
    }

    private void addKingMoves(List<Move> out, int sq, int p, int enemy) {
        for (int df = -1; df <= 1; df++) {
            for (int dr = -1; dr <= 1; dr++) {
                if (df == 0 && dr == 0) continue;
                int tr = sq / SIZE + dr;
                int tf = sq % SIZE + df;
                if (tr < 0 || tr > 7 || tf < 0 || tf > 7) continue;
                int to = tr * SIZE + tf;
                int t = board[to];
                if (t == EMPTY) {
                    out.add(new Move(sq, to, p, 0, 0, false));
                } else if (colorOf(t) != colorOf(p)) {
                    out.add(new Move(sq, to, p, t, 0, false));
                }
            }
        }
        int color = colorOf(p);
        int mineRight = (color == WHITE) ? ((sq == 4) ? (castling & 0b0011) : 0) : (((castling >> 2) & 0b11) & (sq == 60 ? 0b11 : 0));
        if (mineRight == 0) return;
        if (isAttacked(this, sq, enemy != WHITE)) return;
        if (color == WHITE) {
            if ((mineRight & 0b0001) != 0 && board[5] == EMPTY && board[6] == EMPTY
                    && !isAttacked(this, 5, enemy != WHITE) && !isAttacked(this, 6, enemy != WHITE)) {
                out.add(new Move(4, 6, WK, 0, 0, false));
            }
            if ((mineRight & 0b0010) != 0 && board[3] == EMPTY && board[2] == EMPTY && board[1] == EMPTY
                    && !isAttacked(this, 3, enemy != WHITE) && !isAttacked(this, 2, enemy != WHITE)) {
                out.add(new Move(4, 2, WK, 0, 0, false));
            }
        } else {
            if ((mineRight & 0b0100) != 0 && board[61] == EMPTY && board[62] == EMPTY
                    && !isAttacked(this, 61, enemy != WHITE) && !isAttacked(this, 62, enemy != WHITE)) {
                out.add(new Move(60, 62, BK, 0, 0, false));
            }
            if ((mineRight & 0b1000) != 0 && board[59] == EMPTY && board[58] == EMPTY && board[57] == EMPTY
                    && !isAttacked(this, 59, enemy != WHITE) && !isAttacked(this, 58, enemy != WHITE)) {
                out.add(new Move(60, 58, BK, 0, 0, false));
            }
        }
    }

    private boolean inBoard(int from, int to) {
        if (to < 0 || to >= SIZE * SIZE) return false;
        int fr = from / SIZE;
        int ff = from % SIZE;
        int tr = to / SIZE;
        int tf = to % SIZE;
        int dr = tr - fr;
        int df = tf - ff;
        if (dr == 0) return Math.abs(df) == 1;
        if (df == 0) return Math.abs(dr) == 1;
        if (Math.abs(df) == Math.abs(dr)) return Math.abs(dr) <= 7;
        return false;
    }

    private static int colorOf(int p) {
        return p >= 7 ? BLACK : WHITE;
    }

    private static int typeOf(int p) {
        return (p - 1) % 6 + 1;
    }

    private int findKing(Chess c, int color) {
        int base = color == WHITE ? WK : BK;
        for (int sq = 0; sq < SIZE * SIZE; sq++) {
            if (c.board[sq] == base) return sq;
        }
        return -1;
    }

    private boolean isAttacked(Chess c, int sq, boolean byWhite) {
        int enemyPawn = byWhite ? WP : BP;
        int enemyKnight = byWhite ? WN : BN;
        int enemyKing = byWhite ? WK : BK;
        int enemyBishop = byWhite ? WB : BB;
        int enemyRook = byWhite ? WR : BR;
        int enemyQueen = byWhite ? WQ : BQ;
        int f = sq % SIZE;
        int r = sq / SIZE;
        int pawnRow = byWhite ? r - 1 : r + 1;
        if (pawnRow >= 0 && pawnRow < SIZE) {
            int pawnFile = f - 1;
            if (pawnFile >= 0 && c.board[pawnRow * SIZE + pawnFile] == enemyPawn) return true;
            pawnFile = f + 1;
            if (pawnFile < SIZE && c.board[pawnRow * SIZE + pawnFile] == enemyPawn) return true;
        }
        int[] knightDeltas = {-17, -15, -10, -6, 6, 10, 15, 17};
        for (int d : knightDeltas) {
            int to = sq + d;
            if (inBoard(sq, to) && c.board[to] == enemyKnight) return true;
        }
        int[] kingDeltas = {-9, -8, -7, -1, 1, 7, 8, 9};
        for (int d : kingDeltas) {
            int to = sq + d;
            if (inBoard(sq, to) && c.board[to] == enemyKing) return true;
        }
        int[] orth = {-8, -1, 1, 8};
        int[] diag = {-9, -7, 7, 9};
        for (int d : orth) {
            int to = sq + d;
            boolean straight = (d == -8 || d == 8) || (d == -1 || d == 1);
            while (inBoard(sq, to)) {
                int p = c.board[to];
                if (p != EMPTY) {
                    if (colorOf(p) != (byWhite ? BLACK : WHITE)) {
                        int t = typeOf(p);
                        if (straight ? (t == 3 || t == 2) : (t == 4 || t == 2)) return true;
                    }
                    break;
                }
                to += d;
            }
        }
        for (int d : diag) {
            int to = sq + d;
            while (inBoard(sq, to)) {
                int p = c.board[to];
                if (p != EMPTY) {
                    if (colorOf(p) != (byWhite ? BLACK : WHITE)) {
                        int t = typeOf(p);
                        if (t == 4 || t == 2) return true;
                    }
                    break;
                }
                to += d;
            }
        }
        return false;
    }

    private Chess copy() {
        Chess c = new Chess();
        System.arraycopy(board, 0, c.board, 0, board.length);
        c.turn = turn;
        c.castling = castling;
        c.enPassant = enPassant;
        c.halfmove = halfmove;
        c.fullmove = fullmove;
        return c;
    }

    public Chess apply(Move m) {
        Chess next = copy();
        int color = next.turn;
        next.board[m.from] = EMPTY;
        if (m.enPassant) {
            int capSq = (m.from / SIZE) * SIZE + (m.to % SIZE);
            next.board[capSq] = EMPTY;
        }
        next.board[m.to] = m.promotion != 0 ? m.promotion : m.piece;
        if ((m.piece == WK || m.piece == BK) && Math.abs(m.to - m.from) == 2) {
            if (m.piece == WK) {
                int rookFrom = (m.to > m.from) ? 5 : 3;
                int rookTo = (m.to > m.from) ? 7 : 1;
                next.board[rookFrom] = EMPTY;
                next.board[rookTo] = WR;
                next.castling &= ~(0b0011);
            } else {
                int rookFrom = (m.to > m.from) ? 61 : 59;
                int rookTo = (m.to > m.from) ? 63 : 57;
                next.board[rookFrom] = EMPTY;
                next.board[rookTo] = BR;
                next.castling &= ~(0b1100);
            }
        }
        if ((m.piece == WR || m.piece == BR) && (m.from % SIZE == 0 || m.from % SIZE == 7)) {
            if (color == WHITE) next.castling &= (m.from % SIZE == 0) ? 0b0010 : 0b0001;
            else next.castling &= (m.from % SIZE == 0) ? 0b1000 : 0b0100;
        }
        next.turn = next.turn == WHITE ? BLACK : WHITE;
        if (next.turn == WHITE) next.fullmove++;
        boolean movedPawn = (m.piece == WP || m.piece == BP);
        boolean captured = m.captured != 0 || m.enPassant;
        if (movedPawn || captured) next.halfmove = 0;
        else next.halfmove++;
        if (movedPawn) next.enPassant = (Math.abs(m.to - m.from) == 16) ? (m.from + (m.to - m.from) / 2) : -1;
        else next.enPassant = -1;
        return next;
    }

    public boolean isCheck() {
        int king = findKing(this, turn);
        if (king < 0) return false;
        return isAttacked(this, king, turn != WHITE);
    }

    public boolean isCheckmate() {
        return isCheck() && legalMoves().isEmpty();
    }

    public boolean isStalemate() {
        return !isCheck() && legalMoves().isEmpty();
    }

    public boolean isDraw() {
        return halfmove > 100;
    }

    public Move randomLegalMove() {
        List<Move> moves = legalMoves();
        if (moves.isEmpty()) return null;
        return moves.get((int) (Math.random() * moves.size()));
    }

    /**
     * Picks a move with a small alpha-beta search. Depth 1 is almost greedy; depth 3-4
     * gives the bot a bit of foresight. Runs on the server tick, so callers should throttle
     * it (see {@code Room.chessTick}).
     */
    public Move bestMove(int depth) {
        List<Move> moves = legalMoves();
        if (moves.isEmpty()) return null;
        int maxDepth = Math.max(1, Math.min(4, depth));
        Move best = moves.get(0);
        int bestScore = Integer.MIN_VALUE + 1;
        int alpha = -1_000_000;
        for (Move m : moves) {
            Chess next = apply(m);
            int score = -next.negamax(maxDepth - 1, -1_000_000, -alpha, 1);
            if (score > bestScore) {
                bestScore = score;
                best = m;
            }
            if (score > alpha) alpha = score;
        }
        return best;
    }

    private int negamax(int depth, int alpha, int beta, int ply) {
        List<Move> moves = legalMoves();
        if (moves.isEmpty()) {
            return isCheck() ? -100_000 + ply : 0;
        }
        if (halfmove > 100) return 0;
        if (depth <= 0) return evaluate();
        int best = -1_000_000;
        for (Move m : moves) {
            Chess next = apply(m);
            int score = -next.negamax(depth - 1, -beta, -alpha, ply + 1);
            if (score > best) best = score;
            if (best > alpha) alpha = best;
            if (alpha >= beta) break;
        }
        return best;
    }

    /** Material score from the side-to-move point of view, in centipawns. */
    public int evaluate() {
        int score = 0;
        for (int sq = 0; sq < board.length; sq++) {
            int p = board[sq];
            if (p == EMPTY) continue;
            int v = pieceValue(p);
            score += colorOf(p) == WHITE ? v : -v;
        }
        return turn == WHITE ? score : -score;
    }

    private static int pieceValue(int p) {
        switch (typeOf(p)) {
            case WQ: return 900;
            case WR: return 500;
            case WB: return 320;
            case WN: return 300;
            case WP: return 100;
            case WK: return 20_000;
            default: return 0;
        }
    }

    /** Rebuilds a position from the FEN string produced by {@link #toFen()}. */
    public static Chess fromFen(String fen) {
        Chess c = new Chess();
        if (fen == null || fen.isBlank()) return c;
        java.util.Arrays.fill(c.board, EMPTY);
        String[] parts = fen.trim().split("\\s+");
        String[] ranks = parts[0].split("/");
        int rank = SIZE - 1;
        for (String row : ranks) {
            int file = 0;
            for (int i = 0; i < row.length(); i++) {
                char ch = row.charAt(i);
                if (ch >= '1' && ch <= '8') {
                    file += ch - '0';
                } else {
                    if (file < SIZE && rank >= 0) c.board[rank * SIZE + file] = pieceFromChar(ch);
                    file++;
                }
            }
            rank--;
        }
        c.turn = parts.length > 1 && parts[1].startsWith("b") ? BLACK : WHITE;
        c.castling = 0;
        if (parts.length > 2) {
            String cast = parts[2];
            if (cast.indexOf('K') >= 0) c.castling |= 0b0001;
            if (cast.indexOf('Q') >= 0) c.castling |= 0b0010;
            if (cast.indexOf('k') >= 0) c.castling |= 0b0100;
            if (cast.indexOf('q') >= 0) c.castling |= 0b1000;
        }
        c.enPassant = parts.length > 3 && !parts[3].equals("-") ? square(parts[3]) : -1;
        c.halfmove = parts.length > 4 ? parseIntOr(parts[4], 0) : 0;
        c.fullmove = parts.length > 5 ? parseIntOr(parts[5], 1) : 1;
        return c;
    }

    private static int pieceFromChar(char ch) {
        switch (ch) {
            case 'K': return WK;
            case 'Q': return WQ;
            case 'R': return WR;
            case 'B': return WB;
            case 'N': return WN;
            case 'P': return WP;
            case 'k': return BK;
            case 'q': return BQ;
            case 'r': return BR;
            case 'b': return BB;
            case 'n': return BN;
            case 'p': return BP;
            default: return EMPTY;
        }
    }

    private static int parseIntOr(String text, int def) {
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public String toFen() {
        StringBuilder sb = new StringBuilder();
        for (int r = SIZE - 1; r >= 0; r--) {
            int empty = 0;
            for (int f = 0; f < SIZE; f++) {
                int p = board[r * SIZE + f];
                if (p == EMPTY) {
                    empty++;
                    continue;
                }
                if (empty > 0) {
                    sb.append(empty);
                    empty = 0;
                }
                sb.append(pieceChar(p));
            }
            if (empty > 0) sb.append(empty);
            if (r > 0) sb.append('/');
        }
        sb.append(' ').append(turn == WHITE ? 'w' : 'b');
        String castlingText;
        if (castling == 0b1111) castlingText = "KQkq";
        else {
            castlingText = "";
            if ((castling & 0b0001) != 0) castlingText += "K";
            if ((castling & 0b0010) != 0) castlingText += "Q";
            if ((castling & 0b0100) != 0) castlingText += "k";
            if ((castling & 0b1000) != 0) castlingText += "q";
        }
        sb.append(' ').append(castlingText.isEmpty() ? "-" : castlingText);
        if (enPassant >= 0) sb.append(' ').append(squareName(enPassant));
        else sb.append(" -");
        sb.append(' ').append(halfmove).append(' ').append(fullmove);
        return sb.toString();
    }

    private char pieceChar(int p) {
        int type = typeOf(p);
        char base = "KQRBNP".charAt(type - 1);
        return p >= 7 ? Character.toLowerCase(base) : Character.toUpperCase(base);
    }
}
