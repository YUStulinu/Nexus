package nexus.games.play;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import nexus.games.rules.Game;

/**
 * A match between two players, as Gambit plays them: every game starts with a few uniformly random
 * moves (so the match covers many openings instead of replaying one), and every opening is played
 * twice with the colours swapped, so neither side profits from a lucky opening or from moving
 * first. Games run in parallel on all cores.
 */
public final class Arena {
    private Arena() {
    }

    /** Result from A's point of view. */
    public record Result(String a, String b, int wins, int draws, int losses) {
        public int games() {
            return wins + draws + losses;
        }

        public double score() {
            return games() == 0 ? 0.5 : (wins + 0.5 * draws) / games();
        }

        /** Elo difference implied by the score (clamped for 0% / 100%). */
        public double elo() {
            return eloOf(score(), games());
        }

        /** Half-width of the 95% confidence interval of the Elo difference. */
        public double eloMargin() {
            int n = games();
            if (n < 2) return Double.NaN;
            double s = score();
            double var = (wins * Math.pow(1 - s, 2) + draws * Math.pow(0.5 - s, 2) + losses * Math.pow(s, 2)) / n;
            double se = Math.sqrt(var / n);
            double lo = eloOf(Math.max(1e-3, s - 1.96 * se), n), hi = eloOf(Math.min(1 - 1e-3, s + 1.96 * se), n);
            return (hi - lo) / 2;
        }

        /** Likelihood of superiority: the probability that A is really stronger, Φ((W - L) / sqrt(W + L)). */
        public double los() {
            if (wins + losses == 0) return 0.5;
            return phi((wins - losses) / Math.sqrt(wins + losses));
        }

        @Override
        public String toString() {
            return String.format(java.util.Locale.ROOT, "+%d =%d -%d (%.1f%%, %+.0f ± %.0f Elo, LOS %.0f%%)", wins, draws, losses, 100 * score(), elo(),
                                 eloMargin(), 100 * los());
        }
    }

    static double eloOf(double score, int games) {
        double s = Math.clamp(score, 0.5 / Math.max(1, games), 1 - 0.5 / Math.max(1, games));
        return -400 * Math.log10(1 / s - 1);
    }

    static double phi(double x) {
        return 0.5 * (1 + erf(x / Math.sqrt(2)));
    }

    static double erf(double x) {
        double t = 1 / (1 + 0.3275911 * Math.abs(x));
        double y = 1 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t + 0.254829592) * t * Math.exp(-x * x);
        return x >= 0 ? y : -y;
    }

    /** One finished game (A's result: 1 win, 0.5 draw, 0 loss). */
    public record GameRecord(int index, boolean aFirst, String moves, double resultForA) {
    }

    /**
     * Plays {@code games} games (rounded up to an even number).
     *
     * @param randomOpening maximum number of random opening moves (0..n, uniformly)
     * @param progress      called after every game with the running result
     */
    public static Result play(String gameName, Player.Factory a, Player.Factory b, int games, int randomOpening, long seed,
                              BooleanSupplier cancelled, Consumer<Result> progress, Consumer<GameRecord> finished) throws InterruptedException {
        int pairs = (games + 1) / 2;
        var rng = new Random(seed);
        var openings = new ArrayList<int[]>();
        for (int i = 0; i < pairs; i++) openings.add(opening(gameName, rng, randomOpening));
        var w = new AtomicInteger();
        var d = new AtomicInteger();
        var l = new AtomicInteger();
        int threads = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
        ExecutorService pool = Executors.newFixedThreadPool(threads, r -> {
            var t = new Thread(r, "arena");
            t.setDaemon(true);
            return t;
        });
        try {
            var futures = new ArrayList<Future<?>>();
            for (int i = 0; i < 2 * pairs; i++) {
                final int index = i;
                futures.add(pool.submit(() -> {
                    if (cancelled != null && cancelled.getAsBoolean()) return;
                    boolean aFirst = index % 2 == 0;
                    var pa = a.create(seed * 1_000_003L + index * 2L);
                    var pb = b.create(seed * 1_000_003L + index * 2L + 1);
                    var g = Game.create(gameName);
                    var record = new StringBuilder();
                    for (int m : openings.get(index / 2)) {
                        if (!g.isLegal(m)) break;
                        g.play(m);
                        record.append(g.actionName(m)).append(' ');
                    }
                    while (!g.isOver()) {
                        if (cancelled != null && cancelled.getAsBoolean()) return;
                        boolean aToMove = (g.sideToMove() == 0) == aFirst;
                        int m = (aToMove ? pa : pb).chooseMove(g);
                        g.play(m);
                        record.append(g.actionName(m)).append(' ');
                    }
                    double r;
                    if (g.outcome() == Game.Outcome.DRAW) r = 0.5;
                    else {
                        boolean lastMoverIsA = (((g.ply() - 1) & 1) == 0) == aFirst;
                        r = lastMoverIsA ? 1 : 0;
                    }
                    (r == 1 ? w : r == 0 ? l : d).incrementAndGet();
                    if (finished != null) finished.accept(new GameRecord(index, aFirst, record.toString().strip(), r));
                    if (progress != null) progress.accept(new Result(a.name(), b.name(), w.get(), d.get(), l.get()));
                }));
            }
            for (var f : futures) {
                try {
                    f.get();
                } catch (java.util.concurrent.ExecutionException e) {
                    throw new IllegalStateException(e.getCause());
                }
            }
        } finally {
            pool.shutdownNow();
        }
        return new Result(a.name(), b.name(), w.get(), d.get(), l.get());
    }

    /** 0..max random legal moves that do not end the game. */
    static int[] opening(String gameName, Random rng, int max) {
        var g = Game.create(gameName);
        int n = max <= 0 ? 0 : rng.nextInt(max + 1);
        List<Integer> moves = new ArrayList<>();
        int[] buf = new int[g.actions()];
        for (int i = 0; i < n; i++) {
            int k = g.legalMoves(buf);
            int m = buf[rng.nextInt(k)];
            var probe = g.copy();
            probe.play(m);
            if (probe.isOver()) break;
            g.play(m);
            moves.add(m);
        }
        return moves.stream().mapToInt(Integer::intValue).toArray();
    }
}
