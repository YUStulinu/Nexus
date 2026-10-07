package nexus.games.rules;

/**
 * Connect Four (7 columns x 6 rows) on two 64-bit bitboards, as in Gambit (and Pascal Pons'
 * solver): column c occupies bits 7c .. 7c+6, bottom row first, bit 7c+6 a sentinel that is always
 * empty. {@code mask} holds every stone, {@code current} those of the side to move; playing a
 * column is one addition, and four in a row is four shift-and-AND pairs over the whole board.
 */
public final class ConnectFour implements Game {
    public static final int COLUMNS = 7, ROWS = 6;
    static final int STRIDE = ROWS + 1;
    public static final long BOTTOM_ROW = 0b0000001_0000001_0000001_0000001_0000001_0000001_0000001L;
    public static final long BOARD_MASK = BOTTOM_ROW * ((1L << ROWS) - 1);

    long current, mask;
    int moves;
    Outcome outcome = Outcome.ONGOING;

    @Override
    public String name() {
        return "connect4";
    }

    @Override
    public int width() {
        return COLUMNS;
    }

    @Override
    public int height() {
        return ROWS;
    }

    @Override
    public int actions() {
        return COLUMNS;
    }

    @Override
    public int planes() {
        return 3;
    }

    @Override
    public ConnectFour copy() {
        var c = new ConnectFour();
        c.current = current;
        c.mask = mask;
        c.moves = moves;
        c.outcome = outcome;
        return c;
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

    public long current() {
        return current;
    }

    public long mask() {
        return mask;
    }

    /** Unique key of the position. */
    public long key() {
        return current + mask;
    }

    static long topMask(int col) {
        return 1L << (ROWS - 1 + col * STRIDE);
    }

    static long bottomMask(int col) {
        return 1L << (col * STRIDE);
    }

    static long columnMask(int col) {
        return ((1L << ROWS) - 1) << (col * STRIDE);
    }

    @Override
    public boolean isLegal(int action) {
        return action >= 0 && action < COLUMNS && outcome == Outcome.ONGOING && (mask & topMask(action)) == 0;
    }

    @Override
    public int legalMoves(int[] buffer) {
        if (outcome != Outcome.ONGOING) return 0;
        int n = 0;
        for (int c = 0; c < COLUMNS; c++) if ((mask & topMask(c)) == 0) buffer[n++] = c;
        return n;
    }

    @Override
    public void play(int action) {
        current ^= mask;
        mask |= mask + bottomMask(action);
        moves++;
        if (hasFour(current ^ mask)) outcome = Outcome.LOSS;          // the stones of the player who just moved
        else if (moves == COLUMNS * ROWS) outcome = Outcome.DRAW;
    }

    public static boolean hasFour(long p) {
        long m = p & (p >>> 1);
        if ((m & (m >>> 2)) != 0) return true;                      // vertical
        m = p & (p >>> STRIDE);
        if ((m & (m >>> (2 * STRIDE))) != 0) return true;           // horizontal
        m = p & (p >>> (STRIDE - 1));
        if ((m & (m >>> (2 * (STRIDE - 1)))) != 0) return true;     // diagonal /
        m = p & (p >>> (STRIDE + 1));
        return (m & (m >>> (2 * (STRIDE + 1)))) != 0;               // diagonal \
    }

    /** Whether the side to move wins by playing {@code col}. */
    public boolean isWinningMove(int col) {
        return hasFour(current | ((mask + bottomMask(col)) & columnMask(col)));
    }

    public int columnHeight(int col) {
        return Long.bitCount(mask & columnMask(col));
    }

    @Override
    public void encode(float[] planes, int offset) {
        int area = COLUMNS * ROWS;
        java.util.Arrays.fill(planes, offset, offset + 3 * area, 0f);
        long own = current, opp = current ^ mask;
        for (int c = 0; c < COLUMNS; c++)
            for (int r = 0; r < ROWS; r++) {
                long bit = 1L << (c * STRIDE + r);
                int cell = (ROWS - 1 - r) * COLUMNS + c;           // y = 0 is the top row
                if ((own & bit) != 0) planes[offset + cell] = 1f;
                else if ((opp & bit) != 0) planes[offset + area + cell] = 1f;
            }
        if ((moves & 1) == 1) java.util.Arrays.fill(planes, offset + 2 * area, offset + 3 * area, 1f);
    }

    @Override
    public int cellAt(int x, int y) {
        int r = ROWS - 1 - y;
        long bit = 1L << (x * STRIDE + r);
        if ((mask & bit) == 0) return 0;
        long first = (moves & 1) == 0 ? current : current ^ mask;
        return (first & bit) != 0 ? 1 : 2;
    }

    @Override
    public int cellOf(int action) {
        int h = columnHeight(action);
        return (ROWS - 1 - Math.min(h, ROWS - 1)) * COLUMNS + action;
    }

    @Override
    public String actionName(int action) {
        return String.valueOf((char) ('1' + action));
    }

    @Override
    public int parseAction(String text) {
        return text.length() == 1 && text.charAt(0) >= '1' && text.charAt(0) <= '7' ? text.charAt(0) - '1' : -1;
    }

    @Override
    public String toString() {
        var sb = new StringBuilder();
        for (int y = 0; y < ROWS; y++) {
            for (int x = 0; x < COLUMNS; x++) sb.append(switch (cellAt(x, y)) { case 1 -> " X"; case 2 -> " O"; default -> " ."; });
            sb.append('\n');
        }
        return sb.toString();
    }
}
