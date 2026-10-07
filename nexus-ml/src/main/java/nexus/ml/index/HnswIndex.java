package nexus.ml.index;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Random;
import nexus.ml.tensor.Kernels;

/**
 * A Hierarchical Navigable Small World graph (Malkov &amp; Yashunin, 2016) for approximate
 * nearest-neighbour search over unit vectors, by cosine similarity (dot product).
 *
 * <p>Every vector gets a random top level (geometric, factor 1/ln M). A search descends greedily
 * through the sparse upper levels to land near the query, then runs a best-first search with a
 * candidate list of size ef on the dense bottom level. Insertion searches the same way and links
 * the new node to neighbours chosen with the diversity heuristic (a candidate is skipped if it is
 * closer to an already chosen neighbour than to the new node), which keeps the graph navigable.
 *
 * <p>Distances are 1 - dot(a, b), computed with the Vector API.
 */
public final class HnswIndex {
    private final int dim, m, m0, efConstruction;
    private final double levelMult;
    private final Random rng;
    private float[] vectors = new float[0];
    private int[][][] links = new int[0][][];     // node -> level -> neighbours
    private int[][] counts = new int[0][];        // node -> level -> neighbour count
    private int size;
    private int entry = -1, topLevel = -1;

    public HnswIndex(int dim) {
        this(dim, 16, 200, 42);
    }

    public HnswIndex(int dim, int m, int efConstruction, long seed) {
        this.dim = dim;
        this.m = m;
        this.m0 = 2 * m;
        this.efConstruction = efConstruction;
        this.levelMult = 1 / Math.log(m);
        this.rng = new Random(seed);
    }

    public int size() {
        return size;
    }

    public int dimension() {
        return dim;
    }

    public float[] vector(int id) {
        return Arrays.copyOfRange(vectors, id * dim, (id + 1) * dim);
    }

    private float distance(float[] q, int node) {
        return 1 - Kernels.dot(q, 0, vectors, node * dim, dim);
    }

    private float distance(int a, int b) {
        return 1 - Kernels.dot(vectors, a * dim, vectors, b * dim, dim);
    }

    private void ensureCapacity(int n) {
        if (n <= links.length) return;
        int cap = Math.max(n, links.length * 2 + 16);
        vectors = Arrays.copyOf(vectors, cap * dim);
        links = Arrays.copyOf(links, cap);
        counts = Arrays.copyOf(counts, cap);
    }

    /** Adds a (unit) vector; returns its id (ids are assigned 0, 1, 2, ...). */
    public synchronized int add(float[] v) {
        if (v.length != dim) throw new IllegalArgumentException("vector of " + v.length + " dimensions, index has " + dim);
        int id = size;
        ensureCapacity(id + 1);
        System.arraycopy(v, 0, vectors, id * dim, dim);
        int level = (int) Math.floor(-Math.log(1 - rng.nextDouble()) * levelMult);
        links[id] = new int[level + 1][];
        counts[id] = new int[level + 1];
        for (int l = 0; l <= level; l++) links[id][l] = new int[(l == 0 ? m0 : m) + 1];
        size++;
        if (entry < 0) {
            entry = id;
            topLevel = level;
            return id;
        }
        int cur = entry;
        float curDist = distance(v, cur);
        for (int l = topLevel; l > level; l--) {
            boolean changed = true;
            while (changed) {
                changed = false;
                for (int i = 0; i < counts[cur][l]; i++) {
                    int nb = links[cur][l][i];
                    float d = distance(v, nb);
                    if (d < curDist) {
                        curDist = d;
                        cur = nb;
                        changed = true;
                    }
                }
            }
        }
        for (int l = Math.min(level, topLevel); l >= 0; l--) {
            var found = searchLayer(v, cur, efConstruction, l);
            var chosen = selectNeighbours(id, found, l == 0 ? m0 : m);
            for (int nb : chosen) {
                link(id, nb, l);
                link(nb, id, l);
            }
            cur = (int) found.getFirst()[1];
        }
        if (level > topLevel) {
            topLevel = level;
            entry = id;
        }
        return id;
    }

    /** Adds {@code to} to {@code from}'s list at level l, pruning with the heuristic when full. */
    private void link(int from, int to, int l) {
        int cap = l == 0 ? m0 : m;
        int n = counts[from][l];
        for (int i = 0; i < n; i++) if (links[from][l][i] == to) return;
        if (n < cap) {
            links[from][l][n] = to;
            counts[from][l]++;
            return;
        }
        var candidates = new ArrayList<float[]>(n + 1);
        for (int i = 0; i < n; i++) candidates.add(new float[]{distance(from, links[from][l][i]), links[from][l][i]});
        candidates.add(new float[]{distance(from, to), to});
        candidates.sort((a, b) -> Float.compare(a[0], b[0]));
        var kept = selectNeighbours(from, candidates, cap);
        for (int i = 0; i < kept.size(); i++) links[from][l][i] = kept.get(i);
        counts[from][l] = kept.size();
    }

    /** The diversity heuristic: keep a candidate only if it is closer to the node than to every kept one. */
    private List<Integer> selectNeighbours(int node, List<float[]> sortedCandidates, int max) {
        var kept = new ArrayList<Integer>(max);
        for (var c : sortedCandidates) {
            int cand = (int) c[1];
            if (cand == node) continue;
            boolean good = true;
            for (int k : kept) {
                if (distance(cand, k) < c[0]) {
                    good = false;
                    break;
                }
            }
            if (good) kept.add(cand);
            if (kept.size() >= max) break;
        }
        // Fill up with the nearest ones if the heuristic was too strict.
        if (kept.size() < max)
            for (var c : sortedCandidates) {
                int cand = (int) c[1];
                if (cand != node && !kept.contains(cand)) kept.add(cand);
                if (kept.size() >= max) break;
            }
        return kept;
    }

    /** Best-first search on one level; returns {distance, id} pairs sorted by distance. */
    private List<float[]> searchLayer(float[] q, int start, int ef, int level) {
        var visited = new java.util.BitSet(size);
        var candidates = new PriorityQueue<float[]>((a, b) -> Float.compare(a[0], b[0]));
        var results = new PriorityQueue<float[]>((a, b) -> Float.compare(b[0], a[0]));   // max-heap
        float d0 = distance(q, start);
        candidates.add(new float[]{d0, start});
        results.add(new float[]{d0, start});
        visited.set(start);
        while (!candidates.isEmpty()) {
            var c = candidates.poll();
            if (c[0] > results.peek()[0] && results.size() >= ef) break;
            int node = (int) c[1];
            if (level >= links[node].length) continue;
            for (int i = 0; i < counts[node][level]; i++) {
                int nb = links[node][level][i];
                if (visited.get(nb)) continue;
                visited.set(nb);
                float d = distance(q, nb);
                if (results.size() < ef || d < results.peek()[0]) {
                    candidates.add(new float[]{d, nb});
                    results.add(new float[]{d, nb});
                    if (results.size() > ef) results.poll();
                }
            }
        }
        var out = new ArrayList<>(results);
        out.sort((a, b) -> Float.compare(a[0], b[0]));
        return out;
    }

    /** A search result: the vector id and its cosine similarity to the query. */
    public record Hit(int id, float similarity) {
    }

    /** The k most similar vectors (approximately), exploring ef candidates. */
    public synchronized List<Hit> search(float[] q, int k, int ef) {
        if (entry < 0) return List.of();
        int cur = entry;
        float curDist = distance(q, cur);
        for (int l = topLevel; l > 0; l--) {
            boolean changed = true;
            while (changed) {
                changed = false;
                for (int i = 0; i < counts[cur][l]; i++) {
                    int nb = links[cur][l][i];
                    float d = distance(q, nb);
                    if (d < curDist) {
                        curDist = d;
                        cur = nb;
                        changed = true;
                    }
                }
            }
        }
        var found = searchLayer(q, cur, Math.max(ef, k), 0);
        var hits = new ArrayList<Hit>(k);
        for (int i = 0; i < Math.min(k, found.size()); i++) hits.add(new Hit((int) found.get(i)[1], 1 - found.get(i)[0]));
        return hits;
    }

    /** Exact search (for checking recall). */
    public List<Hit> bruteForce(float[] q, int k) {
        var all = new ArrayList<Hit>(size);
        for (int i = 0; i < size; i++) all.add(new Hit(i, 1 - distance(q, i)));
        all.sort((a, b) -> Float.compare(b.similarity(), a.similarity()));
        return all.subList(0, Math.min(k, all.size()));
    }

    // ---- persistence --------------------------------------------------------------------------------------

    public synchronized void write(DataOutputStream out) throws IOException {
        out.writeInt(dim);
        out.writeInt(m);
        out.writeInt(efConstruction);
        out.writeInt(size);
        out.writeInt(entry);
        out.writeInt(topLevel);
        for (int i = 0; i < size * dim; i++) out.writeFloat(vectors[i]);
        for (int i = 0; i < size; i++) {
            out.writeInt(links[i].length);
            for (int l = 0; l < links[i].length; l++) {
                out.writeInt(counts[i][l]);
                for (int j = 0; j < counts[i][l]; j++) out.writeInt(links[i][l][j]);
            }
        }
    }

    public static HnswIndex read(DataInputStream in) throws IOException {
        int dim = in.readInt(), m = in.readInt(), efc = in.readInt();
        var h = new HnswIndex(dim, m, efc, 42);
        int n = in.readInt();
        h.ensureCapacity(n);
        h.size = n;
        h.entry = in.readInt();
        h.topLevel = in.readInt();
        for (int i = 0; i < n * dim; i++) h.vectors[i] = in.readFloat();
        for (int i = 0; i < n; i++) {
            int levels = in.readInt();
            h.links[i] = new int[levels][];
            h.counts[i] = new int[levels];
            for (int l = 0; l < levels; l++) {
                int c = in.readInt();
                h.links[i][l] = new int[(l == 0 ? h.m0 : h.m) + 1];
                h.counts[i][l] = c;
                for (int j = 0; j < c; j++) h.links[i][l][j] = in.readInt();
            }
        }
        return h;
    }
}
