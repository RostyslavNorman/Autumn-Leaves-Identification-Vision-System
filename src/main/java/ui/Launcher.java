package ui;

/**
 * Launcher wrapper required for JavaFX when running from a fat JAR.
 * This class must NOT extend Application.
 */
public class Launcher {
    public static void main(String[] args) {
        Main.main(args);
    }
}