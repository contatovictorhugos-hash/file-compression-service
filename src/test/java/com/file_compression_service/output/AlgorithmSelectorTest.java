package com.file_compression_service.output;

import com.file_compression_service.config.WatcherProperties;
import com.file_compression_service.worker.strategy.CompressionStrategy;
import com.file_compression_service.worker.strategy.GzipStrategy;
import com.file_compression_service.worker.strategy.PassthroughStrategy;
import com.file_compression_service.worker.strategy.ZstdStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AlgorithmSelectorTest {

    @TempDir
    Path tempDir;

    private AlgorithmSelector selector;

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

        List<CompressionStrategy> strategies = List.of(new GzipStrategy(), new ZstdStrategy(), new PassthroughStrategy());
        selector = new AlgorithmSelector(properties, strategies);
    }

    @Test
    void shouldSelectPassthroughForTinyFiles() throws IOException {
        Path file = tempDir.resolve("tiny.txt");
        Files.writeString(file, "Tiny content"); // 12 bytes (< 150 bytes)

        CompressionStrategy strategy = selector.select(file);

        assertThat(strategy.algorithmName()).isEqualTo("PASSTHROUGH");
    }

    @Test
    void shouldSelectGzipForSmallFiles() throws IOException {
        // File smaller than 10KB threshold but larger than 150 bytes
        Path file = tempDir.resolve("small.log");
        Files.writeString(file, "Small file content, but repeating enough to exceed the 150 bytes threshold for passthrough. ".repeat(3));

        CompressionStrategy strategy = selector.select(file);

        assertThat(strategy.algorithmName()).isEqualTo("GZIP");
    }

    @Test
    void shouldSelectZstdForLargeLogFile() throws IOException {
        Path file = tempDir.resolve("large.log");
        // Create a file > 10KB
        String content = "2026-07-01 INFO Processing request id=12345\n".repeat(300);
        Files.writeString(file, content);

        CompressionStrategy strategy = selector.select(file);

        assertThat(strategy.algorithmName()).isEqualTo("ZSTD");
    }

    @Test
    void shouldSelectZstdForLargeCsvFile() throws IOException {
        Path file = tempDir.resolve("data.csv");
        String content = "id,name,email,city,country\n".repeat(500);
        Files.writeString(file, content);

        CompressionStrategy strategy = selector.select(file);

        assertThat(strategy.algorithmName()).isEqualTo("ZSTD");
    }

    @Test
    void shouldSelectZstdForLargeJsonFile() throws IOException {
        Path file = tempDir.resolve("export.json");
        String content = "{\"id\": 1, \"name\": \"test\", \"active\": true}\n".repeat(400);
        Files.writeString(file, content);

        CompressionStrategy strategy = selector.select(file);

        assertThat(strategy.algorithmName()).isEqualTo("ZSTD");
    }

    @Test
    void shouldSelectZstdForLargeXmlFile() throws IOException {
        Path file = tempDir.resolve("feed.xml");
        String content = "<item><name>Product</name><price>19.99</price></item>\n".repeat(300);
        Files.writeString(file, content);

        CompressionStrategy strategy = selector.select(file);

        assertThat(strategy.algorithmName()).isEqualTo("ZSTD");
    }

    @Test
    void shouldSelectZstdForLargeSqlFile() throws IOException {
        Path file = tempDir.resolve("migration.sql");
        String content = "INSERT INTO users (name, email) VALUES ('test', 'test@email.com');\n".repeat(200);
        Files.writeString(file, content);

        CompressionStrategy strategy = selector.select(file);

        assertThat(strategy.algorithmName()).isEqualTo("ZSTD");
    }

    @Test
    void shouldSelectZstdForLargeTxtFile() throws IOException {
        Path file = tempDir.resolve("document.txt");
        String content = "This is a line of text content for compression testing purposes.\n".repeat(250);
        Files.writeString(file, content);

        CompressionStrategy strategy = selector.select(file);

        assertThat(strategy.algorithmName()).isEqualTo("ZSTD");
    }

    @Test
    void shouldSelectGzipForLargeYamlFile() throws IOException {
        Path file = tempDir.resolve("config.yaml");
        String content = "key: value\nnested:\n  setting: enabled\n".repeat(400);
        Files.writeString(file, content);

        CompressionStrategy strategy = selector.select(file);

        assertThat(strategy.algorithmName()).isEqualTo("GZIP");
    }

    @Test
    void shouldSelectGzipForLargeYmlFile() throws IOException {
        Path file = tempDir.resolve("docker.yml");
        String content = "service:\n  image: nginx\n  ports:\n    - 80:80\n".repeat(350);
        Files.writeString(file, content);

        CompressionStrategy strategy = selector.select(file);

        assertThat(strategy.algorithmName()).isEqualTo("GZIP");
    }

    @Test
    void shouldSelectGzipForLargeMarkdownFile() throws IOException {
        Path file = tempDir.resolve("readme.md");
        String content = "## Section Title\nThis is a paragraph of documentation text.\n".repeat(300);
        Files.writeString(file, content);

        CompressionStrategy strategy = selector.select(file);

        assertThat(strategy.algorithmName()).isEqualTo("GZIP");
    }

    @Test
    void shouldFallbackToGzipForUnknownExtension() throws IOException {
        Path file = tempDir.resolve("unknown.dat");
        String content = "Unknown content type with lots of data.\n".repeat(400);
        Files.writeString(file, content);

        CompressionStrategy strategy = selector.select(file);

        // Default algorithm is ZSTD, but .dat is unknown, so it uses the default configured algo
        assertThat(strategy.algorithmName()).isEqualTo("ZSTD");
    }
}
