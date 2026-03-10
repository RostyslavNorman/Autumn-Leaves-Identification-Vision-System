package jmh;

import model.DisjointSet;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.results.format.ResultFormatType;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * JMH Benchmark for Union-Find operations.
 *
 * Benchmarks key DisjointSet operations with realistic image processing scenarios.
 * Results are output to text file for assignment submission.
 *
 * Run this to generate benchmarks.txt file.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Fork(value = 1, warmups = 0)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 3, time = 1)
public class UnionFindBenchmark {

    // Realistic sizes for image processing
    private static final int SMALL_IMAGE = 256 * 256;   // 65,536 pixels
    private static final int MEDIUM_IMAGE = 512 * 512;  // 262,144 pixels
    private static final int LARGE_IMAGE = 1024 * 1024; // 1,048,576 pixels

    private DisjointSet smallSet;
    private DisjointSet mediumSet;
    private DisjointSet largeSet;
    private Random random;

    @Setup(Level.Trial)
    public void setupTrial() {
        random = new Random(42); // Fixed seed for consistency

        smallSet = new DisjointSet(SMALL_IMAGE);
        mediumSet = new DisjointSet(MEDIUM_IMAGE);
        largeSet = new DisjointSet(LARGE_IMAGE);

        // Pre-populate with some unions (simulate partially processed image)
        performRandomUnions(smallSet, SMALL_IMAGE, 1000);
        performRandomUnions(mediumSet, MEDIUM_IMAGE, 5000);
        performRandomUnions(largeSet, LARGE_IMAGE, 10000);
    }

    private void performRandomUnions(DisjointSet ds, int size, int count) {
        Random r = new Random(42);
        for (int i = 0; i < count; i++) {
            int p = r.nextInt(size);
            int q = r.nextInt(size);
            ds.union(p, q);
        }
    }

    /**
     * Benchmark: Find operation on small image (256x256)
     */
    @Benchmark
    public int findSmallImage() {
        return smallSet.find(random.nextInt(SMALL_IMAGE));
    }

    /**
     * Benchmark: Find operation on medium image (512x512)
     */
    @Benchmark
    public int findMediumImage() {
        return mediumSet.find(random.nextInt(MEDIUM_IMAGE));
    }

    /**
     * Benchmark: Find operation on large image (1024x1024)
     */
    @Benchmark
    public int findLargeImage() {
        return largeSet.find(random.nextInt(LARGE_IMAGE));
    }

    /**
     * Benchmark: Union operation on small image (256x256)
     */
    @Benchmark
    public boolean unionSmallImage() {
        int p = random.nextInt(SMALL_IMAGE);
        int q = random.nextInt(SMALL_IMAGE);
        return smallSet.union(p, q);
    }

    /**
     * Benchmark: Union operation on medium image (512x512)
     */
    @Benchmark
    public boolean unionMediumImage() {
        int p = random.nextInt(MEDIUM_IMAGE);
        int q = random.nextInt(MEDIUM_IMAGE);
        return mediumSet.union(p, q);
    }

    /**
     * Benchmark: Union operation on large image (1024x1024)
     */
    @Benchmark
    public boolean unionLargeImage() {
        int p = random.nextInt(LARGE_IMAGE);
        int q = random.nextInt(LARGE_IMAGE);
        return largeSet.union(p, q);
    }

    /**
     * Benchmark: Connected check on medium image (512x512)
     */
    @Benchmark
    public boolean connectedMediumImage() {
        int p = random.nextInt(MEDIUM_IMAGE);
        int q = random.nextInt(MEDIUM_IMAGE);
        return mediumSet.connected(p, q);
    }

    /**
     * Benchmark: Get set size on medium image (512x512)
     */
    @Benchmark
    public int getSizeMediumImage() {
        return mediumSet.getSetSize(random.nextInt(MEDIUM_IMAGE));
    }

    /**
     * Run the benchmarks and output to text file.
     *
     * Usage: java -cp target/benchmarks.jar benchmark.UnionFindBenchmark
     */
    public static void main(String[] args) throws RunnerException {
        Options opt = new OptionsBuilder()
                .include(UnionFindBenchmark.class.getSimpleName())
                .resultFormat(ResultFormatType.TEXT)
                .result("benchmarks.txt")
                .build();

        new Runner(opt).run();

        System.out.println("\n" + "=".repeat(60));
        System.out.println("Benchmark complete! Results saved to: benchmarks.txt");
        System.out.println("=".repeat(60));
    }
}