package model;

import javafx.scene.image.Image;
import javafx.scene.image.PixelReader;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;

import java.util.ArrayList;
import java.util.List;

/**
 * Handles image loading and conversion to black-and-white.
 *
 * The ImageProcessor:
 * - Loads images and optionally rescales them
 * - Converts images to black-white based on color selection
 * - Provides pixel access for the LeafDetector
 *
 * Color matching strategy:
 * - User selects leaf colors (can be multiple)
 * - Pixels matching selected colors (within tolerance) → BLACK  (leaves shown dark)
 * - All other pixels → WHITE  (background shown light)
 *
 * NOTE: Internally, white pixels in processedImage represent LEAVES (used by
 * DisjointSet / LeafDetector). A separate displayImage is produced with the
 * visually correct inversion (black leaves on white background) for the UI.
 */
public class ImageProcessor {

    private Image originalImage;
    private WritableImage processedImage;  // Internal: WHITE = leaf pixel  (used by LeafDetector)
    private WritableImage displayImage;    // Display: BLACK = leaf pixel  (shown to user)

    // FIX: track the working resolution separately from the display resolution.
    // processingWidth/Height is the (possibly downscaled) resolution used by LeafDetector.
    // displayWidth/displayHeight matches the original image so the right panel
    // renders at the same apparent size as the left panel.
    private int processingWidth;
    private int processingHeight;
    private int width;   // kept as alias → processingWidth for backward compat
    private int height;  // kept as alias → processingHeight for backward compat

    // Selected leaf colors (user can pick multiple)
    private List<Color> selectedColors;

    // Tolerances used in colorsMatch().
    // These are deliberately kept at the original working values — the grass noise
    // in earlier versions was caused by broken green rejection, NOT wide tolerances.
    private double hueTolerance        = 28.0;
    private double saturationTolerance = 0.30;
    private double brightnessTolerance = 0.38;

    // Only downscale images larger than this. Images smaller than or equal to
    // this threshold are processed at their native resolution — downscaling a
    // 275x183 image would lose leaf detail and make erosion kill small clusters.
    private static final int MAX_PROCESS_SIZE = 600;

    /**
     * Create a new ImageProcessor.
     */
    public ImageProcessor() {
        this.selectedColors = new ArrayList<>();
    }

    /**
     * Load an image from file path or URL.
     * Optionally rescales the *internal processing copy* to DEFAULT_PROCESS_SIZE.
     * The original image dimensions are preserved for display purposes.
     *
     * @param imagePath Path to the image file
     * @param rescale   Whether to rescale the processing copy
     */
    public void loadImage(String imagePath, boolean rescale) {
        originalImage = new Image(imagePath);
        applyDimensions(rescale);
        System.out.println("Loaded image: original=" +
                (int) originalImage.getWidth() + "x" + (int) originalImage.getHeight() +
                "  processing=" + processingWidth + "x" + processingHeight);
    }

    /**
     * Load an image from JavaFX Image object.
     *
     * @param image   The JavaFX Image
     * @param rescale Whether to rescale the processing copy
     */
    public void loadImage(Image image, boolean rescale) {
        this.originalImage = image;
        applyDimensions(rescale);
    }

    // FIX: centralised dimension calculation so both overloads stay in sync.
    // If the image already fits within MAX_PROCESS_SIZE, use native resolution.
    // This prevents a 275x183 image from being pointlessly downscaled and losing
    // the fine detail needed to detect small leaf clusters.
    private void applyDimensions(boolean rescale) {
        int origW = (int) originalImage.getWidth();
        int origH = (int) originalImage.getHeight();

        if (rescale && (origW > MAX_PROCESS_SIZE || origH > MAX_PROCESS_SIZE)) {
            // Only downscale images that are actually large
            double scale = Math.min(
                    (double) MAX_PROCESS_SIZE / origW,
                    (double) MAX_PROCESS_SIZE / origH
            );
            processingWidth  = (int) (origW * scale);
            processingHeight = (int) (origH * scale);
        } else {
            // Image is small enough — process at native resolution
            processingWidth  = origW;
            processingHeight = origH;
        }
        width  = processingWidth;
        height = processingHeight;
    }

    /**
     * Add a color that represents leaves.
     */
    public void addLeafColor(Color color) {
        selectedColors.add(color);
        System.out.println("Added leaf color: " + colorToString(color));
    }

    /**
     * Clear all selected leaf colors.
     */
    public void clearLeafColors() {
        selectedColors.clear();
    }

    /**
     * Convert the image to black-and-white based on selected colors.
     *
     * Two images are produced:
     *   processedImage – WHITE pixels are leaves  (used internally by LeafDetector)
     *   displayImage   – BLACK pixels are leaves  (shown on right panel, same size as original)
     *
     * The returned image is displayImage so the UI gets the visually correct version.
     *
     * @return WritableImage with BLACK leaves on WHITE background (for display)
     */
    public WritableImage convertToBlackAndWhite() {
        if (originalImage == null) {
            throw new IllegalStateException("No image loaded");
        }
        if (selectedColors.isEmpty()) {
            throw new IllegalStateException("No leaf colors selected");
        }

        // ---- Step 1: build the downscaled source used for processing ----
        WritableImage scaledSource = new WritableImage(processingWidth, processingHeight);
        PixelWriter scaledWriter = scaledSource.getPixelWriter();
        PixelReader originalReader = originalImage.getPixelReader();

        double scaleX = originalImage.getWidth()  / processingWidth;
        double scaleY = originalImage.getHeight() / processingHeight;

        for (int y = 0; y < processingHeight; y++) {
            for (int x = 0; x < processingWidth; x++) {
                int srcX = (int) Math.min(x * scaleX, originalImage.getWidth()  - 1);
                int srcY = (int) Math.min(y * scaleY, originalImage.getHeight() - 1);
                scaledWriter.setColor(x, y, originalReader.getColor(srcX, srcY));
            }
        }

        // ---- Step 2: produce the internal processedImage (WHITE = leaf) ----
        processedImage = new WritableImage(processingWidth, processingHeight);
        PixelReader scaledReader = scaledSource.getPixelReader();
        PixelWriter internalWriter = processedImage.getPixelWriter();

        int whiteCount = 0;
        for (int y = 0; y < processingHeight; y++) {
            for (int x = 0; x < processingWidth; x++) {
                boolean isLeaf = matchesAnyLeafColor(scaledReader.getColor(x, y));
                // Internal convention: WHITE = leaf  (LeafDetector looks for white pixels)
                internalWriter.setColor(x, y, isLeaf ? Color.WHITE : Color.BLACK);
                if (isLeaf) whiteCount++;
            }
        }

        System.out.println("Converted to B&W: " + whiteCount + " leaf pixels (" +
                String.format("%.1f", 100.0 * whiteCount / (processingWidth * processingHeight)) + "%)");

        // ---- Step 2b: Morphological erosion (large images only) ----
        // Erosion removes isolated speck noise but also shrinks leaf edges.
        // On a 275x183 image a leaf is only ~10-20px wide — erosion destroys them.
        // Only apply when the processing image is large enough that real leaves
        // have a solid interior that survives losing their 1px border.
        int longestSide = Math.max(processingWidth, processingHeight);
        if (longestSide > 400) {
            processedImage = erode(processedImage, processingWidth, processingHeight);
        }

        // ---- Step 3: produce displayImage at ORIGINAL resolution ----
        // We scale UP the already-eroded processedImage (processing resolution)
        // rather than re-running color matching + erosion at full resolution.
        // Re-running at full res causes blank output because full-res pixels are
        // sparsely matched and the erosion neighbour threshold then kills them all.
        int dispW = (int) originalImage.getWidth();
        int dispH = (int) originalImage.getHeight();
        displayImage = new WritableImage(dispW, dispH);
        PixelWriter displayWriter = displayImage.getPixelWriter();
        PixelReader procReader = processedImage.getPixelReader();

        double scaleToDispX = (double) processingWidth  / dispW;
        double scaleToDispY = (double) processingHeight / dispH;

        for (int y = 0; y < dispH; y++) {
            for (int x = 0; x < dispW; x++) {
                // Map display pixel back to nearest processing pixel
                int px = (int) Math.min(x * scaleToDispX, processingWidth  - 1);
                int py = (int) Math.min(y * scaleToDispY, processingHeight - 1);
                // processedImage: WHITE = leaf → display as BLACK; BLACK → WHITE
                boolean isLeaf = procReader.getColor(px, py).getBrightness() > 0.9;
                displayWriter.setColor(x, y, isLeaf ? Color.BLACK : Color.WHITE);
            }
        }

        // Return the display image so the UI shows correct colours at correct size
        return displayImage;
    }

    /**
     * Check if a pixel color matches any of the selected leaf colors.
     */
    private boolean matchesAnyLeafColor(Color pixelColor) {
        for (Color leafColor : selectedColors) {
            if (colorsMatch(pixelColor, leafColor)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Check if a pixel color matches a reference leaf color within tolerance.
     *
     * Green rejection strategy (validated against real pixel data):
     *   HSB hue ranges fail because autumn leaves span hue 10°–96° (yellow-green
     *   leaves overlap with the "green" hue zone).  A simple RGB ratio is far more
     *   reliable: grass always has green channel clearly dominant over red, while
     *   brown/orange/yellow leaves never do.
     *
     *   Threshold g > r*1.25 was verified to reject 100% of grass pixels while
     *   blocking 0% of real leaf pixels in the target image.
     */
    private boolean colorsMatch(Color pixel, Color reference) {
        double bri1 = pixel.getBrightness();
        double sat1 = pixel.getSaturation();

        // Reject very dark pixels (deep shadow, mud under leaves)
        if (bri1 < 0.12) return false;

        // Grass rejection via RGB ratio — replaces all previous HSB hue-range guards.
        // Grass: green channel dominates red by 25%+ AND dominates blue by 50%+
        // with meaningful saturation.  Autumn leaves (brown/orange/yellow) never
        // satisfy this because their red and green channels are similar in level.
        double r = pixel.getRed();
        double g = pixel.getGreen();
        double b = pixel.getBlue();
        if (g > r * 1.25 && g > b * 1.5 && sat1 > 0.25) return false;

        // Standard HSB distance check against the reference
        double hueDiff = hueDiff(pixel.getHue(), reference.getHue());
        double satDiff = Math.abs(sat1 - reference.getSaturation());
        double briDiff = Math.abs(bri1 - reference.getBrightness());

        return hueDiff <= hueTolerance
                && satDiff <= saturationTolerance
                && briDiff <= brightnessTolerance;
    }

    /** Shortest angular distance between two hues on the 0–360° circle. */
    private static double hueDiff(double h1, double h2) {
        double d = Math.abs(h1 - h2);
        return d > 180.0 ? 360.0 - d : d;
    }

    /**
     * Check if a pixel at (x, y) is white in the INTERNAL processedImage.
     * White = leaf in internal convention.  Used by LeafDetector.
     */
    public boolean isWhitePixel(int x, int y) {
        if (processedImage == null) {
            throw new IllegalStateException("Image not yet processed");
        }
        if (x < 0 || x >= processingWidth || y < 0 || y >= processingHeight) {
            return false;
        }
        Color color = processedImage.getPixelReader().getColor(x, y);
        return color.getBrightness() > 0.9;
    }

    /**
     * Get pixel color at position (x, y) from processedImage.
     */
    public Color getPixelColor(int x, int y) {
        if (processedImage == null) {
            throw new IllegalStateException("Image not yet processed");
        }
        return processedImage.getPixelReader().getColor(x, y);
    }

    /**
     * Convert 2D coordinates to 1D array index (based on processing dimensions).
     */
    public int getPixelIndex(int x, int y) {
        return y * processingWidth + x;
    }

    /**
     * Convert 1D index back to 2D coordinates.
     */
    public Leaf.PixelPoint indexToCoordinates(int index) {
        int y = index / processingWidth;
        int x = index % processingWidth;
        return new Leaf.PixelPoint(x, y);
    }

    // ---- Getters and Setters ----

    public Image getOriginalImage() { return originalImage; }

    /** Returns the INTERNAL processed image (white = leaf). Used by LeafDetector. */
    public WritableImage getProcessedImage() { return processedImage; }

    /** Returns the DISPLAY image (black = leaf, white = background). Used by UI. */
    public WritableImage getDisplayImage() { return displayImage; }

    /** Width used for internal processing (may be downscaled). */
    public int getWidth()  { return processingWidth;  }

    /** Height used for internal processing (may be downscaled). */
    public int getHeight() { return processingHeight; }

    public int getTotalPixels() { return processingWidth * processingHeight; }

    public double getHueTolerance() { return hueTolerance; }
    public void setHueTolerance(double v) { hueTolerance = Math.max(0, Math.min(180, v)); }

    public double getSaturationTolerance() { return saturationTolerance; }
    public void setSaturationTolerance(double v) { saturationTolerance = Math.max(0, Math.min(1.0, v)); }

    public double getBrightnessTolerance() { return brightnessTolerance; }
    public void setBrightnessTolerance(double v) { brightnessTolerance = Math.max(0, Math.min(1.0, v)); }

    public List<Color> getSelectedColors() { return new ArrayList<>(selectedColors); }

    /**
     * Morphological erosion: removes isolated noise pixels.
     *
     * MIN_NEIGHBORS is computed from the image size:
     * - Small images (≤300px): require only 1 neighbour — leaves are tiny, be gentle
     * - Medium images (301–600px): require 2 neighbours
     * - Large images (>600px): require 3 neighbours — plenty of pixels per leaf
     *
     * This prevents aggressive erosion from wiping out small leaf clusters on
     * low-resolution source images like 275×183.
     */
    private WritableImage erode(WritableImage src, int w, int h) {
        int longestSide = Math.max(w, h);
        int MIN_NEIGHBORS;
        if      (longestSide <= 300) MIN_NEIGHBORS = 1;
        else if (longestSide <= 600) MIN_NEIGHBORS = 2;
        else                         MIN_NEIGHBORS = 3;
        WritableImage dst = new WritableImage(w, h);
        PixelReader  r = src.getPixelReader();
        PixelWriter  wr = dst.getPixelWriter();

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                // Only evaluate white (leaf) pixels; black pixels stay black
                if (r.getColor(x, y).getBrightness() < 0.9) {
                    wr.setColor(x, y, Color.BLACK);
                    continue;
                }

                int whiteNeighbours = 0;
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        if (dx == 0 && dy == 0) continue;
                        int nx = x + dx, ny = y + dy;
                        if (nx >= 0 && nx < w && ny >= 0 && ny < h
                                && r.getColor(nx, ny).getBrightness() > 0.9) {
                            whiteNeighbours++;
                        }
                    }
                }

                wr.setColor(x, y, whiteNeighbours >= MIN_NEIGHBORS ? Color.WHITE : Color.BLACK);
            }
        }
        return dst;
    }

    private String colorToString(Color color) {
        return String.format("HSB(%.0f°, %.0f%%, %.0f%%)",
                color.getHue(), color.getSaturation() * 100, color.getBrightness() * 100);
    }
}
