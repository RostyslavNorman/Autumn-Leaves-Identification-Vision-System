package model;

import javafx.scene.image.PixelReader;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;

import java.util.*;

/**
 * DisjointSetVisualizer
 *
 * Produces coloured versions of the B&W processed image so the user can see
 * exactly which pixels belong to which disjoint set (leaf cluster).
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * TWO MODES
 * ─────────────────────────────────────────────────────────────────────────────
 *
 *  1. RANDOM MODE  – every detected leaf cluster gets a unique, vivid random
 *                    colour.  Non-leaf pixels stay black.
 *                    Call: randomColourAllSets()
 *
 *  2. SINGLE MODE  – only the disjoint set that contains a chosen pixel is
 *                    coloured (warm orange).  All other leaf pixels become white.
 *                    Non-leaf pixels stay black.
 *                    Call: highlightSingleSet(procX, procY)
 *                       or highlightSingleSet(leaf)
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * HOW IT WORKS
 * ─────────────────────────────────────────────────────────────────────────────
 *
 *  After LeafDetector runs, every white pixel in the processing-resolution image
 *  belongs to exactly one disjoint set identified by its ROOT element ID.
 *
 *  We walk every pixel once:
 *    • Black pixel → always written as BLACK (background, not a leaf).
 *    • White pixel → call DisjointSet.find(pixelIndex) → get root → look up the
 *                    colour assigned to that root in a Map<Integer, Color>.
 *
 *  The resulting image is scaled up to the original display resolution using
 *  nearest-neighbour interpolation (same method as ImageProcessor) so it fits
 *  perfectly in the B&W panel.
 */
public class DisjointSetVisualizer {

    // ── Dependencies ──────────────────────────────────────────────────────────
    private final ImageProcessor imageProcessor;  // source of pixel data & dimensions
    private final DisjointSet    disjointSet;      // union-find structure (for find())
    private final List<Leaf>     detectedLeaves;   // only valid (non-noise) clusters

    // ── Colour constants ──────────────────────────────────────────────────────
    /** Colour used to highlight a single chosen set. */
    private static final Color HIGHLIGHT_COLOR  = Color.rgb(210, 120, 20);  // warm orange

    /** Minimum saturation for random colours (keeps them vivid, not grey). */
    private static final double MIN_SATURATION  = 0.65;

    /** Minimum brightness for random colours (no near-black shades). */
    private static final double MIN_BRIGHTNESS  = 0.70;

    /** Reproducible random colours within one session. */
    private final Random rng = new Random(42);

    // ────────────────────────────────────────────────────────────────────────
    // CONSTRUCTOR
    // ────────────────────────────────────────────────────────────────────────

    /**
     * @param imageProcessor  Holds the processing-resolution B&W image.
     * @param disjointSet     Union-find built by LeafDetector — used only for find().
     * @param detectedLeaves  The filtered list of valid leaf clusters.
     */
    public DisjointSetVisualizer(ImageProcessor imageProcessor,
                                 DisjointSet    disjointSet,
                                 List<Leaf>     detectedLeaves) {
        this.imageProcessor = imageProcessor;
        this.disjointSet    = disjointSet;
        this.detectedLeaves = detectedLeaves;
    }

    // ════════════════════════════════════════════════════════════════════════
    // PUBLIC API
    // ════════════════════════════════════════════════════════════════════════

    /**
     * MODE 1 — Randomly colour all disjoint sets.
     *
     * Each valid leaf cluster gets one unique vivid colour.  Noise clusters
     * that were filtered out remain black (they have no mapping in the colour
     * map, so they fall through to the default — black).
     *
     * @return A display-resolution WritableImage ready for bwImageView.setImage().
     */
    public WritableImage randomColourAllSets() {
        // Build colour map: one random vivid colour per valid leaf root
        Map<Integer, Color> colorMap = new HashMap<>();
        for (Leaf leaf : detectedLeaves) {
            colorMap.put(leaf.getRoot(), randomVividColor());
        }

        // Paint at processing resolution, then scale up to display resolution
        WritableImage proc = paintPixels(colorMap, Color.BLACK);
        return scaleToDisplay(proc);
    }

    /**
     * MODE 2 — Highlight a single disjoint set by processing-space coordinates.
     *
     * The set owning the pixel at (procX, procY) is coloured orange.
     * All other leaf pixels become white so the selected set stands out clearly.
     *
     * @param procX  X in processing space (from canvasToProcessing).
     * @param procY  Y in processing space.
     * @return A display-resolution WritableImage, or null if the pixel is not
     *         part of any valid leaf cluster.
     */
    public WritableImage highlightSingleSet(int procX, int procY) {
        // The clicked pixel must be a leaf pixel
        if (!imageProcessor.isWhitePixel(procX, procY)) return null;

        int pixelIndex = imageProcessor.getPixelIndex(procX, procY);
        int root       = disjointSet.find(pixelIndex);

        // Confirm the root belongs to a valid (non-filtered) leaf
        boolean isValid = detectedLeaves.stream().anyMatch(l -> l.getRoot() == root);
        if (!isValid) return null;

        // Colour map: only this root gets the highlight colour
        Map<Integer, Color> colorMap = new HashMap<>();
        colorMap.put(root, HIGHLIGHT_COLOR);

        // Other white pixels revert to WHITE so they're still visible but de-emphasised
        WritableImage proc = paintPixels(colorMap, Color.WHITE);
        return scaleToDisplay(proc);
    }

    /**
     * MODE 2 overload — Highlight by Leaf object directly.
     * Convenient when the caller already has the Leaf (e.g. from getLeafAtPixel).
     *
     * @param leaf The leaf whose pixels should be highlighted.
     * @return A display-resolution WritableImage.
     */
    public WritableImage highlightSingleSet(Leaf leaf) {
        Map<Integer, Color> colorMap = new HashMap<>();
        colorMap.put(leaf.getRoot(), HIGHLIGHT_COLOR);

        WritableImage proc = paintPixels(colorMap, Color.WHITE);
        return scaleToDisplay(proc);
    }

    /**
     * Restores the plain B&W display image (no colouring).
     * Just returns the display image already stored in ImageProcessor.
     */
    public WritableImage resetToBlackAndWhite() {
        return imageProcessor.getDisplayImage();
    }

    // ════════════════════════════════════════════════════════════════════════
    // PRIVATE HELPERS
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Core pixel-painting loop at processing resolution.
     *
     * For every pixel:
     *   • Black (non-leaf) → written as BLACK.
     *   • White (leaf)     → find its root → look up in colorMap.
     *                        If found: paint that colour.
     *                        If not found: paint defaultLeafColor.
     *
     * @param colorMap         root → Color for sets that should be specially coloured.
     * @param defaultLeafColor fallback colour for leaf pixels with no map entry
     *                         (Color.BLACK hides them; Color.WHITE keeps them visible).
     */
    private WritableImage paintPixels(Map<Integer, Color> colorMap, Color defaultLeafColor) {
        int w  = imageProcessor.getWidth();
        int h  = imageProcessor.getHeight();
        WritableImage result = new WritableImage(w, h);
        PixelWriter   pw     = result.getPixelWriter();

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (!imageProcessor.isWhitePixel(x, y)) {
                    // Background pixel — always black
                    pw.setColor(x, y, Color.BLACK);
                } else {
                    // Leaf pixel — look up its disjoint-set root
                    int   idx   = imageProcessor.getPixelIndex(x, y);
                    int   root  = disjointSet.find(idx);
                    Color color = colorMap.getOrDefault(root, defaultLeafColor);
                    pw.setColor(x, y, color);
                }
            }
        }
        return result;
    }

    /**
     * Scales a processing-resolution image up to the original image's display
     * resolution using nearest-neighbour interpolation.
     *
     * This mirrors the scaling done in ImageProcessor.convertToBlackAndWhite()
     * so the result aligns perfectly with the original image in the SplitPane.
     */
    private WritableImage scaleToDisplay(WritableImage procImage) {
        javafx.scene.image.Image original = imageProcessor.getOriginalImage();
        int dispW = (int) original.getWidth();
        int dispH = (int) original.getHeight();

        WritableImage display = new WritableImage(dispW, dispH);
        PixelWriter   pw      = display.getPixelWriter();
        PixelReader   pr      = procImage.getPixelReader();

        // How many display pixels per processing pixel
        double scaleX = (double) imageProcessor.getWidth()  / dispW;
        double scaleY = (double) imageProcessor.getHeight() / dispH;

        for (int y = 0; y < dispH; y++) {
            for (int x = 0; x < dispW; x++) {
                // Map each display pixel back to the nearest processing pixel
                int px = (int) Math.min(x * scaleX, imageProcessor.getWidth()  - 1);
                int py = (int) Math.min(y * scaleY, imageProcessor.getHeight() - 1);
                pw.setColor(x, y, pr.getColor(px, py));
            }
        }
        return display;
    }

    /**
     * Generates a random vivid colour by picking a random hue and clamping
     * saturation and brightness above their minimum thresholds.
     *
     * Using HSB space guarantees we never accidentally produce near-grey or
     * near-black colours that would be hard to distinguish.
     */
    private Color randomVividColor() {
        double hue = rng.nextDouble() * 360.0;
        double sat = MIN_SATURATION + rng.nextDouble() * (1.0 - MIN_SATURATION);
        double bri = MIN_BRIGHTNESS + rng.nextDouble() * (1.0 - MIN_BRIGHTNESS);
        return Color.hsb(hue, sat, bri);
    }

    // ── Getters (optional, useful for UI status messages) ─────────────────

    /** @return The warm-orange colour used in single-set highlight mode. */
    public static Color getHighlightColor() {
        return HIGHLIGHT_COLOR;
    }
}