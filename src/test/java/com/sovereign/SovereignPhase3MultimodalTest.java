package com.sovereign;

import com.sovereign.core.tools.LogAnalyzerTool;
import com.sovereign.core.tools.MultimodalIngestTool;
import com.sovereign.core.tools.ScreenCaptureTool;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>SovereignPhase3MultimodalTest</b>
 *
 * <p>Phase 3 Verification Test Suite validating Multimodal File & Image Ingest,
 * Apache PDFBox zero-cost PDF extraction, Screen Capture safety, and Log Diagnostics.</p>
 */
public class SovereignPhase3MultimodalTest {

    @Test
    @DisplayName("Test 1: MultimodalIngestTool detects MIME types and formats vision JSON payloads")
    void testImageMimeAndVisionPayloadFormatting() throws IOException {
        assertThat(MultimodalIngestTool.isImageFile("photo.png")).isTrue();
        assertThat(MultimodalIngestTool.isImageFile("diagram.WEBP")).isTrue();
        assertThat(MultimodalIngestTool.isImageFile("script.java")).isFalse();

        assertThat(MultimodalIngestTool.detectImageMimeType("photo.png")).isEqualTo("image/png");
        assertThat(MultimodalIngestTool.detectImageMimeType("image.jpg")).isEqualTo("image/jpeg");
        assertThat(MultimodalIngestTool.detectImageMimeType("capture.webp")).isEqualTo("image/webp");

        String json = MultimodalIngestTool.buildVisionRequestJson("dGVzdA==", "image/png", "What is on the screen?");
        assertThat(json)
                .contains("\"inline_data\"")
                .contains("\"mime_type\": \"image/png\"")
                .contains("\"data\": \"dGVzdA==\"")
                .contains("What is on the screen?");
    }

    @Test
    @DisplayName("Test 2: MultimodalIngestTool extracts text from PDF files using Apache PDFBox")
    void testPdfTextExtractionWithPdfBox(@TempDir Path tempDir) throws IOException {
        Path pdfPath = tempDir.resolve("sample.pdf");

        // Programmatically generate a real 1-page PDF using PDFBox
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                stream.beginText();
                stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                stream.newLineAtOffset(100, 700);
                stream.showText("Sovereign Assistant Zero-Cost PDF Ingestion Test");
                stream.endText();
            }
            document.save(pdfPath.toFile());
        }

        assertThat(pdfPath).exists();

        MultimodalIngestTool ingestTool = new MultimodalIngestTool(null); // Offline mode
        MultimodalIngestTool.IngestResult result = ingestTool.ingest(pdfPath, "Summarize this PDF");

        assertThat(result.success()).isTrue();
        assertThat(result.fileType()).isEqualTo("pdf");
        assertThat(result.summary()).contains("Sovereign Assistant Zero-Cost PDF Ingestion Test");
    }

    @Test
    @DisplayName("Test 3: ScreenCaptureTool handles headless environments safely without exceptions")
    void testScreenCaptureHeadlessSafety() {
        ScreenCaptureTool captureTool = new ScreenCaptureTool();
        ScreenCaptureTool.ScreenCaptureResult result = captureTool.captureAndAnalyze("Inspect screen");

        if (java.awt.GraphicsEnvironment.isHeadless()) {
            assertThat(result.success()).isFalse();
            assertThat(result.message()).contains("headless");
        } else {
            // When run on a machine with display (e.g. desktop), it should capture or report status
            assertThat(result.message()).isNotBlank();
        }
    }

    @Test
    @DisplayName("Test 4: LogAnalyzerTool pinpoints compilation errors, stack traces, and recommendations")
    void testLogAnalyzerDiagnostics(@TempDir Path tempDir) throws IOException {
        LogAnalyzerTool analyzer = new LogAnalyzerTool();

        // 1. Test compilation error parsing
        String compileLog = """
                [INFO] Compiling 1 source file to target/classes
                [ERROR] src/main/java/com/sovereign/ReasoningSDK.java:[42,15] cannot find symbol
                  symbol:   class MissingSymbol
                  location: class com.sovereign.ReasoningSDK
                [INFO] BUILD FAILURE
                """;
        LogAnalyzerTool.LogAnalysis compileRes = analyzer.analyzeLogText(compileLog);
        assertThat(compileRes.isFailure()).isTrue();
        assertThat(compileRes.errorType()).isEqualTo("CompilationError");
        assertThat(compileRes.sourceFile()).isEqualTo("src/main/java/com/sovereign/ReasoningSDK.java");
        assertThat(compileRes.lineNumber()).isEqualTo(42);
        assertThat(compileRes.suggestion()).contains("imported correctly");

        // 2. Test stack trace parsing
        String stackTraceLog = """
                java.lang.NullPointerException: Cannot read field "name" because "user" is null
                    at com.sovereign.cli.SovereignReplRunner.main(SovereignReplRunner.java:100)
                """;
        LogAnalyzerTool.LogAnalysis stackRes = analyzer.analyzeLogText(stackTraceLog);
        assertThat(stackRes.isFailure()).isTrue();
        assertThat(stackRes.errorType()).isEqualTo("java.lang.NullPointerException");

        // 3. Test clean log
        String cleanLog = "[INFO] BUILD SUCCESS\n[INFO] Total time: 5.2s";
        LogAnalyzerTool.LogAnalysis cleanRes = analyzer.analyzeLogText(cleanLog);
        assertThat(cleanRes.isFailure()).isFalse();
    }
}
