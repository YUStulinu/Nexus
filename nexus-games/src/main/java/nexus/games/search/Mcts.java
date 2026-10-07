package nexus.games.search;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import nexus.games.rules.Game;

/**
 * Monte Carlo tree search with PUCT selection, as in AlphaZero - a port of Gambit's
 * {@code SearchTree}, with the same constants and tie-breaking, so with the same network it
 * spends its simulations exactly as Gambit does.
 *
 * <ul>
 *   <li><b>PUCT:</b> a child's score is Q + c(N) * P * sqrt(N) / (1 + n), with
 *       c(N) = 1.25 + log((N + 19652 + 1) / 19652).</li>
 *   <li><b>First-play urgency:</b> an unvisited child is worth the parent's value minus
 *       0.25 * sqrt(prior mass already visited) - siblings of good moves are tried first.</li>
 *   <li><b>Virtual loss:</b> several leaves can be selected before any is evaluated (each pending
 *       visit counts as a loss), so the network evaluates them in one parallel batch.</li>
 *   <li><b>Tree reuse:</b> after a move, the subtree below it is kept.</li>
 * </ul>
 *
 * Nodes are parallel primitive arrays, children of a node contiguous; nothing is allocated per
 * simulation besides the copy of the root position the walk plays its moves on.
 */
public final class Mcts {
    public record Config(float cpuctInit, float cpuctBase, float cpuctFactor, float fpuReduction, float noiseFraction, float dirichletAlpha) {
        public static final Config PLAY = new Config(1.25f, 19652f, 1f, 0.25f, 0f, 1f);
    }

    /** Evaluates positions: policy logits (actions() per position) and values for the side to move. */
    public interface Evaluator {
        void evaluate(List<Game> positions, float[] logits, float[] values);
    }

    /** One root move: visits, network prior, value (for the player making it), and its share of the visits. */
    public record MoveStat(int action, int visits, float prior, float q, double share) {
    }

    /** A snapshot of the search, for display. */
    public record Report(int simulations, float rootValue, List<MoveStat> moves, List<Integer> principalVariation, double elapsedMs) {
        public MoveStat best() {
            return moves.stream().max((a, b) -> a.visits != b.visits ? Integer.compare(a.visits, b.visits)
                                                                     : Float.compare(a.prior, b.prior)).orElse(null);
        }
    }

    private static final int PENDING = 1, EXPANDED = 2, TERMINAL = 4, TERMINAL_DRAW = 8;

    private final Config cfg;
    private final Random rng;
    private int[] parent, firstChild, visits, virtual;
    private float[] prior, valueSum;
    private short[] action;
    private short[] childCount;
    private byte[] flags;
    private int count;
    private Game root;
    private final int[] moves;
    private final float[] probs;

    public Mcts(Game initial, Config cfg, long seed) {
        this.cfg = cfg;
        this.rng = new Random(seed);
        moves = new int[initial.actions()];
        probs = new float[initial.actions()];
        allocateArrays(4096);
        reset(initial);
    }

    private void allocateArrays(int n) {
        parent = new int[n];
        firstChild = new int[n];
        visits = new int[n];
        virtual = new int[n];
        prior = new float[n];
        valueSum = new float[n];
        action = new short[n];
        childCount = new short[n];
        flags = new byte[n];
    }

    private void grow(int need) {
        if (need <= parent.length) return;
        int n = Math.max(parent.length * 2, need);
        parent = Arrays.copyOf(parent, n);
        firstChild = Arrays.copyOf(firstChild, n);
        visits = Arrays.copyOf(visits, n);
        virtual = Arrays.copyOf(virtual, n);
        prior = Arrays.copyOf(prior, n);
        valueSum = Arrays.copyOf(valueSum, n);
        action = Arrays.copyOf(action, n);
        childCount = Arrays.copyOf(childCount, n);
        flags = Arrays.copyOf(flags, n);
    }

    public void reset(Game state) {
        root = state.copy();
        count = 1;
        clearNode(0, -1, 0f, (short) -1);
    }

    private void clearNode(int i, int par, float p, short a) {
        parent[i] = par;
        firstChild[i] = -1;
        visits[i] = 0;
        virtual[i] = 0;
        prior[i] = p;
        valueSum[i] = 0;
        action[i] = a;
        childCount[i] = 0;
        flags[i] = 0;
    }

    public Game rootState() {
        return root.copy();
    }

    public int rootVisits() {
        return visits[0];
    }

    public int nodeCount() {
        return count;
    }

    private float cpuct(int n) {
        return cfg.cpuctInit + cfg.cpuctFactor * (float) Math.log((n + cfg.cpuctBase + 1f) / cfg.cpuctBase);
    }

    private int selectChild(int p) {
        int first = firstChild[p], n = childCount[p];
        int parentN = visits[p] + virtual[p];
        float sqrtN = (float) Math.sqrt(Math.max(parentN, 1));
        float c = cpuct(parentN) * sqrtN;
        float parentQ = visits[p] > 0 ? -valueSum[p] / visits[p] : 0f;
        float visitedPrior = 0f;
        for (int i = 0; i < n; i++) if (visits[first + i] + virtual[first + i] > 0) visitedPrior += prior[first + i];
        float fpu = parentQ - cfg.fpuReduction * (float) Math.sqrt(visitedPrior);
        int best = first;
        float bestScore = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < n; i++) {
            int ch = first + i;
            int v = visits[ch] + virtual[ch];
            float q = v > 0 ? (valueSum[ch] - virtual[ch]) / v : fpu;
            float score = q + c * prior[ch] / (1 + v);
            if (score > bestScore) {
                bestScore = score;
                best = ch;
            }
        }
        return best;
    }

    /** A leaf waiting for the network. */
    private record Leaf(int node, Game state) {
    }

    /** Walks to a leaf; returns null for a terminal leaf (already backed up) or a collision. */
    private Leaf selectLeaf() {
        var state = root.copy();
        int node = 0;
        while ((flags[node] & EXPANDED) != 0) {
            node = selectChild(node);
            state.play(action[node]);
        }
        if ((flags[node] & TERMINAL) == 0 && state.isOver()) {
            flags[node] |= TERMINAL;
            if (state.outcome() == Game.Outcome.DRAW) flags[node] |= TERMINAL_DRAW;
        }
        if ((flags[node] & TERMINAL) != 0) {
            backup(node, (flags[node] & TERMINAL_DRAW) != 0 ? 0f : -1f);
            return null;
        }
        if ((flags[node] & PENDING) != 0) return null;
        flags[node] |= PENDING;
        for (int n = node; n >= 0; n = parent[n]) virtual[n]++;
        return new Leaf(node, state);
    }

    private void expand(int leaf, Game state, float[] logits, int offset, float value) {
        int n = state.legalMoves(moves);
        grow(count + n);
        int first = count;
        count += n;
        float max = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < n; i++) max = Math.max(max, logits[offset + moves[i]]);
        float sum = 0f;
        for (int i = 0; i < n; i++) sum += probs[i] = (float) Math.exp(logits[offset + moves[i]] - max);
        for (int i = 0; i < n; i++) clearNode(first + i, leaf, probs[i] / sum, (short) moves[i]);
        firstChild[leaf] = first;
        childCount[leaf] = (short) n;
        flags[leaf] = (byte) ((flags[leaf] & ~PENDING) | EXPANDED);
        for (int k = leaf; k >= 0; k = parent[k]) virtual[k]--;
        backup(leaf, value);
    }

    private void backup(int node, float value) {
        float v = -value;           // a node's value sum is from the point of view of the player who moved into it
        for (int n = node; n >= 0; n = parent[n]) {
            visits[n]++;
            valueSum[n] += v;
            v = -v;
        }
    }

    /**
     * Runs {@code simulations} more simulations, evaluating up to {@code batch} leaves at a time.
     *
     * @param report    receives a snapshot about every {@code reportEveryMs} (may be null)
     * @param cancelled stops the search early when true
     */
    public Report search(Evaluator net, int simulations, int batch, BooleanSupplier cancelled, Consumer<Report> report, long reportEveryMs) {
        long start = System.nanoTime(), lastReport = start;
        if (root.isOver()) return report(start);
        int target = visits[0] + simulations;
        var leaves = new ArrayList<Leaf>(batch);
        var positions = new ArrayList<Game>(batch);
        float[] logits = new float[batch * root.actions()], values = new float[batch];
        boolean noiseAdded = cfg.noiseFraction <= 0;
        while (visits[0] < target) {
            if (cancelled != null && cancelled.getAsBoolean()) break;
            leaves.clear();
            int want = Math.min(batch, target - visits[0]);
            for (int tries = 0; leaves.size() < want && tries < want * 4; tries++) {
                var l = selectLeaf();
                if (l != null) leaves.add(l);
                else if (visits[0] >= target) break;
            }
            if (leaves.isEmpty()) continue;
            positions.clear();
            for (var l : leaves) positions.add(l.state);
            net.evaluate(positions, logits, values);
            for (int i = 0; i < leaves.size(); i++) expand(leaves.get(i).node, leaves.get(i).state, logits, i * root.actions(), values[i]);
            if (!noiseAdded && (flags[0] & EXPANDED) != 0) {
                addRootNoise();
                noiseAdded = true;
            }
            long now = System.nanoTime();
            if (report != null && now - lastReport > reportEveryMs * 1_000_000L) {
                lastReport = now;
                report.accept(report(start));
            }
        }
        return report(start);
    }

    private void addRootNoise() {
        int n = childCount[0], first = firstChild[0];
        float[] noise = new float[n];
        float sum = 0;
        for (int i = 0; i < n; i++) sum += noise[i] = gamma(cfg.dirichletAlpha);
        for (int i = 0; i < n; i++) prior[first + i] = (1 - cfg.noiseFraction) * prior[first + i] + cfg.noiseFraction * noise[i] / Math.max(sum, 1e-20f);
    }

    private float gamma(float alpha) {
        if (alpha < 1f) return gamma(alpha + 1f) * (float) Math.pow(rng.nextDouble(), 1.0 / alpha);
        float d = alpha - 1f / 3f, c = 1f / (float) Math.sqrt(9f * d);
        while (true) {
            float x, v;
            do {
                x = (float) rng.nextGaussian();
                v = 1f + c * x;
            } while (v <= 0f);
            v = v * v * v;
            float u = (float) rng.nextDouble();
            if (u < 1f - 0.0331f * x * x * x * x) return d * v;
            if (Math.log(u) < 0.5f * x * x + d * (1f - v + Math.log(v))) return d * v;
        }
    }

    // ---- results ---------------------------------------------------------------------------------------------

    public float rootValue() {
        return visits[0] > 0 ? -valueSum[0] / visits[0] : 0f;
    }

    private Report report(long startNanos) {
        var stats = new ArrayList<MoveStat>();
        if ((flags[0] & EXPANDED) != 0) {
            int first = firstChild[0], n = childCount[0];
            int total = 0;
            for (int i = 0; i < n; i++) total += visits[first + i];
            for (int i = 0; i < n; i++) {
                int ch = first + i;
                stats.add(new MoveStat(action[ch], visits[ch], prior[ch], visits[ch] > 0 ? valueSum[ch] / visits[ch] : Float.NaN,
                                       total > 0 ? (double) visits[ch] / total : 0));
            }
        }
        return new Report(visits[0], rootValue(), stats, principalVariation(12), (System.nanoTime() - startNanos) / 1e6);
    }

    /** The move to play: most visits (ties: higher value, then higher prior), or sampled by visits^(1/T). */
    public int chooseMove(float temperature) {
        if ((flags[0] & EXPANDED) == 0) throw new IllegalStateException("search the position first");
        int first = firstChild[0], n = childCount[0];
        if (temperature <= 1e-3f) {
            int best = first;
            for (int i = 1; i < n; i++) {
                int a = first + i, b = best;
                boolean better = visits[a] > visits[b]
                                 || visits[a] == visits[b] && visits[a] > 0 && valueSum[a] / visits[a] > valueSum[b] / visits[b]
                                 || visits[a] == visits[b] && visits[a] == 0 && prior[a] > prior[b];
                if (better) best = a;
            }
            return action[best];
        }
        double total = 0;
        double[] w = new double[n];
        for (int i = 0; i < n; i++) total += w[i] = Math.pow(visits[first + i], 1.0 / temperature);
        if (total <= 0) return action[first + rng.nextInt(n)];
        double x = rng.nextDouble() * total;
        for (int i = 0; i < n; i++) {
            x -= w[i];
            if (x <= 0) return action[first + i];
        }
        return action[first + n - 1];
    }

    public List<Integer> principalVariation(int max) {
        var pv = new ArrayList<Integer>();
        int node = 0;
        while (pv.size() < max && (flags[node] & EXPANDED) != 0) {
            int best = -1;
            for (int i = 0; i < childCount[node]; i++) {
                int ch = firstChild[node] + i;
                if (best < 0 || visits[ch] > visits[best]) best = ch;
            }
            if (best < 0 || visits[best] == 0) break;
            pv.add((int) action[best]);
            node = best;
        }
        return pv;
    }

    /** Plays {@code move} at the root, keeping its subtree (compacted to the front of the arrays). */
    public void advance(int move) {
        var next = root.copy();
        next.play(move);
        int child = -1;
        if ((flags[0] & EXPANDED) != 0)
            for (int i = 0; i < childCount[0]; i++) if (action[firstChild[0] + i] == move) child = firstChild[0] + i;
        if (child < 0 || virtual[child] != 0) {
            reset(next);
            return;
        }
        // Breadth-first copy of the subtree into fresh arrays.
        var order = new int[count];
        var newIndex = new int[count];
        int n = 1;
        order[0] = child;
        newIndex[child] = 0;
        for (int i = 0; i < n; i++) {
            int src = order[i];
            if ((flags[src] & EXPANDED) == 0) continue;
            for (int k = 0; k < childCount[src]; k++) {
                order[n] = firstChild[src] + k;
                newIndex[firstChild[src] + k] = n;
                n++;
            }
        }
        int cap = Math.max(4096, Integer.highestOneBit(Math.max(n, 1)) * 2);
        int[] par = new int[cap], fc = new int[cap], vis = new int[cap], vir = new int[cap];
        float[] pr = new float[cap], vs = new float[cap];
        short[] ac = new short[cap], cc = new short[cap];
        byte[] fl = new byte[cap];
        for (int i = 0; i < n; i++) {
            int src = order[i];
            par[i] = i == 0 ? -1 : newIndex[parent[src]];
            fc[i] = (flags[src] & EXPANDED) != 0 ? newIndex[firstChild[src]] : -1;
            vis[i] = visits[src];
            vir[i] = 0;
            pr[i] = prior[src];
            vs[i] = valueSum[src];
            ac[i] = action[src];
            cc[i] = childCount[src];
            fl[i] = (byte) (flags[src] & ~PENDING);
        }
        parent = par;
        firstChild = fc;
        visits = vis;
        virtual = vir;
        prior = pr;
        valueSum = vs;
        action = ac;
        childCount = cc;
        flags = fl;
        count = n;
        root = next;
    }
}
