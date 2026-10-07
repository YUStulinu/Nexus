package nexus.ml.tensor;

import jdk.incubator.vector.FloatVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

/**
 * The numeric kernels of the encoder, on the Vector API (AVX2 on x86: 8 floats per vector).
 *
 * <p>The central one is {@link #linear}: Y = X W^T + b, with X [rows x k] and W in PyTorch's
 * [out x k] layout. Both operands are read along k, so each output element is a dot product of two
 * contiguous rows. The loop computes a 4 x 2 tile of outputs at a time: eight vector accumulators
 * that stay in registers while 4 rows of X and 2 rows of W stream through, so every loaded vector
 * is used 2 or 4 times - the register blocking that turns a memory-bound dot product into a
 * compute-bound kernel.
 */
public final class Kernels {
    private Kernels() {
    }

    static final VectorSpecies<Float> S = FloatVector.SPECIES_PREFERRED;
    static final int L = S.length();

    /** Output columns per block: 32 rows of W (32 x 384 floats = 48 KB) stay in L1/L2 while every row of X streams by. */
    static final int COL_BLOCK = 32;

    /** Y[r, o] = b[o] + sum_k X[r, k] * W[o, k]; X is rows x k, W is out x k, Y is rows x out. */
    public static void linear(float[] x, int rows, int k, float[] w, int out, float[] b, float[] y) {
        for (int ob = 0; ob < out; ob += COL_BLOCK) {
            int oe = Math.min(out, ob + COL_BLOCK);
            int r = 0;
            for (; r + 4 <= rows; r += 4) {
                int o = ob;
                for (; o + 2 <= oe; o += 2) tile4x2(x, r, k, w, o, b, y, out);
                for (; o < oe; o++)
                    for (int rr = r; rr < r + 4; rr++) y[rr * out + o] = dot(x, rr * k, w, o * k, k) + (b == null ? 0 : b[o]);
            }
            for (; r < rows; r++)
                for (int o = ob; o < oe; o++) y[r * out + o] = dot(x, r * k, w, o * k, k) + (b == null ? 0 : b[o]);
        }
    }

    private static void tile4x2(float[] x, int r, int k, float[] w, int o, float[] b, float[] y, int out) {
        int x0 = r * k, x1 = x0 + k, x2 = x1 + k, x3 = x2 + k, w0 = o * k, w1 = w0 + k;
        var a00 = FloatVector.zero(S);
        var a01 = FloatVector.zero(S);
        var a10 = FloatVector.zero(S);
        var a11 = FloatVector.zero(S);
        var a20 = FloatVector.zero(S);
        var a21 = FloatVector.zero(S);
        var a30 = FloatVector.zero(S);
        var a31 = FloatVector.zero(S);
        int i = 0;
        int upper = S.loopBound(k);
        for (; i < upper; i += L) {
            var v0 = FloatVector.fromArray(S, w, w0 + i);
            var v1 = FloatVector.fromArray(S, w, w1 + i);
            var u = FloatVector.fromArray(S, x, x0 + i);
            a00 = u.fma(v0, a00);
            a01 = u.fma(v1, a01);
            u = FloatVector.fromArray(S, x, x1 + i);
            a10 = u.fma(v0, a10);
            a11 = u.fma(v1, a11);
            u = FloatVector.fromArray(S, x, x2 + i);
            a20 = u.fma(v0, a20);
            a21 = u.fma(v1, a21);
            u = FloatVector.fromArray(S, x, x3 + i);
            a30 = u.fma(v0, a30);
            a31 = u.fma(v1, a31);
        }
        float s00 = a00.reduceLanes(VectorOperators.ADD), s01 = a01.reduceLanes(VectorOperators.ADD);
        float s10 = a10.reduceLanes(VectorOperators.ADD), s11 = a11.reduceLanes(VectorOperators.ADD);
        float s20 = a20.reduceLanes(VectorOperators.ADD), s21 = a21.reduceLanes(VectorOperators.ADD);
        float s30 = a30.reduceLanes(VectorOperators.ADD), s31 = a31.reduceLanes(VectorOperators.ADD);
        for (; i < k; i++) {
            float v0 = w[w0 + i], v1 = w[w1 + i];
            s00 += x[x0 + i] * v0;
            s01 += x[x0 + i] * v1;
            s10 += x[x1 + i] * v0;
            s11 += x[x1 + i] * v1;
            s20 += x[x2 + i] * v0;
            s21 += x[x2 + i] * v1;
            s30 += x[x3 + i] * v0;
            s31 += x[x3 + i] * v1;
        }
        float b0 = b == null ? 0 : b[o], b1 = b == null ? 0 : b[o + 1];
        y[r * out + o] = s00 + b0;
        y[r * out + o + 1] = s01 + b1;
        y[(r + 1) * out + o] = s10 + b0;
        y[(r + 1) * out + o + 1] = s11 + b1;
        y[(r + 2) * out + o] = s20 + b0;
        y[(r + 2) * out + o + 1] = s21 + b1;
        y[(r + 3) * out + o] = s30 + b0;
        y[(r + 3) * out + o + 1] = s31 + b1;
    }

    public static float dot(float[] a, int ao, float[] b, int bo, int n) {
        var acc = FloatVector.zero(S);
        int i = 0;
        int upper = S.loopBound(n);
        for (; i < upper; i += L) acc = FloatVector.fromArray(S, a, ao + i).fma(FloatVector.fromArray(S, b, bo + i), acc);
        float s = acc.reduceLanes(VectorOperators.ADD);
        for (; i < n; i++) s += a[ao + i] * b[bo + i];
        return s;
    }

    /** x[i] += y[i] over n elements. */
    public static void addInPlace(float[] x, float[] y, int n) {
        int i = 0;
        int upper = S.loopBound(n);
        for (; i < upper; i += L) FloatVector.fromArray(S, x, i).add(FloatVector.fromArray(S, y, i)).intoArray(x, i);
        for (; i < n; i++) x[i] += y[i];
    }

    /** Layer normalisation of each row of x (rows x d), in place. */
    public static void layerNorm(float[] x, int rows, int d, float[] gamma, float[] beta, float eps) {
        for (int r = 0; r < rows; r++) {
            int o = r * d;
            double mean = 0;
            for (int i = 0; i < d; i++) mean += x[o + i];
            mean /= d;
            double var = 0;
            for (int i = 0; i < d; i++) {
                double t = x[o + i] - mean;
                var += t * t;
            }
            float inv = (float) (1.0 / Math.sqrt(var / d + eps));
            float m = (float) mean;
            for (int i = 0; i < d; i++) x[o + i] = (x[o + i] - m) * inv * gamma[i] + beta[i];
        }
    }

    /**
     * Exact GELU, x * Phi(x) = 0.5 x (1 + erf(x / sqrt 2)), in place, vectorised. erf uses the
     * Abramowitz-Stegun 7.1.26 rational approximation (|error| < 1.5e-7) with the Vector API's exp.
     */
    public static void gelu(float[] x, int n) {
        int i = 0;
        int upper = S.loopBound(n);
        var half = FloatVector.broadcast(S, 0.5f);
        var one = FloatVector.broadcast(S, 1f);
        var invSqrt2 = FloatVector.broadcast(S, (float) (1 / Math.sqrt(2)));
        for (; i < upper; i += L) {
            var v = FloatVector.fromArray(S, x, i);
            var z = v.mul(invSqrt2);
            var az = z.abs();
            var t = one.div(az.mul(0.3275911f).add(one));
            var poly = t.mul(1.061429749f).add(-1.453152027f).mul(t).add(1.421413741f).mul(t).add(-0.284496736f).mul(t).add(0.254829592f).mul(t);
            var e = az.mul(az).neg().lanewise(VectorOperators.EXP);
            var erfAbs = one.sub(poly.mul(e));
            var erf = erfAbs.blend(erfAbs.neg(), z.compare(VectorOperators.LT, 0f));
            half.mul(v).mul(one.add(erf)).intoArray(x, i);
        }
        for (; i < n; i++) {
            double v = x[i];
            x[i] = (float) (0.5 * v * (1 + erf(v / Math.sqrt(2))));
        }
    }

    /** erf with |error| < 1.2e-7 (Numerical Recipes erfc, Chebyshev). */
    public static double erf(double x) {
        double z = Math.abs(x);
        double t = 1 / (1 + 0.5 * z);
        double ans = t * Math.exp(-z * z - 1.26551223 + t * (1.00002368 + t * (0.37409196 + t * (0.09678418
                + t * (-0.18628806 + t * (0.27886807 + t * (-1.13520398 + t * (1.48851587
                + t * (-0.82215223 + t * 0.17087277)))))))));
        double r = 1 - ans;
        return x >= 0 ? r : -r;
    }

    /** Softmax of x[o .. o+n) in place (vectorised exp). */
    public static void softmax(float[] x, int o, int n) {
        float max = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < n; i++) max = Math.max(max, x[o + i]);
        int i = 0;
        int upper = S.loopBound(n);
        var acc = FloatVector.zero(S);
        for (; i < upper; i += L) {
            var e = FloatVector.fromArray(S, x, o + i).sub(max).lanewise(VectorOperators.EXP);
            e.intoArray(x, o + i);
            acc = acc.add(e);
        }
        double sum = acc.reduceLanes(VectorOperators.ADD);
        for (; i < n; i++) {
            float e = (float) Math.exp(x[o + i] - max);
            x[o + i] = e;
            sum += e;
        }
        float inv = (float) (1 / sum);
        i = 0;
        for (; i < upper; i += L) FloatVector.fromArray(S, x, o + i).mul(inv).intoArray(x, o + i);
        for (; i < n; i++) x[o + i] *= inv;
    }
}
