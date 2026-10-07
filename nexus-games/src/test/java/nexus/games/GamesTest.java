package nexus.games;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import nexus.games.net.GnetNetwork;
import nexus.games.play.Arena;
import nexus.games.play.Player;
import nexus.games.rules.ConnectFour;
import nexus.games.rules.Game;
import nexus.games.search.ConnectFourSolver;
import nexus.games.search.Mcts;
import org.junit.jupiter.api.Test;

class GamesTest {
    @Test
    void connectFourRules() {
        var g = (ConnectFour) Game.fromMoves("connect4", "121212");
        assertFalse(g.isOver());
        assertTrue(g.isWinningMove(0));
        g.play(0);
        assertEquals(Game.Outcome.LOSS, g.outcome(), "four in column 1");
        // diagonal
        var d = Game.fromMoves("connect4", "12233434544");
        assertEquals(Game.Outcome.LOSS, d.outcome());
        // a full column is illegal
        var f = Game.fromMoves("connect4", "111111");
        assertFalse(f.isLegal(0));
        assertEquals(2, f.cellAt(0, 0));
        assertEquals(1, f.cellAt(0, 5));
    }

    @Test
    void gomokuRules() {
        var g = Game.fromMoves("gomoku", "a1 a2 b1 b2 c1 c2 d1 d2");
        assertFalse(g.isOver());
        g.play(g.parseAction("e1"));
        assertEquals(Game.Outcome.LOSS, g.outcome());
        assertEquals("e1", g.actionName(g.parseAction("e1")));
        var diag = Game.fromMoves("gomoku", "a1 i9 b2 i8 c3 i7 d4 i6 e5");
        assertEquals(Game.Outcome.LOSS, diag.outcome());
    }

    @Test
    void solverAgreesWithGambitsSolvedPositions() throws Exception {
        var solver = new ConnectFourSolver();
        int checked = 0;
        long t0 = System.nanoTime();
        try (var r = new BufferedReader(new InputStreamReader(getClass().getResourceAsStream("/connect4-solved.txt"), StandardCharsets.UTF_8))) {
            // Every 5th position by default (all 1000 with -Dsolver.all=true: a few minutes).
            int step = Boolean.getBoolean("solver.all") ? 1 : 5, i = 0;
            for (String line; (line = r.readLine()) != null; ) {
                if (i++ % step != 0) continue;
                var f = line.split(" ");
                var g = (ConnectFour) Game.fromMoves("connect4", f[0]);
                var scores = solver.scoreMoves(g);
                for (int c = 0; c < 7; c++) {
                    if (f[c + 1].equals("-")) assertEquals(null, scores[c]);
                    else assertEquals(Integer.parseInt(f[c + 1]), scores[c], line + " column " + (c + 1));
                }
                checked++;
            }
        }
        System.out.printf("solver: %d positions x 7 moves in %.1f s%n", checked, (System.nanoTime() - t0) / 1e9);
        assertEquals(Boolean.getBoolean("solver.all") ? 1000 : 200, checked);
        assertEquals("draw", ConnectFourSolver.describe(0, 10));
        assertEquals("wins with its 21st move", ConnectFourSolver.describe(1, 0));
    }

    @Test
    void treeReuseKeepsTheSubtree() throws Exception {
        var file = GambitReferenceTest.MODELS.resolve("connect4.gnet");
        assumeTrue(Files.exists(file));
        var net = GnetNetwork.load(file);
        var m = new Mcts(Game.create("connect4"), Mcts.Config.PLAY, 1);
        Mcts.Evaluator ev = net::evaluateBatch;
        m.search(ev, 800, 8, null, null, 0);
        int move = m.chooseMove(0);
        var before = m.search(ev, 0, 8, null, null, 0).moves().stream().filter(s -> s.action() == move).findFirst().orElseThrow().visits();
        m.advance(move);
        assertEquals(before, m.rootVisits(), "the chosen child's visits carry over");
        assertEquals(1, m.rootState().ply());
        var r = m.search(ev, 200, 8, null, null, 0);
        assertEquals(before + 200, r.simulations());
    }

    @Test
    void theTrainedNetworkBeatsRandomAndClassicMcts() throws Exception {
        var file = GambitReferenceTest.MODELS.resolve("connect4.gnet");
        assumeTrue(Files.exists(file));
        var net = GnetNetwork.load(file);
        var vsRandom = Arena.play("connect4", Player.network(net, "gambit", 1), Player.random(), 20, 2, 7, null, null, null);
        System.out.println("network alone vs random: " + vsRandom);
        assertTrue(vsRandom.score() >= 0.9, vsRandom.toString());
        var vsMcts = Arena.play("connect4", Player.network(net, "gambit", 50), Player.rollouts(200), 20, 4, 7, null, null, null);
        System.out.println("network + MCTS 50 vs classic MCTS 200: " + vsMcts);
        assertTrue(vsMcts.score() >= 0.7, vsMcts.toString());
    }
}
