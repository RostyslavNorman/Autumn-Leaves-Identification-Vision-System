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

    private double hueTolerance = 28.0;
    private double saturationTolerance = 0.30;
    private double brightnessTolerance = 0.38;

    // FIX: raised default processing size so leaves are not over-downscaled.
    // 400 is a good balance between speed and accuracy.
    private static final int DEFAULT_PROCESS_SIZE = 400;

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
    private void applyDimensions(boolean rescale) {
        if (rescale) {
            double scale = Math.min(
                    DEFAULT_PROCESS_SIZE / originalImage.getWidth(),
                    DEFAULT_PROCESS_SIZE / originalImage.getHeight()
            );
            processingWidth  = (int) (originalImage.getWidth()  * scale);
            processingHeight = (int) (originalImage.getHeight() * scale);
        } else {
            processingWidth  = (int) originalImage.getWidth();
            processingHeight = (int) originalImage.getHeight();
        }
        // Keep legacy aliases in sync
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

        // FIX ---- Step 3: produce displayImage at ORIGINAL resolution ----
        // Re-sample the original image at full resolution so the right panel
        // shows at the same apparent size as the left panel.
        int dispW = (int) originalImage.getWidth();
        int dispH = (int) originalImage.getHeight();
        displayImage = new WritableImage(dispW, dispH);
        PixelWriter displayWriter = displayImage.getPixelWriter();

        for (int y = 0; y < dispH; y++) {
            for (int x = 0; x < dispW; x++) {
                Color c = originalReader.getColor(x, y);
                boolean isLeaf = matchesAnyLeafColor(c);
                // FIX: display convention: BLACK = leaf, WHITE = background
                // This makes leaves visually dark on a light background.
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
     * Check if two colors match within tolerance using HSB space.
     */
    private boolean colorsMatch(Color color1, Color color2) {
        double hue1 = color1.getHue();
        double sat1 = color1.getSaturation();
        double bri1 = color1.getBrightness();

        double hue2 = color2.getHue();
        double sat2 = color2.getSaturation();
        double bri2 = color2.getBrightness();

        if (bri1 < 0.15) return false;

        if (hue1 >= 80 && hue1 <= 160 && sat1 > 0.25) return false;

        if (sat1 < 0.15 && sat2 < 0.15) {
            return Math.abs(bri1 - bri2) <= brightnessTolerance;
        }

        if (sat1 < 0.15 && sat2 >= 0.15) {
            return Math.abs(bri1 - bri2) <= brightnessTolerance * 0.6;
        }

        double hueDiff = Math.abs(hue1 - hue2);
        if (hueDiff > 180) hueDiff = 360 - hueDiff;

        double satDiff = Math.abs(sat1 - sat2);
        double briDiff = Math.abs(bri1 - bri2);

        return hueDiff <= hueTolerance &&
                satDiff <= saturationTolerance &&
                briDiff <= brightnessTolerance;
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

    private String colorToString(Color color) {
        return String.format("HSB(%.0f°, %.0f%%, %.0f%%)",
                color.getHue(), color.getSaturation() * 100, color.getBrightness() * 100);
    }
}