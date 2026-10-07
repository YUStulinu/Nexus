package nexus.games.search;

import java.util.Arrays;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import nexus.games.rules.ConnectFour;
import nexus.games.rules.Game;

/**
 * Perfect play for Connect Four - a port of Gambit's solver (after Pascal Pons): negamax with
 * alpha-beta pruning on bitboards, a transposition table, moves ordered by the threats they
 * create, moves that hand the opponent a win discarded up front, and a null-window search that
 * narrows the score by bisection.
 *
 * <p>Score of a position for the side to move: 0 for a draw; positive if it wins, equal to
 * (43 - moves at the end of the game) / 2, so a faster win scores higher; negative if it loses.
 * Not thread-safe (one table per solver).
 */
public final class ConnectFourSolver {
    static final int W = ConnectFour.COLUMNS, H = ConnectFour.ROWS, CELLS = W * H;
    static final int MIN_SCORE = -CELLS / 2 + 3, MAX_SCORE = (CELLS + 1) / 2 - 3;
    static final long BOTTOM = ConnectFour.BOTTOM_ROW, BOARD = ConnectFour.BOARD_MASK;
    private static final int[] COLUMN_ORDER = {3, 2, 4, 1, 5, 0, 6};

    private final long[] keys;
    /** Move lists per depth, reused (allocating them in every node costs a third of the time). */
    private final long[][] moveBuf = new long[CELLS + 1][W];
    private final int[][] scoreBuf = new int[CELLS + 1][W];
    private final byte[] values;
    private final int size;
    private long nodes;
    private BooleanSupplier cancelled = () -> false;

    public ConnectFourSolver() {
        this(23);
    }

    public ConnectFourSolver(int tableSizeLog2) {
        size = (1 << tableSizeLog2) + 9;
        keys = new long[size];
        values = new byte[size];
    }

    public void clear() {
        Arrays.fill(keys, 0);
        Arrays.fill(values, (byte) 0);
    }

    /** Positions visited by the last solve. */
    public long nodes() {
        return nodes;
    }

    /** Makes long solves abort (with a CancellationException) when this turns true. */
    public void setCancel(BooleanSupplier c) {
        cancelled = c == null ? () -> false : c;
    }

    static long winningPositions(long p, long mask) {
        long r = (p << 1) & (p << 2) & (p << 3);
        long q = (p << (H + 1)) & (p << 2 * (H + 1));
        r |= q & (p << 3 * (H + 1));
        r |= q & (p >>> (H + 1));
        q = (p >>> (H + 1)) & (p >>> 2 * (H + 1));
        r |= q & (p << (H + 1));
        r |= q & (p >>> 3 * (H + 1));
        q = (p << H) & (p << 2 * H);
        r |= q & (p << 3 * H);
        r |= q & (p >>> H);
        q = (p >>> H) & (p >>> 2 * H);
        r |= q & (p << H);
        r |= q & (p >>> 3 * H);
        q = (p << (H + 2)) & (p << 2 * (H + 2));
        r |= q & (p << 3 * (H + 2));
        r |= q & (p >>> (H + 2));
        q = (p >>> (H + 2)) & (p >>> 2 * (H + 2));
        r |= q & (p << (H + 2));
        r |= q & (p >>> 3 * (H + 2));
        return r & (BOARD ^ mask);
    }

    static long columnMask(int c) {
        return ((1L << H) - 1) << (c * (H + 1));
    }

    private int negamax(long current, long mask, int moves, int alpha, int beta) {
        if ((++nodes & 0xFFFF) == 0 && cancelled.getAsBoolean()) throw new CancellationException("solver stopped");
        long possible = (mask + BOTTOM) & BOARD;
        long oppWin = winningPositions(current ^ mask, mask);
        long forced = possible & oppWin;
        long next;
        if (forced != 0) {
            if ((forced & (forced - 1)) != 0) next = 0;      // two threats: cannot block both
            else next = forced & ~(oppWin >>> 1);
        } else next = possible & ~(oppWin >>> 1);           // never play right below an opponent's threat
        if (next == 0) return -(CELLS - moves) / 2;
        if (moves >= CELLS - 2) return 0;
        int min = -(CELLS - 2 - moves) / 2;
        if (alpha < min) {
            alpha = min;
            if (alpha >= beta) return alpha;
        }
        int max = (CELLS - 1 - moves) / 2;
        long key = current + mask;
        int slot = (int) (key % size);
        if (keys[slot] == key) max = values[slot] + MIN_SCORE - 1;
        if (beta > max) {
            beta = max;
            if (alpha >= beta) return beta;
        }
        long[] ms = moveBuf[moves];
        int[] sc = scoreBuf[moves];
        int n = 0;
        for (int i = W - 1; i >= 0; i--) {
            long move = next & columnMask(COLUMN_ORDER[i]);
            if (move == 0) continue;
            int s = Long.bitCount(winningPositions(current | move, mask));
            int j = n++;
            for (; j > 0 && sc[j - 1] > s; j--) {
                ms[j] = ms[j - 1];
                sc[j] = sc[j - 1];
            }
            ms[j] = move;
            sc[j] = s;
        }
        for (int i = n - 1; i >= 0; i--) {
            int score = -negamax(current ^ mask, mask | ms[i], moves + 1, -beta, -alpha);
            if (score >= beta) return score;
            if (score > alpha) alpha = score;
        }
        keys[slot] = key;
        values[slot] = (byte) (alpha - MIN_SCORE + 1);
        return alpha;
    }

    /** The exact score of the position for the side to move. */
    public int solve(ConnectFour g) {
        if (g.outcome() == Game.Outcome.LOSS) return -(CELLS + 2 - g.ply()) / 2;
        if (g.outcome() == Game.Outcome.DRAW) return 0;
        nodes = 0;
        long current = g.current(), mask = g.mask();
        int moves = g.ply();
        if ((winningPositions(current, mask) & (mask + BOTTOM) & BOARD) != 0) return (CELLS + 1 - moves) / 2;
        int min = -(CELLS - moves) / 2, max = (CELLS + 1 - moves) / 2;
        while (min < max) {
            int med = min + (max - min) / 2;
            if (med <= 0 && min / 2 < med) med = min / 2;
            else if (med >= 0 && max / 2 > med) med = max / 2;
            int r = negamax(current, mask, moves, med, med + 1);
            if (r <= med) max = r;
            else min = r;
        }
        return min;
    }

    /** The score of every column from the mover's point of view, null where the column is full. */
    public Integer[] scoreMoves(ConnectFour g) {
        var result = new Integer[W];
        for (int c = 0; c < W; c++) {
            if (!g.isLegal(c)) continue;
            if (g.isWinningMove(c)) {
                result[c] = (CELLS + 1 - g.ply()) / 2;
                continue;
            }
            var child = g.copy();
            child.play(c);
            result[c] = child.outcome() == Game.Outcome.DRAW ? 0 : -solve(child);
        }
        return result;
    }

    /**
     * A score in words for the side to move at {@code ply}: "draw", "wins with its 3rd move",
     * "loses to the opponent's 2nd move". A win scored s ends with 43 - 2s stones on the board.
     */
    public static String describe(int score, int ply) {
        if (score == 0) return "draw";
        int movesAtEnd = CELLS + 1 - 2 * Math.abs(score);
        if (score > 0) {
            int own = Math.max(1, (movesAtEnd - ply + 1) / 2);
            return "wins with its " + ordinal(own) + " move";
        }
        int theirs = Math.max(1, (movesAtEnd - ply) / 2);
        return "loses to the opponent's " + ordinal(theirs) + " move";
    }

    static String ordinal(int n) {
        String suffix = n % 100 >= 11 && n % 100 <= 13 ? "th" : switch (n % 10) {
            case 1 -> "st";
            case 2 -> "nd";
            case 3 -> "rd";
            default -> "th";
        };
        return n + suffix;
    }
}
