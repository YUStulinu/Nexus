package nexus.games.rules;

/**
 * A two-player, zero-sum, perfect-information board game - the Java side of Gambit's
 * {@code IGame}. Positions are small mutable objects; the search copies the root and plays moves
 * on the copy.
 *
 * <p>The encoding matches Gambit's exactly, so networks trained there (.gnet files) evaluate
 * positions here identically: {@link #planes()} planes of height x width floats, from the point of
 * view of the side to move (own stones, opponent stones, a plane of ones if the second player is
 * to move).
 */
public interface Game {
    /** Outcome for the side to move. */
    enum Outcome { ONGOING, LOSS, DRAW }

    String name();

    int width();

    int height();

    /** Size of the policy vector. */
    int actions();

    int planes();

    Game copy();

    int ply();

    /** 0 if the first player is to move. */
    int sideToMove();

    Outcome outcome();

    default boolean isOver() {
        return outcome() != Outcome.ONGOING;
    }

    boolean isLegal(int action);

    /** Writes the legal actions into {@code buffer}; returns how many there are. */
    int legalMoves(int[] buffer);

    void play(int action);

    /** Writes planes() x height() x width() floats at {@code offset}. */
    void encode(float[] planes, int offset);

    /** 0 empty, 1 first player, 2 second player; y = 0 is the top row. */
    int cellAt(int x, int y);

    String actionName(int action);

    /** Parses an action name, or returns -1. */
    int parseAction(String text);

    /** The cell an action fills (for display); Connect Four: the landing cell of the column. */
    int cellOf(int action);

    /** Value of a finished position for the side to move: -1 lost, 0 drawn. */
    default float terminalValue() {
        return switch (outcome()) {
            case LOSS -> -1f;
            case DRAW -> 0f;
            case ONGOING -> throw new IllegalStateException("the game is not over");
        };
    }

    /** A new game by name: "connect4" or "gomoku". */
    static Game create(String name) {
        return switch (name) {
            case "connect4" -> new ConnectFour();
            case "gomoku" -> new Gomoku();
            default -> throw new IllegalArgumentException("unknown game '" + name + "'");
        };
    }

    /** Plays a space- or digit-separated move list ("4453" for Connect Four, "e5 d4" for Gomoku). */
    static Game fromMoves(String name, String moves) {
        var g = create(name);
        var tokens = name.equals("connect4") ? moves.replace(" ", "").split("") : moves.strip().split("\\s+");
        for (var t : tokens) {
            if (t.isBlank()) continue;
            int a = g.parseAction(t);
            if (a < 0 || !g.isLegal(a)) throw new IllegalArgumentException("illegal move '" + t + "' in \"" + moves + "\"");
            g.play(a);
        }
        return g;
    }
}
