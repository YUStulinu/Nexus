package nexus.ml;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.ArrayList;
import nexus.ml.bert.BertEncoder;
import nexus.ml.tensor.Kernels;
import org.junit.jupiter.api.Test;

/** Speed of the Java encoder (run with -Dbench=true). */
class EncoderBench {
    @Test
    void bench() throws Exception {
        assumeTrue(Boolean.getBoolean("bench"));
        int rows = 256, k = 384, out = 1536;
        float[] x = new float[rows * k], w = new float[out * k], y = new float[rows * out];
        for (int i = 0; i < x.length; i++) x[i] = (float) Math.sin(i);
        for (int i = 0; i < w.length; i++) w[i] = (float) Math.cos(i);
        for (int r = 0; r < 20; r++) Kernels.linear(x, rows, k, w, out, null, y);
        long t0 = System.nanoTime();
        int reps = 200;
        for (int r = 0; r < reps; r++) Kernels.linear(x, rows, k, w, out, null, y);
        double s = (System.nanoTime() - t0) / 1e9;
        System.out.printf("linear 256x384 -> 1536, one thread: %.1f GFLOPS%n", 2.0 * rows * k * out * reps / s / 1e9);

        try (var enc = new BertEncoder(E5ReferenceTest.MODEL)) {
            String para = "Inteligența artificială este un domeniu al informaticii care studiază cum pot fi construite sisteme capabile să învețe din date. ".repeat(10);
            int tokens = enc.tokenizer().encode("passage: " + para, 512).length;
            var texts = new ArrayList<String>();
            for (int i = 0; i < 64; i++) texts.add("passage: " + para);
            enc.embedAll(texts.subList(0, 16), null);
            long t1 = System.nanoTime();
            enc.embedAll(texts, null);
            double s2 = (System.nanoTime() - t1) / 1e9;
            System.out.printf("embed %d chunks of %d tokens on %d threads: %.2f s = %.1f chunks/s, %.0f tokens/s%n",
                              texts.size(), tokens, Runtime.getRuntime().availableProcessors(), s2, texts.size() / s2, texts.size() * tokens / s2);
            long t2 = System.nanoTime();
            for (int i = 0; i < 20; i++) enc.embed("query: Cine se ocupă de grafice?");
            System.out.printf("one query embedding: %.1f ms%n", (System.nanoTime() - t2) / 1e6 / 20);
        }
    }
}
