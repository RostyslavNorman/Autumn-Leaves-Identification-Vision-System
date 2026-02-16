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
 * - Pixels matching selected colors (within tolerance) → WHITE
 * - All other pixels → BLACK
 */
public class ImageProcessor {

    private Image originalImage;
    private WritableImage processedImage;  // Black-white version

    private int width;
    private int height;

    // Selected leaf colors (user can pick multiple)
    private List<Color> selectedColors;

    // Tolerance settings for color matching
    private double hueTolerance = 30.0;        // Degrees (0-360)
    private double saturationTolerance = 0.3;  // 0.0-1.0
    private double brightnessTolerance = 0.3;  // 0.0-1.0

    // Target size for processing (smaller = faster)
    private static final int DEFAULT_PROCESS_SIZE = 512;

    /**
     * Create a new ImageProcessor.
     */
    public ImageProcessor() {
        this.selectedColors = new ArrayList<>();
    }

    /**
     * Load an image from file path or URL.
     * Optionally rescales to target size for faster processing.
     *
     * @param imagePath Path to the image file
     * @param rescale Whether to rescale to default size
     */
    public void loadImage(String imagePath, boolean rescale) {
        originalImage = new Image(imagePath);

        if (rescale) {
            // Rescale to default size while maintaining aspect ratio
            double scale = Math.min(
                    DEFAULT_PROCESS_SIZE / originalImage.getWidth(),
                    DEFAULT_PROCESS_SIZE / originalImage.getHeight()
            );

            width = (int) (originalImage.getWidth() * scale);
            height = (int) (originalImage.getHeight() * scale);
        } else {
            width = (int) originalImage.getWidth();
            height = (int) originalImage.getHeight();
        }

        System.out.println("Loaded image: " + width + "x" + height);
    }

    /**
     * Load an image from JavaFX Image object.
     *
     * @param image The JavaFX Image
     * @param rescale Whether to rescale to default size
     */
    public void loadImage(Image image, boolean rescale) {
        this.originalImage = image;

        if (rescale) {
            double scale = Math.min(
                    DEFAULT_PROCESS_SIZE / image.getWidth(),
                    DEFAULT_PROCESS_SIZE / image.getHeight()
            );

            width = (int) (image.getWidth() * scale);
            height = (int) (image.getHeight() * scale);
        } else {
            width = (int) image.getWidth();
            height = (int) image.getHeight();
        }
    }

    /**
     * Add a color that represents leaves.
     * User can add multiple colors for different leaf shades.
     *
     * @param color The leaf color to match
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
     * Algorithm:
     * 1. For each pixel in the (possibly rescaled) original image
     * 2. Check if pixel color matches any selected leaf color
     * 3. If match → WHITE, else → BLACK
     *
     * @return WritableImage containing black-white version
     */
    public WritableImage convertToBlackAndWhite() {
        if (originalImage == null) {
            throw new IllegalStateException("No image loaded");
        }

        if (selectedColors.isEmpty()) {
            throw new IllegalStateException("No leaf colors selected");
        }

        // Create the scaled image if needed
        Image sourceImage = originalImage;
        if (width != originalImage.getWidth() || height != originalImage.getHeight()) {
            sourceImage = new WritableImage(
                    originalImage.getPixelReader(),
                    (int) originalImage.getWidth(),
                    (int) originalImage.getHeight()
            );
            // Create a properly scaled version
            sourceImage = new Image(
                    originalImage.getUrl(),
                    width,
                    height,
                    true,  // preserveRatio
                    true   // smooth
            );
        }

        processedImage = new WritableImage(width, height);
        PixelReader reader = sourceImage.getPixelReader();
        PixelWriter writer = processedImage.getPixelWriter();

        int whiteCount = 0;

        // Process each pixel
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                Color pixelColor = reader.getColor(x, y);

                // Check if this pixel matches any selected leaf color
                boolean isLeaf = matchesAnyLeafColor(pixelColor);

                if (isLeaf) {
                    writer.setColor(x, y, Color.WHITE);
                    whiteCount++;
                } else {
                    writer.setColor(x, y, Color.BLACK);
                }
            }
        }

        System.out.println("Converted to B&W: " + whiteCount + " white pixels (" +
                (100.0 * whiteCount / (width * height)) + "%)");

        return processedImage;
    }

    /**
     * Check if a pixel color matches any of the selected leaf colors.
     * Uses HSB (Hue, Saturation, Brightness) color space for better matching.
     *
     * @param pixelColor The color to check
     * @return true if pixel matches a leaf color
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
     * Check if two colors match within tolerance.
     * Uses HSB color space for perceptually better matching.
     *
     * HSB is better than RGB for color matching because:
     * - Hue represents the actual color (red, orange, yellow, etc.)
     * - Saturation represents color intensity
     * - Brightness represents lightness
     *
     * @param color1 First color
     * @param color2 Second color
     * @return true if colors match within tolerance
     */
    private boolean colorsMatch(Color color1, Color color2) {
        double hue1 = color1.getHue();
        double sat1 = color1.getSaturation();
        double bri1 = color1.getBrightness();

        double hue2 = color2.getHue();
        double sat2 = color2.getSaturation();
        double bri2 = color2.getBrightness();

        // Handle special case: both colors are grayscale
        if (sat1 < 0.1 && sat2 < 0.1) {
            // For grayscale, only compare brightness
            return Math.abs(bri1 - bri2) <= brightnessTolerance;
        }

        // Hue difference (handle wraparound at 0/360)
        double hueDiff = Math.abs(hue1 - hue2);
        if (hueDiff > 180) {
            hueDiff = 360 - hueDiff;
        }

        // Saturation difference
        double satDiff = Math.abs(sat1 - sat2);

        // Brightness difference
        double briDiff = Math.abs(bri1 - bri2);

        // Color matches if all components are within tolerance
        return hueDiff <= hueTolerance &&
                satDiff <= saturationTolerance &&
                briDiff <= brightnessTolerance;
    }

    /**
     * Check if a pixel at (x, y) is white in the processed image.
     *
     * @param x X coordinate
     * @param y Y coordinate
     * @return true if pixel is white
     */
    public boolean isWhitePixel(int x, int y) {
        if (processedImage == null) {
            throw new IllegalStateException("Image not yet processed");
        }

        if (x < 0 || x >= width || y < 0 || y >= height) {
            return false;
        }

        Color color = processedImage.getPixelReader().getColor(x, y);
        // White pixel has brightness close to 1.0
        return color.getBrightness() > 0.9;
    }

    /**
     * Get pixel color at position (x, y) from processed image.
     *
     * @param x X coordinate
     * @param y Y coordinate
     * @return Color at that position
     */
    public Color getPixelColor(int x, int y) {
        if (processedImage == null) {
            throw new IllegalStateException("Image not yet processed");
        }
        return processedImage.getPixelReader().getColor(x, y);
    }

    /**
     * Convert 2D coordinates to 1D array index.
     * This is crucial for the DisjointSet which uses 1D array.
     *
     * Example for 512x512 image:
     * Pixel at (3, 2) → index = 2 * 512 + 3 = 1027
     *
     * @param x X coordinate
     * @param y Y coordinate
     * @return 1D array index
     */
    public int getPixelIndex(int x, int y) {
        return y * width + x;
    }

    /**
     * Convert 1D index back to 2D coordinates.
     *
     * @param index The 1D array index
     * @return PixelPoint with x, y coordinates
     */
    public Leaf.PixelPoint indexToCoordinates(int index) {
        int y = index / width;
        int x = index % width;
        return new Leaf.PixelPoint(x, y);
    }

    // Getters and Setters

    public Image getOriginalImage() {
        return originalImage;
    }

    public WritableImage getProcessedImage() {
        return processedImage;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public int getTotalPixels() {
        return width * height;
    }

    public double getHueTolerance() {
        return hueTolerance;
    }

    public void setHueTolerance(double hueTolerance) {
        this.hueTolerance = Math.max(0, Math.min(180, hueTolerance));
    }

    public double getSaturationTolerance() {
        return saturationTolerance;
    }

    public void setSaturationTolerance(double saturationTolerance) {
        this.saturationTolerance = Math.max(0, Math.min(1.0, saturationTolerance));
    }

    public double getBrightnessTolerance() {
        return brightnessTolerance;
    }

    public void setBrightnessTolerance(double brightnessTolerance) {
        this.brightnessTolerance = Math.max(0, Math.min(1.0, brightnessTolerance));
    }

    public List<Color> getSelectedColors() {
        return new ArrayList<>(selectedColors);
    }

    /**
     * Helper method to convert Color to readable string.
     */
    private String colorToString(Color color) {
        return String.format("HSB(%.0f°, %.0f%%, %.0f%%)",
                color.getHue(),
                color.getSaturation() * 100,
                color.getBrightness() * 100);
    }
}