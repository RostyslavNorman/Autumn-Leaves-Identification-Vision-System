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
import java.util.Optional;

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

    @FXML
    public void initialize() {
        System.out.println("Initializing MainController...");

        imageProcessor = new ImageProcessor();
        detectedLeaves = new ArrayList<>();

        overlayCanvas.setOnMouseClicked(this::handleCanvasClick);

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
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Settings");
        dialog.setHeaderText("Detection Settings");

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);

        Label hueLabel = new Label("Hue Tolerance (°):");
        Slider hueSlider = new Slider(0, 180, imageProcessor.getHueTolerance());
        hueSlider.setShowTickLabels(true);
        hueSlider.setShowTickMarks(true);
        hueSlider.setMajorTickUnit(30);
        Label hueValue = new Label(String.format("%.0f", hueSlider.getValue()));
        hueSlider.valueProperty().addListener((obs, old, val) ->
                hueValue.setText(String.format("%.0f", val.doubleValue())));

        Label satLabel = new Label("Saturation Tolerance:");
        Slider satSlider = new Slider(0, 1, imageProcessor.getSaturationTolerance());
        satSlider.setShowTickLabels(true);
        satSlider.setShowTickMarks(true);
        satSlider.setMajorTickUnit(0.2);
        Label satValue = new Label(String.format("%.2f", satSlider.getValue()));
        satSlider.valueProperty().addListener((obs, old, val) ->
                satValue.setText(String.format("%.2f", val.doubleValue())));

        Label briLabel = new Label("Brightness Tolerance:");
        Slider briSlider = new Slider(0, 1, imageProcessor.getBrightnessTolerance());
        briSlider.setShowTickLabels(true);
        briSlider.setShowTickMarks(true);
        briSlider.setMajorTickUnit(0.2);
        Label briValue = new Label(String.format("%.2f", briSlider.getValue()));
        briSlider.valueProperty().addListener((obs, old, val) ->
                briValue.setText(String.format("%.2f", val.doubleValue())));

        Label minLabel = new Label("Min Leaf Size (pixels):");
        TextField minField = new TextField(String.valueOf(
                leafDetector != null ? leafDetector.getMinLeafSize() : 10));

        Label maxLabel = new Label("Max Leaf Size (pixels):");
        TextField maxField = new TextField(String.valueOf(
                leafDetector != null ? leafDetector.getMaxLeafSize() : 50000));

        grid.add(hueLabel, 0, 0); grid.add(hueSlider, 1, 0); grid.add(hueValue, 2, 0);
        grid.add(satLabel, 0, 1); grid.add(satSlider, 1, 1); grid.add(satValue, 2, 1);
        grid.add(briLabel, 0, 2); grid.add(briSlider, 1, 2); grid.add(briValue, 2, 2);
        grid.add(minLabel, 0, 3); grid.add(minField, 1, 3, 2, 1);
        grid.add(maxLabel, 0, 4); grid.add(maxField, 1, 4, 2, 1);

        dialog.getDialogPane().setContent(grid);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        Optional<ButtonType> result = dialog.showAndWait();
        if (result.isPresent() && result.get() == ButtonType.OK) {
            imageProcessor.setHueTolerance(hueSlider.getValue());
            imageProcessor.setSaturationTolerance(satSlider.getValue());
            imageProcessor.setBrightnessTolerance(briSlider.getValue());

            if (leafDetector != null) {
                try {
                    leafDetector.setLeafSizeRange(
                            Integer.parseInt(minField.getText()),
                            Integer.parseInt(maxField.getText()));
                } catch (NumberFormatException e) {
                    showError("Invalid Input", "Please enter valid numbers for leaf size.");
                }
            }
            updateStatus("Settings updated");
        }
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

    /** Highlight a single leaf during animation. */
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

    private void handleCanvasClick(MouseEvent event) {
        if (detectedLeaves.isEmpty() || leafDetector == null) return;

        Image image = imageView.getImage();
        if (image == null) return;

        double fitW = imageView.getFitWidth();
        double fitH = imageView.getFitHeight();
        double uniformScale = Math.min(fitW / image.getWidth(), fitH / image.getHeight());

        double offsetX = (fitW  - image.getWidth()  * uniformScale) / 2.0;
        double offsetY = (fitH  - image.getHeight() * uniformScale) / 2.0;

        // Canvas click → processing space
        double origX = (event.getX() - offsetX) / uniformScale;
        double origY = (event.getY() - offsetY) / uniformScale;

        int procX = (int) (origX / (image.getWidth()  / (double) imageProcessor.getWidth()));
        int procY = (int) (origY / (image.getHeight() / (double) imageProcessor.getHeight()));

        Leaf clickedLeaf = leafDetector.getLeafAtPixel(procX, procY);
        if (clickedLeaf != null) {
            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.setTitle("Leaf Information");
            alert.setHeaderText("Clicked Leaf Details");
            alert.setContentText(String.format(
                    "Leaf #%d\nSize: %d pixels\nBounds: (%d,%d) to (%d,%d)",
                    clickedLeaf.getSequentialNumber(), clickedLeaf.getSize(),
                    clickedLeaf.getMinX(), clickedLeaf.getMinY(),
                    clickedLeaf.getMaxX(), clickedLeaf.getMaxY()));
            alert.showAndWait();
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