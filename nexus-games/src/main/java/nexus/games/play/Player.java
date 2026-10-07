package nexus.games.play;

import java.util.Random;
import nexus.games.net.GnetNetwork;
import nexus.games.rules.ConnectFour;
import nexus.games.rules.Game;
import nexus.games.search.ConnectFourSolver;
import nexus.games.search.Mcts;

/** Something that picks moves. Players are used by one game at a time (each arena game makes its own). */
public interface Player {
    String name();

    int chooseMove(Game position);

    /** A factory, so that parallel games each get their own player. */
    interface Factory {
        String name();

        Player create(long seed);
    }

    /** AlphaZero: the network guiding {@code simulations} MCTS simulations per move (1 = the network's prior alone). */
    static Factory network(GnetNetwork net, String label, int simulations) {
        return new Factory() {
            @Override
            public String name() {
                return label + " + MCTS " + simulations;
            }

            @Override
            public Player create(long seed) {
                Mcts.Evaluator ev = (ps, lg, vs) -> {
                    // One position at a time: parallelism comes from the games played side by side.
                    for (int i = 0; i < ps.size(); i++) {
                        var planes = new float[net.spec().planes() * net.spec().area()];
                        ps.get(i).encode(planes, 0);
                        vs[i] = net.evaluate(planes, 0, lg, i * net.spec().actions());
                    }
                };
                return new Player() {
                    @Override
                    public String name() {
                        return label + " + MCTS " + simulations;
                    }

                    @Override
                    public int chooseMove(Game position) {
                        var m = new Mcts(position, Mcts.Config.PLAY, seed);
                        m.search(ev, simulations, 1, null, null, 0);
                        return m.chooseMove(0);
                    }
                };
            }
        };
    }

    /** Classic MCTS: UCT with random playouts and no knowledge - the baseline Gambit's Elo is anchored to. */
    static Factory rollouts(int simulations) {
        return new Factory() {
            @Override
            public String name() {
                return "classic MCTS " + simulations;
            }

            @Override
            public Player create(long seed) {
                return new RolloutMcts(simulations, seed);
            }
        };
    }

    static Factory random() {
        return new Factory() {
            @Override
            public String name() {
                return "random";
            }

            @Override
            public Player create(long seed) {
                var rng = new Random(seed);
                return new Player() {
                    @Override
                    public String name() {
                        return "random";
                    }

                    @Override
                    public int chooseMove(Game g) {
                        int[] buf = new int[g.actions()];
                        return buf[rng.nextInt(g.legalMoves(buf))];
                    }
                };
            }
        };
    }

    /** Perfect play (Connect Four only): among the best-scored columns, a random one. */
    static Factory perfect() {
        return new Factory() {
            @Override
            public String name() {
                return "perfect solver";
            }

            @Override
            public Player create(long seed) {
                var rng = new Random(seed);
                var solver = new ConnectFourSolver(21);
                return new Player() {
                    @Override
                    public String name() {
                        return "perfect solver";
                    }

                    @Override
                    public int chooseMove(Game g) {
                        if (!(g instanceof ConnectFour c4)) throw new IllegalArgumentException("the solver plays Connect Four only");
                        var scores = solver.scoreMoves(c4);
                        int best = Integer.MIN_VALUE, pick = -1, ties = 0;
                        for (int c = 0; c < scores.length; c++) {
                            if (scores[c] == null) continue;
                            if (scores[c] > best) {
                                best = scores[c];
                                pick = c;
                                ties = 1;
                            } else if (scores[c] == best && rng.nextInt(++ties) == 0) pick = c;
                        }
                        return pick;
                    }
                };
            }
        };
    }
}
