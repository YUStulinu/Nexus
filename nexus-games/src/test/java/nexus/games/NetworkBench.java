package nexus.games;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import nexus.games.net.GnetNetwork;
import nexus.games.rules.Game;
import nexus.games.search.Mcts;
import org.junit.jupiter.api.Test;

/** Speed of the network and the search (run with -Dbench=true). */
class NetworkBench {
    @Test
    void bench() throws Exception {
        if (!Boolean.getBoolean("bench")) return;
        for (var game : List.of("connect4", "gomoku")) {
            var file = GambitReferenceTest.MODELS.resolve(game + ".gnet");
            if (!Files.exists(file)) continue;
            var net = GnetNetwork.load(file);
            var g = Game.fromMoves(game, game.equals("connect4") ? "4453" : "e5 d4");
            float[] logits = new float[g.actions()];
            for (int i = 0; i < 3000; i++) net.evaluate(g, logits);
            int n = 3000;
            long t0 = System.nanoTime();
            for (int i = 0; i < n; i++) net.evaluate(g, logits);
            double single = n / ((System.nanoTime() - t0) / 1e9);
            var batch = new ArrayList<Game>();
            for (int i = 0; i < 256; i++) batch.add(g);
            float[] lg = new float[256 * g.actions()], vs = new float[256];
            for (int i = 0; i < 20; i++) net.evaluateBatch(batch, lg, vs);
            t0 = System.nanoTime();
            for (int i = 0; i < 40; i++) net.evaluateBatch(batch, lg, vs);
            double par = 40 * 256 / ((System.nanoTime() - t0) / 1e9);
            Mcts.Evaluator ev = net::evaluateBatch;
            var m = new Mcts(g, Mcts.Config.PLAY, 1);
            m.search(ev, 2000, 16, null, null, 0);
            m.reset(g);
            t0 = System.nanoTime();
            m.search(ev, 6400, 16, null, null, 0);
            double sims = 6400 / ((System.nanoTime() - t0) / 1e9);
            System.out.printf("%s %s: %.0f positions/s on one thread, %.0f positions/s batched on %d threads, MCTS (batches of 16) %.0f simulations/s%n",
                              game, net.spec(), single, par, Runtime.getRuntime().availableProcessors(), sims);
        }
    }
}
