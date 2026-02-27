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
 *
 * Handles all user interactions and coordinates between the UI and model layers.
 */
public class MainController {

    // FXML Components - Images
    @FXML private ImageView imageView;
    @FXML private ImageView bwImageView;
    @FXML private Canvas overlayCanvas;
    @FXML private StackPane imageStackPane;

    // FXML Components - Menu Items
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

    // FXML Components - Buttons
    @FXML private Button btnSelectColors;
    @FXML private Button btnConvertBW;
    @FXML private Button btnDetectLeaves;
    @FXML private Button btnAnimatePath;

    // FXML Components - Labels
    @FXML private Label statusLabel;
    @FXML private Label labelImageInfo;
    @FXML private Label labelColorsInfo;
    @FXML private Label labelLeavesCount;
    @FXML private Label labelProcessingTime;
    @FXML private ProgressBar progressBar;

    // Model components
    private Stage primaryStage;
    private ImageProcessor imageProcessor;
    private LeafDetector leafDetector;
    private List<Leaf> detectedLeaves;
    private Timeline animationTimeline;

    // State
    private boolean showRectangles = true;
    private boolean showNumbers = false;

    /**
     * Initialize the controller. Called automatically by JavaFX.
     */
    @FXML
    public void initialize() {
        System.out.println("Initializing MainController...");

        imageProcessor = new ImageProcessor();
        detectedLeaves = new ArrayList<>();

        // Setup canvas mouse click handler
        overlayCanvas.setOnMouseClicked(this::handleCanvasClick);

        updateStatus("Ready. Load an image to begin.");
        System.out.println("MainController initialized");
    }

    /**
     * Set the primary stage reference.
     */
    public void setPrimaryStage(Stage stage) {
        this.primaryStage = stage;
    }

    // ========================================================================
    // FILE MENU HANDLERS
    // ========================================================================

    /**
     * Handle: File > Open Image
     */
    @FXML
    private void handleOpenImage() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Open Autumn Leaves Image");
        fileChooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Image Files", "*.png", "*.jpg", "*.jpeg", "*.gif", "*.bmp"),
                new FileChooser.ExtensionFilter("All Files", "*.*")
        );

        File file = fileChooser.showOpenDialog(primaryStage);
        if (file != null) {
            loadImage(file);
        }
    }

    /**
     * Handle: File > Exit
     */
    @FXML
    private void handleExit() {
        Platform.exit();
    }

    // ========================================================================
    // PROCESS MENU HANDLERS
    // ========================================================================

    /**
     * Handle: Process > Select Leaf Colors
     */
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

        // Enable click-to-select mode on the image
        overlayCanvas.setMouseTransparent(true);
        imageView.setOnMouseClicked(this::handleImageClickForColor);
    }

    /**
     * Handle image click to select leaf color
     */
    private void handleImageClickForColor(MouseEvent event) {
        Image image = imageView.getImage();
        if (image == null) return;

        // Calculate actual rendered size accounting for preserveRatio letterboxing
        double fitW = imageView.getFitWidth();
        double fitH = imageView.getFitHeight();
        double imgW = image.getWidth();
        double imgH = image.getHeight();

        double scale = Math.min(fitW / imgW, fitH / imgH);
        double renderedWidth = imgW * scale;
        double renderedHeight = imgH * scale;

        // Letterbox offsets (image is centered inside fitWidth x fitHeight)
        double offsetX = (fitW - renderedWidth) / 2.0;
        double offsetY = (fitH - renderedHeight) / 2.0;

        // Convert click position to image pixel coordinates
        double imageX = (event.getX() - offsetX) / scale;
        double imageY = (event.getY() - offsetY) / scale;

        // Ignore clicks outside the actual image area
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

    /**
     * Handle: Process > Convert to B&W
     */
    @FXML
    private void handleConvertToBlackWhite() {
        if (imageProcessor.getSelectedColors().isEmpty()) {
            showError("No Colors Selected", "Please select leaf colors first.");
            return;
        }

        updateStatus("Converting to black and white...");
        progressBar.setVisible(true);

        // Run in background thread
        new Thread(() -> {
            try {
                long startTime = System.currentTimeMillis();

                var bwImage = imageProcessor.convertToBlackAndWhite();

                long endTime = System.currentTimeMillis();
                final long processingTime = endTime - startTime;

                // Update UI on JavaFX thread
                Platform.runLater(() -> {
                    bwImageView.setImage(bwImage);
                    labelProcessingTime.setText(processingTime + " ms");
                    updateStatus("B&W conversion complete");
                    progressBar.setVisible(false);

                    // Enable detect button
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

    /**
     * Handle: Process > Detect Leaves
     */
    @FXML
    private void handleDetectLeaves() {
        if (bwImageView.getImage() == null) {
            showError("No B&W Image", "Please convert to black & white first.");
            return;
        }

        updateStatus("Detecting leaves using Union-Find...");
        progressBar.setVisible(true);

        // Run in background thread
        new Thread(() -> {
            try {
                long startTime = System.currentTimeMillis();

                leafDetector = new LeafDetector(imageProcessor);
                detectedLeaves = leafDetector.detectLeaves();

                long endTime = System.currentTimeMillis();
                final long processingTime = endTime - startTime;

                // Update UI on JavaFX thread
                Platform.runLater(() -> {
                    labelLeavesCount.setText(String.valueOf(detectedLeaves.size()));
                    labelProcessingTime.setText(processingTime + " ms");
                    updateStatus("Detection complete: " + detectedLeaves.size() + " leaves found");
                    progressBar.setVisible(false);

                    // Draw rectangles
                    if (showRectangles) {
                        drawLeafRectangles();
                    }

                    // Enable animation
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

    /**
     * Handle: Process > Settings
     */
    @FXML
    private void handleSettings() {
        // Create settings dialog
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Settings");
        dialog.setHeaderText("Detection Settings");

        // Create form
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);

        // Hue tolerance
        Label hueLabel = new Label("Hue Tolerance (°):");
        Slider hueSlider = new Slider(0, 180, imageProcessor.getHueTolerance());
        hueSlider.setShowTickLabels(true);
        hueSlider.setShowTickMarks(true);
        hueSlider.setMajorTickUnit(30);
        Label hueValue = new Label(String.format("%.0f", hueSlider.getValue()));
        hueSlider.valueProperty().addListener((obs, old, val) -> {
            hueValue.setText(String.format("%.0f", val.doubleValue()));
        });

        // Saturation tolerance
        Label satLabel = new Label("Saturation Tolerance:");
        Slider satSlider = new Slider(0, 1, imageProcessor.getSaturationTolerance());
        satSlider.setShowTickLabels(true);
        satSlider.setShowTickMarks(true);
        satSlider.setMajorTickUnit(0.2);
        Label satValue = new Label(String.format("%.2f", satSlider.getValue()));
        satSlider.valueProperty().addListener((obs, old, val) -> {
            satValue.setText(String.format("%.2f", val.doubleValue()));
        });

        // Brightness tolerance
        Label briLabel = new Label("Brightness Tolerance:");
        Slider briSlider = new Slider(0, 1, imageProcessor.getBrightnessTolerance());
        briSlider.setShowTickLabels(true);
        briSlider.setShowTickMarks(true);
        briSlider.setMajorTickUnit(0.2);
        Label briValue = new Label(String.format("%.2f", briSlider.getValue()));
        briSlider.valueProperty().addListener((obs, old, val) -> {
            briValue.setText(String.format("%.2f", val.doubleValue()));
        });

        // Min/Max leaf size
        Label minLabel = new Label("Min Leaf Size (pixels):");
        TextField minField = new TextField(String.valueOf(leafDetector != null ? leafDetector.getMinLeafSize() : 10));

        Label maxLabel = new Label("Max Leaf Size (pixels):");
        TextField maxField = new TextField(String.valueOf(leafDetector != null ? leafDetector.getMaxLeafSize() : 50000));

        // Add to grid
        grid.add(hueLabel, 0, 0);
        grid.add(hueSlider, 1, 0);
        grid.add(hueValue, 2, 0);

        grid.add(satLabel, 0, 1);
        grid.add(satSlider, 1, 1);
        grid.add(satValue, 2, 1);

        grid.add(briLabel, 0, 2);
        grid.add(briSlider, 1, 2);
        grid.add(briValue, 2, 2);

        grid.add(minLabel, 0, 3);
        grid.add(minField, 1, 3, 2, 1);

        grid.add(maxLabel, 0, 4);
        grid.add(maxField, 1, 4, 2, 1);

        dialog.getDialogPane().setContent(grid);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        Optional<ButtonType> result = dialog.showAndWait();
        if (result.isPresent() && result.get() == ButtonType.OK) {
            // Apply settings
            imageProcessor.setHueTolerance(hueSlider.getValue());
            imageProcessor.setSaturationTolerance(satSlider.getValue());
            imageProcessor.setBrightnessTolerance(briSlider.getValue());

            if (leafDetector != null) {
                try {
                    int minSize = Integer.parseInt(minField.getText());
                    int maxSize = Integer.parseInt(maxField.getText());
                    leafDetector.setLeafSizeRange(minSize, maxSize);
                } catch (NumberFormatException e) {
                    showError("Invalid Input", "Please enter valid numbers for leaf size.");
                }
            }

            updateStatus("Settings updated");
        }
    }

    // ========================================================================
    // VIEW MENU HANDLERS
    // ========================================================================

    @FXML
    private void handleToggleOriginal() {
        imageView.setVisible(menuShowOriginal.isSelected());
    }

    @FXML
    private void handleToggleBW() {
        bwImageView.setVisible(menuShowBW.isSelected());
    }

    @FXML
    private void handleToggleRectangles() {
        showRectangles = menuShowRectangles.isSelected();
        if (showRectangles && !detectedLeaves.isEmpty()) {
            drawLeafRectangles();
        } else {
            clearCanvas();
        }
    }

    @FXML
    private void handleToggleNumbers() {
        showNumbers = menuShowNumbers.isSelected();
        if (!detectedLeaves.isEmpty()) {
            drawLeafRectangles();
        }
    }

    // ========================================================================
    // ANIMATION MENU HANDLERS
    // ========================================================================

    /**
     * Handle: Animation > Animate Path
     */
    @FXML
    private void handleAnimatePath() {
        if (detectedLeaves.isEmpty()) {
            showError("No Leaves", "Please detect leaves first.");
            return;
        }

        // Ask user which leaf to start from
        TextInputDialog dialog = new TextInputDialog("1");
        dialog.setTitle("Start Path Animation");
        dialog.setHeaderText("Animate TSP Path");
        dialog.setContentText("Start from leaf number:");

        Optional<String> result = dialog.showAndWait();
        result.ifPresent(numberStr -> {
            try {
                int startNumber = Integer.parseInt(numberStr);
                animatePath(startNumber);
            } catch (NumberFormatException e) {
                showError("Invalid Input", "Please enter a valid leaf number.");
            }
        });
    }

    /**
     * Animate the TSP path starting from specified leaf
     */
    private void animatePath(int startNumber) {
        // Find path using TSP
        List<Leaf> path = TSPSolver.findPathFromNumber(detectedLeaves, startNumber);

        if (path.isEmpty()) {
            showError("Path Error", "Could not find path.");
            return;
        }

        updateStatus("Animating path: " + TSPSolver.formatPath(path));

        // Stop any existing animation
        if (animationTimeline != null) {
            animationTimeline.stop();
        }

        // Enable stop button
        menuStopAnimation.setDisable(false);

        // Create animation timeline
        // Target: 5 seconds total
        double durationPerLeaf = 5000.0 / path.size(); // milliseconds

        animationTimeline = new Timeline();

        for (int i = 0; i < path.size(); i++) {
            final int index = i;
            final Leaf leaf = path.get(i);

            // Highlight frame
            KeyFrame highlightFrame = new KeyFrame(
                    Duration.millis(i * durationPerLeaf),
                    event -> highlightLeaf(leaf, Color.YELLOW)
            );

            // Restore frame
            KeyFrame restoreFrame = new KeyFrame(
                    Duration.millis(i * durationPerLeaf + durationPerLeaf * 0.8),
                    event -> highlightLeaf(leaf, Color.BLUE)
            );

            animationTimeline.getKeyFrames().addAll(highlightFrame, restoreFrame);
        }

        // End of animation
        animationTimeline.setOnFinished(event -> {
            updateStatus("Animation complete");
            menuStopAnimation.setDisable(true);
            drawLeafRectangles(); // Restore original view
        });

        animationTimeline.play();
    }

    /**
     * Handle: Animation > Stop Animation
     */
    @FXML
    private void handleStopAnimation() {
        if (animationTimeline != null) {
            animationTimeline.stop();
            drawLeafRectangles(); // Restore view
            updateStatus("Animation stopped");
            menuStopAnimation.setDisable(true);
        }
    }

    // ========================================================================
    // HELP MENU HANDLERS
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
                        "Created for Data Structures & Algorithms 2"
        );
        alert.showAndWait();
    }

    // ========================================================================
    // HELPER METHODS
    // ========================================================================

    /**
     * Load an image file
     */
    private void loadImage(File file) {
        try {
            updateStatus("Loading image: " + file.getName());

            String imagePath = file.toURI().toString();
            imageProcessor.loadImage(imagePath, true); // Rescale to 512x512

            Image image = new Image(imagePath);
            imageView.setImage(image);
            imageView.setFitWidth(500);
            imageView.setFitHeight(500);

            // Setup canvas
            overlayCanvas.setWidth(imageView.getFitWidth());
            overlayCanvas.setHeight(imageView.getFitHeight());

            // Update info
            labelImageInfo.setText(String.format("%s (%.0fx%.0f)",
                    file.getName(), image.getWidth(), image.getHeight()));

            // Enable color selection
            menuSelectColors.setDisable(false);
            btnSelectColors.setDisable(false);

            // Clear previous results
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
     * Draw blue rectangles around detected leaves
     */
    private void drawLeafRectangles() {
        if (detectedLeaves.isEmpty() || overlayCanvas == null) {
            return;
        }

        GraphicsContext gc = overlayCanvas.getGraphicsContext2D();
        clearCanvas();

        // Calculate scale factors
        Image image = imageView.getImage();
        if (image == null) return;

        double scaleX = imageView.getFitWidth() / image.getWidth();
        double scaleY = imageView.getFitHeight() / image.getHeight();
        double scale = Math.min(scaleX, scaleY);

        // Draw each leaf
        for (Leaf leaf : detectedLeaves) {
            javafx.geometry.Rectangle2D bounds = leaf.getBoundingBox();

            double x = bounds.getMinX() * scale;
            double y = bounds.getMinY() * scale;
            double width = bounds.getWidth() * scale;
            double height = bounds.getHeight() * scale;

            // Draw rectangle
            gc.setStroke(Color.BLUE);
            gc.setLineWidth(2);
            gc.strokeRect(x, y, width, height);

            // Draw number if enabled
            if (showNumbers) {
                gc.setFill(Color.BLUE);
                gc.setFont(new javafx.scene.text.Font(12));
                gc.fillText("#" + leaf.getSequentialNumber(), x + 5, y + 15);
            }
        }
    }

    /**
     * Highlight a specific leaf with a color
     */
    private void highlightLeaf(Leaf leaf, Color color) {
        if (overlayCanvas == null) return;

        GraphicsContext gc = overlayCanvas.getGraphicsContext2D();

        // Calculate scale
        Image image = imageView.getImage();
        if (image == null) return;

        double scaleX = imageView.getFitWidth() / image.getWidth();
        double scaleY = imageView.getFitHeight() / image.getHeight();
        double scale = Math.min(scaleX, scaleY);

        javafx.geometry.Rectangle2D bounds = leaf.getBoundingBox();

        double x = bounds.getMinX() * scale;
        double y = bounds.getMinY() * scale;
        double width = bounds.getWidth() * scale;
        double height = bounds.getHeight() * scale;

        // Draw highlighted rectangle
        gc.setStroke(color);
        gc.setLineWidth(3);
        gc.strokeRect(x, y, width, height);

        // Draw number
        gc.setFill(color);
        gc.setFont(new javafx.scene.text.Font(14));
        gc.fillText("#" + leaf.getSequentialNumber(), x + 5, y + 15);
    }

    /**
     * Handle canvas click (for leaf query)
     */
    private void handleCanvasClick(MouseEvent event) {
        if (detectedLeaves.isEmpty()) return;

        // Convert click coordinates to image space
        Image image = imageView.getImage();
        if (image == null) return;

        double scaleX = imageView.getFitWidth() / image.getWidth();
        double scaleY = imageView.getFitHeight() / image.getHeight();
        double scale = Math.min(scaleX, scaleY);

        int imageX = (int) (event.getX() / scale);
        int imageY = (int) (event.getY() / scale);

        // Find leaf at this position
        Leaf clickedLeaf = leafDetector.getLeafAtPixel(imageX, imageY);

        if (clickedLeaf != null) {
            String info = String.format(
                    "Leaf #%d\nSize: %d pixels\nBounds: (%d,%d) to (%d,%d)",
                    clickedLeaf.getSequentialNumber(),
                    clickedLeaf.getSize(),
                    clickedLeaf.getMinX(), clickedLeaf.getMinY(),
                    clickedLeaf.getMaxX(), clickedLeaf.getMaxY()
            );

            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.setTitle("Leaf Information");
            alert.setHeaderText("Clicked Leaf Details");
            alert.setContentText(info);
            alert.showAndWait();
        }
    }

    /**
     * Clear the overlay canvas
     */
    private void clearCanvas() {
        if (overlayCanvas != null) {
            GraphicsContext gc = overlayCanvas.getGraphicsContext2D();
            gc.clearRect(0, 0, overlayCanvas.getWidth(), overlayCanvas.getHeight());
        }
    }

    /**
     * Enable processing buttons after color selection
     */
    private void enableProcessingButtons() {
        menuConvertBW.setDisable(false);
        btnConvertBW.setDisable(false);
    }

    /**
     * Update the colors info label
     */
    private void updateColorsInfo() {
        int count = imageProcessor.getSelectedColors().size();
        labelColorsInfo.setText(count + " color(s) selected");
    }

    /**
     * Update status bar
     */
    private void updateStatus(String message) {
        if (statusLabel != null) {
            statusLabel.setText(message);
        }
        System.out.println("[Status] " + message);
    }

    /**
     * Show error dialog
     */
    private void showError(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }

    /**
     * Convert Color to readable string
     */
    private String colorToString(Color color) {
        return String.format("HSB(%.0f°, %.0f%%, %.0f%%)",
                color.getHue(),
                color.getSaturation() * 100,
                color.getBrightness() * 100);
    }
}