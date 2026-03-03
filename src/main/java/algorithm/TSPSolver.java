package algorithm;

import model.Leaf;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Solves the Traveling Salesman Problem (TSP) to find a path connecting all leaf clusters.
 *
 * This implementation uses the Nearest Neighbor algorithm, which is:
 * - Simple to implement
 * - Fast: O(n²) where n = number of leaves
 * - Produces "good enough" paths for visualization
 * - Not optimal, but adequate for the assignment
 *
 * The path can be used to animate a vacuum cleaner collecting leaves.
 */
public class TSPSolver {

    /**
     * Find a path connecting all leaves using Nearest Neighbor algorithm.
     *
     * Algorithm:
     * 1. Start at the given leaf
     * 2. Repeatedly visit the nearest unvisited leaf
     * 3. Continue until all leaves are visited
     *
     * @param leaves List of all leaf clusters
     * @param startLeaf The leaf to start from (null = use first leaf)
     * @return Ordered list of leaves representing the path
     */
    public static List<Leaf> findPath(List<Leaf> leaves, Leaf startLeaf) {
        if (leaves == null || leaves.isEmpty()) {
            return new ArrayList<>();
        }

        // If no start leaf specified, use the first one
        if (startLeaf == null) {
            startLeaf = leaves.get(0);
        }

        // Verify start leaf is in the list
        if (!leaves.contains(startLeaf)) {
            startLeaf = leaves.get(0);
        }

        List<Leaf> path = new ArrayList<>();
        Set<Leaf> unvisited = new HashSet<>(leaves);

        // Start at the specified leaf
        Leaf current = startLeaf;
        path.add(current);
        unvisited.remove(current);

        // Nearest Neighbor: repeatedly visit closest unvisited leaf
        while (!unvisited.isEmpty()) {
            Leaf nearest = findNearestLeaf(current, unvisited);
            path.add(nearest);
            unvisited.remove(nearest);
            current = nearest;
        }

        return path;
    }

    /**
     * Find a path connecting all leaves, starting from the leaf with given sequential number.
     *
     * @param leaves List of all leaf clusters
     * @param startNumber Sequential number of starting leaf (e.g., 1, 2, 3...)
     * @return Ordered list of leaves representing the path
     */
    public static List<Leaf> findPathFromNumber(List<Leaf> leaves, int startNumber) {
        if (leaves == null || leaves.isEmpty()) {
            return new ArrayList<>();
        }

        // Find the leaf with the given sequential number
        Leaf startLeaf = null;
        for (Leaf leaf : leaves) {
            if (leaf.getSequentialNumber() == startNumber) {
                startLeaf = leaf;
                break;
            }
        }

        // If not found, use first leaf
        if (startLeaf == null) {
            startLeaf = leaves.get(0);
        }

        return findPath(leaves, startLeaf);
    }

    /**
     * Find the nearest unvisited leaf to the current leaf.
     * Uses Euclidean distance between leaf centers.
     *
     * @param current The current leaf
     * @param unvisited Set of unvisited leaves
     * @return The nearest unvisited leaf
     */
    private static Leaf findNearestLeaf(Leaf current, Set<Leaf> unvisited) {
        Leaf nearest = null;
        double minDistance = Double.MAX_VALUE;

        Leaf.PixelPoint currentCenter = current.getCenter();

        for (Leaf candidate : unvisited) {
            Leaf.PixelPoint candidateCenter = candidate.getCenter();
            double distance = calculateDistance(currentCenter, candidateCenter);

            if (distance < minDistance) {
                minDistance = distance;
                nearest = candidate;
            }
        }

        return nearest;
    }

    /**
     * Calculate Euclidean distance between two points.
     *
     * Formula: distance = √[(x₂-x₁)² + (y₂-y₁)²]
     *
     * @param p1 First point
     * @param p2 Second point
     * @return Euclidean distance
     */
    private static double calculateDistance(Leaf.PixelPoint p1, Leaf.PixelPoint p2) {
        int dx = p2.getX() - p1.getX();
        int dy = p2.getY() - p1.getY();
        return Math.sqrt(dx * dx + dy * dy);
    }

    /**
     * Calculate the total path length.
     * Useful for comparing different paths.
     *
     * @param path The ordered list of leaves
     * @return Total distance traveled
     */
    public static double calculatePathLength(List<Leaf> path) {
        if (path == null || path.size() < 2) {
            return 0.0;
        }

        double totalDistance = 0.0;

        for (int i = 0; i < path.size() - 1; i++) {
            Leaf.PixelPoint p1 = path.get(i).getCenter();
            Leaf.PixelPoint p2 = path.get(i + 1).getCenter();
            totalDistance += calculateDistance(p1, p2);
        }

        return totalDistance;
    }

    /**
     * Get the path as a list of sequential numbers for easy display.
     *
     * Example: [3, 7, 1, 5, 2] means visit leaves in order: #3 → #7 → #1 → #5 → #2
     *
     * @param path The ordered list of leaves
     * @return List of sequential numbers
     */
    public static List<Integer> getPathAsNumbers(List<Leaf> path) {
        List<Integer> numbers = new ArrayList<>();
        for (Leaf leaf : path) {
            numbers.add(leaf.getSequentialNumber());
        }
        return numbers;
    }

    /**
     * Format the path as a readable string.
     *
     * Example: "Leaf #3 → Leaf #7 → Leaf #1 → Leaf #5 → Leaf #2"
     *
     * @param path The ordered list of leaves
     * @return Formatted string
     */
    public static String formatPath(List<Leaf> path) {
        if (path == null || path.isEmpty()) {
            return "(empty path)";
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < path.size(); i++) {
            sb.append("Leaf #").append(path.get(i).getSequentialNumber());
            if (i < path.size() - 1) {
                sb.append(" → ");
            }
        }

        double length = calculatePathLength(path);
        sb.append(String.format(" (total distance: %.1f pixels)", length));

        return sb.toString();
    }

    /**
     * Alternative TSP algorithm: Random path (for comparison/testing).
     * Just returns leaves in their current order.
     *
     * @param leaves List of leaves
     * @return The same list (no optimization)
     */
    public static List<Leaf> findRandomPath(List<Leaf> leaves) {
        return new ArrayList<>(leaves);
    }

    /**
     * Alternative TSP algorithm: Greedy path starting from largest leaf.
     * Always starts from leaf #1 (largest).
     *
     * @param leaves List of leaves
     * @return Path starting from largest leaf
     */
    public static List<Leaf> findPathFromLargest(List<Leaf> leaves) {
        if (leaves == null || leaves.isEmpty()) {
            return new ArrayList<>();
        }

        // Find the largest leaf (sequential number 1)
        Leaf largest = null;
        for (Leaf leaf : leaves) {
            if (leaf.getSequentialNumber() == 1) {
                largest = leaf;
                break;
            }
        }

        // If not found, use first leaf
        if (largest == null) {
            largest = leaves.get(0);
        }

        return findPath(leaves, largest);
    }
}