package nexus.engines.train;

import static nexus.core.graph.PortSpec.optionalIn;
import static nexus.core.graph.PortSpec.out;
import static nexus.core.types.DataTypes.ANY;
import static nexus.core.types.DataTypes.TABLE;
import static nexus.core.types.DataTypes.TEXT;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import nexus.core.graph.NodeDefinition;
import nexus.core.graph.ParamSpec;
import nexus.core.registry.NodeLibrary;
import nexus.core.types.Table;
import nexus.engines.EngineManager;

/** Training Kindling's GPT with Anvil or PyTorch, and comparing runs. */
public final class TrainingNodes implements NodeLibrary {
    public static final String CATEGORY = "Training";
    static final List<String> FRAMEWORKS = List.of("Anvil (own CUDA kernels)", "PyTorch (Kindling)");

    public TrainingNodes() {
    }

    @Override
    public String name() {
        return CATEGORY;
    }

    @Override
    public List<NodeDefinition> definitions() {
        return List.of(train(), compare());
    }

    static NodeDefinition train() {
        return NodeDefinition.builder("gpt.train", "Train GPT", CATEGORY)
                .description("Trains Kindling's Romanian GPT with Anvil or PyTorch on the GPU, with live loss and throughput.")
                .input(optionalIn("after", "after", ANY))
                .output(out("curve", "curve", TABLE)).output(out("summary", "summary", TEXT))
                .param(ParamSpec.choice("framework", "Framework", FRAMEWORKS.getFirst(), FRAMEWORKS, "Which framework trains the model"))
                .param(ParamSpec.integer("steps", "Steps", 1000, 50, 20000, "Optimizer steps (6000 = Kindling's full run)"))
                .param(ParamSpec.integer("eval", "Evaluate every", 100, 10, 2000, "Steps between validation measurements"))
                .param(ParamSpec.integer("batch", "Batch size", 32, 4, 64, "Sequences of 256 tokens per step"))
                .param(ParamSpec.text("out", "Run folder", "", "Where checkpoints go (empty: ~/.nexus/runs/gpt-<framework>)"))
                .view("loss-curve").notCacheable()
                .behavior(() -> ctx -> {
                    var fw = ctx.paramText("framework").startsWith("Anvil") ? GptTraining.Framework.ANVIL : GptTraining.Framework.PYTORCH;
                    String outText = ctx.paramText("out").strip();
                    if (outText.startsWith("~")) outText = System.getProperty("user.home") + outText.substring(1);
                    Path out = outText.isBlank() ? Path.of(System.getProperty("user.home"), ".nexus", "runs", "gpt-" + fw.name().toLowerCase(Locale.ROOT))
                                                 : Path.of(outText);
                    var mgr = EngineManager.instance();
                    var opts = new GptTraining.Options(fw, mgr.projects(), out, ctx.paramInt("steps"), ctx.paramInt("eval"), ctx.paramInt("batch"));
                    int steps = opts.steps();
                    var points = new ArrayList<GptTraining.Point>();
                    var evals = new GptTraining(opts, mgr.broker()).run(new GptTraining.Listener() {
                        @Override
                        public void line(String text) {
                            ctx.emit("line", text);
                        }

                        @Override
                        public void point(GptTraining.Point p) {
                            points.add(p);
                            ctx.emit("point", p);
                            ctx.progress((double) p.step() / steps, String.format(Locale.ROOT, "step %d, loss %.3f, %.0fk tok/s", p.step(), p.loss(),
                                                                                  p.tokensPerSecond() / 1000));
                        }

                        @Override
                        public void eval(GptTraining.Eval e) {
                            ctx.emit("eval", e);
                        }

                        @Override
                        public void state(String text) {
                            ctx.emit("state", text);
                            ctx.log(text);
                        }
                    }, ctx::isCancelled);
                    var t = Table.builder("framework", "step", "seconds", "train loss", "val loss", "val perplexity");
                    for (var e : evals)
                        t.row(e.framework(), (double) e.step(), Math.round(e.seconds() * 10) / 10.0, round3(e.train()), round3(e.val()),
                              Math.round(e.perplexity() * 100) / 100.0);
                    ctx.output("curve", t.build());
                    double tokS = points.stream().filter(p -> p.step() > 0).mapToDouble(GptTraining.Point::tokensPerSecond).average().orElse(0);
                    var best = evals.stream().min((a, b) -> Double.compare(a.val(), b.val())).orElse(null);
                    String summary = best == null ? fw.label + ": no evaluation"
                                                  : String.format(Locale.ROOT, "%s: best validation loss %.4f (perplexity %.1f) at step %d, %.0fk tokens/s, %.0f s",
                                                                  fw.label, best.val(), best.perplexity(), best.step(), tokS / 1000,
                                                                  evals.getLast().seconds());
                    ctx.output("summary", summary);
                    ctx.log(summary);
                });
    }

    static double round3(double v) {
        return Math.round(v * 1000) / 1000.0;
    }

    static NodeDefinition compare() {
        return NodeDefinition.builder("train.compare", "Compare training runs", CATEGORY)
                .description("Validation loss of several runs against wall-clock time and steps, on the same axes.")
                .input(optionalIn("a", "run A", TABLE)).input(optionalIn("b", "run B", TABLE)).input(optionalIn("c", "run C", TABLE))
                .output(out("table", "comparison", TABLE)).output(out("runs", "runs", ANY))
                .view("curves")
                .behavior(() -> ctx -> {
                    var t = Table.builder("run", "evaluations", "best val loss", "seconds", "val loss at the end");
                    var runs = new ArrayList<Table>();
                    for (var k : List.of("a", "b", "c")) {
                        if (!(ctx.input(k) instanceof Table run) || run.size() == 0) continue;
                        runs.add(run);
                        double best = Double.MAX_VALUE;
                        for (var r : run.rows()) best = Math.min(best, ((Number) r.get(4)).doubleValue());
                        var last = run.rows().getLast();
                        t.row(String.valueOf(last.get(0)), (double) run.size(), best, last.get(2), last.get(4));
                    }
                    ctx.output("table", t.build());
                    ctx.output("runs", List.copyOf(runs));
                });
    }
}
