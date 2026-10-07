package nexus.engines.llm;

import static nexus.core.graph.PortSpec.in;
import static nexus.core.graph.PortSpec.optionalIn;
import static nexus.core.graph.PortSpec.out;
import static nexus.core.types.DataTypes.TABLE;
import static nexus.core.types.DataTypes.TEXT;
import static nexus.core.types.DataTypes.TEXT_STREAM;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import nexus.core.exec.NodeContext;
import nexus.core.graph.NodeDefinition;
import nexus.core.graph.ParamSpec;
import nexus.core.registry.NodeLibrary;
import nexus.core.types.Table;
import nexus.core.types.TextStream;
import nexus.engines.EngineManager;
import nexus.engines.EngineSpec;
import nexus.engines.llm.OpenAiClient.Message;

/**
 * Language-model nodes, run on Ember (or any OpenAI-compatible server NEXUS supervises).
 *
 * Besides the general Chat node there are "recipes": a fixed instruction around the input text.
 * They are local, offline versions of the small Claude-powered apps built earlier (BriefDesk,
 * ToneCraft, QuizForge, FridgeChef), now running on the user's own GPU through Ember.
 */
public final class LlmNodes implements NodeLibrary {
    public static final String CATEGORY = "Language models";

    public LlmNodes() {
    }

    @Override
    public String name() {
        return "Language models (Ember)";
    }

    /** "auto" plus every configured LLM engine. */
    static List<String> engineChoices() {
        var list = new ArrayList<String>();
        list.add("auto");
        for (var e : EngineManager.instance().ofKind(EngineSpec.Kind.LLM)) list.add(e.spec().id());
        return list;
    }

    static List<ParamSpec> commonParams(double temperature, int maxTokens) {
        return List.of(
                ParamSpec.choice("engine", "Engine", "auto", engineChoices(), "Which model server answers (auto: one already running)"),
                ParamSpec.number("temperature", "Temperature", temperature, 0, 2, "0 = deterministic, higher = more varied"),
                ParamSpec.integer("maxTokens", "Max tokens", maxTokens, 16, 4096, "Upper bound on the length of the answer"),
                ParamSpec.bool("thinking", "Think first", false, "Let Qwen3 reason before answering (slower, shown separately)"));
    }

    static final List<String> LANGUAGES = List.of("same as the text", "Romanian", "English", "French", "German", "Spanish", "Italian");

    static String languageRule(NodeContext ctx) {
        String l = ctx.paramText("language");
        return l.isBlank() || l.equals("same as the text") ? "Answer in the same language as the text."
                                                           : "Answer in " + l + ".";
    }

    @Override
    public List<NodeDefinition> definitions() {
        var defs = new ArrayList<NodeDefinition>();
        defs.add(chat());
        defs.add(recipe("llm.summarize", "Summarize", "A summary of the text, as long as you choose.",
                        List.of(ParamSpec.choice("length", "Length", "one paragraph",
                                                 List.of("one sentence", "one paragraph", "detailed (several paragraphs)", "bullet points"), "How long"),
                                ParamSpec.choice("language", "Language", "same as the text", LANGUAGES, "Language of the summary")),
                        ctx -> "You summarise texts faithfully, without adding information that is not in them. Produce "
                               + ctx.paramText("length") + ". " + languageRule(ctx)));
        defs.add(recipe("llm.brief", "Brief: points and actions", "Summary, key points and action items (like BriefDesk).",
                        List.of(ParamSpec.choice("language", "Language", "same as the text", LANGUAGES, "Language of the brief")),
                        ctx -> "Read the text and produce, in Markdown: a 2-3 sentence summary; then 'Key points' as 4-8 bullets; "
                               + "then 'Action items' listing every task, deadline or decision found in the text (or 'none'). "
                               + "Do not invent anything. " + languageRule(ctx)));
        defs.add(recipe("llm.rewrite", "Rewrite in a tone", "The same message in another tone (like ToneCraft).",
                        List.of(ParamSpec.choice("tone", "Tone", "polite and formal",
                                                 List.of("polite and formal", "warm and friendly", "short and direct", "persuasive",
                                                         "apologetic", "enthusiastic", "neutral and professional"), "The tone to write in"),
                                ParamSpec.choice("language", "Language", "same as the text", LANGUAGES, "Language of the result")),
                        ctx -> "Rewrite the user's message in a " + ctx.paramText("tone") + " tone. Keep every fact and request; "
                               + "output only the rewritten message. " + languageRule(ctx)));
        defs.add(recipe("llm.quiz", "Make a quiz", "Multiple-choice questions from study material (like QuizForge).",
                        List.of(ParamSpec.integer("questions", "Questions", 5, 1, 20, "How many questions"),
                                ParamSpec.choice("difficulty", "Difficulty", "medium", List.of("easy", "medium", "hard"), "How hard"),
                                ParamSpec.choice("language", "Language", "same as the text", LANGUAGES, "Language of the quiz")),
                        ctx -> "Write " + ctx.paramInt("questions") + " " + ctx.paramText("difficulty")
                               + " multiple-choice questions about the text, each with options A-D, then the correct letter and a "
                               + "one-sentence explanation. Only use facts from the text. " + languageRule(ctx)));
        defs.add(recipe("llm.translate", "Translate", "Translates the text.",
                        List.of(ParamSpec.choice("target", "Into", "English", LANGUAGES.subList(1, LANGUAGES.size()), "Target language")),
                        ctx -> "Translate the user's text into " + ctx.paramText("target")
                               + ". Output only the translation, keeping the formatting."));
        defs.add(recipe("llm.recipe", "Recipe from ingredients", "Suggests a meal from what you have (like FridgeChef).",
                        List.of(ParamSpec.choice("meal", "Meal", "dinner", List.of("breakfast", "lunch", "dinner", "dessert", "snack"), "What for"),
                                ParamSpec.choice("language", "Language", "same as the text", LANGUAGES, "Language of the recipe")),
                        ctx -> "The user lists ingredients they have. Suggest one " + ctx.paramText("meal")
                               + " recipe: name, ingredients (mark the ones they lack), steps, time. " + languageRule(ctx)));
        defs.add(ask());
        defs.add(duel());
        defs.add(conversation());
        return defs;
    }

    // ---- the nodes ------------------------------------------------------------------------------------------

    static NodeDefinition chat() {
        var b = NodeDefinition.builder("llm.chat", "Chat", CATEGORY)
                .description("Sends a prompt to a language model and streams its answer.")
                .input(in("prompt", "prompt", TEXT)).input(optionalIn("system", "system", TEXT))
                .output(out("reply", "reply", TEXT_STREAM)).output(out("reasoning", "reasoning", TEXT)).output(out("stats", "stats", TABLE))
                .view("llm").notCacheable();
        for (var p : commonParams(0.7, 512)) b.param(p);
        return b.behavior(() -> ctx -> {
            var msgs = new ArrayList<Message>();
            String sys = ctx.inputText("system");
            if (sys != null && !sys.isBlank()) msgs.add(Message.system(sys));
            msgs.add(Message.user(ctx.inputText("prompt")));
            complete(ctx, msgs);
        });
    }

    static NodeDefinition recipe(String id, String title, String description, List<ParamSpec> params,
                                 Function<NodeContext, String> instruction) {
        var b = NodeDefinition.builder(id, title, CATEGORY).description(description)
                .input(in("text", "text", TEXT))
                .output(out("reply", "reply", TEXT_STREAM)).output(out("reasoning", "reasoning", TEXT)).output(out("stats", "stats", TABLE))
                .view("llm").notCacheable();
        for (var p : params) b.param(p);
        for (var p : commonParams(0.3, 700)) b.param(p);
        return b.behavior(() -> ctx -> complete(ctx, List.of(Message.system(instruction.apply(ctx)), Message.user(ctx.inputText("text")))));
    }

    static NodeDefinition ask() {
        var b = NodeDefinition.builder("llm.ask", "Answer from context", CATEGORY)
                .description("Answers a question using only the given context, quoting it (for RAG).")
                .input(in("question", "question", TEXT)).input(in("context", "context", TEXT))
                .output(out("reply", "reply", TEXT_STREAM)).output(out("reasoning", "reasoning", TEXT)).output(out("stats", "stats", TABLE))
                .param(ParamSpec.choice("language", "Language", "same as the text", LANGUAGES, "Language of the answer"))
                .view("llm").notCacheable();
        for (var p : commonParams(0.2, 600)) b.param(p);
        return b.behavior(() -> ctx -> {
            String sys = "Answer the question using ONLY the numbered passages below. After each claim, cite the passage "
                         + "number in brackets, e.g. [2]. If the passages do not contain the answer, say so plainly. "
                         + languageRule(ctx).replace("the text", "the question") + "\n\nPassages:\n" + ctx.inputText("context");
            complete(ctx, List.of(Message.system(sys), Message.user(ctx.inputText("question"))));
        });
    }

    static NodeDefinition duel() {
        var choices = engineChoices().subList(1, engineChoices().size());
        String a = choices.isEmpty() ? "auto" : choices.getFirst();
        String bEngine = choices.size() > 1 ? choices.get(1) : a;
        return NodeDefinition.builder("llm.duel", "Model duel", CATEGORY)
                .description("The same prompt to two models at once; answers side by side with their speed.")
                .input(in("prompt", "prompt", TEXT))
                .output(out("a", "answer A", TEXT_STREAM)).output(out("b", "answer B", TEXT_STREAM)).output(out("stats", "stats", TABLE))
                .param(ParamSpec.choice("engineA", "Model A", a, engineChoices(), "First model"))
                .param(ParamSpec.choice("engineB", "Model B", bEngine, engineChoices(), "Second model"))
                .param(ParamSpec.number("temperature", "Temperature", 0.7, 0, 2, ""))
                .param(ParamSpec.integer("maxTokens", "Max tokens", 400, 16, 4096, ""))
                .view("duel").notCacheable()
                .behavior(() -> ctx -> {
                    var mgr = EngineManager.instance();
                    var ea = mgr.pickLlm(ctx.paramText("engineA"));
                    var eb = mgr.pickLlm(ctx.paramText("engineB"));
                    var opts = new OpenAiClient.Options(ctx.paramNumber("temperature"), ctx.paramInt("maxTokens"), false, 0);
                    var sa = new TextStream();
                    var sb = new TextStream();
                    ctx.output("a", sa);
                    ctx.output("b", sb);
                    var msgs = List.of(Message.user(ctx.inputText("prompt")));
                    ctx.emit("names", List.of(ea.spec().name(), eb.spec().name()));
                    try (var scope = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
                        var fa = scope.submit(() -> stream(ctx, ea, msgs, opts, sa, "a"));
                        var fb = scope.submit(() -> stream(ctx, eb, msgs, opts, sb, "b"));
                        var ra = fa.get();
                        var rb = fb.get();
                        ctx.output("stats", Table.builder("model", "tokens", "tokens/s", "first token ms", "total ms")
                                .row(ea.spec().name(), (double) ra.completionTokens(), round1(ra.tokensPerSecond()), round1(ra.ttftMs()), round1(ra.totalMs()))
                                .row(eb.spec().name(), (double) rb.completionTokens(), round1(rb.tokensPerSecond()), round1(rb.ttftMs()), round1(rb.totalMs()))
                                .build());
                    } catch (java.util.concurrent.ExecutionException e) {
                        sa.fail(e.getCause());
                        sb.fail(e.getCause());
                        throw (e.getCause() instanceof Exception ex) ? ex : e;
                    }
                });
    }

    /**
     * An interactive conversation: the chat happens inside the node (the app's "chat-session" view),
     * outside of workflow runs. Running the workflow only refreshes the context the conversation is
     * grounded in (e.g. a document fed into the input).
     */
    static NodeDefinition conversation() {
        var b = NodeDefinition.builder("llm.conversation", "Conversation", CATEGORY)
                .description("Chat with a model right inside the node, optionally grounded in a connected text.")
                .input(optionalIn("context", "context", TEXT))
                .output(out("context", "context", TEXT))
                .param(ParamSpec.multiline("system", "System prompt",
                                           "You are a helpful assistant. Answer concisely, in the language of the user.",
                                           "Instructions for the model; the connected context is appended to them"))
                .view("chat-session");
        for (var p : commonParams(0.7, 700)) b.param(p);
        return b.behavior(() -> ctx -> {
            String c = ctx.inputText("context");
            ctx.output("context", c == null ? "" : c);
        });
    }

    // ---- shared machinery -----------------------------------------------------------------------------------

    static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }

    /** Runs a completion for a node with the common parameters, publishing reply / reasoning / stats. */
    static void complete(NodeContext ctx, List<Message> messages) throws Exception {
        var engine = EngineManager.instance().pickLlm(ctx.paramText("engine"));
        var opts = new OpenAiClient.Options(ctx.paramNumber("temperature"), ctx.paramInt("maxTokens"), ctx.paramBool("thinking"), 0);
        var reply = new TextStream();
        ctx.output("reply", reply);
        var r = stream(ctx, engine, messages, opts, reply, "text");
        ctx.output("reasoning", r.reasoning());
        ctx.output("stats", Table.builder("measure", "value")
                .row("model", r.model())
                .row("prompt tokens", (double) r.promptTokens())
                .row("answer tokens", (double) r.completionTokens())
                .row("tokens / second", round1(r.tokensPerSecond()))
                .row("first token (ms)", round1(r.ttftMs()))
                .row("total (ms)", round1(r.totalMs()))
                .row("finish", String.valueOf(r.finishReason()))
                .build());
    }

    /** Starts the engine if needed and streams one completion into {@code into}, emitting on {@code channel}. */
    static OpenAiClient.Reply stream(NodeContext ctx, nexus.engines.Engine engine, List<Message> messages, OpenAiClient.Options opts,
                                     TextStream into, String channel) throws Exception {
        try (var _ = engine.use()) {         // marks the engine busy: it is never evicted mid-answer
            if (!engine.state().isUp()) {
                ctx.progress(-1, "starting " + engine.spec().name());
                ctx.log("starting " + engine.spec().name());
                engine.awaitReady(Duration.ofSeconds(150));
            }
            ctx.progress(-1, "thinking");
            var client = new OpenAiClient(engine.spec().baseUrl());
            long start = System.nanoTime();
            int[] pieces = {0};
            var r = client.chat(messages, opts, new OpenAiClient.Listener() {
                @Override
                public void content(String piece) {
                    into.append(piece);
                    ctx.emit(channel, piece);
                    if (++pieces[0] % 8 == 0) {
                        double s = (System.nanoTime() - start) / 1e9;
                        ctx.progress(Math.min(0.99, pieces[0] / (double) opts.maxTokens()),
                                     String.format(Locale.ROOT, "%d tok · %.0f tok/s", pieces[0], pieces[0] / Math.max(s, 1e-3)));
                    }
                }

                @Override
                public void reasoning(String piece) {
                    ctx.emit(channel + "-reasoning", piece);
                }
            }, ctx::isCancelled);
            into.complete();
            ctx.emit(channel + "-stats", r);
            return r;
        } catch (Exception e) {
            into.fail(e);
            throw e;
        }
    }
}
