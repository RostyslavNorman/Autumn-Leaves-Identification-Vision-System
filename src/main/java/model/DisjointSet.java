package model;

/**
 * Array-based Disjoint Set (Union-Find) data structure.
 *
 * This implementation uses:
 * - Union-by-size for efficient merging
 * - Path compression for faster find operations
 *
 * Each element is represented by an integer ID (0 to n-1).
 * The parent array stores either:
 * - A positive value: the parent element's ID
 * - A negative value: this element is a root, and the absolute value is the set size
 *
 * Example:
 * parent[5] = -10  → Element 5 is a root with 10 elements in its set
 * parent[3] = 5    → Element 3's parent is element 5
 */
public class DisjointSet {

    private int[] parent;  // Stores parent pointers (or negative size for roots)
    private int numSets;   // Number of disjoint sets remaining

    /**
     * Creates a disjoint set structure with n elements.
     * Initially, each element is in its own set (singleton).
     *
     * @param n Number of elements (e.g., number of pixels in image)
     */
    public DisjointSet(int n) {
        parent = new int[n];
        numSets = n;

        // Initialize: each element is a root of its own set with size 1
        for (int i = 0; i < n; i++) {
            parent[i] = -1;  // Negative value means root, size = 1
        }
    }

    /**
     * Find the root of the set containing element id.
     * Uses path compression to flatten the tree structure.
     *
     * How it works:
     * - Start at element id
     * - Follow parent pointers until we find a root (negative value)
     * - While going up, compress the path by pointing elements directly to their grandparent
     *
     * Time complexity: Nearly O(1) amortized with path compression
     *
     * @param id The element to find
     * @return The root element of the set containing id
     */
    public int find(int id) {
        // Validation
        if (id < 0 || id >= parent.length) {
            throw new IllegalArgumentException("Invalid element id: " + id);
        }

        // If parent[id] is negative, id is a root
        if (parent[id] < 0) {
            return id;
        }

        // Path compression: make id point directly to the root
        // This recursively finds the root and updates all elements on the path
        parent[id] = find(parent[id]);
        return parent[id];
    }

    /**
     * Iterative version of find (alternative implementation).
     * Uses path compression by making elements point to their grandparent.
     *
     * @param id The element to find
     * @return The root element of the set containing id
     */
    public int findIterative(int id) {
        if (id < 0 || id >= parent.length) {
            throw new IllegalArgumentException("Invalid element id: " + id);
        }

        // Follow parent pointers until we find a root
        while (parent[id] >= 0) {
            // Path compression: point to grandparent
            if (parent[parent[id]] >= 0) {
                parent[id] = parent[parent[id]];
            }
            id = parent[id];
        }
        return id;
    }

    /**
     * Union two sets containing elements p and q.
     * Uses union-by-size: smaller set becomes subtree of larger set.
     *
     * Why union-by-size?
     * - Keeps trees balanced
     * - Ensures find operations remain fast
     * - The larger set becomes the root, minimizing the number of elements
     *   that need to traverse an extra level
     *
     * @param p First element
     * @param q Second element
     * @return true if sets were merged, false if already in same set
     */
    public boolean union(int p, int q) {
        int rootP = find(p);
        int rootQ = find(q);

        // Already in the same set
        if (rootP == rootQ) {
            return false;
        }

        // Get sizes (remember: stored as negative values)
        int sizeP = -parent[rootP];
        int sizeQ = -parent[rootQ];

        // Union-by-size: attach smaller tree under larger tree
        if (sizeP >= sizeQ) {
            // P's tree is larger (or equal), so Q becomes child of P
            parent[rootQ] = rootP;           // Q points to P
            parent[rootP] = -(sizeP + sizeQ); // Update P's size
        } else {
            // Q's tree is larger, so P becomes child of Q
            parent[rootP] = rootQ;           // P points to Q
            parent[rootQ] = -(sizeP + sizeQ); // Update Q's size
        }

        numSets--;  // One fewer disjoint set
        return true;
    }

    /**
     * Check if two elements are in the same set.
     *
     * @param p First element
     * @param q Second element
     * @return true if p and q are in the same set
     */
    public boolean connected(int p, int q) {
        return find(p) == find(q);
    }

    /**
     * Get the size of the set containing element id.
     *
     * @param id The element
     * @return Number of elements in the set containing id
     */
    public int getSetSize(int id) {
        int root = find(id);
        return -parent[root];  // Size is stored as negative value at root
    }

    /**
     * Get the number of disjoint sets remaining.
     *
     * @return Number of separate sets
     */
    public int getNumSets() {
        return numSets;
    }

    /**
     * Get the total number of elements.
     *
     * @return Total number of elements
     */
    public int size() {
        return parent.length;
    }

    /**
     * Check if an element is a root of its set.
     *
     * @param id The element
     * @return true if id is a root
     */
    public boolean isRoot(int id) {
        return parent[id] < 0;
    }

    /**
     * Print the current state (useful for debugging).
     * Shows each element and its root.
     */
    public void printState() {
        System.out.println("DisjointSet State:");
        System.out.println("Total elements: " + size());
        System.out.println("Number of sets: " + numSets);
        System.out.println("\nElement -> Root (Set Size)");
        for (int i = 0; i < parent.length; i++) {
            int root = find(i);
            int setSize = getSetSize(i);
            System.out.printf("%7d -> %4d (size: %d)\n", i, root, setSize);
        }
    }
}