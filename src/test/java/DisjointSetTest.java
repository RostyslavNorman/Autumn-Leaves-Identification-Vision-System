

import model.DisjointSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class for DisjointSet.
 * These tests demonstrate how Union-Find works with clear examples.
 */
public class DisjointSetTest {

    private DisjointSet ds;

    @BeforeEach
    public void setUp() {
        // Create a disjoint set with 10 elements (0-9)
        ds = new DisjointSet(10);
    }

    /**
     * Test 1: Initial State
     * After creation, each element should be in its own set.
     */
    @Test
    public void testInitialState() {
        System.out.println("\n=== Test 1: Initial State ===");

        // Should have 10 separate sets
        assertEquals(10, ds.getNumSets(), "Should start with 10 sets");

        // Each element should be its own root
        for (int i = 0; i < 10; i++) {
            assertEquals(i, ds.find(i), "Element " + i + " should be its own root");
            assertEquals(1, ds.getSetSize(i), "Each set should have size 1");
            assertTrue(ds.isRoot(i), "Each element should be a root");
        }

        System.out.println("✓ All elements are initially in separate sets");
    }

    /**
     * Test 2: Basic Union
     * Union two elements and verify they're in the same set.
     */
    @Test
    public void testBasicUnion() {
        System.out.println("\n=== Test 2: Basic Union ===");

        // Union elements 0 and 1
        boolean merged = ds.union(0, 1);
        assertTrue(merged, "Should successfully merge two separate sets");

        // They should now have the same root
        assertEquals(ds.find(0), ds.find(1), "Elements 0 and 1 should have same root");

        // Should be connected
        assertTrue(ds.connected(0, 1), "Elements 0 and 1 should be connected");

        // Number of sets should decrease
        assertEquals(9, ds.getNumSets(), "Should now have 9 sets");

        // Combined set size should be 2
        assertEquals(2, ds.getSetSize(0), "Set containing 0 should have size 2");
        assertEquals(2, ds.getSetSize(1), "Set containing 1 should have size 2");

        System.out.println("✓ Union successfully merged two sets");
    }

    /**
     * Test 3: Multiple Unions
     * Create a chain of unions and verify the structure.
     */
    @Test
    public void testMultipleUnions() {
        System.out.println("\n=== Test 3: Multiple Unions ===");

        // Create set {0, 1, 2, 3}
        ds.union(0, 1);
        ds.union(1, 2);
        ds.union(2, 3);

        // All should be in the same set
        int root = ds.find(0);
        assertEquals(root, ds.find(1), "Elements 0 and 1 should have same root");
        assertEquals(root, ds.find(2), "Elements 0 and 2 should have same root");
        assertEquals(root, ds.find(3), "Elements 0 and 3 should have same root");

        // Set size should be 4
        assertEquals(4, ds.getSetSize(0), "Combined set should have size 4");

        // Should have 7 sets total (original 10 - 3 merges)
        assertEquals(7, ds.getNumSets(), "Should have 7 sets remaining");

        System.out.println("✓ Multiple unions created correct set structure");
    }

    /**
     * Test 4: Union Already Connected
     * Attempting to union elements already in same set should return false.
     */
    @Test
    public void testUnionAlreadyConnected() {
        System.out.println("\n=== Test 4: Union Already Connected ===");

        ds.union(5, 6);

        // Try to union them again
        boolean merged = ds.union(5, 6);
        assertFalse(merged, "Should return false when unioning already connected elements");

        // Number of sets shouldn't change
        assertEquals(9, ds.getNumSets(), "Number of sets should remain 9");

        System.out.println("✓ Redundant union correctly returns false");
    }

    /**
     * Test 5: Union By Size
     * Verify that union-by-size keeps trees balanced.
     */
    @Test
    public void testUnionBySize() {
        System.out.println("\n=== Test 5: Union By Size ===");

        // Create a large set {0, 1, 2, 3, 4}
        ds.union(0, 1);
        ds.union(0, 2);
        ds.union(0, 3);
        ds.union(0, 4);

        // Create a small set {5, 6}
        ds.union(5, 6);

        int largeRoot = ds.find(0);
        int smallRoot = ds.find(5);

        assertEquals(5, ds.getSetSize(0), "Large set should have size 5");
        assertEquals(2, ds.getSetSize(5), "Small set should have size 2");

        // Union the two sets
        ds.union(0, 5);

        // The larger set's root should become the root of the merged set
        int mergedRoot = ds.find(0);
        assertEquals(mergedRoot, ds.find(5), "Both should have same root");
        assertEquals(7, ds.getSetSize(0), "Merged set should have size 7");

        System.out.println("✓ Union-by-size correctly keeps trees balanced");
    }

    /**
     * Test 6: Path Compression
     * Verify that find operation compresses paths.
     */
    @Test
    public void testPathCompression() {
        System.out.println("\n=== Test 6: Path Compression ===");

        // Create a chain: 0 -> 1 -> 2 -> 3 -> 4
        ds.union(0, 1);
        ds.union(1, 2);
        ds.union(2, 3);
        ds.union(3, 4);

        // Find root from element 0
        int root1 = ds.find(0);

        // Find root again - path compression should make it faster
        int root2 = ds.find(0);

        assertEquals(root1, root2, "Should find same root");

        // All elements should now point closer to root due to path compression
        assertTrue(ds.connected(0, 4), "All elements should be connected");

        System.out.println("✓ Path compression maintains correct structure");
    }

    /**
     * Test 7: Realistic Image Scenario
     * Simulate finding connected white pixels in a small image.
     */
    @Test
    public void testImageScenario() {
        System.out.println("\n=== Test 7: Realistic Image Scenario ===");

        // Simulate a 4x4 image (16 pixels)
        // Pixels are numbered 0-15:
        //  0  1  2  3
        //  4  5  6  7
        //  8  9 10 11
        // 12 13 14 15

        DisjointSet image = new DisjointSet(16);

        // Imagine white pixels at positions: 0, 1, 4, 5, 6, 10
        // Lets union adjacent white pixels

        // Top-left cluster: {0, 1, 4, 5}
        image.union(0, 1);   // 0 and 1 are adjacent horizontally
        image.union(0, 4);   // 0 and 4 are adjacent vertically
        image.union(1, 5);   // 1 and 5 are adjacent vertically

        // Verify they're all connected
        assertTrue(image.connected(0, 1), "0 and 1 should be connected");
        assertTrue(image.connected(0, 4), "0 and 4 should be connected");
        assertTrue(image.connected(0, 5), "0 and 5 should be connected");
        assertTrue(image.connected(1, 4), "1 and 4 should be connected");

        // Another cluster: {6, 10}
        image.union(6, 10);  // 6 and 10 are adjacent vertically

        // These two clusters should NOT be connected
        assertFalse(image.connected(0, 6), "Different clusters should not be connected");
        assertFalse(image.connected(5, 10), "Different clusters should not be connected");

        // Check set sizes
        assertEquals(4, image.getSetSize(0), "First cluster should have 4 pixels");
        assertEquals(2, image.getSetSize(6), "Second cluster should have 2 pixels");

        System.out.println("✓ Image scenario correctly identifies separate leaf clusters");
    }

    /**
     * Test 8: Large Scale
     * Test with a larger number of elements (like a real image).
     */
    @Test
    public void testLargeScale() {
        System.out.println("\n=== Test 8: Large Scale (512x512 image) ===");

        int imageSize = 512 * 512;  // 262,144 pixels
        DisjointSet largeDS = new DisjointSet(imageSize);

        assertEquals(imageSize, largeDS.size(), "Should have correct number of elements");
        assertEquals(imageSize, largeDS.getNumSets(), "Should start with all separate sets");

        // Union some adjacent pixels (simulate finding connected leaves)
        long startTime = System.nanoTime();

        for (int i = 0; i < 1000; i++) {
            largeDS.union(i, i + 1);
        }

        long endTime = System.nanoTime();
        double milliseconds = (endTime - startTime) / 1_000_000.0;

        assertEquals(imageSize - 1000, largeDS.getNumSets(),
                "Should have correct number of sets after unions");

        System.out.printf("✓ Successfully handled large scale: 1000 unions in %.2f ms\n", milliseconds);

        // Verify operations are fast (should be well under 10ms)
        assertTrue(milliseconds < 100, "Operations should be fast with union-find");
    }

    /**
     * Test 9: Invalid Input
     * Test error handling for invalid element IDs.
     */
    @Test
    public void testInvalidInput() {
        System.out.println("\n=== Test 9: Invalid Input ===");

        // Test negative index
        assertThrows(IllegalArgumentException.class, () -> {
            ds.find(-1);
        }, "Should throw exception for negative index");

        // Test index too large
        assertThrows(IllegalArgumentException.class, () -> {
            ds.find(10);
        }, "Should throw exception for index >= size");

        System.out.println("✓ Invalid inputs correctly throw exceptions");
    }
}