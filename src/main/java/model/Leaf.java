package model;

import javafx.geometry.Rectangle2D;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Represents a detected leaf or leaf cluster in an image.
 *
 * Each leaf is a connected component of white pixels identified
 * through union-find algorithm.
 *
 * A Leaf stores:
 * - Size (number of pixels)
 * - Bounding box (rectangle containing all pixels)
 * - Sequential number (ranking by size)
 * - Root element from DisjointSet
 */
public class Leaf implements Comparable<Leaf> {

    private final int root;           // Root element ID from DisjointSet
    private final int size;           // Number of pixels in this leaf cluster
    private int sequentialNumber;     // Ranking: 1=largest, 2=2nd largest, etc.

    // Bounding box coordinates (in original image space)
    private int minX, minY, maxX, maxY;

    // Optional: store actual pixel coordinates for visualization
    private List<PixelPoint> pixels;

    /**
     * Create a new Leaf with the given root and size.
     *
     * @param root The root element ID from DisjointSet
     * @param size Number of pixels in this cluster
     */
    public Leaf(int root, int size) {
        this.root = root;
        this.size = size;
        this.sequentialNumber = -1;  // Will be set later when sorted
        this.pixels = new ArrayList<>();

        // Initialize bounds (will be updated as pixels are added)
        this.minX = Integer.MAX_VALUE;
        this.minY = Integer.MAX_VALUE;
        this.maxX = Integer.MIN_VALUE;
        this.maxY = Integer.MIN_VALUE;
    }

    /**
     * Add a pixel to this leaf cluster and update bounding box.
     *
     * @param x X coordinate of pixel
     * @param y Y coordinate of pixel
     */
    public void addPixel(int x, int y) {
        pixels.add(new PixelPoint(x, y));

        // Update bounding box
        if (x < minX) minX = x;
        if (x > maxX) maxX = x;
        if (y < minY) minY = y;
        if (y > maxY) maxY = y;
    }

    /**
     * Get the bounding rectangle for this leaf.
     * Returns a rectangle in the original image coordinate space.
     *
     * @return Rectangle2D representing the bounding box
     */
    public Rectangle2D getBoundingBox() {
        if (pixels.isEmpty()) {
            return new Rectangle2D(0, 0, 0, 0);
        }

        double width = maxX - minX + 1;
        double height = maxY - minY + 1;
        return new Rectangle2D(minX, minY, width, height);
    }

    /**
     * Get the center point of this leaf cluster.
     *
     * @return PixelPoint representing the center
     */
    public PixelPoint getCenter() {
        if (pixels.isEmpty()) {
            return new PixelPoint(0, 0);
        }

        int centerX = (minX + maxX) / 2;
        int centerY = (minY + maxY) / 2;
        return new PixelPoint(centerX, centerY);
    }

    /**
     * Check if this leaf cluster is likely noise based on size.
     *
     * @param minSize Minimum acceptable size
     * @param maxSize Maximum acceptable size
     * @return true if this is likely a valid leaf (not noise)
     */
    public boolean isValidLeaf(int minSize, int maxSize) {
        return size >= minSize && size <= maxSize;
    }

    /**
     * Compare leaves by size (descending order).
     * Larger leaves come first.
     *
     * This is used for sorting leaves by size to assign sequential numbers.
     */
    @Override
    public int compareTo(Leaf other) {
        // Sort by size in descending order (larger first)
        return Integer.compare(other.size, this.size);
    }

    // Getters and Setters

    public int getRoot() {
        return root;
    }

    public int getSize() {
        return size;
    }

    public int getSequentialNumber() {
        return sequentialNumber;
    }

    public void setSequentialNumber(int sequentialNumber) {
        this.sequentialNumber = sequentialNumber;
    }

    public int getMinX() {
        return minX;
    }

    public int getMinY() {
        return minY;
    }

    public int getMaxX() {
        return maxX;
    }

    public int getMaxY() {
        return maxY;
    }

    public List<PixelPoint> getPixels() {
        return pixels;
    }

    public int getWidth() {
        return maxX - minX + 1;
    }

    public int getHeight() {
        return maxY - minY + 1;
    }

    @Override
    public String toString() {
        return String.format("Leaf #%d [root=%d, size=%d pixels, bounds=(%d,%d)-(%d,%d)]",
                sequentialNumber, root, size, minX, minY, maxX, maxY);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Leaf leaf = (Leaf) o;
        return root == leaf.root;
    }

    @Override
    public int hashCode() {
        return Objects.hash(root);
    }

    /**
     * Simple class to represent a pixel coordinate.
     */
    public static class PixelPoint {
        private final int x;
        private final int y;

        public PixelPoint(int x, int y) {
            this.x = x;
            this.y = y;
        }

        public int getX() {
            return x;
        }

        public int getY() {
            return y;
        }

        @Override
        public String toString() {
            return "(" + x + "," + y + ")";
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            PixelPoint that = (PixelPoint) o;
            return x == that.x && y == that.y;
        }

        @Override
        public int hashCode() {
            return Objects.hash(x, y);
        }
    }
}