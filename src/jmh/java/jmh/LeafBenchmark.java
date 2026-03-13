package jmh;

import algorithm.TSPSolver;
import model.DisjointSet;
import model.Leaf;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.results.format.ResultFormatType;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * ============================================================
 *  JMH Benchmarks — Autumn Leaves Identification System
 * ============================================================
 *
 *  Runtime: ~4-5 minutes total.
 *
 *  Output files produced:
 *    benchmark_results.txt  — human-readable table (console copy)
 *    benchmark_results.csv  — spreadsheet-friendly for reports
 *
 *  Methods benchmarked:
 *    DisjointSet  : find() recursive, find() iterative, union(),
 *                   connected(), getSetSize()
 *    LeafDetector : full Union-Find detection pipeline
 *    TSPSolver    : findPath(), findPathFromNumber(),
 *                   findPathFromLargest(), calculatePathLength(),
 *                   formatPath()
 * ============================================================
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 2, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(value = 1, warmups = 0)
public class LeafBenchmark {

    // =========================================================================
    // PARAMETERS  — 2 values each keeps total combinations low
    // =========================================================================

    /** Small vs large DisjointSet — mirrors 100x100 vs 600x600 processed image. */
    @Param({"10000", "360000"})
    private int disjointSetSize;

    /** Few vs many leaves — shows O(n^2) TSP scaling clearly. */
    @Param({"10", "100"})
    private int leafCount;

    // =========================================================================
    // SHARED FIXTURES
    // =========================================================================

    private DisjointSet freshSet;
    private DisjointSet mergedSet;
    private List<Leaf>  syntheticLeaves;

    @Setup(Level.Trial)
    public void setUp() {
        buildDisjointSets();
        buildSyntheticLeaves();
    }

    private void buildDisjointSets() {
        freshSet  = new DisjointSet(disjointSetSize);
        mergedSet = new DisjointSet(disjointSetSize);
        // Union in blocks of 8 to create trees with real depth
        for (int i = 0; i + 1 < disjointSetSize; i += 8) {
            int limit = Math.min(i + 8, disjointSetSize);
            for (int j = i + 1; j < limit; j++) {
                mergedSet.union(i, j);
            }
        }
    }

    private void buildSyntheticLeaves() {
        syntheticLeaves = new ArrayList<>();
        int cols     = Math.max(1, (int) Math.sqrt(leafCount));
        int gridStep = 900 / Math.max(cols, 1);
        for (int i = 0; i < leafCount; i++) {
            int cx = (i % cols) * gridStep + 50;
            int cy = (i / cols) * gridStep + 50;
            Leaf leaf = new Leaf(i, 100 + i * 7);
            leaf.addPixel(cx - 10, cy - 10);
            leaf.addPixel(cx + 10, cy + 10);
            leaf.setSequentialNumber(i + 1);
            syntheticLeaves.add(leaf);
        }
    }

    // =========================================================================
    // DisjointSet — find()
    // =========================================================================

    /** Baseline: find() on singletons — every element is already its own root. */
    @Benchmark
    public void DisjointSet_find_fresh(Blackhole bh) {
        int step = Math.max(1, disjointSetSize / 200);
        for (int i = 0; i < disjointSetSize; i += step) {
            bh.consume(freshSet.find(i));
        }
    }

    /** Recursive find() with path compression on a merged set (real-world case). */
    @Benchmark
    public void DisjointSet_find_recursive_merged(Blackhole bh) {
        int step = Math.max(1, disjointSetSize / 200);
        for (int i = 0; i < disjointSetSize; i += step) {
            bh.consume(mergedSet.find(i));
        }
    }

    /** Iterative find() (path-halving) — compare vs recursive above. */
    @Benchmark
    public void DisjointSet_find_iterative_merged(Blackhole bh) {
        int step = Math.max(1, disjointSetSize / 200);
        for (int i = 0; i < disjointSetSize; i += step) {
            bh.consume(mergedSet.findIterative(i));
        }
    }

    // =========================================================================
    // DisjointSet — union()
    // =========================================================================

    /**
     * union() needs a fresh set each invocation — it is destructive.
     * Level.Invocation setup rebuilds before every single measured call.
     */
    @State(Scope.Thread)
    public static class UnionState {
        @Param({"10000", "100000"})
        public int size;
        public DisjointSet ds;

        @Setup(Level.Invocation)
        public void setUp() {
            ds = new DisjointSet(size);
        }
    }

    /** union() simulating pixel-adjacency pairs as in LeafDetector. */
    @Benchmark
    public void DisjointSet_union_consecutive_pairs(UnionState state, Blackhole bh) {
        for (int i = 0; i + 1 < state.size; i += 2) {
            bh.consume(state.ds.union(i, i + 1));
        }
    }

    // =========================================================================
    // DisjointSet — connected() and getSetSize()
    // =========================================================================

    /** connected() — two find() calls + root comparison. */
    @Benchmark
    public void DisjointSet_connected(Blackhole bh) {
        int step = Math.max(1, disjointSetSize / 200);
        for (int i = 0; i + step < disjointSetSize; i += step) {
            bh.consume(mergedSet.connected(i, i + step));
        }
    }

    /** getSetSize() — find() + negated parent read. Called per cluster in extraction. */
    @Benchmark
    public void DisjointSet_getSetSize(Blackhole bh) {
        int step = Math.max(1, disjointSetSize / 200);
        for (int i = 0; i < disjointSetSize; i += step) {
            bh.consume(mergedSet.getSetSize(i));
        }
    }

    // =========================================================================
    // LeafDetector — full detection pipeline
    // =========================================================================

    @State(Scope.Thread)
    public static class DetectionState {
        /** Small vs large image — 100x100 and 600x600. */
        @Param({"100", "600"})
        public int imageSize;
        public SyntheticPixelGrid grid;

        @Setup(Level.Trial)
        public void setUp() {
            grid = new SyntheticPixelGrid(imageSize, imageSize);
        }
    }

    /**
     * Full Union-Find pipeline:
     *   init DisjointSet → union adjacent white pixels →
     *   extract clusters → filter noise → sort + number.
     * This is the most important benchmark in the project.
     */
    @Benchmark
    public List<Leaf> LeafDetector_detectLeaves_fullPipeline(DetectionState state, Blackhole bh) {
        List<Leaf> leaves = runDetectionOnGrid(state.grid);
        bh.consume(leaves);
        return leaves;
    }

    /** Runs the detection algorithm on a SyntheticPixelGrid (no JavaFX needed). */
    private List<Leaf> runDetectionOnGrid(SyntheticPixelGrid grid) {
        int w = grid.getWidth();
        int h = grid.getHeight();

        DisjointSet ds = new DisjointSet(w * h);

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (!grid.isWhite(x, y)) continue;
                int idx = y * w + x;
                if (x + 1 < w && grid.isWhite(x + 1, y))
                    ds.union(idx, y * w + (x + 1));
                if (y + 1 < h && grid.isWhite(x, y + 1))
                    ds.union(idx, (y + 1) * w + x);
                if (x + 1 < w && y + 1 < h && grid.isWhite(x + 1, y + 1))
                    ds.union(idx, (y + 1) * w + (x + 1));
                if (x - 1 >= 0 && y + 1 < h && grid.isWhite(x - 1, y + 1))
                    ds.union(idx, (y + 1) * w + (x - 1));
            }
        }

        java.util.Map<Integer, Leaf> leafMap = new java.util.HashMap<>();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (!grid.isWhite(x, y)) continue;
                int root = ds.find(y * w + x);
                Leaf leaf = leafMap.computeIfAbsent(root,
                        r -> new Leaf(r, ds.getSetSize(r)));
                leaf.addPixel(x, y);
            }
        }

        int minSize = Math.max(5, (w * h) / 10000);
        int maxSize = (w * h) / 12;
        List<Leaf> result = new ArrayList<>();
        for (Leaf leaf : leafMap.values()) {
            if (leaf.getSize() >= minSize && leaf.getSize() <= maxSize)
                result.add(leaf);
        }

        result.sort((a, b) -> Integer.compare(b.getSize(), a.getSize()));
        for (int i = 0; i < result.size(); i++)
            result.get(i).setSequentialNumber(i + 1);

        return result;
    }

    // =========================================================================
    // TSPSolver — path finding
    // =========================================================================

    /** Nearest-neighbour TSP. O(n^2) — scaling between 10 and 100 leaves is clear. */
    @Benchmark
    public List<Leaf> TSPSolver_findPath(Blackhole bh) {
        List<Leaf> path = TSPSolver.findPath(syntheticLeaves, syntheticLeaves.get(0));
        bh.consume(path);
        return path;
    }

    /** TSP + linear scan to locate start leaf by sequential number. */
    @Benchmark
    public List<Leaf> TSPSolver_findPathFromNumber(Blackhole bh) {
        List<Leaf> path = TSPSolver.findPathFromNumber(syntheticLeaves, 1);
        bh.consume(path);
        return path;
    }

    /** TSP starting from the largest leaf (leaf #1). */
    @Benchmark
    public List<Leaf> TSPSolver_findPathFromLargest(Blackhole bh) {
        List<Leaf> path = TSPSolver.findPathFromLargest(syntheticLeaves);
        bh.consume(path);
        return path;
    }

    // =========================================================================
    // TSPSolver — utility methods
    // =========================================================================

    /** Sum of Euclidean distances between consecutive leaf centres. */
    @Benchmark
    public double TSPSolver_calculatePathLength(Blackhole bh) {
        double len = TSPSolver.calculatePathLength(syntheticLeaves);
        bh.consume(len);
        return len;
    }

    /** String building for the status bar — verifies it is not a bottleneck. */
    @Benchmark
    public String TSPSolver_formatPath(Blackhole bh) {
        String s = TSPSolver.formatPath(syntheticLeaves);
        bh.consume(s);
        return s;
    }

    // =========================================================================
    // MAIN — runs all benchmarks, writes readable + CSV output
    // =========================================================================

    public static void main(String[] args) throws RunnerException {

        String txtFile = "benchmark_results.txt";
        String csvFile = "benchmark_results.csv";

        // ---- Print a header to the txt file before JMH starts ----
        try (java.io.PrintWriter pw =
                     new java.io.PrintWriter(new java.io.FileWriter(txtFile, false))) {
            pw.println("================================================================");
            pw.println("  AUTUMN LEAVES IDENTIFICATION SYSTEM — JMH BENCHMARK RESULTS");
            pw.println("================================================================");
            pw.println("  Mode    : AverageTime (lower is better)");
            pw.println("  Unit    : microseconds (us)  [1 us = 0.000001 seconds]");
            pw.println("  Warmup  : 2 iterations x 1 s");
            pw.println("  Measure : 3 iterations x 1 s");
            pw.println("  Fork    : 1 JVM per benchmark");
            pw.println();
            pw.println("  Classes benchmarked:");
            pw.println("    model.DisjointSet   - find, union, connected, getSetSize");
            pw.println("    model.LeafDetector  - full detection pipeline");
            pw.println("    algorithm.TSPSolver - findPath, calculatePathLength, formatPath");
            pw.println();
            pw.println("  Parameters:");
            pw.println("    disjointSetSize : 10000 (small) / 360000 (large, ~600x600 image)");
            pw.println("    leafCount       : 10 (few) / 100 (many)");
            pw.println("    imageSize       : 100 (small) / 600 (large)");
            pw.println();
            pw.println("  Started : " + new java.util.Date());
            pw.println("================================================================");
            pw.println();
        } catch (java.io.IOException e) {
            System.err.println("Warning: could not write header to " + txtFile);
        }

        // ---- JMH run — appends results table after our header ----
        Options opt = new OptionsBuilder()
                .include(LeafBenchmark.class.getSimpleName())
                .warmupIterations(2)
                .measurementIterations(3)
                .forks(1)
                .shouldDoGC(true)
                // Human-readable table appended to our txt file
                .resultFormat(ResultFormatType.TEXT)
                .result(txtFile)
                // CSV copy for spreadsheets
                .resultFormat(ResultFormatType.CSV)
                .result(csvFile)
                .build();

        new Runner(opt).run();

        // ---- Append a footer with a plain-English summary ----
        try (java.io.PrintWriter pw =
                     new java.io.PrintWriter(new java.io.FileWriter(txtFile, true))) {
            pw.println();
            pw.println("================================================================");
            pw.println("  HOW TO READ THIS TABLE");
            pw.println("================================================================");
            pw.println("  Benchmark column  : ClassName_methodName");
            pw.println("  (disjointSetSize) : pixel count used for DisjointSet tests");
            pw.println("  (leafCount)       : number of leaves used for TSP tests");
            pw.println("  (imageSize)       : synthetic image side length for detection");
            pw.println("  Score             : average time per operation in microseconds");
            pw.println("  Error (+/-)       : 99.9% confidence interval");
            pw.println();
            pw.println("  KEY FINDINGS TO LOOK FOR:");
            pw.println("  - DisjointSet_find_recursive_merged vs _iterative_merged");
            pw.println("    -> which path-compression strategy is faster?");
            pw.println("  - LeafDetector_detectLeaves_fullPipeline at size 100 vs 600");
            pw.println("    -> does detection time scale roughly as O(n) with pixel count?");
            pw.println("  - TSPSolver_findPath at leafCount 10 vs 100");
            pw.println("    -> TSP is O(n^2), expect ~100x slower with 10x more leaves");
            pw.println();
            pw.println("  Completed : " + new java.util.Date());
            pw.println("================================================================");
        } catch (java.io.IOException e) {
            System.err.println("Warning: could not write footer to " + txtFile);
        }

        System.out.println("\nResults saved to: " + txtFile + "  and  " + csvFile);
    }

    // =========================================================================
    // SYNTHETIC PIXEL GRID — no JavaFX, no file I/O
    // =========================================================================

    /**
     * Plain Java pixel map for the detection pipeline benchmark.
     *
     * Pattern: 20x20 white blobs every 25 pixels.
     * A 100x100 grid produces ~16 blobs; a 600x600 grid produces ~576 blobs.
     * This gives the LeafDetector a realistic number of clusters to find.
     */
    public static class SyntheticPixelGrid {

        private final int       width;
        private final int       height;
        private final boolean[] pixels;

        public SyntheticPixelGrid(int width, int height) {
            this.width  = width;
            this.height = height;
            this.pixels = new boolean[width * height];

            int blobSize = 20;
            int blobStep = 25;

            for (int by = 0; by + blobSize <= height; by += blobStep) {
                for (int bx = 0; bx + blobSize <= width; bx += blobStep) {
                    for (int dy = 0; dy < blobSize; dy++) {
                        for (int dx = 0; dx < blobSize; dx++) {
                            int px = bx + dx;
                            int py = by + dy;
                            if (px < width && py < height) {
                                pixels[py * width + px] = true;
                            }
                        }
                    }
                }
            }
        }

        public boolean isWhite(int x, int y) {
            if (x < 0 || x >= width || y < 0 || y >= height) return false;
            return pixels[y * width + x];
        }

        public int getWidth()  { return width; }
        public int getHeight() { return height; }
    }
}