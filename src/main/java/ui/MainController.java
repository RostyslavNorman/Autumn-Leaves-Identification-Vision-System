package ui;

import algorithm.TSPSolver;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.util.Duration;
import model.*;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Main controller for the Autumn Leaves Identification System GUI.
 *
 * Changes in this version:
 * 1. Leaf numbers are ALWAYS visible on canvas (persistent), not just on hover.
 * 2. TSP animation draws orange dashed lines connecting leaf centres, with
 *    filled circles at each stop. Lines persist after animation finishes.
 * 3. Color palette panel in the toolbar: shows color swatches for every
 *    selected color, allows removing individual colors, and has an "Add Color"
 *    button that opens a standard ColorPicker dialog.
 * 4. RESET button: clears all state (animation, detection, colors, B&W image)
 *    so the user can start fresh with the same or a different image.
 * 5. Color selection is now UNLIMITED — the 3-color minimum gate is removed.
 *    Even a single color is enough to trigger B&W conversion.
 * 6. Magnifier loupe during color-picking mode for precise pixel selection.
 *    Fixed coordinate mapping so clicks always hit the correct pixel.
 */
public class MainController {

    // ---- FXML injected nodes ----
    @FXML private ImageView imageView;
    @FXML private ImageView bwImageView;
    @FXML private Canvas    overlayCanvas;
    @FXML private StackPane imageStackPane;

    @FXML private MenuItem      menuOpenImage;
    @FXML private MenuItem      menuSelectColors;
    @FXML private MenuItem      menuConvertBW;
    @FXML private MenuItem      menuDetectLeaves;
    @FXML private MenuItem      menuAnimatePath;
    @FXML private MenuItem      menuStopAnimation;
    @FXML private MenuItem      menuSettings;
    @FXML private CheckMenuItem menuShowOriginal;
    @FXML private CheckMenuItem menuShowBW;
    @FXML private CheckMenuItem menuShowRectangles;
    @FXML private CheckMenuItem menuShowNumbers;

    @FXML private Button btnSelectColors;
    @FXML private Button btnConvertBW;
    @FXML private Button btnDetectLeaves;
    @FXML private Button btnAnimatePath;

    /** Reset button — wired in FXML with fx:id="btnReset" onAction="#handleReset" */
    @FXML private Button btnReset;

    @FXML private Label       statusLabel;
    @FXML private Label       labelImageInfo;
    @FXML private Label       labelColorsInfo;
    @FXML private Label       labelLeavesCount;
    @FXML private Label       labelProcessingTime;
    @FXML private ProgressBar progressBar;

    /**
     * Color palette HBox injected from FXML (fx:id="colorPaletteBox").
     * Lives in the toolbar row and shows live color swatches.
     */
    @FXML private HBox colorPaletteBox;

    // ---- Application state ----
    private Stage          primaryStage;
    private ImageProcessor imageProcessor;
    private LeafDetector   leafDetector;
    private List<Leaf>     detectedLeaves;
    private Timeline       animationTimeline;

    /** Whether to draw bounding rectangles. Default: true. */
    private boolean showRectangles = true;

    /**
     * showNumbers defaults to TRUE — numbers are always painted.
     * View > Show Leaf Numbers menu item toggles this field.
     */
    private boolean showNumbers = true;

    /** Leaf last clicked by the user — drawn with an orange border. */
    private Leaf selectedLeaf = null;

    /** Leaf currently under the mouse cursor — drawn in green (transient). */
    private Leaf hoveredLeaf = null;

    /** Floating tooltip shown while hovering. */
    private final Tooltip leafTooltip = new Tooltip();

    /**
     * The last computed TSP path. Kept so orange lines are redrawn after
     * any overlay refresh (e.g. toggling numbers, clicking a leaf).
     */
    private List<Leaf> lastTSPPath = null;

    /** True after an animation has run — controls whether path lines are drawn. */
    private boolean showTSPPath = false;

    /** Settings window — singleton to prevent duplicates. */
    private Stage  settingsStage  = null;

    /** Background thread for live-preview BW conversion. */
    private volatile Thread previewThread = null;

    // ---- Magnifier loupe state ----
    /** The floating magnifier canvas shown during color-picking mode. */
    private Canvas magnifierCanvas = null;

    /** Whether color-picking mode is currently active. */
    private boolean colorPickingMode = false;

    /** Size of the magnifier loupe in pixels (display size). */
    private static final int MAGNIFIER_SIZE = 120;

    /** Zoom factor: how many source pixels are captured. E.g. 11 = 11x11 src pixels. */
    private static final int ZOOM_PIXELS = 11;

    // ========================================================================
    // INIT
    // ========================================================================

    @FXML
    public void initialize() {
        System.out.println("Initializing MainController...");

        imageProcessor = new ImageProcessor();
        detectedLeaves = new ArrayList<>();

        overlayCanvas.setOnMouseMoved(this::handleCanvasHover);
        overlayCanvas.setOnMouseExited(e -> clearHover());
        overlayCanvas.setOnMouseClicked(this::handleCanvasClick);

        leafTooltip.setStyle("-fx-font-size: 12px;");
        leafTooltip.setShowDelay(Duration.ZERO);
        leafTooltip.setHideDelay(Duration.ZERO);
        leafTooltip.setShowDuration(Duration.INDEFINITE);

        updateStatus("Ready. Load an image to begin.");
        refreshColorPalettePanel();
        System.out.println("MainController initialized");
    }

    public void setPrimaryStage(Stage stage) {
        this.primaryStage = stage;
    }

    // ========================================================================
    // FILE MENU
    // ========================================================================

    @FXML
    private void handleOpenImage() {
        FileChooser fc = new FileChooser();
        fc.setTitle("Open Autumn Leaves Image");
        fc.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Image Files",
                        "*.png", "*.jpg", "*.jpeg", "*.gif", "*.bmp"),
                new FileChooser.ExtensionFilter("All Files", "*.*"));
        File file = fc.showOpenDialog(primaryStage);
        if (file != null) loadImage(file);
    }

    @FXML
    private void handleExit() {
        Platform.exit();
    }

    // ========================================================================
    // PROCESS MENU
    // ========================================================================

    @FXML
    private void handleSelectColors() {
        if (imageView.getImage() == null) {
            showError("No Image", "Please load an image first.");
            return;
        }

        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("Select Leaf Colors");
        alert.setHeaderText("Click on leaf pixels to pick colors");
        alert.setContentText(
                "Click directly on leaf areas in the left image.\n" +
                        "A magnifier loupe will appear near your cursor for precision.\n" +
                        "Each click adds one color to the palette panel.\n" +
                        "Click 'Done Picking' in the toolbar when finished.\n\n" +
                        "You can also use '+ Add Color' in the palette panel\n" +
                        "to pick any color without clicking the image.\n\n" +
                        "There is no minimum — even one color is enough.");
        alert.showAndWait();

        enterColorPickingMode();
    }

    /**
     * Switches the UI into color-picking mode:
     * - Canvas becomes pass-through so imageView receives clicks.
     * - A magnifier loupe appears near the cursor for precise color selection.
     * - A "Done Picking" button appears in the toolbar.
     */
    private void enterColorPickingMode() {
        colorPickingMode = true;
        updateStatus("Color-picking mode: hover to magnify, click on leaves. Press 'Done Picking' when finished.");

        overlayCanvas.setMouseTransparent(true);

        // Create magnifier canvas and add it to the StackPane
        magnifierCanvas = new Canvas(MAGNIFIER_SIZE + 20, MAGNIFIER_SIZE + 40);
        magnifierCanvas.setMouseTransparent(true);
        magnifierCanvas.setVisible(false);
        if (imageStackPane != null) {
            imageStackPane.getChildren().add(magnifierCanvas);
        }

        // Wire mouse events on the imageView
        imageView.setOnMouseMoved(this::handleColorPickHover);
        imageView.setOnMouseExited(e -> hideMagnifier());
        imageView.setOnMouseClicked(this::handleImageClickForColor);

        // Swap button
        btnSelectColors.setText("Done Picking");
        btnSelectColors.setStyle("-fx-background-color: #F57C00; -fx-text-fill: white;");
        btnSelectColors.setOnAction(e -> exitColorPickingMode());
    }

    /** Exits color-picking mode and restores the normal Select Colors button. */
    private void exitColorPickingMode() {
        colorPickingMode = false;

        imageView.setOnMouseMoved(null);
        imageView.setOnMouseExited(null);
        imageView.setOnMouseClicked(null);
        overlayCanvas.setMouseTransparent(false);

        // Remove and destroy the magnifier canvas
        hideMagnifier();
        if (magnifierCanvas != null && imageStackPane != null) {
            imageStackPane.getChildren().remove(magnifierCanvas);
            magnifierCanvas = null;
        }

        btnSelectColors.setText("Select Colors");
        btnSelectColors.setStyle("");
        btnSelectColors.setOnAction(e -> handleSelectColors());

        int count = imageProcessor.getSelectedColors().size();
        if (count == 0) {
            updateStatus("No colors selected yet. Add colors before converting.");
        } else {
            updateStatus(count + " color(s) selected. Ready to convert to B&W.");
            enableProcessingButtons();
        }
    }

    /**
     * Computes the image-space (x, y) from a mouse event on the ImageView.
     *
     * ImageView uses preserveRatio=true with fitWidth/fitHeight, so the image
     * is letter-boxed inside the fit rectangle.  The event coordinates are
     * relative to the ImageView node itself — NOT the StackPane — so we must
     * account for the internal letterbox offset only (no StackPane offset needed).
     *
     * @return double[]{imageX, imageY} in original image pixels, or null if outside image bounds
     */
    private double[] imageViewToImageCoords(MouseEvent event) {
        Image image = imageView.getImage();
        if (image == null) return null;

        double imgW  = image.getWidth();
        double imgH  = image.getHeight();
        double fitW  = imageView.getFitWidth();
        double fitH  = imageView.getFitHeight();

        // Scale that preserves ratio and fits inside fitW x fitH
        double scale   = Math.min(fitW / imgW, fitH / imgH);
        double rendW   = imgW * scale;
        double rendH   = imgH * scale;

        // Letter-box offsets (within the ImageView node bounds)
        double offsetX = (fitW - rendW) / 2.0;
        double offsetY = (fitH - rendH) / 2.0;

        double imageX = (event.getX() - offsetX) / scale;
        double imageY = (event.getY() - offsetY) / scale;

        if (imageX < 0 || imageX >= imgW || imageY < 0 || imageY >= imgH) return null;
        return new double[]{imageX, imageY};
    }

    /**
     * Handles mouse movement over the image during color-picking mode.
     * Updates the magnifier loupe to show a zoomed view around the cursor.
     */
    private void handleColorPickHover(MouseEvent event) {
        if (!colorPickingMode || magnifierCanvas == null) return;

        double[] coords = imageViewToImageCoords(event);
        if (coords == null) {
            hideMagnifier();
            return;
        }

        double imageX = coords[0];
        double imageY = coords[1];

        updateMagnifier(event, (int) imageX, (int) imageY);
    }

    /**
     * Draws a zoomed loupe near the cursor showing the pixels around (imgX, imgY).
     * The loupe repositions to avoid going off-screen.
     */
    private void updateMagnifier(MouseEvent event, int imgX, int imgY) {
        if (magnifierCanvas == null) return;

        Image image = imageView.getImage();
        if (image == null) return;

        int totalW = (int) (MAGNIFIER_SIZE + 20);
        int totalH = (int) (MAGNIFIER_SIZE + 40);
        magnifierCanvas.setWidth(totalW);
        magnifierCanvas.setHeight(totalH);

        GraphicsContext gc = magnifierCanvas.getGraphicsContext2D();
        gc.clearRect(0, 0, totalW, totalH);

        // --- Draw background panel ---
        gc.setFill(Color.rgb(30, 30, 30, 0.88));
        gc.fillRoundRect(0, 0, totalW, totalH, 12, 12);
        gc.setStroke(Color.rgb(255, 200, 50, 0.9));
        gc.setLineWidth(2);
        gc.strokeRoundRect(1, 1, totalW - 2, totalH - 2, 12, 12);

        // --- Draw title ---
        gc.setFill(Color.rgb(255, 200, 50));
        gc.setFont(Font.font("Arial", FontWeight.BOLD, 10));
        gc.fillText("🔍 Magnifier", 8, 14);

        // --- Draw zoomed pixels ---
        PixelReader pr = image.getPixelReader();
        int half     = ZOOM_PIXELS / 2;
        double cellW = (double) MAGNIFIER_SIZE / ZOOM_PIXELS;
        double cellH = (double) MAGNIFIER_SIZE / ZOOM_PIXELS;
        double startX = 10;
        double startY = 18;

        for (int dy = 0; dy < ZOOM_PIXELS; dy++) {
            for (int dx = 0; dx < ZOOM_PIXELS; dx++) {
                int px = imgX - half + dx;
                int py = imgY - half + dy;

                Color c;
                if (px < 0 || px >= (int) image.getWidth() || py < 0 || py >= (int) image.getHeight()) {
                    c = Color.rgb(20, 20, 20);
                } else {
                    c = pr.getColor(px, py);
                }

                gc.setFill(c);
                gc.fillRect(startX + dx * cellW, startY + dy * cellH, cellW, cellH);
            }
        }

        // --- Draw crosshair at center cell ---
        double cx = startX + half * cellW + cellW / 2;
        double cy = startY + half * cellH + cellH / 2;
        gc.setStroke(Color.WHITE);
        gc.setLineWidth(1.2);
        gc.strokeLine(startX, cy, startX + MAGNIFIER_SIZE, cy);
        gc.strokeLine(cx, startY, cx, startY + MAGNIFIER_SIZE);

        // Highlight center cell border
        gc.setStroke(Color.rgb(255, 80, 80));
        gc.setLineWidth(2);
        gc.strokeRect(startX + half * cellW, startY + half * cellH, cellW, cellH);

        // --- Draw center pixel color swatch + hex ---
        Color centerColor = (imgX >= 0 && imgX < (int) image.getWidth()
                && imgY >= 0 && imgY < (int) image.getHeight())
                ? pr.getColor(imgX, imgY)
                : Color.BLACK;

        double swatchX = startX;
        double swatchY = startY + MAGNIFIER_SIZE + 4;
        gc.setFill(centerColor);
        gc.fillRoundRect(swatchX, swatchY, 16, 12, 3, 3);
        gc.setStroke(Color.LIGHTGRAY);
        gc.setLineWidth(0.8);
        gc.strokeRoundRect(swatchX, swatchY, 16, 12, 3, 3);

        String hex = String.format("#%02X%02X%02X",
                (int) (centerColor.getRed()   * 255),
                (int) (centerColor.getGreen() * 255),
                (int) (centerColor.getBlue()  * 255));
        gc.setFill(Color.WHITE);
        gc.setFont(Font.font("Monospaced", 9));
        gc.fillText(hex + "  (" + imgX + "," + imgY + ")", swatchX + 20, swatchY + 10);

        // --- Position loupe: offset from cursor, keep within ImageView bounds ---
        double fitW      = imageView.getFitWidth();
        double fitH      = imageView.getFitHeight();
        double imgWd     = image.getWidth();
        double imgHd     = image.getHeight();
        double scale     = Math.min(fitW / imgWd, fitH / imgHd);
        double rendW     = imgWd * scale;
        double rendH     = imgHd * scale;
        double offsetX   = (fitW - rendW) / 2.0;
        double offsetY   = (fitH - rendH) / 2.0;

        // cursor position on the ImageView node
        double cursorX = event.getX();
        double cursorY = event.getY();

        double loupeOffX = 18;
        double loupeOffY = 18;

        // Flip left if near right edge
        if (cursorX + loupeOffX + totalW > fitW - offsetX) loupeOffX = -(totalW + 8);
        // Flip up if near bottom edge
        if (cursorY + loupeOffY + totalH > fitH - offsetY) loupeOffY = -(totalH + 8);

        // StackPane.setAlignment is CENTER, so we offset from center
        double stackW = imageStackPane.getWidth();
        double stackH = imageStackPane.getHeight();

        // imageView's top-left corner inside the StackPane
        double ivLeft = (stackW - fitW) / 2.0;
        double ivTop  = (stackH - fitH) / 2.0;

        double loupeLeft = ivLeft + cursorX + loupeOffX;
        double loupeTop  = ivTop  + cursorY + loupeOffY;

        // Clamp within StackPane
        loupeLeft = Math.max(0, Math.min(loupeLeft, stackW - totalW));
        loupeTop  = Math.max(0, Math.min(loupeTop,  stackH - totalH));

        StackPane.setAlignment(magnifierCanvas, javafx.geometry.Pos.TOP_LEFT);
        StackPane.setMargin(magnifierCanvas, new Insets(loupeTop, 0, 0, loupeLeft));

        magnifierCanvas.setVisible(true);
    }

    private void hideMagnifier() {
        if (magnifierCanvas != null) {
            magnifierCanvas.setVisible(false);
        }
    }

    /**
     * Handles a single click on the image during color-picking mode.
     *
     * Uses the fixed imageViewToImageCoords() helper so the sampled pixel
     * always matches exactly what the user sees under the crosshair.
     */
    private void handleImageClickForColor(MouseEvent event) {
        Image image = imageView.getImage();
        if (image == null) return;

        double[] coords = imageViewToImageCoords(event);
        if (coords == null) return;

        int imageX = (int) coords[0];
        int imageY = (int) coords[1];

        Color color = image.getPixelReader().getColor(imageX, imageY);
        imageProcessor.addLeafColor(color);

        // Flash the magnifier border to confirm selection
        if (magnifierCanvas != null && magnifierCanvas.isVisible()) {
            GraphicsContext gc = magnifierCanvas.getGraphicsContext2D();
            gc.setStroke(Color.LIMEGREEN);
            gc.setLineWidth(3);
            gc.strokeRoundRect(1, 1,
                    magnifierCanvas.getWidth() - 2,
                    magnifierCanvas.getHeight() - 2,
                    12, 12);
        }

        updateStatus("Added color: " + colorToString(color)
                + "  (total: " + imageProcessor.getSelectedColors().size() + ")"
                + "  at pixel (" + imageX + "," + imageY + ")");
        updateColorsInfo();
        refreshColorPalettePanel();

        // Enable conversion as soon as we have at least one color
        enableProcessingButtons();
    }

    @FXML
    private void handleConvertToBlackWhite() {
        if (imageProcessor.getSelectedColors().isEmpty()) {
            showError("No Colors Selected", "Please select at least one leaf color first.");
            return;
        }

        updateStatus("Converting to black and white...");
        progressBar.setVisible(true);

        new Thread(() -> {
            try {
                long start    = System.currentTimeMillis();
                var displayBW = imageProcessor.convertToBlackAndWhite();
                long elapsed  = System.currentTimeMillis() - start;

                Platform.runLater(() -> {
                    bwImageView.setImage(displayBW);
                    bwImageView.setFitWidth(imageView.getFitWidth());
                    bwImageView.setFitHeight(imageView.getFitHeight());
                    labelProcessingTime.setText(elapsed + " ms");
                    updateStatus("B&W conversion complete (" + elapsed + " ms)");
                    progressBar.setVisible(false);
                    menuDetectLeaves.setDisable(false);
                    btnDetectLeaves.setDisable(false);
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    showError("Conversion Error", e.getMessage());
                    progressBar.setVisible(false);
                });
                e.printStackTrace();
            }
        }).start();
    }

    @FXML
    private void handleDetectLeaves() {
        if (imageProcessor.getProcessedImage() == null) {
            showError("No B&W Image", "Please convert to black & white first.");
            return;
        }

        updateStatus("Detecting leaves using Union-Find...");
        progressBar.setVisible(true);

        new Thread(() -> {
            try {
                long start     = System.currentTimeMillis();
                leafDetector   = new LeafDetector(imageProcessor);
                detectedLeaves = leafDetector.detectLeaves();
                long elapsed   = System.currentTimeMillis() - start;

                Platform.runLater(() -> {
                    labelLeavesCount.setText(String.valueOf(detectedLeaves.size()));
                    labelProcessingTime.setText(elapsed + " ms");
                    updateStatus("Detection complete: " + detectedLeaves.size()
                            + " leaves found (" + elapsed + " ms)");
                    progressBar.setVisible(false);

                    lastTSPPath  = null;
                    showTSPPath  = false;
                    selectedLeaf = null;

                    if (showRectangles) drawLeafOverlay();

                    menuAnimatePath.setDisable(false);
                    btnAnimatePath.setDisable(false);
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    showError("Detection Error", e.getMessage());
                    progressBar.setVisible(false);
                });
                e.printStackTrace();
            }
        }).start();
    }

    // ========================================================================
    // RESET
    // ========================================================================

    @FXML
    private void handleReset() {
        if (animationTimeline != null) {
            animationTimeline.stop();
            animationTimeline = null;
        }

        if (previewThread != null) {
            previewThread.interrupt();
            previewThread = null;
        }

        if (settingsStage != null && settingsStage.isShowing()) {
            settingsStage.close();
        }

        if (colorPickingMode) {
            exitColorPickingMode();
        }

        detectedLeaves.clear();
        leafDetector    = null;
        lastTSPPath     = null;
        showTSPPath     = false;
        selectedLeaf    = null;
        hoveredLeaf     = null;
        leafTooltip.hide();

        imageProcessor.clearLeafColors();
        bwImageView.setImage(null);
        clearCanvas();

        labelColorsInfo.setText("None");
        labelLeavesCount.setText("0");
        labelProcessingTime.setText("-");

        refreshColorPalettePanel();
        updateColorsInfo();

        menuConvertBW.setDisable(true);
        btnConvertBW.setDisable(true);
        menuDetectLeaves.setDisable(true);
        btnDetectLeaves.setDisable(true);
        menuAnimatePath.setDisable(true);
        btnAnimatePath.setDisable(true);
        menuStopAnimation.setDisable(true);

        boolean imageLoaded = imageView.getImage() != null;
        menuSelectColors.setDisable(!imageLoaded);
        btnSelectColors.setDisable(!imageLoaded);

        updateStatus(imageLoaded
                ? "Reset complete. Select new leaf colors to start again."
                : "Reset complete. Load an image to begin.");

        System.out.println("[Reset] All state cleared.");
    }

    // ========================================================================
    // SETTINGS
    // ========================================================================

    @FXML
    private void handleSettings() {
        if (settingsStage != null && settingsStage.isShowing()) {
            settingsStage.toFront();
            return;
        }
        if (imageProcessor.getSelectedColors().isEmpty()) {
            showError("No Colors", "Please select leaf colors before opening Settings.");
            return;
        }

        final double origHue = imageProcessor.getHueTolerance();
        final double origSat = imageProcessor.getSaturationTolerance();
        final double origBri = imageProcessor.getBrightnessTolerance();
        final int    origMin = leafDetector != null ? leafDetector.getMinLeafSize() : 5;
        final int    origMax = leafDetector != null ? leafDetector.getMaxLeafSize() : 15000;

        Slider hueSlider = new Slider(0, 180, origHue);
        Slider satSlider = new Slider(0,   1, origSat);
        Slider briSlider = new Slider(0,   1, origBri);
        for (Slider s : new Slider[]{hueSlider, satSlider, briSlider}) {
            s.setShowTickLabels(true);
            s.setShowTickMarks(true);
            s.setPrefWidth(260);
        }
        hueSlider.setMajorTickUnit(30);
        satSlider.setMajorTickUnit(0.2);
        briSlider.setMajorTickUnit(0.2);

        Label hueValue = new Label(String.format("%.0f°",  origHue));
        Label satValue = new Label(String.format("%.2f",   origSat));
        Label briValue = new Label(String.format("%.2f",   origBri));
        for (Label l : new Label[]{hueValue, satValue, briValue}) {
            l.setMinWidth(40);
            l.setStyle("-fx-font-weight: bold;");
        }

        TextField minField = new TextField(String.valueOf(origMin));
        TextField maxField = new TextField(String.valueOf(origMax));

        ProgressIndicator spinner = new ProgressIndicator();
        spinner.setPrefSize(20, 20);
        spinner.setVisible(false);
        Label previewLabel = new Label("Preview up-to-date");
        previewLabel.setStyle("-fx-text-fill: grey; -fx-font-size: 11px;");

        javafx.scene.layout.GridPane grid = new javafx.scene.layout.GridPane();
        grid.setHgap(10);
        grid.setVgap(12);
        grid.setPadding(new Insets(16));

        grid.add(new Label("Hue Tolerance (°):"),    0, 0);
        grid.add(hueSlider,                          1, 0);
        grid.add(hueValue,                           2, 0);
        grid.add(new Label("Saturation Tolerance:"), 0, 1);
        grid.add(satSlider,                          1, 1);
        grid.add(satValue,                           2, 1);
        grid.add(new Label("Brightness Tolerance:"), 0, 2);
        grid.add(briSlider,                          1, 2);
        grid.add(briValue,                           2, 2);
        grid.add(new Label("Min Leaf Size (px):"),   0, 3);
        grid.add(minField,                           1, 3);
        grid.add(new Label("Max Leaf Size (px):"),   0, 4);
        grid.add(maxField,                           1, 4);

        HBox statusRow = new HBox(8, spinner, previewLabel);
        statusRow.setAlignment(Pos.CENTER_LEFT);
        grid.add(statusRow, 0, 5, 3, 1);

        Button applyBtn  = new Button("Apply & Close");
        Button cancelBtn = new Button("Cancel");
        applyBtn.setDefaultButton(true);
        applyBtn.setStyle("-fx-base: #4CAF50; -fx-text-fill: white;");

        HBox buttons = new HBox(10, applyBtn, cancelBtn);
        buttons.setAlignment(Pos.CENTER_RIGHT);
        buttons.setPadding(new Insets(8, 0, 0, 0));
        grid.add(buttons, 0, 6, 3, 1);

        Runnable triggerPreview = () -> {
            imageProcessor.setHueTolerance(hueSlider.getValue());
            imageProcessor.setSaturationTolerance(satSlider.getValue());
            imageProcessor.setBrightnessTolerance(briSlider.getValue());

            hueValue.setText(String.format("%.0f°", hueSlider.getValue()));
            satValue.setText(String.format("%.2f",  satSlider.getValue()));
            briValue.setText(String.format("%.2f",  briSlider.getValue()));

            Thread prev = previewThread;
            if (prev != null) prev.interrupt();

            spinner.setVisible(true);
            previewLabel.setText("Processing...");

            Thread t = new Thread(() -> {
                try {
                    Thread.sleep(120);
                    if (Thread.currentThread().isInterrupted()) return;
                    var displayBW = imageProcessor.convertToBlackAndWhite();
                    if (Thread.currentThread().isInterrupted()) return;
                    Platform.runLater(() -> {
                        bwImageView.setImage(displayBW);
                        bwImageView.setFitWidth(imageView.getFitWidth());
                        bwImageView.setFitHeight(imageView.getFitHeight());
                        if (!detectedLeaves.isEmpty()) {
                            LeafDetector ld = new LeafDetector(imageProcessor);
                            try {
                                ld.setLeafSizeRange(
                                        Integer.parseInt(minField.getText().trim()),
                                        Integer.parseInt(maxField.getText().trim()));
                            } catch (NumberFormatException ignored) {}
                            detectedLeaves = ld.detectLeaves();
                            leafDetector   = ld;
                            labelLeavesCount.setText(String.valueOf(detectedLeaves.size()));
                            if (showRectangles) drawLeafOverlay();
                        }
                        spinner.setVisible(false);
                        previewLabel.setText("Preview up-to-date");
                        updateStatus("Live preview — Hue: " +
                                String.format("%.0f", imageProcessor.getHueTolerance()) + "deg" +
                                "  Sat: " + String.format("%.2f", imageProcessor.getSaturationTolerance()) +
                                "  Bri: " + String.format("%.2f", imageProcessor.getBrightnessTolerance()));
                    });
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            });
            t.setDaemon(true);
            previewThread = t;
            t.start();
        };

        hueSlider.valueProperty().addListener((obs, o, n) -> triggerPreview.run());
        satSlider.valueProperty().addListener((obs, o, n) -> triggerPreview.run());
        briSlider.valueProperty().addListener((obs, o, n) -> triggerPreview.run());
        minField.focusedProperty().addListener((obs, o, focused) -> { if (!focused) triggerPreview.run(); });
        maxField.focusedProperty().addListener((obs, o, focused) -> { if (!focused) triggerPreview.run(); });

        applyBtn.setOnAction(e -> {
            if (leafDetector != null) {
                try {
                    leafDetector.setLeafSizeRange(
                            Integer.parseInt(minField.getText().trim()),
                            Integer.parseInt(maxField.getText().trim()));
                } catch (NumberFormatException ex) {
                    showError("Invalid Input", "Please enter valid numbers for leaf size.");
                    return;
                }
            }
            updateStatus("Settings applied.");
            settingsStage.close();
        });

        cancelBtn.setOnAction(e -> {
            imageProcessor.setHueTolerance(origHue);
            imageProcessor.setSaturationTolerance(origSat);
            imageProcessor.setBrightnessTolerance(origBri);
            if (leafDetector != null) leafDetector.setLeafSizeRange(origMin, origMax);
            new Thread(() -> {
                var displayBW = imageProcessor.convertToBlackAndWhite();
                Platform.runLater(() -> {
                    bwImageView.setImage(displayBW);
                    bwImageView.setFitWidth(imageView.getFitWidth());
                    bwImageView.setFitHeight(imageView.getFitHeight());
                    if (!detectedLeaves.isEmpty()) {
                        LeafDetector ld = new LeafDetector(imageProcessor);
                        ld.setLeafSizeRange(origMin, origMax);
                        detectedLeaves = ld.detectLeaves();
                        leafDetector   = ld;
                        labelLeavesCount.setText(String.valueOf(detectedLeaves.size()));
                        if (showRectangles) drawLeafOverlay();
                    }
                    updateStatus("Settings cancelled.");
                });
            }).start();
            settingsStage.close();
        });

        javafx.scene.Scene scene = new javafx.scene.Scene(grid);
        settingsStage = new Stage();
        settingsStage.setTitle("Detection Settings (Live Preview)");
        settingsStage.setScene(scene);
        settingsStage.setResizable(false);
        settingsStage.initOwner(primaryStage);
        settingsStage.initModality(javafx.stage.Modality.NONE);
        settingsStage.setOnCloseRequest(e -> cancelBtn.fire());
        settingsStage.show();
    }

    // ========================================================================
    // VIEW MENU
    // ========================================================================

    @FXML private void handleToggleOriginal()  { imageView.setVisible(menuShowOriginal.isSelected()); }
    @FXML private void handleToggleBW()        { bwImageView.setVisible(menuShowBW.isSelected()); }

    @FXML
    private void handleToggleRectangles() {
        showRectangles = menuShowRectangles.isSelected();
        if (showRectangles && !detectedLeaves.isEmpty()) drawLeafOverlay();
        else clearCanvas();
    }

    @FXML
    private void handleToggleNumbers() {
        showNumbers = menuShowNumbers.isSelected();
        if (!detectedLeaves.isEmpty()) drawLeafOverlay();
    }

    // ========================================================================
    // ANIMATION — TSP with persistent orange path lines
    // ========================================================================

    @FXML
    private void handleAnimatePath() {
        if (detectedLeaves.isEmpty()) {
            showError("No Leaves", "Please detect leaves first.");
            return;
        }
        TextInputDialog dialog = new TextInputDialog("1");
        dialog.setTitle("Start Path Animation");
        dialog.setHeaderText("Animate TSP Path");
        dialog.setContentText("Start from leaf number:");
        dialog.showAndWait().ifPresent(s -> {
            try {
                animatePath(Integer.parseInt(s.trim()));
            } catch (NumberFormatException e) {
                showError("Invalid Input", "Please enter a valid leaf number.");
            }
        });
    }

    private void animatePath(int startNumber) {
        List<Leaf> path = TSPSolver.findPathFromNumber(detectedLeaves, startNumber);
        if (path.isEmpty()) { showError("Path Error", "Could not compute path."); return; }

        lastTSPPath = path;
        showTSPPath = true;

        updateStatus("Animating TSP path from leaf #" + startNumber
                + "  (" + path.size() + " stops)");

        if (animationTimeline != null) animationTimeline.stop();
        menuStopAnimation.setDisable(false);

        double msPerLeaf = 5000.0 / path.size();
        animationTimeline = new Timeline();

        for (int i = 0; i < path.size(); i++) {
            final Leaf leaf = path.get(i);
            animationTimeline.getKeyFrames().add(new KeyFrame(
                    Duration.millis(i * msPerLeaf),
                    e -> highlightLeafAnimation(leaf, Color.YELLOW, 3.5)));
            animationTimeline.getKeyFrames().add(new KeyFrame(
                    Duration.millis(i * msPerLeaf + msPerLeaf * 0.8),
                    e -> highlightLeafAnimation(leaf, Color.CORNFLOWERBLUE, 2.0)));
        }

        animationTimeline.setOnFinished(e -> {
            double dist = TSPSolver.calculatePathLength(path);
            updateStatus(String.format(
                    "Animation complete — TSP distance: %.0f px | %s",
                    dist, TSPSolver.formatPath(path)));
            menuStopAnimation.setDisable(true);
            drawLeafOverlay();
        });

        animationTimeline.play();
    }

    @FXML
    private void handleStopAnimation() {
        if (animationTimeline != null) {
            animationTimeline.stop();
            drawLeafOverlay();
            updateStatus("Animation stopped");
            menuStopAnimation.setDisable(true);
        }
    }

    // ========================================================================
    // HELP
    // ========================================================================

    @FXML
    private void handleAbout() {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("About");
        alert.setHeaderText("Autumn Leaves Identification System");
        alert.setContentText(
                "Version 2.2\n\n" +
                        "Features:\n" +
                        "- Union-Find leaf detection (DisjointSet)\n" +
                        "- Unlimited color selection with live palette panel\n" +
                        "- Magnifier loupe during color-picking for pixel precision\n" +
                        "- Fixed coordinate mapping (no offset drift)\n" +
                        "- Persistent leaf numbers always visible on canvas\n" +
                        "- TSP animation with orange dashed connecting lines\n" +
                        "- Full Reset button to start fresh without reloading\n\n" +
                        "Created for Data Structures & Algorithms 2");
        alert.showAndWait();
    }

    // ========================================================================
    // COLOR PALETTE PANEL
    // ========================================================================

    public void refreshColorPalettePanel() {
        if (colorPaletteBox == null) return;
        colorPaletteBox.getChildren().clear();

        Label title = new Label("Leaf Colors:");
        title.setStyle("-fx-font-weight: bold; -fx-font-size: 12px;");
        colorPaletteBox.getChildren().add(title);

        List<Color> colors = imageProcessor.getSelectedColors();

        if (colors.isEmpty()) {
            Label none = new Label("(none)");
            none.setStyle("-fx-text-fill: grey; -fx-font-size: 11px;");
            colorPaletteBox.getChildren().add(none);
        } else {
            for (int i = 0; i < colors.size(); i++) {
                colorPaletteBox.getChildren().add(buildSwatch(colors.get(i), i));
            }
        }

        Button addBtn = new Button("+ Add Color");
        addBtn.setStyle(
                "-fx-font-size: 11px; -fx-background-color: #388E3C; " +
                        "-fx-text-fill: white; -fx-cursor: hand;");
        addBtn.setOnAction(e -> openColorPickerDialog());
        colorPaletteBox.getChildren().add(addBtn);
    }

    private HBox buildSwatch(Color color, int index) {
        Rectangle square = new Rectangle(18, 18, color);
        square.setStroke(Color.DARKGRAY);
        square.setStrokeWidth(1.0);
        square.setArcWidth(3);
        square.setArcHeight(3);

        Label colorLabel = new Label(String.format("H%.0f S%.0f B%.0f",
                color.getHue(),
                color.getSaturation() * 100,
                color.getBrightness() * 100));
        colorLabel.setStyle("-fx-font-size: 10px;");

        Button removeBtn = new Button("x");
        removeBtn.setStyle(
                "-fx-font-size: 10px; -fx-padding: 1 5; " +
                        "-fx-background-color: #C62828; -fx-text-fill: white; -fx-cursor: hand;");
        removeBtn.setOnAction(e -> {
            List<Color> current = new ArrayList<>(imageProcessor.getSelectedColors());
            current.remove(index);
            imageProcessor.clearLeafColors();
            current.forEach(imageProcessor::addLeafColor);
            updateColorsInfo();
            refreshColorPalettePanel();
            updateStatus("Removed color #" + (index + 1) + " from palette");
        });

        HBox swatch = new HBox(4, square, colorLabel, removeBtn);
        swatch.setAlignment(Pos.CENTER_LEFT);
        swatch.setPadding(new Insets(2, 6, 2, 6));
        swatch.setStyle(
                "-fx-border-color: #bdbdbd; -fx-border-radius: 4; " +
                        "-fx-background-radius: 4; -fx-background-color: #f5f5f5;");
        return swatch;
    }

    private void openColorPickerDialog() {
        Dialog<Color> dialog = new Dialog<>();
        dialog.setTitle("Add Leaf Color");
        dialog.setHeaderText("Choose a color to add to the palette");

        ColorPicker picker = new ColorPicker(Color.ORANGE);
        picker.setPrefWidth(200);

        VBox content = new VBox(8, new Label("Pick a color:"), picker);
        content.setPadding(new Insets(16));
        dialog.getDialogPane().setContent(content);

        ButtonType addType = new ButtonType("Add to Palette", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(addType, ButtonType.CANCEL);
        dialog.setResultConverter(btn -> btn == addType ? picker.getValue() : null);

        dialog.showAndWait().ifPresent(color -> {
            imageProcessor.addLeafColor(color);
            updateColorsInfo();
            refreshColorPalettePanel();
            updateStatus("Added color: " + colorToString(color));
            enableProcessingButtons();
        });
    }

    // ========================================================================
    // CANVAS DRAWING — unified entry point
    // ========================================================================

    private void drawLeafOverlay() {
        if (detectedLeaves.isEmpty() || overlayCanvas == null) return;

        GraphicsContext gc = overlayCanvas.getGraphicsContext2D();
        clearCanvas();

        Image image = imageView.getImage();
        if (image == null) return;

        double procToOrigX  = image.getWidth()  / (double) imageProcessor.getWidth();
        double procToOrigY  = image.getHeight() / (double) imageProcessor.getHeight();
        double fitW         = imageView.getFitWidth();
        double fitH         = imageView.getFitHeight();
        double uniformScale = Math.min(fitW / image.getWidth(), fitH / image.getHeight());
        double offsetX      = (fitW - image.getWidth()  * uniformScale) / 2.0;
        double offsetY      = (fitH - image.getHeight() * uniformScale) / 2.0;

        // Layer 1: blue rectangles
        if (showRectangles) {
            gc.setLineWidth(1.8);
            gc.setStroke(Color.DODGERBLUE);
            for (Leaf leaf : detectedLeaves) {
                double[] r = leafToCanvas(leaf, procToOrigX, procToOrigY,
                        uniformScale, offsetX, offsetY);
                gc.strokeRect(r[0], r[1], r[2], r[3]);
            }
        }

        // Layer 2: persistent number labels
        if (showNumbers) {
            gc.setFont(Font.font("Arial", FontWeight.BOLD, 11));
            for (Leaf leaf : detectedLeaves) {
                double[] r = leafToCanvas(leaf, procToOrigX, procToOrigY,
                        uniformScale, offsetX, offsetY);
                String label = "#" + leaf.getSequentialNumber();
                double tx = r[0] + 2;
                double ty = r[1] + 12;

                gc.setFill(Color.rgb(0, 80, 200, 0.6));
                gc.fillRoundRect(tx - 1, ty - 11, label.length() * 7.0 + 4, 14, 3, 3);
                gc.setFill(Color.WHITE);
                gc.fillText(label, tx, ty);
            }
        }

        // Layer 3: orange TSP path lines
        if (showTSPPath && lastTSPPath != null && lastTSPPath.size() >= 2) {
            gc.setStroke(Color.ORANGE);
            gc.setLineWidth(2.5);
            gc.setLineDashes(9, 5);

            for (int i = 0; i < lastTSPPath.size() - 1; i++) {
                Leaf.PixelPoint a = lastTSPPath.get(i).getCenter();
                Leaf.PixelPoint b = lastTSPPath.get(i + 1).getCenter();

                double ax = a.getX() * procToOrigX * uniformScale + offsetX;
                double ay = a.getY() * procToOrigY * uniformScale + offsetY;
                double bx = b.getX() * procToOrigX * uniformScale + offsetX;
                double by = b.getY() * procToOrigY * uniformScale + offsetY;

                gc.strokeLine(ax, ay, bx, by);
                gc.setLineDashes();
                gc.setFill(Color.ORANGE);
                gc.fillOval(ax - 4, ay - 4, 8, 8);
                gc.setLineDashes(9, 5);
            }

            Leaf.PixelPoint last = lastTSPPath.get(lastTSPPath.size() - 1).getCenter();
            double lx = last.getX() * procToOrigX * uniformScale + offsetX;
            double ly = last.getY() * procToOrigY * uniformScale + offsetY;
            gc.setLineDashes();
            gc.setFill(Color.ORANGE);
            gc.fillOval(lx - 4, ly - 4, 8, 8);
        }

        gc.setLineDashes();

        // Layer 4: selected-leaf orange-red border
        if (selectedLeaf != null) {
            double[] r = leafToCanvas(selectedLeaf, procToOrigX, procToOrigY,
                    uniformScale, offsetX, offsetY);
            gc.setStroke(Color.ORANGERED);
            gc.setLineWidth(3.5);
            gc.strokeRect(r[0], r[1], r[2], r[3]);
        }
    }

    private double[] leafToCanvas(Leaf leaf,
                                  double procToOrigX, double procToOrigY,
                                  double uniformScale,
                                  double offsetX,    double offsetY) {
        javafx.geometry.Rectangle2D b = leaf.getBoundingBox();
        return new double[]{
                b.getMinX()   * procToOrigX * uniformScale + offsetX,
                b.getMinY()   * procToOrigY * uniformScale + offsetY,
                b.getWidth()  * procToOrigX * uniformScale,
                b.getHeight() * procToOrigY * uniformScale
        };
    }

    private void highlightLeafAnimation(Leaf leaf, Color color, double lineWidth) {
        if (overlayCanvas == null || imageView.getImage() == null) return;
        drawLeafOverlay();

        Image image = imageView.getImage();
        double procToOrigX  = image.getWidth()  / (double) imageProcessor.getWidth();
        double procToOrigY  = image.getHeight() / (double) imageProcessor.getHeight();
        double fitW         = imageView.getFitWidth();
        double fitH         = imageView.getFitHeight();
        double uniformScale = Math.min(fitW / image.getWidth(), fitH / image.getHeight());
        double offsetX      = (fitW - image.getWidth()  * uniformScale) / 2.0;
        double offsetY      = (fitH - image.getHeight() * uniformScale) / 2.0;

        double[] r = leafToCanvas(leaf, procToOrigX, procToOrigY, uniformScale, offsetX, offsetY);

        GraphicsContext gc = overlayCanvas.getGraphicsContext2D();
        gc.setStroke(color);
        gc.setLineWidth(lineWidth);
        gc.strokeRect(r[0], r[1], r[2], r[3]);
    }

    // ========================================================================
    // MOUSE INTERACTION
    // ========================================================================

    private void handleCanvasHover(MouseEvent event) {
        if (detectedLeaves.isEmpty() || leafDetector == null) return;

        int[] proc = canvasToProcessing(event.getX(), event.getY());
        if (proc == null) return;

        Leaf leaf = leafDetector.getLeafAtPixel(proc[0], proc[1]);
        if (leaf == hoveredLeaf) return;
        hoveredLeaf = leaf;

        drawLeafOverlay();

        if (leaf != null) {
            Image image = imageView.getImage();
            double procToOrigX  = image.getWidth()  / (double) imageProcessor.getWidth();
            double procToOrigY  = image.getHeight() / (double) imageProcessor.getHeight();
            double fitW         = imageView.getFitWidth();
            double fitH         = imageView.getFitHeight();
            double uniformScale = Math.min(fitW / image.getWidth(), fitH / image.getHeight());
            double offsetX      = (fitW - image.getWidth()  * uniformScale) / 2.0;
            double offsetY      = (fitH - image.getHeight() * uniformScale) / 2.0;

            double[] r = leafToCanvas(leaf, procToOrigX, procToOrigY,
                    uniformScale, offsetX, offsetY);
            GraphicsContext gc = overlayCanvas.getGraphicsContext2D();
            gc.setStroke(Color.LIMEGREEN);
            gc.setLineWidth(2.5);
            gc.strokeRect(r[0], r[1], r[2], r[3]);

            leafTooltip.setText(String.format(
                    "Leaf #%d  |  %d px  |  (%d,%d)-(%d,%d)",
                    leaf.getSequentialNumber(), leaf.getSize(),
                    leaf.getMinX(), leaf.getMinY(),
                    leaf.getMaxX(), leaf.getMaxY()));
            Tooltip.install(overlayCanvas, leafTooltip);
            leafTooltip.show(overlayCanvas,
                    event.getScreenX() + 12, event.getScreenY() + 12);
        } else {
            leafTooltip.hide();
        }
    }

    private void clearHover() {
        hoveredLeaf = null;
        leafTooltip.hide();
        drawLeafOverlay();
    }

    private void handleCanvasClick(MouseEvent event) {
        if (detectedLeaves.isEmpty() || leafDetector == null) return;

        int[] proc = canvasToProcessing(event.getX(), event.getY());
        if (proc == null) return;

        Leaf leaf = leafDetector.getLeafAtPixel(proc[0], proc[1]);
        if (leaf != null) {
            selectedLeaf = leaf;
            updateStatus(String.format(
                    "Leaf #%d  |  Size: %d px  |  Bounds: (%d,%d) -> (%d,%d)",
                    leaf.getSequentialNumber(), leaf.getSize(),
                    leaf.getMinX(), leaf.getMinY(),
                    leaf.getMaxX(), leaf.getMaxY()));
        } else {
            selectedLeaf = null;
            updateStatus("Detection complete: " + detectedLeaves.size() + " leaves found");
        }
        drawLeafOverlay();
    }

    // ========================================================================
    // COORDINATE HELPERS
    // ========================================================================

    private int[] canvasToProcessing(double canvasX, double canvasY) {
        Image image = imageView.getImage();
        if (image == null) return null;

        double fitW         = imageView.getFitWidth();
        double fitH         = imageView.getFitHeight();
        double uniformScale = Math.min(fitW / image.getWidth(), fitH / image.getHeight());
        double offsetX      = (fitW - image.getWidth()  * uniformScale) / 2.0;
        double offsetY      = (fitH - image.getHeight() * uniformScale) / 2.0;

        double origX = (canvasX - offsetX) / uniformScale;
        double origY = (canvasY - offsetY) / uniformScale;

        int procX = (int) (origX / (image.getWidth()  / (double) imageProcessor.getWidth()));
        int procY = (int) (origY / (image.getHeight() / (double) imageProcessor.getHeight()));
        return new int[]{procX, procY};
    }

    // ========================================================================
    // UTILITY
    // ========================================================================

    private void loadImage(File file) {
        try {
            updateStatus("Loading image: " + file.getName());
            String imagePath = file.toURI().toString();

            imageProcessor.loadImage(imagePath, true);

            Image image = new Image(imagePath);
            imageView.setImage(image);
            imageView.setFitWidth(500);
            imageView.setFitHeight(500);
            imageView.setPreserveRatio(true);

            bwImageView.setFitWidth(500);
            bwImageView.setFitHeight(500);
            bwImageView.setPreserveRatio(true);

            overlayCanvas.setWidth(imageView.getFitWidth());
            overlayCanvas.setHeight(imageView.getFitHeight());

            labelImageInfo.setText(String.format("%s (%.0fx%.0f)",
                    file.getName(), image.getWidth(), image.getHeight()));

            menuSelectColors.setDisable(false);
            btnSelectColors.setDisable(false);

            bwImageView.setImage(null);
            detectedLeaves.clear();
            imageProcessor.clearLeafColors();
            clearCanvas();
            labelColorsInfo.setText("None");
            labelLeavesCount.setText("0");
            labelProcessingTime.setText("-");

            lastTSPPath  = null;
            showTSPPath  = false;
            selectedLeaf = null;
            hoveredLeaf  = null;

            menuConvertBW.setDisable(true);
            btnConvertBW.setDisable(true);
            menuDetectLeaves.setDisable(true);
            btnDetectLeaves.setDisable(true);
            menuAnimatePath.setDisable(true);
            btnAnimatePath.setDisable(true);
            menuStopAnimation.setDisable(true);

            refreshColorPalettePanel();
            updateStatus("Image loaded: " + file.getName()
                    + "  — select leaf colors to continue.");

        } catch (Exception e) {
            showError("Load Error", "Failed to load image: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void clearCanvas() {
        if (overlayCanvas != null) {
            overlayCanvas.getGraphicsContext2D()
                    .clearRect(0, 0, overlayCanvas.getWidth(), overlayCanvas.getHeight());
        }
    }

    private void enableProcessingButtons() {
        menuConvertBW.setDisable(false);
        btnConvertBW.setDisable(false);
    }

    private void updateColorsInfo() {
        labelColorsInfo.setText(imageProcessor.getSelectedColors().size() + " color(s) selected");
    }

    private void updateStatus(String message) {
        if (statusLabel != null) statusLabel.setText(message);
        System.out.println("[Status] " + message);
    }

    private void showError(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }

    private String colorToString(Color color) {
        return String.format("HSB(%.0f deg, %.0f%%, %.0f%%)",
                color.getHue(),
                color.getSaturation() * 100,
                color.getBrightness() * 100);
    }
}