package nexus.games.rules;

import java.util.Arrays;

/**
 * Gomoku on a 9x9 board: five or more in a row wins. Actions are cell indices y * 9 + x. Only the
 * lines through the stone just played can have become five, so that is all {@link #play} checks.
 */
public final class Gomoku implements Game {
    public static final int SIZE = 9, CELLS = SIZE * SIZE;
    private static final int[][] DIRECTIONS = {{1, 0}, {0, 1}, {1, 1}, {1, -1}};

    final byte[] board = new byte[CELLS];     // 0 empty, 1 black (first), 2 white
    int moves;
    Outcome outcome = Outcome.ONGOING;

    @Override
    public String name() {
        return "gomoku";
    }

    @Override
    public int width() {
        return SIZE;
    }

    @Override
    public int height() {
        return SIZE;
    }

    @Override
    public int actions() {
        return CELLS;
    }

    @Override
    public int planes() {
        return 3;
    }

    @Override
    public Gomoku copy() {
        var g = new Gomoku();
        System.arraycopy(board, 0, g.board, 0, CELLS);
        g.moves = moves;
        g.outcome = outcome;
        return g;
    }

    @Override
    public int ply() {
        return moves;
    }

    @Override
    public int sideToMove() {
        return moves & 1;
    }

    @Override
    public Outcome outcome() {
        return outcome;
    }

    @Override
    public boolean isLegal(int action) {
        return action >= 0 && action < CELLS && outcome == Outcome.ONGOING && board[action] == 0;
    }

    @Override
    public int legalMoves(int[] buffer) {
        if (outcome != Outcome.ONGOING) return 0;
        int n = 0;
        for (int a = 0; a < CELLS; a++) if (board[a] == 0) buffer[n++] = a;
        return n;
    }

    @Override
    public void play(int action) {
        byte stone = (byte) ((moves & 1) == 0 ? 1 : 2);
        board[action] = stone;
        moves++;
        if (fiveThrough(action, stone)) outcome = Outcome.LOSS;
        else if (moves == CELLS) outcome = Outcome.DRAW;
    }

    private boolean fiveThrough(int cell, byte stone) {
        int x0 = cell % SIZE, y0 = cell / SIZE;
        for (var d : DIRECTIONS) {
            int run = 1;
            for (int s = -1; s <= 1; s += 2)
                for (int x = x0 + s * d[0], y = y0 + s * d[1]; x >= 0 && x < SIZE && y >= 0 && y < SIZE && board[y * SIZE + x] == stone;
                     x += s * d[0], y += s * d[1])
                    run++;
            if (run >= 5) return true;
        }
        return false;
    }

    @Override
    public void encode(float[] planes, int offset) {
        Arrays.fill(planes, offset, offset + 3 * CELLS, 0f);
        boolean blackToMove = (moves & 1) == 0;
        byte own = (byte) (blackToMove ? 1 : 2);
        for (int a = 0; a < CELLS; a++) {
            if (board[a] == own) planes[offset + a] = 1f;
            else if (board[a] != 0) planes[offset + CELLS + a] = 1f;
        }
        if (!blackToMove) Arrays.fill(planes, offset + 2 * CELLS, offset + 3 * CELLS, 1f);
    }

    @Override
    public int cellAt(int x, int y) {
        return board[y * SIZE + x];
    }

    @Override
    public int cellOf(int action) {
        return action;
    }

    @Override
    public String actionName(int action) {
        return "" + (char) ('a' + action % SIZE) + (SIZE - action / SIZE);
    }

    @Override
    public int parseAction(String text) {
        if (text.length() < 2) return -1;
        int x = Character.toLowerCase(text.charAt(0)) - 'a';
        int row;
        try {
            row = Integer.parseInt(text.substring(1));
        } catch (NumberFormatException e) {
            return -1;
        }
        int y = SIZE - row;
        return x >= 0 && x < SIZE && y >= 0 && y < SIZE ? y * SIZE + x : -1;
    }
}
