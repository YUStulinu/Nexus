package nexus.games.nodes;

import static nexus.core.graph.PortSpec.in;
import static nexus.core.graph.PortSpec.optionalIn;
import static nexus.core.graph.PortSpec.out;
import static nexus.core.types.DataTypes.TABLE;
import static nexus.core.types.DataTypes.TEXT;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import nexus.core.exec.NodeContext;
import nexus.core.graph.NodeDefinition;
import nexus.core.graph.ParamSpec;
import nexus.core.nodes.FileKeys;
import nexus.core.registry.NodeLibrary;
import nexus.core.types.DataType;
import nexus.core.types.DataTypes;
import nexus.core.types.Table;
import nexus.engines.EngineManager;
import nexus.games.net.GnetNetwork;
import nexus.games.play.Arena;
import nexus.games.play.Player;
import nexus.games.rules.ConnectFour;
import nexus.games.rules.Game;
import nexus.games.search.ConnectFourSolver;
import nexus.games.train.GambitTraining;

/**
 * Gambit's AlphaZero as nodes: load a network, play against it on a live board, pit two players
 * in an arena, train new networks with self-play (on the GPU, under the memory broker), and ask the
 * perfect solver about a Connect Four position.
 */
public final class GameNodes implements NodeLibrary {
    public static final String CATEGORY = "Games (Gambit)";
    static final List<String> GAMES = List.of("connect4", "gomoku");

    /** A loaded network and where it came from. */
    public record Model(GnetNetwork net, String label, Path file) {
        public String game() {
            return net.spec().game();
        }

        @Override
        public String toString() {
            return label;
        }
    }

    public static final DataType GAME_MODEL = DataTypes.register(new DataType("game-model", "Game network", "#e599f7", Model.class));

    static {
        DataTypes.registerConversion(GAME_MODEL, TEXT, v -> ((Model) v).label() + " (" + ((Model) v).net().spec() + ")");
    }

    public GameNodes() {
    }

    @Override
    public String name() {
        return CATEGORY;
    }

    @Override
    public List<NodeDefinition> definitions() {
        return List.of(model(), play(), arena(), train(), solve());
    }

    // ---- networks --------------------------------------------------------------------------------------------

    private static final Map<String, Model> LOADED = new ConcurrentHashMap<>();

    /** The network shipped with Gambit for {@code game}. */
    public static Path defaultModel(String game) {
        return EngineManager.instance().project("Gambit").resolve("models").resolve(game + ".gnet");
    }

    public static Model load(Path file, String label) throws IOException {
        String key = file.toAbsolutePath() + "|" + FileKeys.of(file.toString());
        var m = LOADED.get(key);
        if (m != null) return m;
        if (!Files.isRegularFile(file)) throw new IOException("network not found: " + file);
        m = new Model(GnetNetwork.load(file), label, file);
        LOADED.put(key, m);
        return m;
    }

    static Model modelFor(NodeContext ctx, String input, String game) throws IOException {
        if (ctx.isConnected(input) && ctx.input(input) instanceof Model m) {
            if (!m.game().equals(game)) throw new IllegalArgumentException("the network plays " + m.game() + ", the node is set to " + game);
            return m;
        }
        return load(defaultModel(game), "Gambit " + game);
    }

    static Table info(Model m) {
        var s = m.net().spec();
        return Table.builder("measure", "value")
                .row("game", s.game())
                .row("network", s.blocks() + " residual blocks x " + s.channels() + " channels")
                .row("parameters", (double) m.net().parameters())
                .row("board", s.width() + " x " + s.height())
                .row("file", m.file().getFileName().toString()).build();
    }

    static NodeDefinition model() {
        return NodeDefinition.builder("game.model", "Game network", CATEGORY)
                .description("Loads an AlphaZero network trained by Gambit (.gnet): Gambit's own, or any generation of a training run.")
                .output(out("model", "network", GAME_MODEL)).output(out("info", "info", TABLE)).view("table")
                .param(ParamSpec.choice("game", "Game", "connect4", GAMES, "Which game"))
                .param(ParamSpec.file("file", "Network file", "A .gnet file (empty: the network shipped with Gambit)"))
                .contentKey(p -> String.valueOf(p.get("game")) + "|" + FileKeys.of(String.valueOf(p.getOrDefault("file", ""))))
                .behavior(() -> ctx -> {
                    String file = ctx.paramText("file");
                    String game = ctx.paramText("game");
                    var path = file.isBlank() ? defaultModel(game) : Path.of(file);
                    var m = load(path, file.isBlank() ? "Gambit " + game : path.getFileName().toString().replace(".gnet", ""));
                    if (!m.game().equals(game)) throw new IllegalArgumentException(path.getFileName() + " plays " + m.game() + ", not " + game);
                    ctx.output("model", m);
                    ctx.output("info", info(m));
                });
    }

    // ---- the board -------------------------------------------------------------------------------------------

    static NodeDefinition play() {
        return NodeDefinition.builder("game.play", "Play against AlphaZero", CATEGORY)
                .description("A live board: play the network, watch it think (how it spreads its search over the moves), ask for hints.")
                .input(optionalIn("model", "network", GAME_MODEL))
                .output(out("model", "network", GAME_MODEL))
                .param(ParamSpec.choice("game", "Game", "connect4", GAMES, "Which game"))
                .param(ParamSpec.integer("simulations", "Simulations per move", 800, 1, 20000, "How long the AI thinks (1 = the network alone)"))
                .param(ParamSpec.choice("you", "You play", "first", List.of("first", "second", "both sides (analysis)", "watch AI vs AI"),
                                        "Your side"))
                .param(ParamSpec.bool("solver", "Perfect-play check", true, "Connect Four: grade every move with the perfect solver"))
                .view("game-board").notCacheable()
                .behavior(() -> ctx -> {
                    var m = modelFor(ctx, "model", ctx.paramText("game"));
                    ctx.log("loaded " + m.label() + ": " + m.net().spec() + ", " + m.net().parameters() + " parameters");
                    ctx.output("model", m);
                });
    }

    // ---- arena -----------------------------------------------------------------------------------------------

    static final List<String> OPPONENTS = List.of("classic MCTS (random playouts)", "network B", "random moves", "perfect solver (Connect Four)");

    static NodeDefinition arena() {
        return NodeDefinition.builder("game.arena", "Arena", CATEGORY)
                .description("A match between two players, openings played twice with colours swapped: score, Elo difference, likelihood of superiority.")
                .input(in("a", "network A", GAME_MODEL)).input(optionalIn("b", "network B", GAME_MODEL))
                .output(out("result", "result", TABLE)).output(out("summary", "summary", TEXT))
                .param(ParamSpec.integer("simsA", "Simulations A", 200, 1, 5000, "MCTS simulations per move for network A"))
                .param(ParamSpec.choice("opponent", "Opponent", OPPONENTS.getFirst(), OPPONENTS, "Who A plays against"))
                .param(ParamSpec.integer("simsB", "Simulations B", 200, 1, 50000, "Simulations per move of the opponent (MCTS or network B)"))
                .param(ParamSpec.integer("games", "Games", 40, 2, 2000, "Number of games (each opening twice)"))
                .param(ParamSpec.integer("opening", "Random opening moves", 4, 0, 12, "Up to this many random moves start each game"))
                .view("arena")
                .behavior(() -> ctx -> {
                    var a = (Model) ctx.input("a");
                    String game = a.game();
                    var pa = Player.network(a.net(), a.label(), ctx.paramInt("simsA"));
                    String opp = ctx.paramText("opponent");
                    int simsB = ctx.paramInt("simsB");
                    Player.Factory pb;
                    if (opp.startsWith("network")) {
                        if (!(ctx.input("b") instanceof Model b)) throw new IllegalArgumentException("connect network B, or choose another opponent");
                        if (!b.game().equals(game)) throw new IllegalArgumentException("A plays " + game + ", B plays " + b.game());
                        pb = Player.network(b.net(), b.label(), simsB);
                    } else if (opp.startsWith("classic")) pb = Player.rollouts(simsB);
                    else if (opp.startsWith("random")) pb = Player.random();
                    else {
                        if (!game.equals("connect4")) throw new IllegalArgumentException("the perfect solver plays Connect Four only");
                        pb = Player.perfect();
                    }
                    int games = ctx.paramInt("games");
                    ctx.emit("names", List.of(pa.name(), pb.name()));
                    var result = Arena.play(game, pa, pb, games, ctx.paramInt("opening"), 1, ctx::isCancelled, r -> {
                        ctx.emit("arena", r);
                        ctx.progress((double) r.games() / (games + games % 2), r.toString());
                    }, rec -> ctx.emit("game", rec));
                    ctx.checkCancelled();
                    ctx.output("result", Table.builder("measure", "value")
                            .row("A", result.a()).row("B", result.b())
                            .row("wins / draws / losses", result.wins() + " / " + result.draws() + " / " + result.losses())
                            .row("score of A", Math.round(1000 * result.score()) / 10.0)
                            .row("Elo difference", (double) Math.round(result.elo()))
                            .row("95% margin (Elo)", (double) Math.round(result.eloMargin()))
                            .row("likelihood of superiority %", Math.round(1000 * result.los()) / 10.0).build());
                    ctx.output("summary", result.a() + " vs " + result.b() + ": " + result);
                    ctx.log(result.toString());
                });
    }

    // ---- training --------------------------------------------------------------------------------------------

    static NodeDefinition train() {
        return NodeDefinition.builder("game.train", "Train with self-play", CATEGORY)
                .description("AlphaZero training on the GPU with Gambit's trainer: live losses and Elo; pauses when interactive work needs the GPU.")
                .output(out("model", "best network", GAME_MODEL)).output(out("history", "history", TABLE))
                .param(ParamSpec.choice("game", "Game", "connect4", GAMES, "Which game"))
                .param(ParamSpec.text("run", "Run folder", "", "Where the run is kept (empty: ~/.nexus/runs/<game>); an existing run resumes"))
                .param(ParamSpec.integer("generations", "Generations", 10, 1, 500, "Self-play / train / gate cycles"))
                .param(ParamSpec.integer("games", "Games per generation", 1024, 64, 16384, "Self-play games per generation"))
                .param(ParamSpec.integer("parallel", "Parallel games", 1024, 64, 4096, "Games played at once (the GPU batch)"))
                .param(ParamSpec.integer("simulations", "Simulations", 200, 16, 1600, "MCTS simulations for a full-search move"))
                .view("training").notCacheable()
                .behavior(() -> ctx -> {
                    String game = ctx.paramText("game");
                    var mgr = EngineManager.instance();
                    String runText = ctx.paramText("run").strip();
                    if (runText.startsWith("~")) runText = System.getProperty("user.home") + runText.substring(1);
                    Path run = runText.isBlank() ? nexusHome().resolve("runs").resolve(game) : Path.of(runText);
                    var opts = new GambitTraining.Options(GambitTraining.defaultExecutable(mgr.projects()), game, run, ctx.paramInt("generations"),
                                                          ctx.paramInt("games"), ctx.paramInt("parallel"), ctx.paramInt("simulations"));
                    var history = new ArrayList<GambitTraining.Generation>();
                    ctx.log("run folder: " + run + " (" + opts.vramMiB() + " MiB of GPU memory)");
                    var training = new GambitTraining(opts, mgr.broker());
                    var best = training.run(new GambitTraining.Listener() {
                        @Override
                        public void line(String text) {
                            ctx.emit("line", text);
                        }

                        @Override
                        public void generation(GambitTraining.Generation g) {
                            history.add(g);
                            ctx.emit("generation", g);
                            ctx.progress((double) g.generation() / opts.generations(),
                                         String.format(Locale.ROOT, "generation %d, Elo %.0f", g.generation(), g.elo()));
                        }

                        @Override
                        public void state(String text) {
                            ctx.emit("state", text);
                            ctx.log(text);
                        }
                    }, ctx::isCancelled);
                    var t = Table.builder("generation", "policy loss", "value loss", "games/s", "gating", "Elo");
                    for (var g : history)
                        t.row((double) g.generation(), round(g.policyLoss(), 3), round(g.valueLoss(), 3), round(g.gamesPerSecond(), 1),
                              (g.accepted() ? "accepted " : "rejected ") + g.arena(), (double) Math.round(g.elo()));
                    ctx.output("history", t.build());
                    ctx.output("model", load(best, game + " run, generation " + (history.isEmpty() ? 0 : history.getLast().best())));
                });
    }

    static Path nexusHome() {
        String env = System.getenv("NEXUS_HOME");
        return env != null && !env.isBlank() ? Path.of(env) : Path.of(System.getProperty("user.home"), ".nexus");
    }

    static double round(double v, int digits) {
        double f = Math.pow(10, digits);
        return Math.round(v * f) / f;
    }

    // ---- the solver ------------------------------------------------------------------------------------------

    static NodeDefinition solve() {
        return NodeDefinition.builder("game.solve", "Solve Connect Four", CATEGORY)
                .description("The exact value of every move in a Connect Four position, by the perfect solver.")
                .input(optionalIn("moves", "moves", TEXT))
                .output(out("scores", "scores", TABLE)).output(out("verdict", "verdict", TEXT))
                .param(ParamSpec.text("moves", "Moves", "4453", "Columns played so far, 1-7 (used when nothing is connected)"))
                .view("table")
                .behavior(() -> ctx -> {
                    String moves = ctx.isConnected("moves") ? ctx.inputText("moves") : ctx.paramText("moves");
                    var g = (ConnectFour) Game.fromMoves("connect4", moves.replaceAll("[^1-7]", ""));
                    if (g.isOver()) throw new IllegalArgumentException("the game is already over");
                    var solver = new ConnectFourSolver();
                    solver.setCancel(ctx::isCancelled);
                    long t0 = System.nanoTime();
                    var scores = solver.scoreMoves(g);
                    double ms = (System.nanoTime() - t0) / 1e6;
                    var t = Table.builder("column", "score", "result");
                    int best = Integer.MIN_VALUE;
                    for (var s : scores) if (s != null) best = Math.max(best, s);
                    for (int c = 0; c < scores.length; c++)
                        if (scores[c] != null)
                            t.row(String.valueOf(c + 1) + (scores[c] == best ? " ★" : ""), (double) scores[c],
                                  ConnectFourSolver.describe(scores[c], g.ply()));
                    ctx.output("scores", t.build());
                    int col = 0;
                    for (int c = 0; c < scores.length; c++) if (scores[c] != null && scores[c] == best) {
                        col = c;
                        break;
                    }
                    String who = g.sideToMove() == 0 ? "First player" : "Second player";
                    ctx.output("verdict", who + " to move " + ConnectFourSolver.describe(best, g.ply()) + " with perfect play; best: column " + (col + 1));
                    ctx.log(String.format(Locale.ROOT, "solved in %.0f ms", ms));
                });
    }
}
