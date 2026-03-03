package ui;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

/**
 * Main entry point for the Autumn Leaves Identification System.
 *
 * This is a JavaFX application that allows users to:
 * - Load autumn leaf images
 * - Select leaf colors
 * - Convert images to black-and-white
 * - Detect leaf clusters using Union-Find
 * - Visualize results with rectangles and numbering
 * - Animate TSP path connecting all leaves
 */
public class Main extends Application {

    @Override
    public void start(Stage primaryStage) throws Exception {
        try {
            // Load the FXML layout
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/main.fxml"));
            Parent root = loader.load();

            // Get the controller
            MainController controller = loader.getController();
            controller.setPrimaryStage(primaryStage);

            // Configure the scene
            Scene scene = new Scene(root, 1200, 800);

            // Load CSS if available
            try {
                String css = getClass().getResource("/css/style.css").toExternalForm();
                scene.getStylesheets().add(css);
            } catch (Exception e) {
                System.out.println("Note: CSS file not found, using default styling");
            }

            // Configure the stage
            primaryStage.setTitle("Autumn Leaves Identification System");
            primaryStage.setScene(scene);
            primaryStage.setMinWidth(800);
            primaryStage.setMinHeight(600);

            // Show the window
            primaryStage.show();

            System.out.println("=".repeat(60));
            System.out.println("AUTUMN LEAVES IDENTIFICATION SYSTEM");
            System.out.println("=".repeat(60));
            System.out.println("Application started successfully");
            System.out.println("Window size: " + scene.getWidth() + "x" + scene.getHeight());
            System.out.println("=".repeat(60));

        } catch (Exception e) {
            System.err.println("Error starting application:");
            e.printStackTrace();
            throw e;
        }
    }

    @Override
    public void stop() {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("Application closing...");
        System.out.println("=".repeat(60));
    }

    /**
     * Main method - entry point of the application.
     *
     * @param args Command line arguments (not used)
     */
    public static void main(String[] args) {
        launch(args);
    }
}