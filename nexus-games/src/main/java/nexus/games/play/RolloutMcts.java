package nexus.games.play;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import nexus.games.rules.Game;

/**
 * Classic Monte Carlo tree search: UCT selection (c = sqrt 2), one random playout to the end of the
 * game per simulation. It knows nothing but the rules - the yardstick AlphaZero is measured
 * against (Gambit anchors Elo 0 at 200 simulations).
 */
final class RolloutMcts implements Player {
    private final int simulations;
    private final Random rng;

    RolloutMcts(int simulations, long seed) {
        this.simulations = simulations;
        this.rng = new Random(seed);
    }

    @Override
    public String name() {
        return "classic MCTS " + simulations;
    }

    private static final class Node {
        final int move;
        final Node parent;
        List<Node> children;
        int[] untried;
        int untriedCount;
        int visits;
        double wins;       // for the player who moved into this node

        Node(int move, Node parent) {
            this.move = move;
            this.parent = parent;
        }
    }

    @Override
    public int chooseMove(Game position) {
        var root = new Node(-1, null);
        int[] buf = new int[position.actions()];
        for (int s = 0; s < simulations; s++) {
            var g = position.copy();
            var node = root;
            // selection
            while (node.untried != null && node.untriedCount == 0 && !node.children.isEmpty()) {
                Node best = null;
                double bestScore = Double.NEGATIVE_INFINITY, logN = Math.log(node.visits);
                for (var ch : node.children) {
                    double score = ch.wins / ch.visits + Math.sqrt(2 * logN / ch.visits);
                    if (score > bestScore) {
                        bestScore = score;
                        best = ch;
                    }
                }
                node = best;
                g.play(node.move);
            }
            // expansion
            if (!g.isOver()) {
                if (node.untried == null) {
                    int n = g.legalMoves(buf);
                    node.untried = java.util.Arrays.copyOf(buf, n);
                    node.untriedCount = n;
                    node.children = new ArrayList<>(n);
                }
                if (node.untriedCount > 0) {
                    int k = rng.nextInt(node.untriedCount);
                    int move = node.untried[k];
                    node.untried[k] = node.untried[--node.untriedCount];
                    var child = new Node(move, node);
                    node.children.add(child);
                    node = child;
                    g.play(move);
                }
            }
            // random playout
            int mover = (g.ply() + 1) & 1;          // the player who made the last move, i.e. moved into `node`
            while (!g.isOver()) {
                int n = g.legalMoves(buf);
                g.play(buf[rng.nextInt(n)]);
            }
            // The last mover won unless it is a draw.
            double resultForMover;
            if (g.outcome() == Game.Outcome.DRAW) resultForMover = 0.5;
            else resultForMover = ((g.ply() + 1) & 1) == mover ? 1 : 0;
            for (var n = node; n != null; n = n.parent) {
                n.visits++;
                n.wins += resultForMover;
                resultForMover = 1 - resultForMover;
            }
        }
        Node best = null;
        for (var ch : root.children) if (best == null || ch.visits > best.visits) best = ch;
        return best.move;
    }
}
