package nexus.app.views;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import javafx.application.Platform;
import javafx.geometry.VPos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.input.MouseEvent;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;
import nexus.core.exec.NodeStatus;
import nexus.games.nodes.GameNodes;
import nexus.games.play.Arena;
import nexus.games.rules.ConnectFour;
import nexus.games.rules.Game;
import nexus.games.search.ConnectFourSolver;
import nexus.games.search.Mcts;
import nexus.games.train.GambitTraining;

/** Views for the game nodes: a playable board, a live arena score, live training charts. */
public final class GameViews {
    private GameViews() {
    }

    public static void register() {
        Bodies.register("game-board", BoardBody::new);
        Bodies.register("arena", ArenaBody::new);
        Bodies.register("training", TrainingBody::new);
    }

    static final Color BG = Color.web("#181a20"), PANEL = Color.web("#1d2027"), GRID = Color.web("#2b3040"), MUTED = Color.web("#8b93a7"),
            TEXT = Color.web("#d5d9e3"), P1 = Color.web("#ff6b6b"), P2 = Color.web("#ffd43b"), SEARCH = Color.web("#4dabf7"),
            WIN = Color.web("#40c057"), DRAW = Color.web("#adb5bd"), LOSS = Color.web("#fa5252");

    // ---- the board -------------------------------------------------------------------------------------------

    /**
     * Play against the network. While the AI thinks, every move shows the share of the search it
     * receives (AlphaZero spends its simulations on the moves it believes in); the bar below is the
     * AI's own estimate of its winning chances. For Connect Four the perfect solver grades each move.
     */
    static final class BoardBody extends NodeBody {
        private final Canvas canvas = new Canvas();
        private final Label status = new Label("loading the network…"), detail = new Label(), solverLine = new Label();
        private final Button newGame = new Button("New game"), undo = new Button("Undo"), hint = new Button("Hint"), aiMove = new Button("AI move");
        private GameNodes.Model model;
        private Game game;
        private final List<Integer> history = new ArrayList<>();
        private Mcts mcts;
        private volatile Mcts.Report report;
        private final AtomicLong epoch = new AtomicLong();      // bumps on every new game / undo: stale searches stop
        private volatile boolean thinking;
        private int hover = -1;
        private Integer[] solverScores;
        private String lastVerdict = "";
        private volatile int searchSide;
        private float barValue = Float.NaN;
        private int barSide;

        BoardBody() {
            status.setStyle("-fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 12px;");
            detail.setStyle("-fx-text-fill: #a5adbf; -fx-font-size: 11px;");
            detail.setWrapText(true);
            solverLine.setStyle("-fx-text-fill: #a5adbf; -fx-font-size: 11px;");
            solverLine.setWrapText(true);
            for (var b : List.of(newGame, undo, hint, aiMove)) b.setStyle("-fx-font-size: 11px; -fx-padding: 2 8;");
            newGame.setOnAction(e -> newGame());
            undo.setOnAction(e -> undo());
            hint.setOnAction(e -> think(false));
            aiMove.setOnAction(e -> think(true));
            canvas.setOnMouseMoved(this::hover);
            canvas.setOnMouseExited(e -> {
                hover = -1;
                draw();
            });
            canvas.setOnMouseClicked(this::click);
            getChildren().addAll(canvas, status, detail, solverLine, newGame, undo, hint, aiMove);
        }

        @Override
        public void bind(BodyContext c) {
            super.bind(c);
            // The node's parameters may be applied just after the view is created: look a moment later.
            Platform.runLater(this::newGame);
        }

        /** The network for the node's current game: the one wired in (after a run), else Gambit's own. */
        private void loadModelThen(Runnable then) {
            String wanted = String.valueOf(context.param("game"));
            Thread.ofVirtual().start(() -> {
                try {
                    var m = context.lastOutputs().get("model") instanceof GameNodes.Model lm && lm.game().equals(wanted) ? lm
                            : GameNodes.load(GameNodes.defaultModel(wanted), "Gambit " + wanted);
                    Platform.runLater(() -> {
                        model = m;
                        then.run();
                    });
                } catch (Exception ex) {
                    Platform.runLater(() -> status.setText("cannot load the network: " + ex.getMessage()));
                }
            });
        }

        @Override
        public double preferredHeight() {
            return 470;
        }

        @Override
        public boolean interactive() {
            return true;
        }

        @Override
        public void outputs(Map<String, Object> outputs) {
            if (outputs.get("model") instanceof GameNodes.Model m && m != model) {
                model = m;
                newGame();
            }
        }

        @Override
        public void status(NodeStatus s, String message) {
            if (s == NodeStatus.ERROR) status.setText(message);
        }

        @Override
        protected void layoutChildren() {
            double w = getWidth(), h = getHeight();
            status.resizeRelocate(0, 0, w, 18);
            double boardH = h - 18 - 112;
            canvas.setWidth(w);
            canvas.setHeight(boardH);
            canvas.relocate(0, 20);
            double y = 22 + boardH;
            detail.resizeRelocate(0, y, w, 46);
            solverLine.resizeRelocate(0, y + 46, w, 30);
            double bx = 0;
            for (var b : List.of(newGame, undo, hint, aiMove)) {
                double bw = b.prefWidth(-1);
                b.resizeRelocate(bx, h - 26, bw, 24);
                bx += bw + 6;
            }
            draw();
        }

        private String you() {
            return String.valueOf(context.param("you"));
        }

        private boolean humanToMove() {
            String y = you();
            if (y.startsWith("watch")) return false;
            if (y.startsWith("both")) return true;
            return (game.sideToMove() == 0) == y.equals("first");
        }

        private void newGame() {
            if (model == null || !model.game().equals(String.valueOf(context.param("game")))) {
                loadModelThen(this::newGame);
                return;
            }
            epoch.incrementAndGet();
            game = Game.create(model.game());
            history.clear();
            mcts = new Mcts(game, Mcts.Config.PLAY, System.nanoTime());
            report = null;
            barValue = Float.NaN;
            solverScores = null;
            lastVerdict = "";
            solverLine.setText("");
            afterMove();
        }

        private void undo() {
            if (model == null || history.isEmpty()) return;
            epoch.incrementAndGet();
            thinking = false;
            history.removeLast();
            boolean versus = you().equals("first") || you().equals("second");
            while (versus && !history.isEmpty() && !isHumanTurnAfter(history.size())) history.removeLast();
            game = rebuild();
            mcts = new Mcts(game, Mcts.Config.PLAY, System.nanoTime());
            report = null;
            lastVerdict = "";
            afterMove();
        }

        /** Whether the human is to move after {@code moves} moves (versus mode). */
        private boolean isHumanTurnAfter(int moves) {
            return (moves % 2 == 0) == you().equals("first");
        }

        private Game rebuild() {
            var g = Game.create(model.game());
            for (int m : history) g.play(m);
            return g;
        }

        private void play(int move) {
            var before = game.copy();
            game.play(move);
            history.add(move);
            mcts.advance(move);
            report = null;
            grade(before, move);
            afterMove();
        }

        /** After every move: the result, or whose turn it is; the AI replies if it is its turn. */
        private void afterMove() {
            solverScores = null;
            if (game.isOver()) {
                status.setText(game.outcome() == Game.Outcome.DRAW ? "Draw." : sideName(1 - game.sideToMove()) + " wins.");
                draw();
                return;
            }
            String side = sideName(game.sideToMove());
            status.setText(humanToMove() ? side + " to move - your turn" : side + " to move - the AI is thinking");
            analyseWithSolver();
            draw();
            if (!humanToMove()) think(true);
        }

        private String sideName(int side) {
            boolean c4 = game instanceof ConnectFour;
            return side == 0 ? (c4 ? "Red (first)" : "Black (first)") : (c4 ? "Yellow (second)" : "White (second)");
        }

        /** Runs the search; plays the best move when {@code play}, otherwise only shows it (a hint). */
        private void think(boolean play) {
            if (model == null || game.isOver() || thinking) return;
            thinking = true;
            searchSide = game.sideToMove();
            long myEpoch = epoch.get();
            int sims = ((Number) context.param("simulations")).intValue();
            var net = model.net();
            Mcts.Evaluator ev = net::evaluateBatch;
            var m = mcts;
            Thread.ofVirtual().name("game-ai").start(() -> {
                try {
                    var r = m.search(ev, sims, sims >= 64 ? 16 : 1, () -> epoch.get() != myEpoch, rep -> {
                        report = rep;
                        Platform.runLater(this::showReport);
                    }, 80);
                    report = r;
                    Platform.runLater(() -> {
                        thinking = false;
                        if (epoch.get() != myEpoch) return;
                        showReport();
                        if (play && !game.isOver()) {
                            if (you().startsWith("watch")) {
                                var pause = new javafx.animation.PauseTransition(javafx.util.Duration.millis(400));
                                int mv = m.chooseMove(0);
                                pause.setOnFinished(e -> {
                                    if (epoch.get() == myEpoch) play(mv);
                                });
                                pause.play();
                            } else play(m.chooseMove(0));
                        }
                    });
                } catch (Exception ex) {
                    Platform.runLater(() -> {
                        thinking = false;
                        status.setText("search failed: " + ex.getMessage());
                    });
                }
            });
        }

        private void showReport() {
            var r = report;
            if (r == null || model == null) return;
            var best = r.best();
            barValue = r.rootValue();
            barSide = searchSide;
            var sorted = new ArrayList<>(r.moves());
            sorted.sort((a, b) -> Integer.compare(b.visits(), a.visits()));
            var sb = new StringBuilder();
            sb.append(String.format(Locale.ROOT, "%d simulations in %.0f ms  ·  ", r.simulations(), r.elapsedMs()));
            for (int i = 0; i < Math.min(4, sorted.size()); i++) {
                var s = sorted.get(i);
                sb.append(String.format(Locale.ROOT, "%s %.0f%% (prior %.0f%%, win %.0f%%)   ", game.actionName(s.action()), 100 * s.share(), 100 * s.prior(),
                                        Float.isNaN(s.q()) ? 50 : 50 * (s.q() + 1)));
            }
            if (!r.principalVariation().isEmpty()) {
                sb.append("\nexpected line: ");
                for (int a : r.principalVariation()) sb.append(game.actionName(a)).append(' ');
            }
            if (best != null) sb.append(String.format(Locale.ROOT, "  ·  best: %s", game.actionName(best.action())));
            detail.setText(sb.toString());
            draw();
        }

        /** Connect Four: the perfect solver's verdict on the move just played (in the background, at most a few seconds). */
        private void grade(Game before, int move) {
            if (!(before instanceof ConnectFour c4) || !Boolean.TRUE.equals(context.param("solver"))) return;
            int ply = c4.ply();
            String who = c4.sideToMove() == 0 ? "Red" : "Yellow";       // Connect Four only
            Thread.ofVirtual().start(() -> {
                var solver = new ConnectFourSolver(22);
                long deadline = System.currentTimeMillis() + 4000;
                solver.setCancel(() -> System.currentTimeMillis() > deadline);
                try {
                    var scores = solver.scoreMoves(c4);
                    int best = Integer.MIN_VALUE;
                    for (var s : scores) if (s != null) best = Math.max(best, s);
                    int got = scores[move];
                    String verdict;
                    if (Integer.signum(got) < Integer.signum(best))
                        verdict = String.format("%s's %s was a mistake: it %s; column %d %s.", who, before.actionName(move),
                                                ConnectFourSolver.describe(got, ply), argBest(scores, best) + 1, ConnectFourSolver.describe(best, ply));
                    else if (got == best) verdict = String.format("%s's %s was perfect (%s).", who, before.actionName(move), ConnectFourSolver.describe(got, ply));
                    else verdict = String.format("%s's %s keeps the result (%s).", who, before.actionName(move), ConnectFourSolver.describe(got, ply));
                    Platform.runLater(() -> {
                        lastVerdict = "Perfect play check: " + verdict;
                        solverLine.setText(lastVerdict);
                    });
                } catch (java.util.concurrent.CancellationException e) {
                    Platform.runLater(() -> solverLine.setText("Perfect play check: this early position takes the solver too long."));
                }
            });
        }

        /** For analysis mode: the solver's score of every column of the current position. */
        private void analyseWithSolver() {
            if (!(game instanceof ConnectFour c4) || !you().startsWith("both") || !Boolean.TRUE.equals(context.param("solver"))) return;
            int ply = c4.ply();
            Thread.ofVirtual().start(() -> {
                var solver = new ConnectFourSolver(22);
                long deadline = System.currentTimeMillis() + 4000;
                solver.setCancel(() -> System.currentTimeMillis() > deadline);
                try {
                    var scores = solver.scoreMoves(c4.copy());
                    Platform.runLater(() -> {
                        if (game.ply() != ply) return;
                        solverScores = scores;
                        draw();
                    });
                } catch (java.util.concurrent.CancellationException ignored) {
                    // too early in the game
                }
            });
        }

        static int argBest(Integer[] scores, int best) {
            for (int c = 0; c < scores.length; c++) if (scores[c] != null && scores[c] == best) return c;
            return 0;
        }

        // ---- geometry and drawing ----------------------------------------------------------------------------

        private double cell, ox, oy;

        private void geometry() {
            int cols = game.width(), rows = game.height();
            double topBand = game instanceof ConnectFour ? 26 : 0, barH = 16;
            cell = Math.min((canvas.getWidth() - 4) / cols, (canvas.getHeight() - topBand - barH - 8) / rows);
            ox = (canvas.getWidth() - cell * cols) / 2;
            oy = topBand;
        }

        private int actionAt(double x, double y) {
            if (game == null) return -1;
            geometry();
            int cx = (int) Math.floor((x - ox) / cell), cy = (int) Math.floor((y - oy) / cell);
            if (cx < 0 || cx >= game.width()) return -1;
            if (game instanceof ConnectFour) return cx;
            if (cy < 0 || cy >= game.height()) return -1;
            return cy * game.width() + cx;
        }

        private void hover(MouseEvent e) {
            int a = actionAt(e.getX(), e.getY());
            if (a != hover) {
                hover = a;
                draw();
            }
        }

        private void click(MouseEvent e) {
            if (game == null || game.isOver() || thinking || !humanToMove()) return;
            int a = actionAt(e.getX(), e.getY());
            if (a >= 0 && game.isLegal(a)) play(a);
        }

        private void draw() {
            var g = canvas.getGraphicsContext2D();
            double w = canvas.getWidth(), h = canvas.getHeight();
            g.setFill(BG);
            g.fillRect(0, 0, w, h);
            if (game == null || w < 20) return;
            geometry();
            int cols = game.width(), rows = game.height();
            var r = report;
            double[] share = new double[game.actions()];
            if (r != null) for (var s : r.moves()) share[s.action()] = s.share();
            boolean c4 = game instanceof ConnectFour;
            g.setTextAlign(TextAlignment.CENTER);
            g.setTextBaseline(VPos.CENTER);
            if (c4) {
                g.setFill(Color.web("#1c3d8f"));
                g.fillRoundRect(ox - 3, oy - 3, cell * cols + 6, cell * rows + 6, 12, 12);
                for (int x = 0; x < cols; x++) {
                    // search share above each column, solver verdict colour
                    if (solverScores != null && solverScores[x] != null) {
                        g.setFill(solverScores[x] > 0 ? WIN : solverScores[x] == 0 ? DRAW : LOSS);
                        g.fillRoundRect(ox + x * cell + 3, 2, cell - 6, 5, 3, 3);
                    }
                    if (share[x] > 0) {
                        g.setFill(SEARCH.deriveColor(0, 1, 1, 0.25 + 0.75 * share[x]));
                        g.fillRoundRect(ox + x * cell + 3, 8, (cell - 6) * share[x], 5, 3, 3);
                        g.setFill(TEXT);
                        g.setFont(Font.font("System", FontWeight.BOLD, 10.5));
                        g.fillText(String.format(Locale.ROOT, "%.0f%%", 100 * share[x]), ox + (x + 0.5) * cell, 19);
                    }
                    if (x == hover && humanToMove() && !game.isOver() && game.isLegal(x)) {
                        g.setFill(Color.web("#ffffff", 0.08));
                        g.fillRect(ox + x * cell, oy, cell, cell * rows);
                    }
                    for (int y = 0; y < rows; y++) {
                        int c = game.cellAt(x, y);
                        g.setFill(c == 1 ? P1 : c == 2 ? P2 : BG);
                        g.fillOval(ox + x * cell + cell * 0.1, oy + y * cell + cell * 0.1, cell * 0.8, cell * 0.8);
                    }
                }
            } else {
                g.setFill(Color.web("#c9a26b"));
                g.fillRoundRect(ox - 3, oy - 3, cell * cols + 6, cell * rows + 6, 8, 8);
                g.setStroke(Color.web("#5c4426"));
                g.setLineWidth(1);
                for (int i = 0; i < cols; i++) {
                    g.strokeLine(ox + (i + 0.5) * cell, oy + 0.5 * cell, ox + (i + 0.5) * cell, oy + (rows - 0.5) * cell);
                    g.strokeLine(ox + 0.5 * cell, oy + (i + 0.5) * cell, ox + (cols - 0.5) * cell, oy + (i + 0.5) * cell);
                }
                for (int y = 0; y < rows; y++)
                    for (int x = 0; x < cols; x++) {
                        int a = y * cols + x, c = game.cellAt(x, y);
                        double cx = ox + (x + 0.5) * cell, cy = oy + (y + 0.5) * cell;
                        if (c != 0) {
                            g.setFill(c == 1 ? Color.web("#212529") : Color.web("#f8f9fa"));
                            g.fillOval(cx - cell * 0.42, cy - cell * 0.42, cell * 0.84, cell * 0.84);
                        } else if (share[a] > 0.005) {
                            double rad = cell * (0.12 + 0.3 * Math.sqrt(share[a]));
                            g.setFill(SEARCH.deriveColor(0, 1, 1, 0.35 + 0.6 * share[a]));
                            g.fillOval(cx - rad, cy - rad, 2 * rad, 2 * rad);
                            if (share[a] > 0.04) {
                                g.setFill(Color.WHITE);
                                g.setFont(Font.font("System", FontWeight.BOLD, 9));
                                g.fillText(String.format(Locale.ROOT, "%.0f", 100 * share[a]), cx, cy);
                            }
                        } else if (a == hover && humanToMove() && game.isLegal(a)) {
                            g.setFill(Color.web("#000000", 0.2));
                            g.fillOval(cx - cell * 0.4, cy - cell * 0.4, cell * 0.8, cell * 0.8);
                        }
                    }
            }
            // last move marker
            if (!history.isEmpty()) {
                int last = history.getLast();
                var prev = rebuildBeforeLast();
                int cellIdx = prev.cellOf(last);
                double cx = ox + (cellIdx % cols + 0.5) * cell, cy = oy + (cellIdx / cols + 0.5) * cell;
                g.setStroke(Color.web("#4dabf7"));
                g.setLineWidth(2);
                g.strokeOval(cx - cell * 0.18, cy - cell * 0.18, cell * 0.36, cell * 0.36);
            }
            // the AI's estimate of the position, for the side it searched for
            if (!Float.isNaN(barValue)) {
                double by = oy + cell * rows + 8, bw = cell * cols;
                double p = (barValue + 1) / 2;
                int side = barSide;
                g.setFill(PANEL);
                g.fillRoundRect(ox, by, bw, 12, 6, 6);
                g.setFill(side == 0 ? P1 : P2);
                g.fillRoundRect(ox, by, bw * p, 12, 6, 6);
                g.setFill(Color.web("#111318"));
                g.setFont(Font.font("System", FontWeight.BOLD, 9.5));
                g.setTextAlign(TextAlignment.LEFT);
                g.fillText(String.format(Locale.ROOT, "AI's winning chance for %s: %.0f%%", sideName(side).replaceAll(" .*", "").toLowerCase(Locale.ROOT), 100 * p),
                           ox + 6, by + 6.5);
            }
        }

        private Game rebuildBeforeLast() {
            var g = Game.create(model.game());
            for (int i = 0; i < history.size() - 1; i++) g.play(history.get(i));
            return g;
        }
    }

    // ---- arena -----------------------------------------------------------------------------------------------

    /** The match as it is played: the score bar, the Elo estimate with its uncertainty, the last games. */
    static final class ArenaBody extends NodeBody {
        private final Canvas canvas = new Canvas();
        private final ListView<String> games = new ListView<>();
        private Arena.Result result;
        private List<?> names = List.of("A", "B");

        ArenaBody() {
            games.setStyle("-fx-font-size: 10.5px;");
            games.setPlaceholder(new Label("not run yet"));
            getChildren().addAll(canvas, games);
        }

        @Override
        public double preferredHeight() {
            return 230;
        }

        @Override
        public boolean interactive() {
            return true;
        }

        @Override
        protected void layoutChildren() {
            canvas.setWidth(getWidth());
            canvas.setHeight(92);
            games.resizeRelocate(0, 96, getWidth(), getHeight() - 96);
            draw();
        }

        @Override
        public void reset() {
            result = null;
            games.getItems().clear();
            draw();
        }

        @Override
        public void emit(String channel, Object payload) {
            switch (channel) {
                case "names" -> names = (List<?>) payload;
                case "arena" -> {
                    var r = (Arena.Result) payload;
                    if (result == null || r.games() >= result.games()) result = r;
                    draw();
                }
                case "game" -> {
                    var g = (Arena.GameRecord) payload;
                    games.getItems().addFirst(String.format("#%d  A %s  %s   %s", g.index() + 1, g.aFirst() ? "first " : "second",
                                                            g.resultForA() == 1 ? "won " : g.resultForA() == 0 ? "lost" : "draw", g.moves()));
                }
                default -> {
                }
            }
        }

        private void draw() {
            var g = canvas.getGraphicsContext2D();
            double w = canvas.getWidth(), h = canvas.getHeight();
            g.setFill(BG);
            g.fillRect(0, 0, w, h);
            g.setTextBaseline(VPos.TOP);
            g.setTextAlign(TextAlignment.LEFT);
            g.setFont(Font.font("System", FontWeight.BOLD, 11.5));
            g.setFill(P1);
            g.fillText("A: " + names.get(0), 0, 0);
            g.setTextAlign(TextAlignment.RIGHT);
            g.setFill(P2);
            g.fillText("B: " + names.get(1), w, 0);
            var r = result;
            if (r == null || r.games() == 0) return;
            double y = 22, bh = 20, n = r.games();
            double ww = w * r.wins() / n, wd = w * r.draws() / n;
            g.setFill(P1);
            g.fillRect(0, y, ww, bh);
            g.setFill(DRAW);
            g.fillRect(ww, y, wd, bh);
            g.setFill(P2);
            g.fillRect(ww + wd, y, w - ww - wd, bh);
            g.setTextAlign(TextAlignment.CENTER);
            g.setFill(Color.web("#111318"));
            g.setFont(Font.font("System", FontWeight.BOLD, 11));
            if (ww > 24) g.fillText(String.valueOf(r.wins()), ww / 2, y + 3);
            if (wd > 24) g.fillText(String.valueOf(r.draws()), ww + wd / 2, y + 3);
            if (w - ww - wd > 24) g.fillText(String.valueOf(r.losses()), (w + ww + wd) / 2, y + 3);
            g.setTextAlign(TextAlignment.LEFT);
            g.setFill(TEXT);
            g.setFont(Font.font("System", 12));
            g.fillText(String.format(Locale.ROOT, "%d games  ·  A scores %.1f%%  ·  Elo %+.0f ± %.0f  ·  LOS %.0f%%", r.games(), 100 * r.score(), r.elo(),
                                     r.eloMargin(), 100 * r.los()), 0, y + bh + 8);
            g.setFill(MUTED);
            g.setFont(Font.font("System", 10.5));
            g.fillText("LOS: the probability that A is really the stronger player (draws carry no information)", 0, y + bh + 28);
        }
    }

    // ---- training --------------------------------------------------------------------------------------------

    /** Elo and losses per generation as they arrive, the trainer's state (running, paused for whom), its output. */
    static final class TrainingBody extends NodeBody {
        private final Canvas canvas = new Canvas();
        private final Label state = new Label("not started");
        private final ListView<String> lines = new ListView<>();
        private final List<GambitTraining.Generation> gens = new ArrayList<>();
        private final AtomicBoolean redraw = new AtomicBoolean();

        TrainingBody() {
            state.setStyle("-fx-text-fill: #ffd43b; -fx-font-size: 11.5px; -fx-font-weight: bold;");
            lines.setStyle("-fx-font-size: 10px;");
            getChildren().addAll(state, canvas, lines);
        }

        @Override
        public double preferredHeight() {
            return 330;
        }

        @Override
        public boolean interactive() {
            return true;
        }

        @Override
        protected void layoutChildren() {
            double w = getWidth(), h = getHeight();
            state.resizeRelocate(0, 0, w, 18);
            canvas.setWidth(w);
            canvas.setHeight(h * 0.6);
            canvas.relocate(0, 20);
            lines.resizeRelocate(0, 24 + h * 0.6, w, h - 24 - h * 0.6);
            draw();
        }

        @Override
        public void reset() {
            gens.clear();
            lines.getItems().clear();
            state.setText("starting…");
            draw();
        }

        @Override
        public void emit(String channel, Object payload) {
            switch (channel) {
                case "generation" -> {
                    var g = (GambitTraining.Generation) payload;
                    gens.removeIf(x -> x.generation() == g.generation());
                    gens.add(g);
                    draw();
                }
                case "state" -> state.setText(String.valueOf(payload));
                case "line" -> {
                    for (var l : String.valueOf(payload).split("\n")) {
                        if (l.isBlank()) continue;
                        lines.getItems().add(l);
                    }
                    if (lines.getItems().size() > 400) lines.getItems().remove(0, lines.getItems().size() - 400);
                    lines.scrollTo(lines.getItems().size() - 1);
                }
                default -> {
                }
            }
        }

        @Override
        public void status(NodeStatus s, String message) {
            if (s == NodeStatus.ERROR) state.setText(message);
            if (s == NodeStatus.DONE) state.setText("finished: " + gens.size() + " generations");
        }

        private void draw() {
            var g = canvas.getGraphicsContext2D();
            double w = canvas.getWidth(), h = canvas.getHeight();
            g.setFill(BG);
            g.fillRect(0, 0, w, h);
            if (w < 40) return;
            double half = (w - 8) / 2;
            chart(g, 0, 0, half, h, "Elo (classic MCTS 200 = 0)", Color.web("#40c057"), GambitTraining.Generation::elo, null);
            chart(g, half + 8, 0, half, h, "loss: policy (blue) · value (orange)", Color.web("#4dabf7"), GambitTraining.Generation::policyLoss,
                  GambitTraining.Generation::valueLoss);
        }

        private void chart(GraphicsContext g, double x0, double y0, double w, double h, String title, Color color,
                           java.util.function.ToDoubleFunction<GambitTraining.Generation> f, java.util.function.ToDoubleFunction<GambitTraining.Generation> f2) {
            g.setFill(PANEL);
            g.fillRoundRect(x0, y0, w, h, 6, 6);
            g.setFill(MUTED);
            g.setFont(Font.font("System", FontWeight.BOLD, 10.5));
            g.setTextAlign(TextAlignment.LEFT);
            g.setTextBaseline(VPos.TOP);
            g.fillText(title, x0 + 6, y0 + 4);
            var data = new ArrayList<>(gens);
            data.sort((a, b) -> Integer.compare(a.generation(), b.generation()));
            if (data.isEmpty()) return;
            double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
            for (var d : data) {
                lo = Math.min(lo, f.applyAsDouble(d));
                hi = Math.max(hi, f.applyAsDouble(d));
                if (f2 != null) {
                    lo = Math.min(lo, f2.applyAsDouble(d));
                    hi = Math.max(hi, f2.applyAsDouble(d));
                }
            }
            if (hi - lo < 1e-9) {
                hi += 1;
                lo -= 1;
            }
            double pad = (hi - lo) * 0.1;
            lo -= pad;
            hi += pad;
            double top = y0 + 22, bottom = y0 + h - 16, left = x0 + 34, right = x0 + w - 8;
            g.setFill(MUTED);
            g.setFont(Font.font("System", 9.5));
            g.setTextAlign(TextAlignment.RIGHT);
            g.setTextBaseline(VPos.CENTER);
            g.fillText(fmt(hi), left - 4, top);
            g.fillText(fmt(lo), left - 4, bottom);
            g.setTextAlign(TextAlignment.CENTER);
            g.setTextBaseline(VPos.TOP);
            int g0 = data.getFirst().generation(), g1 = Math.max(g0 + 1, data.getLast().generation());
            g.fillText("gen " + g0, left, bottom + 2);
            g.fillText("gen " + data.getLast().generation(), right, bottom + 2);
            for (int pass = 0; pass < (f2 == null ? 1 : 2); pass++) {
                var fn = pass == 0 ? f : f2;
                g.setStroke(pass == 0 ? color : Color.web("#ff922b"));
                g.setLineWidth(2);
                double px = 0, py = 0;
                for (int i = 0; i < data.size(); i++) {
                    var d = data.get(i);
                    double x = left + (right - left) * (d.generation() - g0) / (double) (g1 - g0);
                    double y = bottom - (bottom - top) * (fn.applyAsDouble(d) - lo) / (hi - lo);
                    if (i > 0) g.strokeLine(px, py, x, y);
                    g.setFill(pass == 0 ? color : Color.web("#ff922b"));
                    g.fillOval(x - 2.5, y - 2.5, 5, 5);
                    px = x;
                    py = y;
                }
            }
        }

        static String fmt(double v) {
            return Math.abs(v) >= 10 ? String.format(Locale.ROOT, "%.0f", v) : String.format(Locale.ROOT, "%.2f", v);
        }
    }
}
