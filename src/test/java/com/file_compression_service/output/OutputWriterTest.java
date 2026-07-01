package com.file_compression_service.output;

import com.file_compression_service.config.WatcherProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class OutputWriterTest {

    @TempDir
    Path tempDir;

    private OutputWriter outputWriter;

    @BeforeEach
    void setUp() {
        WatcherProperties properties = new WatcherProperties(
                new WatcherProperties.FolderConfig(tempDir.resolve("inbox").toString(), 60000, 100),
                new WatcherProperties.FolderConfig(tempDir.resolve("outbox").toString(), 0, 0),
                new WatcherProperties.FolderConfig(tempDir.resolve("processed").toString(), 0, 0),
                new WatcherProperties.FolderConfig(tempDir.resolve("dlq").toString(), 0, 0),
                new WatcherProperties.QueueConfig(500),
                new WatcherProperties.AlgorithmConfig("ZSTD", 10),
                new WatcherProperties.ValidatorConfig(".txt,.csv,.log,.json,.xml,.yaml,.yml,.sql,.md", true, 100)
        );
        outputWriter = new OutputWriter(properties);
    }

    @Test
    void shouldWriteCompressedFileToOutbox() throws IOException {
        byte[] data = "compressed data bytes".getBytes();

        outputWriter.writeCompressed(data, "test.txt", ".gz");

        Path outputFile = tempDir.resolve("outbox").resolve("test.txt.gz");
        assertThat(outputFile).exists();
        assertThat(Files.readAllBytes(outputFile)).isEqualTo(data);
    }

    @Test
    void shouldCreateOutboxDirectoryIfMissing() throws IOException {
        Path outboxDir = tempDir.resolve("outbox");
        assertThat(outboxDir).doesNotExist();

        outputWriter.writeCompressed("data".getBytes(), "file.csv", ".zst");

        assertThat(outboxDir).exists();
        assertThat(outboxDir.resolve("file.csv.zst")).exists();
    }

    @Test
    void shouldArchiveOriginalToProcessed() throws IOException {
        // Create inbox with a file
        Path inboxDir = tempDir.resolve("inbox");
        Files.createDirectories(inboxDir);
        Path originalFile = inboxDir.resolve("original.txt");
        Files.writeString(originalFile, "original content");

        outputWriter.archiveProcessed(originalFile);

        // Original should be moved (no longer in inbox)
        assertThat(originalFile).doesNotExist();
        // Should be in processed
        Path processedFile = tempDir.resolve("processed").resolve("original.txt");
        assertThat(processedFile).exists();
        assertThat(Files.readString(processedFile)).isEqualTo("original content");
    }

    @Test
    void shouldCreateProcessedDirectoryIfMissing() throws IOException {
        Path processedDir = tempDir.resolve("processed");
        assertThat(processedDir).doesNotExist();

        Path inboxDir = tempDir.resolve("inbox");
        Files.createDirectories(inboxDir);
        Path file = inboxDir.resolve("test.log");
        Files.writeString(file, "log data");

        outputWriter.archiveProcessed(file);

        assertThat(processedDir).exists();
    }

    @Test
    void shouldSendFileToDlqWithErrorSidecar() throws IOException {
        // Create a source file
        Path inboxDir = tempDir.resolve("inbox");
        Files.createDirectories(inboxDir);
        Path failedFile = inboxDir.resolve("broken.json");
        Files.writeString(failedFile, "not valid json");

        Exception error = new RuntimeException("Compression failed: out of memory");

        outputWriter.sendToDlq(failedFile, error);

        // File should be moved to DLQ
        Path dlqFile = tempDir.resolve("dlq").resolve("broken.json");
        assertThat(dlqFile).exists();
        assertThat(failedFile).doesNotExist();

        // Error sidecar should exist
        Path errorSidecar = tempDir.resolve("dlq").resolve("broken.json.error.json");
        assertThat(errorSidecar).exists();

        String errorContent = Files.readString(errorSidecar);
        assertThat(errorContent).contains("\"file\": \"broken.json\"");
        assertThat(errorContent).contains("Compression failed: out of memory");
        assertThat(errorContent).contains("\"timestamp\":");
    }

    @Test
    void shouldCreateDlqDirectoryIfMissing() throws IOException {
        Path dlqDir = tempDir.resolve("dlq");
        assertThat(dlqDir).doesNotExist();

        Path inboxDir = tempDir.resolve("inbox");
        Files.createDirectories(inboxDir);
        Path file = inboxDir.resolve("failing.txt");
        Files.writeString(file, "content");

        outputWriter.sendToDlq(file, new RuntimeException("test error"));

        assertThat(dlqDir).exists();
    }

    @Test
    void shouldWriteWithZstExtension() throws IOException {
        byte[] data = "zstd compressed data".getBytes();

        outputWriter.writeCompressed(data, "data.csv", ".zst");

        Path outputFile = tempDir.resolve("outbox").resolve("data.csv.zst");
        assertThat(outputFile).exists();
    }
}
