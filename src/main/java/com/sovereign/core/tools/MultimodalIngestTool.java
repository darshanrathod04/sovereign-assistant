package com.sovereign.core.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * <b>MultimodalIngestTool</b>
 *
 * <p>Zero-cost file and image understanding tool.
 * Ingests images (PNG, JPEG, WEBP), PDFs, source code, and text files.
 * Uses Gemini's free tier multimodal vision API (via {@code inline_data}) for images,
 * and Apache PDFBox for zero-cost local PDF text extraction.</p>
 */
public class MultimodalIngestTool {

    private static final Logger LOG = Logger.getLogger(MultimodalIngestTool.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static final String DEFAULT_MODEL = "gemini-2.5-flash";
    public static final String ENDPOINT_TEMPLATE =
            "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent?key=%s";

    private final String apiKey;
    private final String model;

    public MultimodalIngestTool() {
        this(resolveApiKey(), resolveModel());
    }

    public MultimodalIngestTool(String apiKey) {
        this(apiKey, resolveModel());
    }

    public MultimodalIngestTool(String apiKey, String model) {
        this.apiKey = (apiKey != null && !apiKey.isBlank()) ? apiKey.trim() : null;
        this.model = (model != null && !model.isBlank()) ? model.trim() : DEFAULT_MODEL;
    }

    public record IngestResult(boolean success, String summary, String details, String fileType) {}

    /**
     * Ingests a file from disk with a user question or instruction.
     */
    public IngestResult ingest(Path filePath, String prompt) {
        if (filePath == null || !Files.exists(filePath)) {
            return new IngestResult(false, "File not found: " + filePath, "", "unknown");
        }

        String fileName = filePath.getFileName().toString().toLowerCase(Locale.ROOT);
        String userPrompt = (prompt != null && !prompt.isBlank())
                ? prompt.trim()
                : "Analyze this file thoroughly and describe its contents, key points, and structure.";

        try {
            if (isImageFile(fileName)) {
                return ingestImage(filePath, userPrompt);
            } else if (fileName.endsWith(".pdf")) {
                return ingestPdf(filePath, userPrompt);
            } else {
                return ingestTextOrCode(filePath, userPrompt);
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "[INGEST] Error ingesting " + filePath + ": " + e.getMessage());
            return new IngestResult(false, "Failed to ingest file: " + e.getMessage(), "", fileName);
        }
    }

    private IngestResult ingestImage(Path imagePath, String prompt) throws IOException {
        byte[] bytes = Files.readAllBytes(imagePath);
        String mimeType = detectImageMimeType(imagePath.getFileName().toString());
        String base64Data = Base64.getEncoder().encodeToString(bytes);

        if (apiKey == null) {
            return new IngestResult(true,
                    "[OFFLINE] Image loaded: " + imagePath.getFileName() + " (" + bytes.length + " bytes, " + mimeType + "). Set GEMINI_API_KEY for vision reasoning.",
                    "File size: " + bytes.length + " bytes",
                    "image");
        }

        String analysis = callGeminiVision(base64Data, mimeType, prompt);
        if (analysis != null && !analysis.isBlank()) {
            return new IngestResult(true, analysis, "Image: " + imagePath.getFileName(), "image");
        }

        return new IngestResult(false, "Vision analysis returned no text.", "", "image");
    }

    private IngestResult ingestPdf(Path pdfPath, String prompt) throws IOException {
        String extractedText;
        try (PDDocument doc = Loader.loadPDF(pdfPath.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            extractedText = stripper.getText(doc).trim();
        }

        if (extractedText.isEmpty()) {
            return new IngestResult(false, "PDF contains no extractable text (it may be scanned/image-only).", "", "pdf");
        }

        // If we have an API key, synthesize an intelligent summary with Gemini
        if (apiKey != null) {
            String truncated = extractedText.length() > 15_000
                    ? extractedText.substring(0, 15_000) + "\n...[truncated for token limit]"
                    : extractedText;
            String instruction = prompt + "\n\n--- PDF TEXT CONTENT ---\n" + truncated;
            String analysis = callGeminiText(instruction);
            if (analysis != null && !analysis.isBlank()) {
                return new IngestResult(true, analysis, "Extracted " + extractedText.length() + " chars from " + pdfPath.getFileName(), "pdf");
            }
        }

        // Offline fallback: return first 500 characters preview
        String preview = extractedText.length() > 500 ? extractedText.substring(0, 500) + "..." : extractedText;
        return new IngestResult(true, "[EXTRACTED PDF TEXT]\n" + preview, "Total length: " + extractedText.length() + " characters", "pdf");
    }

    private IngestResult ingestTextOrCode(Path filePath, String prompt) throws IOException {
        String content = Files.readString(filePath, StandardCharsets.UTF_8).trim();
        if (apiKey != null) {
            String truncated = content.length() > 15_000
                    ? content.substring(0, 15_000) + "\n...[truncated]"
                    : content;
            String instruction = prompt + "\n\n--- FILE: " + filePath.getFileName() + " ---\n" + truncated;
            String analysis = callGeminiText(instruction);
            if (analysis != null && !analysis.isBlank()) {
                return new IngestResult(true, analysis, "File size: " + content.length() + " characters", "text");
            }
        }

        String preview = content.length() > 500 ? content.substring(0, 500) + "..." : content;
        return new IngestResult(true, "[FILE PREVIEW]\n" + preview, "File size: " + content.length() + " characters", "text");
    }

    /**
     * Calls Gemini generateContent with multimodal inline_data image.
     */
    public String callGeminiVision(String base64Data, String mimeType, String prompt) {
        if (apiKey == null) return null;
        try {
            String urlStr = String.format(ENDPOINT_TEMPLATE, model, apiKey);
            URL url = URI.create(urlStr).toURL();
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setDoOutput(true);
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(30_000);

            String requestJson = buildVisionRequestJson(base64Data, mimeType, prompt);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(requestJson.getBytes(StandardCharsets.UTF_8));
            }

            int status = conn.getResponseCode();
            if (status == 200) {
                JsonNode root = MAPPER.readTree(conn.getInputStream());
                JsonNode textNode = root.path("candidates").get(0).path("content").path("parts").get(0).path("text");
                if (!textNode.isMissingNode()) {
                    return textNode.asText().trim();
                }
            } else {
                LOG.warning("[VISION] Gemini Vision HTTP " + status);
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "[VISION] Gemini Vision error: " + e.getMessage());
        }
        return null;
    }

    public String callGeminiText(String prompt) {
        if (apiKey == null) return null;
        try {
            String urlStr = String.format(ENDPOINT_TEMPLATE, model, apiKey);
            URL url = URI.create(urlStr).toURL();
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setDoOutput(true);
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(30_000);

            String requestJson = "{\"contents\":[{\"parts\":[{\"text\":" + MAPPER.writeValueAsString(prompt) + "}]}]}";
            try (OutputStream os = conn.getOutputStream()) {
                os.write(requestJson.getBytes(StandardCharsets.UTF_8));
            }

            int status = conn.getResponseCode();
            if (status == 200) {
                JsonNode root = MAPPER.readTree(conn.getInputStream());
                JsonNode textNode = root.path("candidates").get(0).path("content").path("parts").get(0).path("text");
                if (!textNode.isMissingNode()) {
                    return textNode.asText().trim();
                }
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "[INGEST] Gemini text call error: " + e.getMessage());
        }
        return null;
    }

    public static String buildVisionRequestJson(String base64Data, String mimeType, String prompt) throws IOException {
        String safePrompt = MAPPER.writeValueAsString(prompt);
        return "{\n" +
                "  \"contents\": [{\n" +
                "    \"parts\": [\n" +
                "      {\"text\": " + safePrompt + "},\n" +
                "      {\n" +
                "        \"inline_data\": {\n" +
                "          \"mime_type\": \"" + mimeType + "\",\n" +
                "          \"data\": \"" + base64Data + "\"\n" +
                "        }\n" +
                "      }\n" +
                "    ]\n" +
                "  }]\n" +
                "}";
    }

    public static boolean isImageFile(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".webp") || lower.endsWith(".gif") || lower.endsWith(".bmp");
    }

    public static String detectImageMimeType(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".bmp")) return "image/bmp";
        return "image/png";
    }

    private static String resolveApiKey() {
        String key = System.getenv("GEMINI_API_KEY");
        if (key == null || key.isBlank()) key = System.getProperty("GEMINI_API_KEY");
        if (key == null || key.isBlank()) key = System.getProperty("gemini.api.key");
        return key;
    }

    private static String resolveModel() {
        String m = System.getProperty("sovereign.llm.model");
        if (m == null || m.isBlank()) m = System.getenv("SOVEREIGN_LLM_MODEL");
        return (m != null && !m.isBlank()) ? m.trim() : DEFAULT_MODEL;
    }
}
