package model;

import java.util.*;

/**
 * LeafDetector applies the Union-Find algorithm to detect leaf clusters in an image.
 *
 * Algorithm Steps:
 * 1. Start with each pixel as its own disjoint set
 * 2. Union adjacent white pixels (up, down, left, right)
 * 3. Extract all disjoint sets as Leaf objects
 * 4. Filter out noise (too small or too large clusters)
 * 5. Sort by size and assign sequential numbers
 *
 * This is where Union-Find shines!
 * - Fast: Nearly O(1) operations with path compression
 * - Simple: Just union adjacent pixels
 * - Effective: Automatically identifies connected components
 */
public class LeafDetector {

    private ImageProcessor imageProcessor;
    private DisjointSet disjointSet;
    private List<Leaf> detectedLeaves;

    // Noise filtering parameters
    private int minLeafSize = 10;      // Minimum pixels for valid leaf
    private int maxLeafSize = 50000;   // Maximum pixels for valid leaf

    // Statistics
    private int totalPixels;
    private int whitePixels;
    private int unionOperations;

    /**
     * Create a new LeafDetector.
     *
     * @param imageProcessor The image processor with black-white image
     */
    public LeafDetector(ImageProcessor imageProcessor) {
        this.imageProcessor = imageProcessor;
        this.detectedLeaves = new ArrayList<>();
    }

    /**
     * Detect all leaf clusters in the processed image.
     * This is the main method that applies Union-Find.
     *
     * @return List of detected Leaf objects
     */
    public List<Leaf> detectLeaves() {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("LEAF DETECTION STARTING");
        System.out.println("=".repeat(60));

        long startTime = System.currentTimeMillis();

        // Step 1: Initialize DisjointSet
        totalPixels = imageProcessor.getTotalPixels();
        disjointSet = new DisjointSet(totalPixels);
        System.out.println("Step 1: Initialized " + totalPixels + " disjoint sets");

        // Step 2: Union adjacent white pixels
        unionAdjacentPixels();

        // Step 3: Extract leaf clusters from disjoint sets
        extractLeafClusters();

        // Step 4: Filter out noise
        filterNoise();

        // Step 5: Sort and assign sequential numbers
        assignSequentialNumbers();

        long endTime = System.currentTimeMillis();

        // Print results
        System.out.println("\n" + "=".repeat(60));
        System.out.println("DETECTION COMPLETE");
        System.out.println("=".repeat(60));
        System.out.println("Time taken: " + (endTime - startTime) + " ms");
        System.out.println("Total pixels: " + totalPixels);
        System.out.println("White pixels: " + whitePixels);
        System.out.println("Union operations: " + unionOperations);
        System.out.println("Leaves detected: " + detectedLeaves.size());
        System.out.println("=".repeat(60) + "\n");

        return new ArrayList<>(detectedLeaves);
    }

    /**
     * Step 2: Union adjacent white pixels.
     *
     * For each white pixel, check its neighbors (right and down).
     * We only check right and down to avoid duplicate unions.
     *
     * Why only right and down?
     * - Pixel (x, y) unions with (x+1, y) covers all horizontal connections
     * - Pixel (x, y) unions with (x, y+1) covers all vertical connections
     * - Checking left and up would be redundant
     */
    private void unionAdjacentPixels() {
        System.out.println("Step 2: Unioning adjacent white pixels...");

        int width = imageProcessor.getWidth();
        int height = imageProcessor.getHeight();

        whitePixels = 0;
        unionOperations = 0;

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {

                // Skip black pixels
                if (!imageProcessor.isWhitePixel(x, y)) {
                    continue;
                }

                whitePixels++;
                int currentIndex = imageProcessor.getPixelIndex(x, y);

                // Check RIGHT neighbor (x+1, y)
                if (x + 1 < width && imageProcessor.isWhitePixel(x + 1, y)) {
                    int rightIndex = imageProcessor.getPixelIndex(x + 1, y);
                    if (disjointSet.union(currentIndex, rightIndex)) {
                        unionOperations++;
                    }
                }

                // Check DOWN neighbor (x, y+1)
                if (y + 1 < height && imageProcessor.isWhitePixel(x, y + 1)) {
                    int downIndex = imageProcessor.getPixelIndex(x, y + 1);
                    if (disjointSet.union(currentIndex, downIndex)) {
                        unionOperations++;
                    }
                }
            }
        }

        System.out.println("  - White pixels found: " + whitePixels);
        System.out.println("  - Union operations: " + unionOperations);
    }

    /**
     * Step 3: Extract leaf clusters from disjoint sets.
     *
     * Process:
     * 1. Group all white pixels by their root
     * 2. Create a Leaf object for each unique root
     * 3. Add pixel coordinates to each Leaf
     */
    private void extractLeafClusters() {
        System.out.println("Step 3: Extracting leaf clusters...");

        int width = imageProcessor.getWidth();
        int height = imageProcessor.getHeight();

        // Map: root -> Leaf object
        Map<Integer, Leaf> leafMap = new HashMap<>();

        // Process each white pixel
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {

                if (!imageProcessor.isWhitePixel(x, y)) {
                    continue;
                }

                int pixelIndex = imageProcessor.getPixelIndex(x, y);
                int root = disjointSet.find(pixelIndex);

                // Get or create Leaf for this root
                Leaf leaf = leafMap.get(root);
                if (leaf == null) {
                    int size = disjointSet.getSetSize(root);
                    leaf = new Leaf(root, size);
                    leafMap.put(root, leaf);
                }

                // Add this pixel to the leaf
                leaf.addPixel(x, y);
            }
        }

        detectedLeaves = new ArrayList<>(leafMap.values());
        System.out.println("  - Raw clusters found: " + detectedLeaves.size());
    }

    /**
     * Step 4: Filter out noise.
     *
     * Remove clusters that are too small (noise) or too large (background).
     * Uses simple size thresholds and optionally IQR-based outlier detection.
     */
    private void filterNoise() {
        System.out.println("Step 4: Filtering noise...");

        int beforeCount = detectedLeaves.size();

        // Simple filtering by size
        detectedLeaves.removeIf(leaf -> !leaf.isValidLeaf(minLeafSize, maxLeafSize));

        int afterCount = detectedLeaves.size();
        System.out.println("  - Removed " + (beforeCount - afterCount) + " noise clusters");
        System.out.println("  - Valid leaves remaining: " + afterCount);
    }

    /**
     * Advanced noise filtering using IQR (Interquartile Range) method.
     * This identifies statistical outliers.
     *
     * IQR method:
     * 1. Sort leaf sizes
     * 2. Find Q1 (25th percentile) and Q3 (75th percentile)
     * 3. Calculate IQR = Q3 - Q1
     * 4. Outliers are < Q1 - 1.5*IQR or > Q3 + 1.5*IQR
     */
    public void filterNoiseUsingIQR() {
        if (detectedLeaves.isEmpty()) {
            return;
        }

        System.out.println("Advanced filtering using IQR...");

        // Get all sizes and sort
        List<Integer> sizes = new ArrayList<>();
        for (Leaf leaf : detectedLeaves) {
            sizes.add(leaf.getSize());
        }
        Collections.sort(sizes);

        int n = sizes.size();
        if (n < 4) {
            // Not enough data for IQR
            return;
        }

        // Calculate quartiles
        int q1Index = n / 4;
        int q3Index = 3 * n / 4;

        double q1 = sizes.get(q1Index);
        double q3 = sizes.get(q3Index);
        double iqr = q3 - q1;

        // Calculate bounds
        double lowerBound = q1 - 1.5 * iqr;
        double upperBound = q3 + 1.5 * iqr;

        System.out.printf("  IQR Stats: Q1=%.0f, Q3=%.0f, IQR=%.0f\n", q1, q3, iqr);
        System.out.printf("  Bounds: [%.0f, %.0f]\n", lowerBound, upperBound);

        // Filter outliers
        int beforeCount = detectedLeaves.size();
        detectedLeaves.removeIf(leaf -> {
            int size = leaf.getSize();
            return size < lowerBound || size > upperBound;
        });

        int removed = beforeCount - detectedLeaves.size();
        System.out.println("  - Removed " + removed + " outliers using IQR");
    }

    /**
     * Step 5: Sort leaves by size and assign sequential numbers.
     *
     * Largest leaf gets #1, second largest gets #2, etc.
     */
    private void assignSequentialNumbers() {
        System.out.println("Step 5: Assigning sequential numbers...");

        // Sort by size (descending)
        Collections.sort(detectedLeaves);

        // Assign sequential numbers
        for (int i = 0; i < detectedLeaves.size(); i++) {
            detectedLeaves.get(i).setSequentialNumber(i + 1);
        }

        if (!detectedLeaves.isEmpty()) {
            System.out.println("  - Largest leaf: " + detectedLeaves.get(0).getSize() + " pixels");
            System.out.println("  - Smallest leaf: " +
                    detectedLeaves.get(detectedLeaves.size() - 1).getSize() + " pixels");
        }
    }

    /**
     * Get a specific leaf by its sequential number.
     *
     * @param sequentialNumber The leaf number (1, 2, 3, ...)
     * @return The Leaf object, or null if not found
     */
    public Leaf getLeafByNumber(int sequentialNumber) {
        for (Leaf leaf : detectedLeaves) {
            if (leaf.getSequentialNumber() == sequentialNumber) {
                return leaf;
            }
        }
        return null;
    }

    /**
     * Get the leaf containing a specific pixel.
     *
     * @param x X coordinate
     * @param y Y coordinate
     * @return The Leaf containing this pixel, or null
     */
    public Leaf getLeafAtPixel(int x, int y) {
        if (!imageProcessor.isWhitePixel(x, y)) {
            return null;
        }

        int pixelIndex = imageProcessor.getPixelIndex(x, y);
        int root = disjointSet.find(pixelIndex);

        for (Leaf leaf : detectedLeaves) {
            if (leaf.getRoot() == root) {
                return leaf;
            }
        }

        return null;
    }

    /**
     * Calculate statistics for the detected leaves.
     *
     * @return Map containing various statistics
     */
    public Map<String, Object> getStatistics() {
        Map<String, Object> stats = new HashMap<>();

        stats.put("totalPixels", totalPixels);
        stats.put("whitePixels", whitePixels);
        stats.put("leavesDetected", detectedLeaves.size());
        stats.put("unionOperations", unionOperations);

        if (!detectedLeaves.isEmpty()) {
            int totalLeafPixels = 0;
            for (Leaf leaf : detectedLeaves) {
                totalLeafPixels += leaf.getSize();
            }

            stats.put("averageLeafSize", totalLeafPixels / detectedLeaves.size());
            stats.put("largestLeafSize", detectedLeaves.get(0).getSize());
            stats.put("smallestLeafSize",
                    detectedLeaves.get(detectedLeaves.size() - 1).getSize());
        }

        return stats;
    }

    // Getters and Setters

    public List<Leaf> getDetectedLeaves() {
        return new ArrayList<>(detectedLeaves);
    }

    public DisjointSet getDisjointSet() {
        return disjointSet;
    }

    public int getMinLeafSize() {
        return minLeafSize;
    }

    public void setMinLeafSize(int minLeafSize) {
        this.minLeafSize = Math.max(1, minLeafSize);
    }

    public int getMaxLeafSize() {
        return maxLeafSize;
    }

    public void setMaxLeafSize(int maxLeafSize) {
        this.maxLeafSize = Math.max(minLeafSize, maxLeafSize);
    }

    /**
     * Set both min and max leaf size at once.
     *
     * @param minSize Minimum leaf size
     * @param maxSize Maximum leaf size
     */
    public void setLeafSizeRange(int minSize, int maxSize) {
        this.minLeafSize = Math.max(1, minSize);
        this.maxLeafSize = Math.max(minSize, maxSize);
    }
}