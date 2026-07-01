package com.file_compression_service.watcher;

import com.file_compression_service.config.WatcherProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class FileValidatorTest {

    @TempDir
    Path tempDir;

    private FileValidator validator;

    @BeforeEach
    void setUp() {
        WatcherProperties properties = new WatcherProperties(
                new WatcherProperties.FolderConfig(tempDir.toString(), 60000, 100),
                new WatcherProperties.FolderConfig(tempDir.resolve("outbox").toString(), 0, 0),
                new WatcherProperties.FolderConfig(tempDir.resolve("processed").toString(), 0, 0),
                new WatcherProperties.FolderConfig(tempDir.resolve("dlq").toString(), 0, 0),
                new WatcherProperties.QueueConfig(500),
                new WatcherProperties.AlgorithmConfig("ZSTD", 10),
                new WatcherProperties.ValidatorConfig(".txt,.csv,.log,.json,.xml,.yaml,.yml,.sql,.md", true, 100)
        );
        validator = new FileValidator(properties);
    }

    @Test
    void shouldAcceptValidTextFile() throws IOException {
        Path file = tempDir.resolve("data.txt");
        Files.writeString(file, "Hello, this is a valid text file with some content.");

        var result = validator.validate(file);

        assertThat(result.isValid()).isTrue();
        assertThat(result.status()).isEqualTo(FileValidator.ValidationStatus.VALID);
    }

    @Test
    void shouldAcceptValidCsvFile() throws IOException {
        Path file = tempDir.resolve("report.csv");
        Files.writeString(file, "name,age,city\nAlice,30,NYC\nBob,25,LA");

        var result = validator.validate(file);

        assertThat(result.isValid()).isTrue();
    }

    @Test
    void shouldAcceptValidJsonFile() throws IOException {
        Path file = tempDir.resolve("config.json");
        Files.writeString(file, "{\"key\": \"value\", \"count\": 42}");

        var result = validator.validate(file);

        assertThat(result.isValid()).isTrue();
    }

    @Test
    void shouldAcceptValidLogFile() throws IOException {
        Path file = tempDir.resolve("app.log");
        Files.writeString(file, "2026-07-01 10:00:00 INFO Application started");

        var result = validator.validate(file);

        assertThat(result.isValid()).isTrue();
    }

    @Test
    void shouldRejectFileWithNoExtension() throws IOException {
        Path file = tempDir.resolve("README");
        Files.writeString(file, "Some content");

        var result = validator.validate(file);

        assertThat(result.isValid()).isFalse();
        assertThat(result.status()).isEqualTo(FileValidator.ValidationStatus.NO_EXTENSION);
    }

    @Test
    void shouldRejectInvalidExtension() throws IOException {
        Path file = tempDir.resolve("image.png");
        Files.writeString(file, "not really a png");

        var result = validator.validate(file);

        assertThat(result.isValid()).isFalse();
        assertThat(result.status()).isEqualTo(FileValidator.ValidationStatus.INVALID_EXTENSION);
    }

    @Test
    void shouldRejectEmptyFile() throws IOException {
        Path file = tempDir.resolve("empty.txt");
        Files.writeString(file, "");

        var result = validator.validate(file);

        assertThat(result.isValid()).isFalse();
        assertThat(result.status()).isEqualTo(FileValidator.ValidationStatus.EMPTY_FILE);
    }

    @Test
    void shouldRejectNonExistentFile() {
        Path file = tempDir.resolve("ghost.txt");

        var result = validator.validate(file);

        assertThat(result.isValid()).isFalse();
        assertThat(result.status()).isEqualTo(FileValidator.ValidationStatus.NOT_A_FILE);
    }

    @Test
    void shouldRejectDirectory() throws IOException {
        Path dir = tempDir.resolve("subdir");
        Files.createDirectories(dir);

        var result = validator.validate(dir);

        assertThat(result.isValid()).isFalse();
        assertThat(result.status()).isEqualTo(FileValidator.ValidationStatus.NOT_A_FILE);
    }

    @Test
    void shouldRejectZipMagicBytes() throws IOException {
        Path file = tempDir.resolve("sneaky.txt");
        // ZIP magic bytes: 50 4B 03 04
        byte[] content = new byte[]{0x50, 0x4B, 0x03, 0x04, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00};
        Files.write(file, content);

        var result = validator.validate(file);

        assertThat(result.isValid()).isFalse();
        assertThat(result.status()).isEqualTo(FileValidator.ValidationStatus.ALREADY_COMPRESSED);
    }

    @Test
    void shouldRejectGzipMagicBytes() throws IOException {
        Path file = tempDir.resolve("sneaky.csv");
        // GZIP magic bytes: 1F 8B
        byte[] content = new byte[]{(byte) 0x1F, (byte) 0x8B, 0x08, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00};
        Files.write(file, content);

        var result = validator.validate(file);

        assertThat(result.isValid()).isFalse();
        assertThat(result.status()).isEqualTo(FileValidator.ValidationStatus.ALREADY_COMPRESSED);
    }

    @Test
    void shouldRejectZstdMagicBytes() throws IOException {
        Path file = tempDir.resolve("sneaky.json");
        // ZSTD magic bytes: 28 B5 2F FD
        byte[] content = new byte[]{0x28, (byte) 0xB5, 0x2F, (byte) 0xFD, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00};
        Files.write(file, content);

        var result = validator.validate(file);

        assertThat(result.isValid()).isFalse();
        assertThat(result.status()).isEqualTo(FileValidator.ValidationStatus.ALREADY_COMPRESSED);
    }

    @Test
    void shouldRejectPdfMagicBytes() throws IOException {
        Path file = tempDir.resolve("document.txt");
        // PDF magic bytes: 25 50 44 46
        byte[] content = new byte[]{0x25, 0x50, 0x44, 0x46, 0x2D, 0x31, 0x2E, 0x34, 0x00, 0x00};
        Files.write(file, content);

        var result = validator.validate(file);

        assertThat(result.isValid()).isFalse();
        assertThat(result.status()).isEqualTo(FileValidator.ValidationStatus.UNSUPPORTED_BINARY);
    }

    @Test
    void shouldRejectBzip2MagicBytes() throws IOException {
        Path file = tempDir.resolve("archive.log");
        // BZIP2 magic bytes: 42 5A 68
        byte[] content = new byte[]{0x42, 0x5A, 0x68, 0x39, 0x31, 0x00, 0x00, 0x00, 0x00, 0x00};
        Files.write(file, content);

        var result = validator.validate(file);

        assertThat(result.isValid()).isFalse();
        assertThat(result.status()).isEqualTo(FileValidator.ValidationStatus.ALREADY_COMPRESSED);
    }

    @Test
    void shouldRejectXzMagicBytes() throws IOException {
        Path file = tempDir.resolve("backup.sql");
        // XZ magic bytes: FD 37 7A 58 5A
        byte[] content = new byte[]{(byte) 0xFD, 0x37, 0x7A, 0x58, 0x5A, 0x00, 0x00, 0x00, 0x00, 0x00};
        Files.write(file, content);

        var result = validator.validate(file);

        assertThat(result.isValid()).isFalse();
        assertThat(result.status()).isEqualTo(FileValidator.ValidationStatus.ALREADY_COMPRESSED);
    }

    @Test
    void shouldRejectOversizedFile() throws IOException {
        // Create a validator with 1 byte max to easily test size rejection
        WatcherProperties smallLimitProps = new WatcherProperties(
                new WatcherProperties.FolderConfig(tempDir.toString(), 60000, 100),
                new WatcherProperties.FolderConfig(tempDir.resolve("outbox").toString(), 0, 0),
                new WatcherProperties.FolderConfig(tempDir.resolve("processed").toString(), 0, 0),
                new WatcherProperties.FolderConfig(tempDir.resolve("dlq").toString(), 0, 0),
                new WatcherProperties.QueueConfig(500),
                new WatcherProperties.AlgorithmConfig("ZSTD", 10),
                // maxFileSizeMb = 0 means any file with content is "too large" (0 * 1MB = 0 bytes max)
                new WatcherProperties.ValidatorConfig(".txt,.csv,.log,.json,.xml,.yaml,.yml,.sql,.md", true, 0)
        );
        FileValidator strictValidator = new FileValidator(smallLimitProps);

        Path file = tempDir.resolve("big.txt");
        Files.writeString(file, "Some content that exceeds 0 bytes");

        var result = strictValidator.validate(file);

        assertThat(result.isValid()).isFalse();
        assertThat(result.status()).isEqualTo(FileValidator.ValidationStatus.TOO_LARGE);
    }

    @Test
    void shouldAcceptAllSupportedExtensions() throws IOException {
        String[] extensions = {"txt", "csv", "log", "json", "xml", "yaml", "yml", "sql", "md"};
        for (String ext : extensions) {
            Path file = tempDir.resolve("test-" + ext + "." + ext);
            Files.writeString(file, "Valid content for " + ext + " file testing.");

            var result = validator.validate(file);

            assertThat(result.isValid())
                    .as("Extension .%s should be accepted", ext)
                    .isTrue();
        }
    }
}
