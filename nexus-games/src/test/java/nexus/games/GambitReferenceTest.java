package nexus.games;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import nexus.games.net.GnetNetwork;
import nexus.games.rules.Game;
import nexus.games.search.Mcts;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The Java network and search against Gambit's C# implementation (outputs recorded by
 * tools/reference/gambit): same logits and value to float rounding, and - the search being
 * deterministic with one leaf at a time - the same visit counts after 400 simulations.
 */
class GambitReferenceTest {
    static final Path MODELS = Path.of("..", "..", "Gambit", "models");

    @ParameterizedTest
    @ValueSource(strings = {"connect4", "gomoku"})
    void networkAndSearchMatchGambit(String game) throws Exception {
        var file = MODELS.resolve(game + ".gnet");
        assumeTrue(Files.exists(file), "Gambit models not found");
        var net = GnetNetwork.load(file);
        var ref = new ObjectMapper().readTree(getClass().getResourceAsStream("/gambit-reference.json")).get(game);
        for (var pos : ref) {
            var g = Game.fromMoves(game, pos.get("moves").asText());
            float[] logits = new float[g.actions()];
            float v = net.evaluate(g, logits);
            float[] expected = new float[g.actions()];
            for (int i = 0; i < expected.length; i++) expected[i] = (float) pos.get("logits").get(i).asDouble();
            assertArrayEquals(expected, logits, 2e-4f, "logits of " + pos.get("moves"));
            assertEquals(pos.get("value").asDouble(), v, 2e-5, "value of " + pos.get("moves"));

            var mcts = new Mcts(g, Mcts.Config.PLAY, 1);
            Mcts.Evaluator ev = (ps, lg, vs) -> net.evaluateBatch(ps, lg, vs);
            var report = mcts.search(ev, 400, 1, null, null, 0);
            int[] visits = new int[g.actions()];
            for (var m : report.moves()) visits[m.action()] = m.visits();
            int[] expectedVisits = new int[g.actions()];
            for (int i = 0; i < expectedVisits.length; i++) expectedVisits[i] = pos.get("visits400").get(i).asInt();
            assertArrayEquals(expectedVisits, visits, "visits after 400 simulations from " + pos.get("moves"));
            assertEquals(pos.get("rootValue400").asDouble(), mcts.rootValue(), 1e-3);
        }
    }
}
