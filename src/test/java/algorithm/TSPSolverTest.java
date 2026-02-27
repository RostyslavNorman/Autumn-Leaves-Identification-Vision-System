package algorithm;

import model.Leaf;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Test class for TSPSolver.
 * Verifies that the nearest neighbor algorithm works correctly.
 */
public class TSPSolverTest {

    private List<Leaf> leaves;

    @BeforeEach
    public void setUp() {
        // Create a simple set of leaves for testing
        leaves = new ArrayList<>();

        // Create 5 leaves at known positions
        // Leaf 1: Center at (10, 10) - size 100
        Leaf leaf1 = new Leaf(0, 100);
        leaf1.addPixel(10, 10);
        leaf1.setSequentialNumber(1);
        leaves.add(leaf1);

        // Leaf 2: Center at (30, 10) - size 80
        Leaf leaf2 = new Leaf(1, 80);
        leaf2.addPixel(30, 10);
        leaf2.setSequentialNumber(2);
        leaves.add(leaf2);

        // Leaf 3: Center at (50, 10) - size 60
        Leaf leaf3 = new Leaf(2, 60);
        leaf3.addPixel(50, 10);
        leaf3.setSequentialNumber(3);
        leaves.add(leaf3);

        // Leaf 4: Center at (10, 30) - size 40
        Leaf leaf4 = new Leaf(3, 40);
        leaf4.addPixel(10, 30);
        leaf4.setSequentialNumber(4);
        leaves.add(leaf4);

        // Leaf 5: Center at (30, 30) - size 20
        Leaf leaf5 = new Leaf(4, 20);
        leaf5.addPixel(30, 30);
        leaf5.setSequentialNumber(5);
        leaves.add(leaf5);
    }

    @Test
    public void testFindPathBasic() {
        System.out.println("\n=== Test: Basic Path Finding ===");

        // Find path starting from leaf 1
        List<Leaf> path = TSPSolver.findPath(leaves, leaves.get(0));

        // Should visit all leaves
        assertEquals(5, path.size(), "Path should visit all 5 leaves");

        // First leaf should be the start leaf
        assertEquals(1, path.get(0).getSequentialNumber(), "Path should start at leaf 1");

        // All leaves should be included
        for (Leaf leaf : leaves) {
            assertTrue(path.contains(leaf), "Path should contain leaf #" + leaf.getSequentialNumber());
        }

        System.out.println("Path found: " + TSPSolver.formatPath(path));
        System.out.println("✓ Basic path finding works");
    }

    @Test
    public void testFindPathFromNumber() {
        System.out.println("\n=== Test: Find Path From Number ===");

        // Start from leaf #3
        List<Leaf> path = TSPSolver.findPathFromNumber(leaves, 3);

        // Should start at leaf 3
        assertEquals(3, path.get(0).getSequentialNumber(), "Path should start at leaf 3");

        // Should visit all leaves
        assertEquals(5, path.size(), "Path should visit all 5 leaves");

        System.out.println("Path starting from leaf #3: " + TSPSolver.formatPath(path));
        System.out.println("✓ Path from specific number works");
    }

    @Test
    public void testNearestNeighborLogic() {
        System.out.println("\n=== Test: Nearest Neighbor Logic ===");

        // Starting from (10, 10), nearest should be (30, 10)
        // Distance: sqrt((30-10)² + (10-10)²) = 20

        List<Leaf> path = TSPSolver.findPath(leaves, leaves.get(0));

        // Starting at leaf 1 (10, 10)
        // Nearest is leaf 2 (30, 10) - distance 20
        // Next nearest from (30, 10) could be leaf 3 (50, 10) or leaf 5 (30, 30)

        assertEquals(1, path.get(0).getSequentialNumber(), "First: Leaf 1 at (10,10)");

        // Second should be nearest to (10, 10), which is leaf 2 or leaf 4
        // Leaf 2 at (30, 10): distance = 20
        // Leaf 4 at (10, 30): distance = 20
        // Either is valid (tie), so just check it's one of them
        int secondLeaf = path.get(1).getSequentialNumber();
        assertTrue(secondLeaf == 2 || secondLeaf == 4,
                "Second leaf should be #2 or #4 (both distance 20)");

        System.out.println("Path uses nearest neighbor: " + TSPSolver.formatPath(path));
        System.out.println("✓ Nearest neighbor logic correct");
    }

    @Test
    public void testCalculatePathLength() {
        System.out.println("\n=== Test: Calculate Path Length ===");

        List<Leaf> path = TSPSolver.findPath(leaves, leaves.get(0));
        double length = TSPSolver.calculatePathLength(path);

        // Path length should be positive
        assertTrue(length > 0, "Path length should be positive");

        // For our test case, the path length should be reasonable
        // (less than the total distance of visiting all points)
        assertTrue(length < 500, "Path length should be reasonable");

        System.out.println("Path length: " + length + " pixels");
        System.out.println("✓ Path length calculation works");
    }

    @Test
    public void testGetPathAsNumbers() {
        System.out.println("\n=== Test: Get Path As Numbers ===");

        List<Leaf> path = TSPSolver.findPathFromNumber(leaves, 1);
        List<Integer> numbers = TSPSolver.getPathAsNumbers(path);

        // Should have same size as path
        assertEquals(path.size(), numbers.size(), "Numbers list should match path size");

        // First number should be 1 (starting leaf)
        assertEquals(1, numbers.get(0), "Should start at leaf 1");

        // All numbers should be between 1 and 5
        for (int num : numbers) {
            assertTrue(num >= 1 && num <= 5, "Number should be between 1 and 5");
        }

        System.out.println("Path as numbers: " + numbers);
        System.out.println("✓ Path conversion to numbers works");
    }

    @Test
    public void testFormatPath() {
        System.out.println("\n=== Test: Format Path ===");

        List<Leaf> path = TSPSolver.findPath(leaves, leaves.get(0));
        String formatted = TSPSolver.formatPath(path);

        // Should contain "Leaf #"
        assertTrue(formatted.contains("Leaf #"), "Formatted path should contain 'Leaf #'");

        // Should contain arrow
        assertTrue(formatted.contains("→"), "Formatted path should contain arrows");

        // Should contain distance
        assertTrue(formatted.contains("distance"), "Formatted path should show distance");

        System.out.println("Formatted path: " + formatted);
        System.out.println("✓ Path formatting works");
    }

    @Test
    public void testEmptyList() {
        System.out.println("\n=== Test: Empty List ===");

        List<Leaf> emptyList = new ArrayList<>();
        List<Leaf> path = TSPSolver.findPath(emptyList, null);

        // Should return empty path
        assertEquals(0, path.size(), "Empty list should produce empty path");

        System.out.println("✓ Empty list handled correctly");
    }

    @Test
    public void testSingleLeaf() {
        System.out.println("\n=== Test: Single Leaf ===");

        List<Leaf> singleList = new ArrayList<>();
        singleList.add(leaves.get(0));

        List<Leaf> path = TSPSolver.findPath(singleList, null);

        // Should return path with one leaf
        assertEquals(1, path.size(), "Single leaf should produce path of length 1");
        assertEquals(leaves.get(0), path.get(0), "Should contain the single leaf");

        // Path length should be 0
        assertEquals(0.0, TSPSolver.calculatePathLength(path), "Single leaf path has zero length");

        System.out.println("✓ Single leaf handled correctly");
    }

    @Test
    public void testPathFromLargest() {
        System.out.println("\n=== Test: Path From Largest ===");

        List<Leaf> path = TSPSolver.findPathFromLargest(leaves);

        // Should start from leaf #1 (largest)
        assertEquals(1, path.get(0).getSequentialNumber(), "Should start from largest leaf (#1)");

        // Should visit all leaves
        assertEquals(5, path.size(), "Should visit all leaves");

        System.out.println("Path from largest: " + TSPSolver.formatPath(path));
        System.out.println("✓ Path from largest works");
    }

    @Test
    public void testComparePathLengths() {
        System.out.println("\n=== Test: Compare Different Paths ===");

        // Find path from different starting points
        List<Leaf> path1 = TSPSolver.findPathFromNumber(leaves, 1);
        List<Leaf> path2 = TSPSolver.findPathFromNumber(leaves, 3);
        List<Leaf> path3 = TSPSolver.findPathFromNumber(leaves, 5);

        double length1 = TSPSolver.calculatePathLength(path1);
        double length2 = TSPSolver.calculatePathLength(path2);
        double length3 = TSPSolver.calculatePathLength(path3);

        System.out.println("Path from leaf #1: " + length1 + " pixels");
        System.out.println("Path from leaf #3: " + length2 + " pixels");
        System.out.println("Path from leaf #5: " + length3 + " pixels");

        // All paths should be positive
        assertTrue(length1 > 0, "Path 1 length should be positive");
        assertTrue(length2 > 0, "Path 2 length should be positive");
        assertTrue(length3 > 0, "Path 3 length should be positive");

        System.out.println("✓ Can compare different paths");
    }
}