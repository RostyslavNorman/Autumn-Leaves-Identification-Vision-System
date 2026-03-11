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
 * Main controller for the Autumn Leaves Identification System.
 *
 * ── HOW CANVAS ALIGNMENT WORKS ───────────────────────────────────────────────
 *
 * The ImageView uses fitWidth=500, fitHeight=500, preserveRatio=true.
 * After layout, ImageView.getBoundsInLocal() returns the ACTUAL rendered
 * image rectangle — already shrunk by preserveRatio so no letter-box math
 * is needed. This is our canvas size.
 *
 * Both ImageView and Canvas are children of the same StackPane (CENTER align),
 * so setting canvas size == rendered image size makes them overlap exactly.
 * No translate offsets are needed.
 *
 * Drawing uses:
 *   scaleX = canvasWidth  / imageProcessor.getWidth()
 *   scaleY = canvasHeight / imageProcessor.getHeight()
 */
public class MainController {

    // ── FXML nodes ────────────────────────────────────────────────────────────
    @FXML private ImageView  imageView;
    @FXML private ImageView  bwImageView;
    @FXML private Canvas     overlayCanvas;
    @FXML private StackPane  imageStackPane;

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
    @FXML private Button btnReset;
    @FXML private Button btnColorSets;
    @FXML private Button btnPickSet;
    @FXML private Button btnResetBW;

    @FXML private Label       statusLabel;
    @FXML private Label       labelImageInfo;
    @FXML private Label       labelColorsInfo;
    @FXML private Label       labelLeavesCount;
    @FXML private Label       labelProcessingTime;
    @FXML private ProgressBar progressBar;
    @FXML private HBox        colorPaletteBox;

    // ── Application state ─────────────────────────────────────────────────────
    private Stage          primaryStage;
    private ImageProcessor imageProcessor;
    private LeafDetector   leafDetector;
    private List<Leaf>     detectedLeaves;
    private Timeline       animationTimeline;

    private boolean showRectangles = true;
    private boolean showNumbers    = true;

    private Leaf selectedLeaf = null;
    private Leaf hoveredLeaf  = null;

    private final Tooltip leafTooltip = new Tooltip();

    private List<Leaf> lastTSPPath = null;
    private boolean    showTSPPath = false;

    private Stage           settingsStage = null;
    private volatile Thread previewThread = null;

    // ── Magnifier loupe ───────────────────────────────────────────────────────
    private Canvas  magnifierCanvas  = null;
    private boolean colorPickingMode = false;
    private static final int MAGNIFIER_SIZE = 120;
    private static final int ZOOM_PIXELS    = 11;

    // ── Pick-set mode ─────────────────────────────────────────────────────────
    private boolean pickSetMode = false;

    // ═════════════════════════════════════════════════════════════════════════
    // INIT
    // ═════════════════════════════════════════════════════════════════════════

    @FXML
    public void initialize() {
        imageProcessor = new ImageProcessor();
        detectedLeaves = new ArrayList<>();

        // Sync flags with FXML initial values
        if (menuShowRectangles != null) showRectangles = menuShowRectangles.isSelected();
        if (menuShowNumbers    != null) showNumbers    = menuShowNumbers.isSelected();

        // boundsInLocalProperty reflects the ACTUAL rendered size after preserveRatio.
        // Fire syncCanvasToImage every time layout changes (image loaded, window resize).
        imageView.boundsInLocalProperty().addListener((obs, o, n) -> {
            if (n.getWidth() > 0 && n.getHeight() > 0) {
                syncCanvasToImage();
                if (!detectedLeaves.isEmpty()) drawLeafOverlay();
            }
        });

        overlayCanvas.setOnMouseMoved(this::handleCanvasHover);
        overlayCanvas.setOnMouseExited(e -> clearHover());
        overlayCanvas.setOnMouseClicked(this::handleCanvasClick);

        leafTooltip.setStyle("-fx-font-size:12px;");
        leafTooltip.setShowDelay(Duration.ZERO);
        leafTooltip.setHideDelay(Duration.ZERO);
        leafTooltip.setShowDuration(Duration.INDEFINITE);

        updateStatus("Ready. Load an image to begin.");
        refreshColorPalettePanel();
    }

    public void setPrimaryStage(Stage stage) { this.primaryStage = stage; }

    // ── Canvas synchronisation ────────────────────────────────────────────────

    /**
     * Sets the canvas size to exactly match the rendered image.
     *
     * ImageView.getBoundsInLocal() with preserveRatio=true returns the actual
     * rendered rectangle — no letter-box math required.
     * StackPane centering aligns both nodes automatically.
     *
     * MUST NOT call drawLeafOverlay() — callers do that themselves.
     */
    private void syncCanvasToImage() {
        if (imageView.getImage() == null) return;
        double w = imageView.getBoundsInLocal().getWidth();
        double h = imageView.getBoundsInLocal().getHeight();
        if (w <= 0 || h <= 0) return;
        overlayCanvas.setWidth(w);
        overlayCanvas.setHeight(h);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // FILE MENU
    // ═════════════════════════════════════════════════════════════════════════

    @FXML
    private void handleOpenImage() {
        FileChooser fc = new FileChooser();
        fc.setTitle("Open Autumn Leaves Image");
        fc.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Image Files", "*.png","*.jpg","*.jpeg","*.gif","*.bmp"),
                new FileChooser.ExtensionFilter("All Files", "*.*"));
        File file = fc.showOpenDialog(primaryStage);
        if (file != null) loadImage(file);
    }

    @FXML private void handleExit() { Platform.exit(); }

    // ═════════════════════════════════════════════════════════════════════════
    // COLOR SELECTION
    // ═════════════════════════════════════════════════════════════════════════

    @FXML
    private void handleSelectColors() {
        if (imageView.getImage() == null) { showError("No Image", "Please load an image first."); return; }
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("Select Leaf Colors");
        alert.setHeaderText("Click on leaf pixels to pick colors");
        alert.setContentText(
                "Click directly on leaf areas in the left image.\n" +
                        "A magnifier loupe will appear for precision.\n" +
                        "Each click adds one color to the palette panel.\n" +
                        "Press 'Done Picking' in the toolbar when finished.\n\n" +
                        "You can also use '+ Add Color' to pick from a color wheel.\n" +
                        "There is no minimum — even one color is enough.");
        alert.showAndWait();
        enterColorPickingMode();
    }

    private void enterColorPickingMode() {
        colorPickingMode = true;
        updateStatus("Color-picking: hover to magnify, click a leaf. Press 'Done Picking' when finished.");
        // Let ImageView receive clicks; canvas sits on top but is transparent
        overlayCanvas.setMouseTransparent(true);

        magnifierCanvas = new Canvas(MAGNIFIER_SIZE + 20, MAGNIFIER_SIZE + 40);
        magnifierCanvas.setMouseTransparent(true);
        magnifierCanvas.setVisible(false);
        if (imageStackPane != null) imageStackPane.getChildren().add(magnifierCanvas);

        imageView.setOnMouseMoved(this::handleColorPickHover);
        imageView.setOnMouseExited(e -> hideMagnifier());
        imageView.setOnMouseClicked(this::handleImageClickForColor);

        btnSelectColors.setText("Done Picking");
        btnSelectColors.setStyle("-fx-background-color:#F57C00;-fx-text-fill:white;");
        btnSelectColors.setOnAction(e -> exitColorPickingMode());
    }

    private void exitColorPickingMode() {
        colorPickingMode = false;
        imageView.setOnMouseMoved(null);
        imageView.setOnMouseExited(null);
        imageView.setOnMouseClicked(null);
        overlayCanvas.setMouseTransparent(false);
        hideMagnifier();
        if (magnifierCanvas != null && imageStackPane != null) {
            imageStackPane.getChildren().remove(magnifierCanvas);
            magnifierCanvas = null;
        }
        btnSelectColors.setText("Select Colors");
        btnSelectColors.setStyle("");
        btnSelectColors.setOnAction(e -> handleSelectColors());

        int count = imageProcessor.getSelectedColors().size();
        if (count == 0) updateStatus("No colors selected yet.");
        else { updateStatus(count + " color(s) selected. Ready to convert to B&W."); enableProcessingButtons(); }
    }

    // ── Coordinate helpers ────────────────────────────────────────────────────

    /**
     * ImageView mouse event → original image pixel.
     *
     * With preserveRatio=true the ImageView node is already the rendered size,
     * so event.getX/Y() map directly to rendered pixels. We just scale to the
     * original image resolution.
     */
    private double[] imageViewToImageCoords(MouseEvent event) {
        Image image = imageView.getImage();
        if (image == null) return null;
        double rendW = imageView.getBoundsInLocal().getWidth();
        double rendH = imageView.getBoundsInLocal().getHeight();
        if (rendW <= 0 || rendH <= 0) return null;
        double ix = event.getX() * (image.getWidth()  / rendW);
        double iy = event.getY() * (image.getHeight() / rendH);
        if (ix < 0 || ix >= image.getWidth() || iy < 0 || iy >= image.getHeight()) return null;
        return new double[]{ix, iy};
    }

    // ── Magnifier loupe ───────────────────────────────────────────────────────

    private void handleColorPickHover(MouseEvent event) {
        if (!colorPickingMode || magnifierCanvas == null) return;
        double[] coords = imageViewToImageCoords(event);
        if (coords == null) { hideMagnifier(); return; }
        updateMagnifier(event, (int) coords[0], (int) coords[1]);
    }

    private void updateMagnifier(MouseEvent event, int imgX, int imgY) {
        if (magnifierCanvas == null) return;
        Image image = imageView.getImage();
        if (image == null) return;

        int totalW = MAGNIFIER_SIZE + 20, totalH = MAGNIFIER_SIZE + 40;
        magnifierCanvas.setWidth(totalW); magnifierCanvas.setHeight(totalH);
        GraphicsContext gc = magnifierCanvas.getGraphicsContext2D();
        gc.clearRect(0, 0, totalW, totalH);

        gc.setFill(Color.rgb(30, 30, 30, 0.88));
        gc.fillRoundRect(0, 0, totalW, totalH, 12, 12);
        gc.setStroke(Color.rgb(255, 200, 50, 0.9)); gc.setLineWidth(2);
        gc.strokeRoundRect(1, 1, totalW - 2, totalH - 2, 12, 12);
        gc.setFill(Color.rgb(255, 200, 50));
        gc.setFont(Font.font("Arial", FontWeight.BOLD, 10));
        gc.fillText("Magnifier", 8, 14);

        PixelReader pr  = image.getPixelReader();
        int    half     = ZOOM_PIXELS / 2;
        double cellW    = (double) MAGNIFIER_SIZE / ZOOM_PIXELS;
        double cellH    = (double) MAGNIFIER_SIZE / ZOOM_PIXELS;
        double startX   = 10, startY = 18;

        for (int dy = 0; dy < ZOOM_PIXELS; dy++) {
            for (int dx = 0; dx < ZOOM_PIXELS; dx++) {
                int px = imgX - half + dx, py = imgY - half + dy;
                Color c = (px >= 0 && px < (int)image.getWidth() && py >= 0 && py < (int)image.getHeight())
                        ? pr.getColor(px, py) : Color.rgb(20, 20, 20);
                gc.setFill(c);
                gc.fillRect(startX + dx * cellW, startY + dy * cellH, cellW, cellH);
            }
        }

        double cx = startX + half * cellW + cellW / 2;
        double cy = startY + half * cellH + cellH / 2;
        gc.setStroke(Color.WHITE); gc.setLineWidth(1.2);
        gc.strokeLine(startX, cy, startX + MAGNIFIER_SIZE, cy);
        gc.strokeLine(cx, startY, cx, startY + MAGNIFIER_SIZE);
        gc.setStroke(Color.rgb(255, 80, 80)); gc.setLineWidth(2);
        gc.strokeRect(startX + half * cellW, startY + half * cellH, cellW, cellH);

        Color centerColor = (imgX >= 0 && imgX < (int)image.getWidth() && imgY >= 0 && imgY < (int)image.getHeight())
                ? pr.getColor(imgX, imgY) : Color.BLACK;
        double swatchX = startX, swatchY = startY + MAGNIFIER_SIZE + 4;
        gc.setFill(centerColor); gc.fillRoundRect(swatchX, swatchY, 16, 12, 3, 3);
        gc.setStroke(Color.LIGHTGRAY); gc.setLineWidth(0.8);
        gc.strokeRoundRect(swatchX, swatchY, 16, 12, 3, 3);
        String hex = String.format("#%02X%02X%02X",
                (int)(centerColor.getRed()*255),(int)(centerColor.getGreen()*255),(int)(centerColor.getBlue()*255));
        gc.setFill(Color.WHITE); gc.setFont(Font.font("Monospaced", 9));
        gc.fillText(hex + " (" + imgX + "," + imgY + ")", swatchX + 20, swatchY + 10);

        double rendW = imageView.getBoundsInLocal().getWidth();
        double rendH = imageView.getBoundsInLocal().getHeight();
        double loupeOffX = 18, loupeOffY = 18;
        if (event.getX() + loupeOffX + totalW > rendW) loupeOffX = -(totalW + 8);
        if (event.getY() + loupeOffY + totalH > rendH) loupeOffY = -(totalH + 8);

        double loupeLeft = event.getX() + loupeOffX;
        double loupeTop  = event.getY() + loupeOffY;
        loupeLeft = Math.max(0, Math.min(loupeLeft, imageStackPane.getWidth()  - totalW));
        loupeTop  = Math.max(0, Math.min(loupeTop,  imageStackPane.getHeight() - totalH));

        StackPane.setAlignment(magnifierCanvas, Pos.TOP_LEFT);
        StackPane.setMargin(magnifierCanvas, new Insets(loupeTop, 0, 0, loupeLeft));
        magnifierCanvas.setVisible(true);
    }

    private void hideMagnifier() {
        if (magnifierCanvas != null) magnifierCanvas.setVisible(false);
    }

    private void handleImageClickForColor(MouseEvent event) {
        Image image = imageView.getImage();
        if (image == null) return;
        double[] coords = imageViewToImageCoords(event);
        if (coords == null) return;

        int imageX = (int) coords[0], imageY = (int) coords[1];
        Color color = image.getPixelReader().getColor(imageX, imageY);
        imageProcessor.addLeafColor(color);

        if (magnifierCanvas != null && magnifierCanvas.isVisible()) {
            GraphicsContext gc = magnifierCanvas.getGraphicsContext2D();
            gc.setStroke(Color.LIMEGREEN); gc.setLineWidth(3);
            gc.strokeRoundRect(1, 1, magnifierCanvas.getWidth()-2, magnifierCanvas.getHeight()-2, 12, 12);
        }

        updateStatus("Added color: " + colorToString(color)
                + "  (total: " + imageProcessor.getSelectedColors().size() + ")"
                + "  at pixel (" + imageX + "," + imageY + ")");
        updateColorsInfo(); refreshColorPalettePanel(); enableProcessingButtons();
    }

    // ═════════════════════════════════════════════════════════════════════════
    // CONVERT & DETECT
    // ═════════════════════════════════════════════════════════════════════════

    @FXML
    private void handleConvertToBlackWhite() {
        if (imageProcessor.getSelectedColors().isEmpty()) {
            showError("No Colors Selected", "Please select at least one leaf color first."); return;
        }
        updateStatus("Converting to black and white..."); progressBar.setVisible(true);
        new Thread(() -> {
            try {
                long start = System.currentTimeMillis();
                WritableImage displayBW = imageProcessor.convertToBlackAndWhite();
                long elapsed = System.currentTimeMillis() - start;
                Platform.runLater(() -> {
                    bwImageView.setImage(displayBW);
                    labelProcessingTime.setText(elapsed + " ms");
                    updateStatus("B&W conversion complete (" + elapsed + " ms)");
                    progressBar.setVisible(false);
                    menuDetectLeaves.setDisable(false); btnDetectLeaves.setDisable(false);
                });
            } catch (Exception e) {
                Platform.runLater(() -> { showError("Conversion Error", e.getMessage()); progressBar.setVisible(false); });
            }
        }).start();
    }

    @FXML
    private void handleDetectLeaves() {
        if (imageProcessor.getProcessedImage() == null) {
            showError("No B&W Image", "Please convert to black & white first."); return;
        }
        updateStatus("Detecting leaves using Union-Find..."); progressBar.setVisible(true);
        new Thread(() -> {
            try {
                long start        = System.currentTimeMillis();
                LeafDetector ld   = new LeafDetector(imageProcessor);
                List<Leaf> leaves = ld.detectLeaves();
                long elapsed      = System.currentTimeMillis() - start;
                Platform.runLater(() -> {
                    leafDetector   = ld;
                    detectedLeaves = leaves;
                    labelLeavesCount.setText(String.valueOf(detectedLeaves.size()));
                    labelProcessingTime.setText(elapsed + " ms");
                    updateStatus("Detection complete: " + detectedLeaves.size() + " leaves found (" + elapsed + " ms)");
                    progressBar.setVisible(false);
                    lastTSPPath = null; showTSPPath = false;
                    selectedLeaf = null; hoveredLeaf = null;
                    syncCanvasToImage();
                    drawLeafOverlay();
                    menuAnimatePath.setDisable(false); btnAnimatePath.setDisable(false);
                    if (btnColorSets != null) btnColorSets.setDisable(false);
                    if (btnPickSet   != null) btnPickSet.setDisable(false);
                    if (btnResetBW   != null) btnResetBW.setDisable(false);
                });
            } catch (Exception e) {
                Platform.runLater(() -> { showError("Detection Error", e.getMessage()); progressBar.setVisible(false); });
            }
        }).start();
    }

    // ═════════════════════════════════════════════════════════════════════════
    // DISJOINT-SET VISUALISATION
    // ═════════════════════════════════════════════════════════════════════════

    @FXML
    private void handleColorSetsRandomly() {
        if (leafDetector == null || detectedLeaves.isEmpty()) {
            showError("No Leaves", "Please detect leaves first."); return;
        }
        DisjointSetVisualizer viz = new DisjointSetVisualizer(
                imageProcessor, leafDetector.getDisjointSet(), detectedLeaves);
        bwImageView.setImage(viz.randomColourAllSets());
        updateStatus("All disjoint sets coloured randomly — " + detectedLeaves.size() + " sets.");
    }

    @FXML
    private void handlePickSet() {
        if (leafDetector == null || detectedLeaves.isEmpty()) {
            showError("No Leaves", "Please detect leaves first."); return;
        }
        pickSetMode = true;
        updateStatus("Pick Set: click on any leaf pixel in the original image.");
        if (btnPickSet != null) {
            btnPickSet.setText("Cancel Pick");
            btnPickSet.setStyle("-fx-background-color:#F57C00;-fx-text-fill:white;");
            btnPickSet.setOnAction(e -> cancelPickSetMode());
        }
        overlayCanvas.setOnMouseClicked(this::handlePickSetClick);
    }

    private void cancelPickSetMode() {
        pickSetMode = false;
        overlayCanvas.setOnMouseClicked(this::handleCanvasClick);
        if (btnPickSet != null) {
            btnPickSet.setText("Pick Set");
            btnPickSet.setStyle("");
            btnPickSet.setOnAction(e -> handlePickSet());
        }
        updateStatus("Pick Set mode cancelled.");
    }

    private void handlePickSetClick(MouseEvent event) {
        int[] proc = canvasToProcessing(event.getX(), event.getY());
        if (proc == null) { updateStatus("Clicked outside image — try again."); return; }

        DisjointSetVisualizer viz = new DisjointSetVisualizer(
                imageProcessor, leafDetector.getDisjointSet(), detectedLeaves);
        WritableImage result = viz.highlightSingleSet(proc[0], proc[1]);
        if (result == null) {
            updateStatus("No leaf pixel at that location. Click on a white leaf area."); return;
        }
        bwImageView.setImage(result);

        Leaf clicked = leafDetector.getLeafAtPixel(proc[0], proc[1]);
        updateStatus("Highlighted disjoint set for " +
                (clicked != null ? "Leaf #" + clicked.getSequentialNumber() + " (" + clicked.getSize() + " px)" : "a cluster") +
                " in the B&W panel.");

        pickSetMode = false;
        overlayCanvas.setOnMouseClicked(this::handleCanvasClick);
        if (btnPickSet != null) {
            btnPickSet.setText("Pick Set");
            btnPickSet.setStyle("");
            btnPickSet.setOnAction(e -> handlePickSet());
        }
    }

    @FXML
    private void handleResetBW() {
        if (imageProcessor.getDisplayImage() != null)
            bwImageView.setImage(imageProcessor.getDisplayImage());
        updateStatus("B&W image restored.");
    }

    // ═════════════════════════════════════════════════════════════════════════
    // RESET
    // ═════════════════════════════════════════════════════════════════════════

    @FXML
    private void handleReset() {
        if (animationTimeline != null) { animationTimeline.stop(); animationTimeline = null; }
        if (previewThread != null) { previewThread.interrupt(); previewThread = null; }
        if (settingsStage != null && settingsStage.isShowing()) settingsStage.close();
        if (colorPickingMode) exitColorPickingMode();
        if (pickSetMode) cancelPickSetMode();

        detectedLeaves.clear(); leafDetector = null; lastTSPPath = null;
        showTSPPath = false; selectedLeaf = null; hoveredLeaf = null;
        leafTooltip.hide();
        imageProcessor.clearLeafColors();
        bwImageView.setImage(null);
        clearCanvas();
        labelColorsInfo.setText("None"); labelLeavesCount.setText("0"); labelProcessingTime.setText("-");
        refreshColorPalettePanel(); updateColorsInfo();

        menuConvertBW.setDisable(true);    btnConvertBW.setDisable(true);
        menuDetectLeaves.setDisable(true); btnDetectLeaves.setDisable(true);
        menuAnimatePath.setDisable(true);  btnAnimatePath.setDisable(true);
        menuStopAnimation.setDisable(true);
        if (btnColorSets != null) btnColorSets.setDisable(true);
        if (btnPickSet   != null) btnPickSet.setDisable(true);
        if (btnResetBW   != null) btnResetBW.setDisable(true);

        boolean imageLoaded = imageView.getImage() != null;
        menuSelectColors.setDisable(!imageLoaded); btnSelectColors.setDisable(!imageLoaded);
        updateStatus(imageLoaded ? "Reset complete. Select new leaf colors to start again."
                : "Reset complete. Load an image to begin.");
    }

    // ═════════════════════════════════════════════════════════════════════════
    // SETTINGS
    // ═════════════════════════════════════════════════════════════════════════

    @FXML
    private void handleSettings() {
        if (settingsStage != null && settingsStage.isShowing()) { settingsStage.toFront(); return; }
        if (imageProcessor.getSelectedColors().isEmpty()) {
            showError("No Colors", "Please select leaf colors before opening Settings."); return;
        }
        final double origHue = imageProcessor.getHueTolerance();
        final double origSat = imageProcessor.getSaturationTolerance();
        final double origBri = imageProcessor.getBrightnessTolerance();
        final int origMin = leafDetector != null ? leafDetector.getMinLeafSize() : 5;
        final int origMax = leafDetector != null ? leafDetector.getMaxLeafSize() : 15000;

        Slider hueSlider = new Slider(0, 180, origHue);
        Slider satSlider = new Slider(0,   1, origSat);
        Slider briSlider = new Slider(0,   1, origBri);
        for (Slider s : new Slider[]{hueSlider, satSlider, briSlider}) {
            s.setShowTickLabels(true); s.setShowTickMarks(true); s.setPrefWidth(260);
        }
        hueSlider.setMajorTickUnit(30); satSlider.setMajorTickUnit(0.2); briSlider.setMajorTickUnit(0.2);

        Label hueValue = new Label(String.format("%.0f°", origHue));
        Label satValue = new Label(String.format("%.2f",  origSat));
        Label briValue = new Label(String.format("%.2f",  origBri));
        for (Label l : new Label[]{hueValue, satValue, briValue}) {
            l.setMinWidth(40); l.setStyle("-fx-font-weight:bold;");
        }

        TextField minField = new TextField(String.valueOf(origMin));
        TextField maxField = new TextField(String.valueOf(origMax));
        ProgressIndicator spinner = new ProgressIndicator(); spinner.setPrefSize(20,20); spinner.setVisible(false);
        Label previewLabel = new Label("Preview up-to-date");
        previewLabel.setStyle("-fx-text-fill:grey;-fx-font-size:11px;");

        javafx.scene.layout.GridPane grid = new javafx.scene.layout.GridPane();
        grid.setHgap(10); grid.setVgap(12); grid.setPadding(new Insets(16));
        grid.add(new Label("Hue Tolerance (°):"),    0,0); grid.add(hueSlider,1,0); grid.add(hueValue,2,0);
        grid.add(new Label("Saturation Tolerance:"), 0,1); grid.add(satSlider,1,1); grid.add(satValue,2,1);
        grid.add(new Label("Brightness Tolerance:"), 0,2); grid.add(briSlider,1,2); grid.add(briValue,2,2);
        grid.add(new Label("Min Leaf Size (px):"),   0,3); grid.add(minField,1,3);
        grid.add(new Label("Max Leaf Size (px):"),   0,4); grid.add(maxField,1,4);
        HBox statusRow = new HBox(8, spinner, previewLabel); statusRow.setAlignment(Pos.CENTER_LEFT);
        grid.add(statusRow, 0, 5, 3, 1);

        Button applyBtn  = new Button("Apply & Close"); applyBtn.setDefaultButton(true);
        applyBtn.setStyle("-fx-base:#4CAF50;-fx-text-fill:white;");
        Button cancelBtn = new Button("Cancel");
        HBox btns = new HBox(10, applyBtn, cancelBtn);
        btns.setAlignment(Pos.CENTER_RIGHT); btns.setPadding(new Insets(8,0,0,0));
        grid.add(btns, 0, 6, 3, 1);

        Runnable triggerPreview = () -> {
            imageProcessor.setHueTolerance(hueSlider.getValue());
            imageProcessor.setSaturationTolerance(satSlider.getValue());
            imageProcessor.setBrightnessTolerance(briSlider.getValue());
            hueValue.setText(String.format("%.0f°", hueSlider.getValue()));
            satValue.setText(String.format("%.2f",  satSlider.getValue()));
            briValue.setText(String.format("%.2f",  briSlider.getValue()));
            Thread prev = previewThread; if (prev != null) prev.interrupt();
            spinner.setVisible(true); previewLabel.setText("Processing...");
            Thread t = new Thread(() -> {
                try {
                    Thread.sleep(120);
                    if (Thread.currentThread().isInterrupted()) return;
                    WritableImage displayBW = imageProcessor.convertToBlackAndWhite();
                    if (Thread.currentThread().isInterrupted()) return;
                    Platform.runLater(() -> {
                        bwImageView.setImage(displayBW);
                        if (!detectedLeaves.isEmpty()) {
                            LeafDetector ld = new LeafDetector(imageProcessor);
                            try { ld.setLeafSizeRange(
                                    Integer.parseInt(minField.getText().trim()),
                                    Integer.parseInt(maxField.getText().trim())); }
                            catch (NumberFormatException ignored) {}
                            detectedLeaves = ld.detectLeaves(); leafDetector = ld;
                            labelLeavesCount.setText(String.valueOf(detectedLeaves.size()));
                            syncCanvasToImage();
                            if (showRectangles) drawLeafOverlay();
                        }
                        spinner.setVisible(false); previewLabel.setText("Preview up-to-date");
                    });
                } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
            });
            t.setDaemon(true); previewThread = t; t.start();
        };

        hueSlider.valueProperty().addListener((o,a,b) -> triggerPreview.run());
        satSlider.valueProperty().addListener((o,a,b) -> triggerPreview.run());
        briSlider.valueProperty().addListener((o,a,b) -> triggerPreview.run());
        minField.focusedProperty().addListener((o,a,focused) -> { if (!focused) triggerPreview.run(); });
        maxField.focusedProperty().addListener((o,a,focused) -> { if (!focused) triggerPreview.run(); });

        applyBtn.setOnAction(e -> {
            if (leafDetector != null) {
                try { leafDetector.setLeafSizeRange(
                        Integer.parseInt(minField.getText().trim()),
                        Integer.parseInt(maxField.getText().trim())); }
                catch (NumberFormatException ex) { showError("Invalid Input","Please enter valid numbers."); return; }
            }
            updateStatus("Settings applied."); settingsStage.close();
        });
        cancelBtn.setOnAction(e -> {
            imageProcessor.setHueTolerance(origHue);
            imageProcessor.setSaturationTolerance(origSat);
            imageProcessor.setBrightnessTolerance(origBri);
            if (leafDetector != null) leafDetector.setLeafSizeRange(origMin, origMax);
            new Thread(() -> {
                WritableImage displayBW = imageProcessor.convertToBlackAndWhite();
                Platform.runLater(() -> {
                    bwImageView.setImage(displayBW);
                    if (!detectedLeaves.isEmpty()) {
                        LeafDetector ld = new LeafDetector(imageProcessor);
                        ld.setLeafSizeRange(origMin, origMax);
                        detectedLeaves = ld.detectLeaves(); leafDetector = ld;
                        labelLeavesCount.setText(String.valueOf(detectedLeaves.size()));
                        syncCanvasToImage();
                        if (showRectangles) drawLeafOverlay();
                    }
                    updateStatus("Settings cancelled.");
                });
            }).start();
            settingsStage.close();
        });

        settingsStage = new Stage();
        settingsStage.setTitle("Detection Settings (Live Preview)");
        settingsStage.setScene(new javafx.scene.Scene(grid));
        settingsStage.setResizable(false);
        settingsStage.initOwner(primaryStage);
        settingsStage.initModality(javafx.stage.Modality.NONE);
        settingsStage.setOnCloseRequest(ev -> cancelBtn.fire());
        settingsStage.show();
    }

    // ═════════════════════════════════════════════════════════════════════════
    // VIEW MENU
    // ═════════════════════════════════════════════════════════════════════════

    @FXML private void handleToggleOriginal() { imageView.setVisible(menuShowOriginal.isSelected()); }
    @FXML private void handleToggleBW()       { bwImageView.setVisible(menuShowBW.isSelected()); }

    @FXML
    private void handleToggleRectangles() {
        showRectangles = menuShowRectangles.isSelected();
        if (showRectangles && !detectedLeaves.isEmpty()) drawLeafOverlay(); else clearCanvas();
    }

    @FXML
    private void handleToggleNumbers() {
        showNumbers = menuShowNumbers.isSelected();
        if (!detectedLeaves.isEmpty()) drawLeafOverlay();
    }

    // ═════════════════════════════════════════════════════════════════════════
    // ANIMATION
    // ═════════════════════════════════════════════════════════════════════════

    @FXML
    private void handleAnimatePath() {
        if (detectedLeaves.isEmpty()) { showError("No Leaves", "Please detect leaves first."); return; }
        TextInputDialog dialog = new TextInputDialog("1");
        dialog.setTitle("Start Path Animation"); dialog.setHeaderText("Animate TSP Path");
        dialog.setContentText("Start from leaf number:");
        dialog.showAndWait().ifPresent(s -> {
            try { animatePath(Integer.parseInt(s.trim())); }
            catch (NumberFormatException e) { showError("Invalid Input","Please enter a valid leaf number."); }
        });
    }

    private void animatePath(int startNumber) {
        List<Leaf> path = TSPSolver.findPathFromNumber(detectedLeaves, startNumber);
        if (path.isEmpty()) { showError("Path Error", "Could not compute path."); return; }
        lastTSPPath = path; showTSPPath = true;
        updateStatus("Animating TSP path from leaf #" + startNumber + "  (" + path.size() + " stops)");
        if (animationTimeline != null) animationTimeline.stop();
        menuStopAnimation.setDisable(false);
        double msPerLeaf = 5000.0 / path.size();
        animationTimeline = new Timeline();
        for (int i = 0; i < path.size(); i++) {
            final Leaf leaf = path.get(i);
            animationTimeline.getKeyFrames().add(new KeyFrame(Duration.millis(i * msPerLeaf),
                    e -> highlightLeafAnimation(leaf, Color.YELLOW, 3.5)));
            animationTimeline.getKeyFrames().add(new KeyFrame(Duration.millis(i * msPerLeaf + msPerLeaf * 0.8),
                    e -> highlightLeafAnimation(leaf, Color.CORNFLOWERBLUE, 2.0)));
        }
        animationTimeline.setOnFinished(e -> {
            updateStatus(String.format("Animation complete — TSP distance: %.0f px | %s",
                    TSPSolver.calculatePathLength(path), TSPSolver.formatPath(path)));
            menuStopAnimation.setDisable(true); drawLeafOverlay();
        });
        animationTimeline.play();
    }

    @FXML
    private void handleStopAnimation() {
        if (animationTimeline != null) {
            animationTimeline.stop(); drawLeafOverlay();
            updateStatus("Animation stopped."); menuStopAnimation.setDisable(true);
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // HELP
    // ═════════════════════════════════════════════════════════════════════════

    @FXML
    private void handleAbout() {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("About"); alert.setHeaderText("Autumn Leaves Identification System");
        alert.setContentText("Version 2.7\n\nCanvas alignment fix:\n" +
                "- getBoundsInLocal() gives actual rendered size (preserveRatio applied)\n" +
                "- StackPane centering aligns canvas to image automatically\n" +
                "- No translate offsets, no letter-box math needed\n\n" +
                "Features:\n- Union-Find leaf detection\n- Disjoint-set visualisation\n" +
                "- Magnifier loupe\n- TSP animation\n\nCreated for DSA 2");
        alert.showAndWait();
    }

    // ═════════════════════════════════════════════════════════════════════════
    // COLOR PALETTE PANEL
    // ═════════════════════════════════════════════════════════════════════════

    public void refreshColorPalettePanel() {
        if (colorPaletteBox == null) return;
        colorPaletteBox.getChildren().clear();
        Label title = new Label("Leaf Colors:"); title.setStyle("-fx-font-weight:bold;-fx-font-size:12px;");
        colorPaletteBox.getChildren().add(title);
        List<Color> colors = imageProcessor.getSelectedColors();
        if (colors.isEmpty()) {
            Label none = new Label("(none)"); none.setStyle("-fx-text-fill:grey;-fx-font-size:11px;");
            colorPaletteBox.getChildren().add(none);
        } else {
            for (int i = 0; i < colors.size(); i++)
                colorPaletteBox.getChildren().add(buildSwatch(colors.get(i), i));
        }
        Button addBtn = new Button("+ Add Color");
        addBtn.setStyle("-fx-font-size:11px;-fx-background-color:#388E3C;-fx-text-fill:white;-fx-cursor:hand;");
        addBtn.setOnAction(e -> openColorPickerDialog());
        colorPaletteBox.getChildren().add(addBtn);
    }

    private HBox buildSwatch(Color color, int index) {
        Rectangle square = new Rectangle(18, 18, color);
        square.setStroke(Color.DARKGRAY); square.setStrokeWidth(1.0);
        square.setArcWidth(3); square.setArcHeight(3);
        Label colorLabel = new Label(String.format("H%.0f S%.0f B%.0f",
                color.getHue(), color.getSaturation()*100, color.getBrightness()*100));
        colorLabel.setStyle("-fx-font-size:10px;");
        Button removeBtn = new Button("x");
        removeBtn.setStyle("-fx-font-size:10px;-fx-padding:1 5;-fx-background-color:#C62828;-fx-text-fill:white;-fx-cursor:hand;");
        removeBtn.setOnAction(e -> {
            List<Color> current = new ArrayList<>(imageProcessor.getSelectedColors());
            current.remove(index); imageProcessor.clearLeafColors();
            current.forEach(imageProcessor::addLeafColor);
            updateColorsInfo(); refreshColorPalettePanel();
        });
        HBox swatch = new HBox(4, square, colorLabel, removeBtn);
        swatch.setAlignment(Pos.CENTER_LEFT); swatch.setPadding(new Insets(2,6,2,6));
        swatch.setStyle("-fx-border-color:#bdbdbd;-fx-border-radius:4;-fx-background-radius:4;-fx-background-color:#f5f5f5;");
        return swatch;
    }

    private void openColorPickerDialog() {
        Dialog<Color> dialog = new Dialog<>();
        dialog.setTitle("Add Leaf Color"); dialog.setHeaderText("Choose a color to add to the palette");
        ColorPicker picker = new ColorPicker(Color.ORANGE); picker.setPrefWidth(200);
        VBox content = new VBox(8, new Label("Pick a color:"), picker); content.setPadding(new Insets(16));
        dialog.getDialogPane().setContent(content);
        ButtonType addType = new ButtonType("Add to Palette", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(addType, ButtonType.CANCEL);
        dialog.setResultConverter(btn -> btn == addType ? picker.getValue() : null);
        dialog.showAndWait().ifPresent(color -> {
            imageProcessor.addLeafColor(color); updateColorsInfo(); refreshColorPalettePanel();
            updateStatus("Added color: " + colorToString(color)); enableProcessingButtons();
        });
    }

    // ═════════════════════════════════════════════════════════════════════════
    // CANVAS DRAWING
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Redraws all overlay layers.
     *
     * After syncCanvasToImage():
     *   canvas size == rendered image size
     *   scaleX = canvasW / processingW  maps processing coords to canvas pixels
     *
     * No offset — StackPane aligns canvas to image automatically.
     */
    private void drawLeafOverlay() {
        if (overlayCanvas == null || imageView.getImage() == null) return;

        // Ensure canvas is properly sized before drawing
        if (overlayCanvas.getWidth() <= 0 || overlayCanvas.getHeight() <= 0) {
            syncCanvasToImage();
            if (overlayCanvas.getWidth() <= 0 || overlayCanvas.getHeight() <= 0) return;
        }

        GraphicsContext gc = overlayCanvas.getGraphicsContext2D();
        gc.clearRect(0, 0, overlayCanvas.getWidth(), overlayCanvas.getHeight());

        if (detectedLeaves.isEmpty()) return;

        double[] t = buildTransform();
        double scaleX = t[0], scaleY = t[1];

        // Layer 1: bounding rectangles
        if (showRectangles) {
            gc.setStroke(Color.DODGERBLUE); gc.setLineWidth(1.8);
            for (Leaf leaf : detectedLeaves) {
                double[] r = leafToCanvas(leaf, scaleX, scaleY);
                gc.strokeRect(r[0], r[1], r[2], r[3]);
            }
        }

        // Layer 2: number labels
        if (showNumbers) {
            gc.setFont(Font.font("Arial", FontWeight.BOLD, 11));
            for (Leaf leaf : detectedLeaves) {
                double[] r = leafToCanvas(leaf, scaleX, scaleY);
                String lbl = "#" + leaf.getSequentialNumber();
                double tx = r[0] + 2, ty = r[1] + 12;
                gc.setFill(Color.rgb(0, 80, 200, 0.65));
                gc.fillRoundRect(tx-1, ty-11, lbl.length()*7.0+4, 14, 3, 3);
                gc.setFill(Color.WHITE);
                gc.fillText(lbl, tx, ty);
            }
        }

        // Layer 3: TSP path
        if (showTSPPath && lastTSPPath != null && lastTSPPath.size() >= 2) {
            gc.setStroke(Color.ORANGE); gc.setLineWidth(2.5); gc.setLineDashes(9, 5);
            for (int i = 0; i < lastTSPPath.size()-1; i++) {
                Leaf.PixelPoint a = lastTSPPath.get(i).getCenter();
                Leaf.PixelPoint b = lastTSPPath.get(i+1).getCenter();
                double ax = a.getX() * scaleX, ay = a.getY() * scaleY;
                double bx = b.getX() * scaleX, by = b.getY() * scaleY;
                gc.strokeLine(ax, ay, bx, by);
                gc.setLineDashes();
                gc.setFill(Color.ORANGE); gc.fillOval(ax-4, ay-4, 8, 8);
                gc.setLineDashes(9, 5);
            }
            Leaf.PixelPoint last = lastTSPPath.get(lastTSPPath.size()-1).getCenter();
            gc.setLineDashes();
            gc.setFill(Color.ORANGE);
            gc.fillOval(last.getX()*scaleX-4, last.getY()*scaleY-4, 8, 8);
        }
        gc.setLineDashes();

        // Layer 4: hover highlight
        if (hoveredLeaf != null) {
            double[] r = leafToCanvas(hoveredLeaf, scaleX, scaleY);
            gc.setStroke(Color.LIMEGREEN); gc.setLineWidth(2.5);
            gc.strokeRect(r[0], r[1], r[2], r[3]);
        }

        // Layer 5: selection highlight
        if (selectedLeaf != null) {
            double[] r = leafToCanvas(selectedLeaf, scaleX, scaleY);
            gc.setStroke(Color.ORANGERED); gc.setLineWidth(3.5);
            gc.strokeRect(r[0], r[1], r[2], r[3]);
        }
    }

    /**
     * Returns {scaleX, scaleY} mapping processing-space coords to canvas pixels.
     *   scaleX = canvasWidth  / processingWidth
     *   scaleY = canvasHeight / processingHeight
     */
    private double[] buildTransform() {
        double cW = overlayCanvas.getWidth();
        double cH = overlayCanvas.getHeight();
        double pW = imageProcessor.getWidth();
        double pH = imageProcessor.getHeight();
        return new double[]{ pW > 0 ? cW/pW : 1.0,  pH > 0 ? cH/pH : 1.0 };
    }

    /** Leaf bounding box (processing-space) → canvas pixel rect {x, y, w, h}. */
    private double[] leafToCanvas(Leaf leaf, double scaleX, double scaleY) {
        javafx.geometry.Rectangle2D b = leaf.getBoundingBox();
        return new double[]{
                b.getMinX()   * scaleX,
                b.getMinY()   * scaleY,
                b.getWidth()  * scaleX,
                b.getHeight() * scaleY
        };
    }

    /** Full overlay redraw + one extra animated highlight rectangle. */
    private void highlightLeafAnimation(Leaf leaf, Color color, double lineWidth) {
        if (overlayCanvas == null || imageView.getImage() == null) return;
        drawLeafOverlay();
        double[] t = buildTransform();
        double[] r = leafToCanvas(leaf, t[0], t[1]);
        GraphicsContext gc = overlayCanvas.getGraphicsContext2D();
        gc.setStroke(color); gc.setLineWidth(lineWidth);
        gc.strokeRect(r[0], r[1], r[2], r[3]);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // MOUSE INTERACTION
    // ═════════════════════════════════════════════════════════════════════════

    private void handleCanvasHover(MouseEvent event) {
        if (detectedLeaves.isEmpty() || leafDetector == null) return;
        int[] proc = canvasToProcessing(event.getX(), event.getY());
        if (proc == null) return;
        Leaf leaf = leafDetector.getLeafAtPixel(proc[0], proc[1]);
        if (leaf == hoveredLeaf) return;
        hoveredLeaf = leaf;
        drawLeafOverlay();
        if (leaf != null) {
            leafTooltip.setText(String.format("Leaf #%d  |  %d px  |  (%d,%d)-(%d,%d)",
                    leaf.getSequentialNumber(), leaf.getSize(),
                    leaf.getMinX(), leaf.getMinY(), leaf.getMaxX(), leaf.getMaxY()));
            Tooltip.install(overlayCanvas, leafTooltip);
            leafTooltip.show(overlayCanvas, event.getScreenX()+12, event.getScreenY()+12);
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
            updateStatus(String.format("Leaf #%d  |  Size: %d px  |  Bounds: (%d,%d) -> (%d,%d)",
                    leaf.getSequentialNumber(), leaf.getSize(),
                    leaf.getMinX(), leaf.getMinY(), leaf.getMaxX(), leaf.getMaxY()));
        } else {
            selectedLeaf = null;
            updateStatus("Detection complete: " + detectedLeaves.size() + " leaves found");
        }
        drawLeafOverlay();
    }

    // ═════════════════════════════════════════════════════════════════════════
    // COORDINATE HELPERS
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Canvas-local (x, y) → processing-space (x, y).
     * Linear mapping — canvas covers exactly the rendered image.
     */
    private int[] canvasToProcessing(double canvasX, double canvasY) {
        if (imageProcessor.getWidth()  == 0 || imageProcessor.getHeight() == 0) return null;
        if (overlayCanvas.getWidth()   <= 0 || overlayCanvas.getHeight()  <= 0) return null;
        double scaleX = overlayCanvas.getWidth()  / imageProcessor.getWidth();
        double scaleY = overlayCanvas.getHeight() / imageProcessor.getHeight();
        int px = Math.max(0, Math.min((int)(canvasX / scaleX), imageProcessor.getWidth()  - 1));
        int py = Math.max(0, Math.min((int)(canvasY / scaleY), imageProcessor.getHeight() - 1));
        return new int[]{px, py};
    }

    // ═════════════════════════════════════════════════════════════════════════
    // UTILITY
    // ═════════════════════════════════════════════════════════════════════════

    private void loadImage(File file) {
        try {
            updateStatus("Loading image: " + file.getName());
            String uri = file.toURI().toString();
            imageProcessor.loadImage(uri, true);

            Image image = new Image(uri);
            imageView.setImage(image);
            imageView.setFitWidth(500); imageView.setFitHeight(500);
            imageView.setPreserveRatio(true);
            bwImageView.setFitWidth(500); bwImageView.setFitHeight(500);
            bwImageView.setPreserveRatio(true);

            labelImageInfo.setText(String.format("%s (%.0fx%.0f)",
                    file.getName(), image.getWidth(), image.getHeight()));

            menuSelectColors.setDisable(false); btnSelectColors.setDisable(false);
            bwImageView.setImage(null); detectedLeaves.clear(); imageProcessor.clearLeafColors();
            clearCanvas();
            labelColorsInfo.setText("None"); labelLeavesCount.setText("0"); labelProcessingTime.setText("-");
            lastTSPPath = null; showTSPPath = false; selectedLeaf = null; hoveredLeaf = null;

            menuConvertBW.setDisable(true);    btnConvertBW.setDisable(true);
            menuDetectLeaves.setDisable(true); btnDetectLeaves.setDisable(true);
            menuAnimatePath.setDisable(true);  btnAnimatePath.setDisable(true);
            menuStopAnimation.setDisable(true);
            if (btnColorSets != null) btnColorSets.setDisable(true);
            if (btnPickSet   != null) btnPickSet.setDisable(true);
            if (btnResetBW   != null) btnResetBW.setDisable(true);

            refreshColorPalettePanel();
            updateStatus("Image loaded: " + file.getName() + "  — select leaf colors to continue.");
        } catch (Exception e) {
            showError("Load Error", "Failed to load image: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void clearCanvas() {
        if (overlayCanvas != null)
            overlayCanvas.getGraphicsContext2D()
                    .clearRect(0, 0, overlayCanvas.getWidth(), overlayCanvas.getHeight());
    }

    private void enableProcessingButtons() {
        menuConvertBW.setDisable(false); btnConvertBW.setDisable(false);
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
        alert.setTitle(title); alert.setHeaderText(null);
        alert.setContentText(message); alert.showAndWait();
    }

    private String colorToString(Color color) {
        return String.format("HSB(%.0f°, %.0f%%, %.0f%%)",
                color.getHue(), color.getSaturation()*100, color.getBrightness()*100);
    }
}