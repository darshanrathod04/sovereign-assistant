package com.sovereign.core.tools;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * <b>ScreenCaptureTool</b>
 *
 * <p>Zero-cost screen capture intelligence.
 * Uses Java AWT's {@link Robot} to capture the current desktop screen, saves it locally,
 * and feeds the image into Gemini Vision to analyze open applications, terminal outputs,
 * and UI errors.</p>
 */
public class ScreenCaptureTool {

    private static final Logger LOG = Logger.getLogger(ScreenCaptureTool.class.getName());

    public static final Path SCREENSHOTS_DIR =
            Path.of(System.getProperty("user.home"), ".sovereign", "screenshots");

    private static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault());

    private final MultimodalIngestTool ingestTool;

    public ScreenCaptureTool() {
        this(new MultimodalIngestTool());
    }

    public ScreenCaptureTool(MultimodalIngestTool ingestTool) {
        this.ingestTool = ingestTool != null ? ingestTool : new MultimodalIngestTool();
    }

    public record ScreenCaptureResult(boolean success, Path savedPath, String analysis, String message) {}

    /**
     * Captures the screen, saves to disk, and performs visual analysis.
     */
    public ScreenCaptureResult captureAndAnalyze(String prompt) {
        if (GraphicsEnvironment.isHeadless()) {
            return new ScreenCaptureResult(false, null, null,
                    "Screen capture is unavailable in headless or CI environments without a graphical display.");
        }

        try {
            BufferedImage image = captureScreen();
            if (image == null) {
                return new ScreenCaptureResult(false, null, null, "Failed to capture screen image buffer.");
            }

            Path savedPath = saveScreenshot(image);

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(image, "png", baos);
            byte[] bytes = baos.toByteArray();
            String base64 = Base64.getEncoder().encodeToString(bytes);

            String userPrompt = (prompt != null && !prompt.isBlank())
                    ? prompt.trim()
                    : "Analyze this screen capture thoroughly. Describe what windows, applications, code, terminal text, or error dialogs are currently visible.";

            String analysis = ingestTool.callGeminiVision(base64, "image/png", userPrompt);
            if (analysis == null || analysis.isBlank()) {
                analysis = "[OFFLINE] Screenshot saved to " + savedPath + " (" + bytes.length + " bytes). Set GEMINI_API_KEY for vision analysis.";
            }

            return new ScreenCaptureResult(true, savedPath, analysis, "Screenshot captured successfully.");

        } catch (Exception e) {
            LOG.log(Level.WARNING, "[SCREENSHOT] Screen capture failed: " + e.getMessage());
            return new ScreenCaptureResult(false, null, null, "Screen capture failed: " + e.getMessage());
        }
    }

    public BufferedImage captureScreen() throws AWTException {
        if (GraphicsEnvironment.isHeadless()) return null;
        Robot robot = new Robot();
        Rectangle screenRect = new Rectangle(Toolkit.getDefaultToolkit().getScreenSize());
        return robot.createScreenCapture(screenRect);
    }

    public Path saveScreenshot(BufferedImage image) throws IOException {
        Files.createDirectories(SCREENSHOTS_DIR);
        String fileName = "screenshot-" + TS_FMT.format(Instant.now()) + ".png";
        Path target = SCREENSHOTS_DIR.resolve(fileName);
        ImageIO.write(image, "png", target.toFile());
        return target;
    }

    public boolean isSupported() {
        return !GraphicsEnvironment.isHeadless();
    }
}
