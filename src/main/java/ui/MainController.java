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
     * - A "Done Picking" button appears in the toolbar (replaces the
     *   "Select Colors" button label) so the user can end the mode at any time.
     */
    private void enterColorPickingMode() {
        updateStatus("Color-picking mode: click on leaves. Press 'Done Picking' when finished.");

        overlayCanvas.setMouseTransparent(true);
        imageView.setOnMouseClicked(this::handleImageClickForColor);

        // Swap the Select Colors button to a "Done Picking" button
        btnSelectColors.setText("Done Picking");
        btnSelectColors.setStyle("-fx-background-color: #F57C00; -fx-text-fill: white;");
        btnSelectColors.setOnAction(e -> exitColorPickingMode());
    }

    /** Exits color-picking mode and restores the normal Select Colors button. */
    private void exitColorPickingMode() {
        imageView.setOnMouseClicked(null);
        overlayCanvas.setMouseTransparent(false);

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
     * Handles a single click on the image during color-picking mode.
     * Samples the pixel, adds it to the processor, refreshes the palette panel.
     * No minimum color count — one color is enough.
     */
    private void handleImageClickForColor(MouseEvent event) {
        Image image = imageView.getImage();
        if (image == null) return;

        // Account for letterbox offset (preserveRatio=true)
        double fitW      = imageView.getFitWidth();
        double fitH      = imageView.getFitHeight();
        double imgW      = image.getWidth();
        double imgH      = image.getHeight();
        double scale     = Math.min(fitW / imgW, fitH / imgH);
        double offsetX   = (fitW - imgW * scale) / 2.0;
        double offsetY   = (fitH - imgH * scale) / 2.0;

        double imageX = (event.getX() - offsetX) / scale;
        double imageY = (event.getY() - offsetY) / scale;

        if (imageX < 0 || imageX >= imgW || imageY < 0 || imageY >= imgH) return;

        Color color = image.getPixelReader().getColor((int) imageX, (int) imageY);
        imageProcessor.addLeafColor(color);

        updateStatus("Added color: " + colorToString(color)
                + "  (total: " + imageProcessor.getSelectedColors().size() + ")");
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
    // RESET  (Feature 4)
    // ========================================================================

    /**
     * Full reset handler — can be triggered from the Reset button or menu.
     *
     * What gets cleared:
     *   - Animation (stopped if running)
     *   - TSP path lines
     *   - Leaf detection results
     *   - B&W image
     *   - Selected colors and palette panel
     *   - Canvas overlay
     *   - All status labels back to defaults
     *
     * What is KEPT:
     *   - The loaded original image (the user can immediately re-select
     *     colors and re-run, or load a different image via Open Image).
     */
    @FXML
    private void handleReset() {
        // Stop any running animation first
        if (animationTimeline != null) {
            animationTimeline.stop();
            animationTimeline = null;
        }

        // Cancel any in-flight preview thread
        if (previewThread != null) {
            previewThread.interrupt();
            previewThread = null;
        }

        // Close settings window if open
        if (settingsStage != null && settingsStage.isShowing()) {
            settingsStage.close();
        }

        // Exit color-picking mode cleanly if active
        if (overlayCanvas.isMouseTransparent()) {
            exitColorPickingMode();
        }

        // Clear detection state
        detectedLeaves.clear();
        leafDetector    = null;
        lastTSPPath     = null;
        showTSPPath     = false;
        selectedLeaf    = null;
        hoveredLeaf     = null;
        leafTooltip.hide();

        // Clear colors
        imageProcessor.clearLeafColors();

        // Clear B&W image
        bwImageView.setImage(null);

        // Clear overlay canvas
        clearCanvas();

        // Reset status labels
        labelColorsInfo.setText("None");
        labelLeavesCount.setText("0");
        labelProcessingTime.setText("-");

        // Refresh palette panel (will show "(none)")
        refreshColorPalettePanel();
        updateColorsInfo();

        // Disable downstream buttons — user must re-select colors to proceed
        menuConvertBW.setDisable(true);
        btnConvertBW.setDisable(true);
        menuDetectLeaves.setDisable(true);
        btnDetectLeaves.setDisable(true);
        menuAnimatePath.setDisable(true);
        btnAnimatePath.setDisable(true);
        menuStopAnimation.setDisable(true);

        // Keep Select Colors enabled if an image is loaded
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

    /**
     * Runs the TSP nearest-neighbour animation then draws the full orange path.
     *
     * During animation each arriving leaf flashes YELLOW then settles to BLUE.
     * After it finishes, drawLeafOverlay() redraws everything including the
     * persistent orange dashed path lines stored in lastTSPPath.
     */
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
                "Version 2.1\n\n" +
                        "Features:\n" +
                        "- Union-Find leaf detection (DisjointSet)\n" +
                        "- Unlimited color selection with live palette panel\n" +
                        "- Persistent leaf numbers always visible on canvas\n" +
                        "- TSP animation with orange dashed connecting lines\n" +
                        "- Full Reset button to start fresh without reloading\n" +
                        "- JMH benchmarking (see benchmark.LeafBenchmark)\n\n" +
                        "Created for Data Structures & Algorithms 2");
        alert.showAndWait();
    }

    // ========================================================================
    // COLOR PALETTE PANEL
    // ========================================================================

    /**
     * Rebuilds the colorPaletteBox to reflect the current color list.
     *
     * Layout:
     *   [ "Leaf Colors:" label ]
     *   [ swatch ][ swatch ] ... [ + Add Color button ]
     *
     * Each swatch: [ colored square ][ HSB label ][ x remove button ]
     */
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

    /**
     * Builds one swatch widget for the given color at the given list index.
     */
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

    /**
     * Opens a ColorPicker dialog so the user can add any color directly
     * without clicking on the image.
     */
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

    /**
     * Redraws the full canvas overlay in layered order:
     *
     *  Layer 1 — Blue bounding rectangles for all leaves.
     *  Layer 2 — Persistent number labels (#1, #2, ...) on top of rectangles.
     *  Layer 3 — Orange dashed TSP path lines + node circles (when available).
     *  Layer 4 — Orange-red thick border for the currently selected leaf.
     */
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

                // Semi-transparent backing box
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

            // Last node
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

    /** Converts a Leaf bounding box from processing-space to canvas-space. */
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

    /** Highlights one leaf during animation (calls drawLeafOverlay first to avoid stacking). */
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

            // Full reset of downstream state when a new image is loaded
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

            // Disable downstream buttons until colors are chosen
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