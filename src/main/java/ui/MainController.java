package ui;

import algorithm.TSPSolver;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.util.Duration;
import model.*;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Main controller for the Autumn Leaves Identification System GUI.
 */
public class MainController {

    @FXML private ImageView imageView;
    @FXML private ImageView bwImageView;
    @FXML private Canvas overlayCanvas;
    @FXML private StackPane imageStackPane;

    @FXML private MenuItem menuOpenImage;
    @FXML private MenuItem menuSelectColors;
    @FXML private MenuItem menuConvertBW;
    @FXML private MenuItem menuDetectLeaves;
    @FXML private MenuItem menuAnimatePath;
    @FXML private MenuItem menuStopAnimation;
    @FXML private MenuItem menuSettings;
    @FXML private CheckMenuItem menuShowOriginal;
    @FXML private CheckMenuItem menuShowBW;
    @FXML private CheckMenuItem menuShowRectangles;
    @FXML private CheckMenuItem menuShowNumbers;

    @FXML private Button btnSelectColors;
    @FXML private Button btnConvertBW;
    @FXML private Button btnDetectLeaves;
    @FXML private Button btnAnimatePath;

    @FXML private Label statusLabel;
    @FXML private Label labelImageInfo;
    @FXML private Label labelColorsInfo;
    @FXML private Label labelLeavesCount;
    @FXML private Label labelProcessingTime;
    @FXML private ProgressBar progressBar;

    private Stage primaryStage;
    private ImageProcessor imageProcessor;
    private LeafDetector leafDetector;
    private List<Leaf> detectedLeaves;
    private Timeline animationTimeline;

    private boolean showRectangles = true;
    private boolean showNumbers = false;

    // Hover tooltip shown over leaf rectangles — reused to avoid repeated allocations
    private final Tooltip leafTooltip = new Tooltip();
    // The leaf currently highlighted by hover (null = none)
    private Leaf hoveredLeaf = null;

    // Live-preview settings window — kept as a field so we never open two at once
    private Stage settingsStage = null;
    // Background thread used for live preview conversions; cancelled on each new slider change
    private volatile Thread previewThread = null;

    @FXML
    public void initialize() {
        System.out.println("Initializing MainController...");

        imageProcessor = new ImageProcessor();
        detectedLeaves = new ArrayList<>();

        // Hover: show leaf info in a floating tooltip, highlight the leaf
        overlayCanvas.setOnMouseMoved(this::handleCanvasHover);
        // When the mouse leaves the canvas entirely, clear any highlight
        overlayCanvas.setOnMouseExited(e -> clearHover());

        // Click still works: selects/highlights a leaf with a persistent highlight
        overlayCanvas.setOnMouseClicked(this::handleCanvasClick);

        // Style the reusable tooltip (larger font, no delay)
        leafTooltip.setStyle("-fx-font-size: 12px;");
        leafTooltip.setShowDelay(Duration.ZERO);
        leafTooltip.setHideDelay(Duration.ZERO);
        leafTooltip.setShowDuration(Duration.INDEFINITE);

        updateStatus("Ready. Load an image to begin.");
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
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Open Autumn Leaves Image");
        fileChooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Image Files", "*.png", "*.jpg", "*.jpeg", "*.gif", "*.bmp"),
                new FileChooser.ExtensionFilter("All Files", "*.*")
        );
        File file = fileChooser.showOpenDialog(primaryStage);
        if (file != null) loadImage(file);
    }

    @FXML
    private void handleExit() {
        javafx.application.Platform.exit();
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
        alert.setHeaderText("Click on leaf pixels in the image to select colors");
        alert.setContentText("Typical autumn colors:\n" +
                "- Orange: RGB(255, 165, 0)\n" +
                "- Red: RGB(255, 0, 0)\n" +
                "- Yellow: RGB(255, 200, 0)\n" +
                "- Brown: RGB(165, 42, 42)\n\n" +
                "Click OK, then click on leaves in the image.");
        alert.showAndWait();

        updateStatus("Click on leaves to select their colors...");

        overlayCanvas.setMouseTransparent(true);
        imageView.setOnMouseClicked(this::handleImageClickForColor);
    }

    private void handleImageClickForColor(MouseEvent event) {
        Image image = imageView.getImage();
        if (image == null) return;

        // FIX: account for letterboxing when preserveRatio=true
        double fitW = imageView.getFitWidth();
        double fitH = imageView.getFitHeight();
        double imgW = image.getWidth();
        double imgH = image.getHeight();

        double scale = Math.min(fitW / imgW, fitH / imgH);
        double renderedW = imgW * scale;
        double renderedH = imgH * scale;

        double offsetX = (fitW - renderedW) / 2.0;
        double offsetY = (fitH - renderedH) / 2.0;

        double imageX = (event.getX() - offsetX) / scale;
        double imageY = (event.getY() - offsetY) / scale;

        if (imageX < 0 || imageX >= imgW || imageY < 0 || imageY >= imgH) return;

        Color color = image.getPixelReader().getColor((int) imageX, (int) imageY);
        imageProcessor.addLeafColor(color);

        updateStatus("Added color: " + colorToString(color));
        updateColorsInfo();

        if (imageProcessor.getSelectedColors().size() >= 3) {
            imageView.setOnMouseClicked(null);
            overlayCanvas.setMouseTransparent(false);
            updateStatus("Colors selected. Ready to convert to B&W.");
            enableProcessingButtons();
        }
    }

    @FXML
    private void handleConvertToBlackWhite() {
        if (imageProcessor.getSelectedColors().isEmpty()) {
            showError("No Colors Selected", "Please select leaf colors first.");
            return;
        }

        updateStatus("Converting to black and white...");
        progressBar.setVisible(true);

        new Thread(() -> {
            try {
                long startTime = System.currentTimeMillis();

                // FIX: convertToBlackAndWhite() now returns the DISPLAY image
                // (black leaves, white background) at original resolution.
                var displayBW = imageProcessor.convertToBlackAndWhite();

                long processingTime = System.currentTimeMillis() - startTime;

                Platform.runLater(() -> {
                    // FIX: show the display image (correct colours, correct size)
                    bwImageView.setImage(displayBW);

                    // FIX: match fit dimensions of bwImageView to imageView so
                    // both panels render at the same apparent size.
                    bwImageView.setFitWidth(imageView.getFitWidth());
                    bwImageView.setFitHeight(imageView.getFitHeight());

                    labelProcessingTime.setText(processingTime + " ms");
                    updateStatus("B&W conversion complete");
                    progressBar.setVisible(false);

                    menuDetectLeaves.setDisable(false);
                    btnDetectLeaves.setDisable(false);
                });

            } catch (Exception e) {
                Platform.runLater(() -> {
                    showError("Conversion Error", "Failed to convert image: " + e.getMessage());
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
                long startTime = System.currentTimeMillis();

                leafDetector = new LeafDetector(imageProcessor);
                detectedLeaves = leafDetector.detectLeaves();

                long processingTime = System.currentTimeMillis() - startTime;

                Platform.runLater(() -> {
                    labelLeavesCount.setText(String.valueOf(detectedLeaves.size()));
                    labelProcessingTime.setText(processingTime + " ms");
                    updateStatus("Detection complete: " + detectedLeaves.size() + " leaves found");
                    progressBar.setVisible(false);

                    if (showRectangles) drawLeafRectangles();

                    menuAnimatePath.setDisable(false);
                    btnAnimatePath.setDisable(false);
                });

            } catch (Exception e) {
                Platform.runLater(() -> {
                    showError("Detection Error", "Failed to detect leaves: " + e.getMessage());
                    progressBar.setVisible(false);
                });
                e.printStackTrace();
            }
        }).start();
    }

    @FXML
    private void handleSettings() {
        // Only one settings window at a time
        if (settingsStage != null && settingsStage.isShowing()) {
            settingsStage.toFront();
            return;
        }

        if (imageProcessor.getSelectedColors().isEmpty()) {
            showError("No Colors", "Please select leaf colors before opening Settings.");
            return;
        }

        // ---- Snapshot current tolerances so Cancel can fully revert ----
        final double origHue = imageProcessor.getHueTolerance();
        final double origSat = imageProcessor.getSaturationTolerance();
        final double origBri = imageProcessor.getBrightnessTolerance();
        final int    origMin = leafDetector != null ? leafDetector.getMinLeafSize() : 5;
        final int    origMax = leafDetector != null ? leafDetector.getMaxLeafSize() : 15000;

        // ---- Build sliders ----
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

        // ---- Live-preview spinner shown while re-processing ----
        ProgressIndicator spinner = new ProgressIndicator();
        spinner.setPrefSize(20, 20);
        spinner.setVisible(false);
        Label previewLabel = new Label("Preview up-to-date");
        previewLabel.setStyle("-fx-text-fill: grey; -fx-font-size: 11px;");

        // ---- Layout ----
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(12);
        grid.setPadding(new javafx.geometry.Insets(16));

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

        javafx.scene.layout.HBox statusRow = new javafx.scene.layout.HBox(8, spinner, previewLabel);
        statusRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        grid.add(statusRow, 0, 5, 3, 1);

        Button applyBtn  = new Button("Apply & Close");
        Button cancelBtn = new Button("Cancel");
        applyBtn.setDefaultButton(true);
        applyBtn.setStyle("-fx-base: #4CAF50; -fx-text-fill: white;");

        javafx.scene.layout.HBox buttons = new javafx.scene.layout.HBox(10, applyBtn, cancelBtn);
        buttons.setAlignment(javafx.geometry.Pos.CENTER_RIGHT);
        buttons.setPadding(new javafx.geometry.Insets(8, 0, 0, 0));
        grid.add(buttons, 0, 6, 3, 1);

        // ---- Wire up live preview ----
        // Each slider change: push tolerances into imageProcessor then re-convert on a
        // background thread so the FX thread (and both ImageViews) never freezes.
        Runnable triggerPreview = () -> {
            // Update tolerances immediately so imageProcessor uses them
            imageProcessor.setHueTolerance(hueSlider.getValue());
            imageProcessor.setSaturationTolerance(satSlider.getValue());
            imageProcessor.setBrightnessTolerance(briSlider.getValue());

            hueValue.setText(String.format("%.0f°", hueSlider.getValue()));
            satValue.setText(String.format("%.2f",  satSlider.getValue()));
            briValue.setText(String.format("%.2f",  briSlider.getValue()));

            // Cancel any in-flight preview
            Thread prev = previewThread;
            if (prev != null) prev.interrupt();

            spinner.setVisible(true);
            previewLabel.setText("Processing…");

            Thread t = new Thread(() -> {
                try {
                    // Small debounce so rapid dragging doesn't flood the processor
                    Thread.sleep(120);
                    if (Thread.currentThread().isInterrupted()) return;

                    var displayBW = imageProcessor.convertToBlackAndWhite();

                    if (Thread.currentThread().isInterrupted()) return;

                    Platform.runLater(() -> {
                        bwImageView.setImage(displayBW);
                        bwImageView.setFitWidth(imageView.getFitWidth());
                        bwImageView.setFitHeight(imageView.getFitHeight());

                        // If leaves were already detected, re-detect with new thresholds
                        // so rectangles reflect the updated segmentation
                        if (!detectedLeaves.isEmpty()) {
                            LeafDetector ld = new LeafDetector(imageProcessor);
                            try {
                                int mn = Integer.parseInt(minField.getText().trim());
                                int mx = Integer.parseInt(maxField.getText().trim());
                                ld.setLeafSizeRange(mn, mx);
                            } catch (NumberFormatException ignored) {}
                            detectedLeaves = ld.detectLeaves();
                            leafDetector   = ld;
                            labelLeavesCount.setText(String.valueOf(detectedLeaves.size()));
                            if (showRectangles) drawLeafRectangles();
                        }

                        spinner.setVisible(false);
                        previewLabel.setText("Preview up-to-date");
                        updateStatus("Live preview — Hue: " +
                                String.format("%.0f°", imageProcessor.getHueTolerance()) +
                                "  Sat: " + String.format("%.2f", imageProcessor.getSaturationTolerance()) +
                                "  Bri: " + String.format("%.2f", imageProcessor.getBrightnessTolerance()));
                    });
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt(); // swallow — expected cancellation
                }
            });
            t.setDaemon(true);
            previewThread = t;
            t.start();
        };

        // Attach the live-preview listener to every slider
        hueSlider.valueProperty().addListener((obs, o, n) -> triggerPreview.run());
        satSlider.valueProperty().addListener((obs, o, n) -> triggerPreview.run());
        briSlider.valueProperty().addListener((obs, o, n) -> triggerPreview.run());

        // Leaf size fields: preview on focus-lost (not every keystroke)
        minField.focusedProperty().addListener((obs, o, focused) -> { if (!focused) triggerPreview.run(); });
        maxField.focusedProperty().addListener((obs, o, focused) -> { if (!focused) triggerPreview.run(); });

        // ---- Apply / Cancel ----
        applyBtn.setOnAction(e -> {
            // Tolerances are already live in imageProcessor; just persist leaf sizes
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
            // Revert all tolerances to what they were when the window opened
            imageProcessor.setHueTolerance(origHue);
            imageProcessor.setSaturationTolerance(origSat);
            imageProcessor.setBrightnessTolerance(origBri);
            if (leafDetector != null) leafDetector.setLeafSizeRange(origMin, origMax);

            // Re-run conversion once to restore the original images
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
                        if (showRectangles) drawLeafRectangles();
                    }
                    updateStatus("Settings cancelled — reverted to previous values.");
                });
            }).start();

            settingsStage.close();
        });

        // ---- Show the window ----
        javafx.scene.Scene scene = new javafx.scene.Scene(grid);
        settingsStage = new Stage();
        settingsStage.setTitle("Detection Settings (Live Preview)");
        settingsStage.setScene(scene);
        settingsStage.setResizable(false);
        // Keep it above the main window but don't block it
        settingsStage.initOwner(primaryStage);
        settingsStage.initModality(javafx.stage.Modality.NONE);
        // If the user closes the window via the X button, treat as Cancel
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
        if (showRectangles && !detectedLeaves.isEmpty()) drawLeafRectangles();
        else clearCanvas();
    }

    @FXML
    private void handleToggleNumbers() {
        showNumbers = menuShowNumbers.isSelected();
        if (!detectedLeaves.isEmpty()) drawLeafRectangles();
    }

    // ========================================================================
    // ANIMATION
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
                animatePath(Integer.parseInt(s));
            } catch (NumberFormatException e) {
                showError("Invalid Input", "Please enter a valid leaf number.");
            }
        });
    }

    private void animatePath(int startNumber) {
        List<Leaf> path = TSPSolver.findPathFromNumber(detectedLeaves, startNumber);
        if (path.isEmpty()) { showError("Path Error", "Could not find path."); return; }

        updateStatus("Animating path: " + TSPSolver.formatPath(path));

        if (animationTimeline != null) animationTimeline.stop();

        menuStopAnimation.setDisable(false);

        double durationPerLeaf = 5000.0 / path.size();
        animationTimeline = new Timeline();

        for (int i = 0; i < path.size(); i++) {
            final Leaf leaf = path.get(i);
            animationTimeline.getKeyFrames().add(new KeyFrame(
                    Duration.millis(i * durationPerLeaf),
                    e -> highlightLeaf(leaf, Color.YELLOW)));
            animationTimeline.getKeyFrames().add(new KeyFrame(
                    Duration.millis(i * durationPerLeaf + durationPerLeaf * 0.8),
                    e -> highlightLeaf(leaf, Color.BLUE)));
        }

        animationTimeline.setOnFinished(e -> {
            updateStatus("Animation complete");
            menuStopAnimation.setDisable(true);
            drawLeafRectangles();
        });

        animationTimeline.play();
    }

    @FXML
    private void handleStopAnimation() {
        if (animationTimeline != null) {
            animationTimeline.stop();
            drawLeafRectangles();
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
                "Version 1.0\n\n" +
                        "A JavaFX application for detecting and analyzing autumn leaves in images.\n\n" +
                        "Features:\n" +
                        "- Union-Find algorithm for leaf detection\n" +
                        "- Color-based segmentation\n" +
                        "- TSP path animation\n" +
                        "- Interactive visualization\n\n" +
                        "Created for Data Structures & Algorithms 2");
        alert.showAndWait();
    }

    // ========================================================================
    // HELPERS
    // ========================================================================

    private void loadImage(File file) {
        try {
            updateStatus("Loading image: " + file.getName());

            String imagePath = file.toURI().toString();

            // FIX: pass rescale=true so the processor downscales internally for
            // efficient LeafDetector operation, while still producing a
            // full-resolution displayImage for the right panel.
            imageProcessor.loadImage(imagePath, true);

            Image image = new Image(imagePath);
            imageView.setImage(image);
            imageView.setFitWidth(500);
            imageView.setFitHeight(500);
            imageView.setPreserveRatio(true);

            // FIX: pre-configure bwImageView to match left panel dimensions
            bwImageView.setFitWidth(500);
            bwImageView.setFitHeight(500);
            bwImageView.setPreserveRatio(true);

            // Canvas covers the left panel exactly
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

            updateStatus("Image loaded successfully");

        } catch (Exception e) {
            showError("Load Error", "Failed to load image: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Draw blue rectangles over the left (original) panel.
     *
     * FIX: The overlay canvas sits on top of the left ImageView which uses
     * preserveRatio=true. We must account for letterbox offsets so rectangles
     * align with leaf positions in the rendered image.
     * Leaf coordinates come from the processing (downscaled) space, so we
     * first scale from processing→original and then original→rendered.
     */
    private void drawLeafRectangles() {
        if (detectedLeaves.isEmpty() || overlayCanvas == null) return;

        GraphicsContext gc = overlayCanvas.getGraphicsContext2D();
        clearCanvas();

        Image image = imageView.getImage();
        if (image == null) return;

        // FIX: two-stage scale calculation
        // Stage 1: processing coords → original image coords
        double procToOrigX = image.getWidth()  / (double) imageProcessor.getWidth();
        double procToOrigY = image.getHeight() / (double) imageProcessor.getHeight();

        // Stage 2: original image coords → canvas (rendered) coords,
        // respecting preserveRatio letterboxing
        double fitW = imageView.getFitWidth();
        double fitH = imageView.getFitHeight();
        double uniformScale = Math.min(fitW / image.getWidth(), fitH / image.getHeight());

        double offsetX = (fitW  - image.getWidth()  * uniformScale) / 2.0;
        double offsetY = (fitH  - image.getHeight() * uniformScale) / 2.0;

        for (Leaf leaf : detectedLeaves) {
            javafx.geometry.Rectangle2D bounds = leaf.getBoundingBox();

            // Convert from processing space → canvas space
            double x = bounds.getMinX() * procToOrigX * uniformScale + offsetX;
            double y = bounds.getMinY() * procToOrigY * uniformScale + offsetY;
            double w = bounds.getWidth()  * procToOrigX * uniformScale;
            double h = bounds.getHeight() * procToOrigY * uniformScale;

            gc.setStroke(Color.BLUE);
            gc.setLineWidth(2);
            gc.strokeRect(x, y, w, h);

            if (showNumbers) {
                gc.setFill(Color.BLUE);
                gc.setFont(new javafx.scene.text.Font(12));
                gc.fillText("#" + leaf.getSequentialNumber(), x + 5, y + 15);
            }
        }
    }

    /**
     * Draw a coloured border around one leaf in the overlay canvas.
     * Used for hover (green) and click (orange) feedback without redrawing everything.
     */
    private void drawLeafHighlight(Leaf leaf, Color color, double lineWidth) {
        if (overlayCanvas == null) return;
        Image image = imageView.getImage();
        if (image == null) return;

        double procToOrigX = image.getWidth()  / (double) imageProcessor.getWidth();
        double procToOrigY = image.getHeight() / (double) imageProcessor.getHeight();
        double fitW = imageView.getFitWidth();
        double fitH = imageView.getFitHeight();
        double uniformScale = Math.min(fitW / image.getWidth(), fitH / image.getHeight());
        double offsetX = (fitW  - image.getWidth()  * uniformScale) / 2.0;
        double offsetY = (fitH  - image.getHeight() * uniformScale) / 2.0;

        javafx.geometry.Rectangle2D bounds = leaf.getBoundingBox();
        double x = bounds.getMinX() * procToOrigX * uniformScale + offsetX;
        double y = bounds.getMinY() * procToOrigY * uniformScale + offsetY;
        double w = bounds.getWidth()  * procToOrigX * uniformScale;
        double h = bounds.getHeight() * procToOrigY * uniformScale;

        GraphicsContext gc = overlayCanvas.getGraphicsContext2D();
        gc.setStroke(color);
        gc.setLineWidth(lineWidth);
        gc.strokeRect(x, y, w, h);

        gc.setFill(color);
        gc.setFont(new javafx.scene.text.Font(12));
        gc.fillText("#" + leaf.getSequentialNumber(), x + 5, y + 15);
    }

    /** Highlight a single leaf during animation (kept for TSP animation). */
    private void highlightLeaf(Leaf leaf, Color color) {
        if (overlayCanvas == null) return;

        GraphicsContext gc = overlayCanvas.getGraphicsContext2D();
        Image image = imageView.getImage();
        if (image == null) return;

        double procToOrigX = image.getWidth()  / (double) imageProcessor.getWidth();
        double procToOrigY = image.getHeight() / (double) imageProcessor.getHeight();

        double fitW = imageView.getFitWidth();
        double fitH = imageView.getFitHeight();
        double uniformScale = Math.min(fitW / image.getWidth(), fitH / image.getHeight());

        double offsetX = (fitW  - image.getWidth()  * uniformScale) / 2.0;
        double offsetY = (fitH  - image.getHeight() * uniformScale) / 2.0;

        javafx.geometry.Rectangle2D bounds = leaf.getBoundingBox();

        double x = bounds.getMinX() * procToOrigX * uniformScale + offsetX;
        double y = bounds.getMinY() * procToOrigY * uniformScale + offsetY;
        double w = bounds.getWidth()  * procToOrigX * uniformScale;
        double h = bounds.getHeight() * procToOrigY * uniformScale;

        gc.setStroke(color);
        gc.setLineWidth(3);
        gc.strokeRect(x, y, w, h);

        gc.setFill(color);
        gc.setFont(new javafx.scene.text.Font(14));
        gc.fillText("#" + leaf.getSequentialNumber(), x + 5, y + 15);
    }

    // ---- coordinate helper: canvas mouse position → processing-space pixel ----
    private int[] canvasToProcessing(double canvasX, double canvasY) {
        Image image = imageView.getImage();
        if (image == null) return null;

        double fitW = imageView.getFitWidth();
        double fitH = imageView.getFitHeight();
        double uniformScale = Math.min(fitW / image.getWidth(), fitH / image.getHeight());
        double offsetX = (fitW  - image.getWidth()  * uniformScale) / 2.0;
        double offsetY = (fitH  - image.getHeight() * uniformScale) / 2.0;

        double origX = (canvasX - offsetX) / uniformScale;
        double origY = (canvasY - offsetY) / uniformScale;

        int procX = (int) (origX / (image.getWidth()  / (double) imageProcessor.getWidth()));
        int procY = (int) (origY / (image.getHeight() / (double) imageProcessor.getHeight()));
        return new int[]{procX, procY};
    }

    /**
     * Mouse-move handler: find which leaf (if any) is under the cursor,
     * highlight it with a green stroke and show a floating Tooltip with leaf info.
     * No dialog — no blocking.
     */
    private void handleCanvasHover(MouseEvent event) {
        if (detectedLeaves.isEmpty() || leafDetector == null) return;

        int[] proc = canvasToProcessing(event.getX(), event.getY());
        if (proc == null) return;

        Leaf leaf = leafDetector.getLeafAtPixel(proc[0], proc[1]);

        if (leaf == hoveredLeaf) return;  // same leaf — nothing to redraw
        hoveredLeaf = leaf;

        // Redraw rectangles to remove previous hover highlight
        drawLeafRectangles();

        if (leaf != null) {
            // Draw green highlight over the hovered leaf
            drawLeafHighlight(leaf, Color.LIMEGREEN, 2.5);

            // Update and show tooltip near the cursor
            leafTooltip.setText(String.format(
                    "Leaf #%d  |  %d px  |  (%d,%d)–(%d,%d)",
                    leaf.getSequentialNumber(), leaf.getSize(),
                    leaf.getMinX(), leaf.getMinY(),
                    leaf.getMaxX(), leaf.getMaxY()));

            // Show tooltip slightly below-right of the cursor in screen coords
            Tooltip.install(overlayCanvas, leafTooltip);
            leafTooltip.show(overlayCanvas,
                    event.getScreenX() + 12,
                    event.getScreenY() + 12);
        } else {
            leafTooltip.hide();
        }
    }

    /** Hide tooltip and remove hover highlight when the mouse leaves the canvas. */
    private void clearHover() {
        hoveredLeaf = null;
        leafTooltip.hide();
        drawLeafRectangles();  // restore normal blue rectangles
    }

    /**
     * Click handler: highlight the clicked leaf with orange and show its
     * info in the status bar — no modal dialog, so the user can keep clicking
     * other leaves immediately.
     */
    private void handleCanvasClick(MouseEvent event) {
        if (detectedLeaves.isEmpty() || leafDetector == null) return;

        int[] proc = canvasToProcessing(event.getX(), event.getY());
        if (proc == null) return;

        Leaf leaf = leafDetector.getLeafAtPixel(proc[0], proc[1]);
        if (leaf != null) {
            // Show info in status bar — no blocking dialog
            updateStatus(String.format(
                    "Leaf #%d  |  Size: %d px  |  Bounds: (%d,%d) → (%d,%d)",
                    leaf.getSequentialNumber(), leaf.getSize(),
                    leaf.getMinX(), leaf.getMinY(),
                    leaf.getMaxX(), leaf.getMaxY()));

            // Orange persistent highlight so the user can see which one was last clicked
            drawLeafRectangles();
            drawLeafHighlight(leaf, Color.ORANGE, 3.0);
        } else {
            // Clicked on background: clear selection and restore normal view
            drawLeafRectangles();
            updateStatus("Detection complete: " + detectedLeaves.size() + " leaves found");
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
        return String.format("HSB(%.0f°, %.0f%%, %.0f%%)",
                color.getHue(), color.getSaturation() * 100, color.getBrightness() * 100);
    }
}