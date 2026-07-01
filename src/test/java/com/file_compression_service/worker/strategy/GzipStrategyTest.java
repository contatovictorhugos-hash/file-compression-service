package com.file_compression_service.worker.strategy;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.zip.GZIPInputStream;

import static org.assertj.core.api.Assertions.assertThat;

class GzipStrategyTest {

    private final GzipStrategy strategy = new GzipStrategy();

    @Test
    void shouldCompressAndProduceSmallerOutput() throws IOException {
        // Repetitive content compresses well
        String content = "Hello World! ".repeat(1000);
        byte[] input = content.getBytes();

        byte[] compressed = strategy.compress(input);

        assertThat(compressed.length).isLessThan(input.length);
    }

    @Test
    void shouldProduceValidGzipOutput() throws IOException {
        String content = "Test data for GZIP compression validation";
        byte[] input = content.getBytes();

        byte[] compressed = strategy.compress(input);

        // Verify magic bytes (1F 8B)
        assertThat(compressed[0]).isEqualTo((byte) 0x1F);
        assertThat(compressed[1]).isEqualTo((byte) 0x8B);

        // Verify decompression roundtrip
        try (GZIPInputStream gzis = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            byte[] decompressed = gzis.readAllBytes();
            assertThat(new String(decompressed)).isEqualTo(content);
        }
    }

    @Test
    void shouldReturnCorrectFileExtension() {
        assertThat(strategy.fileExtension()).isEqualTo(".gz");
    }

    @Test
    void shouldReturnCorrectAlgorithmName() {
        assertThat(strategy.algorithmName()).isEqualTo("GZIP");
    }

    @Test
    void shouldHandleSmallInput() throws IOException {
        byte[] input = "Hi".getBytes();

        byte[] compressed = strategy.compress(input);

        // Small input may not compress smaller, but should still produce valid output
        assertThat(compressed).isNotEmpty();
        assertThat(compressed[0]).isEqualTo((byte) 0x1F);
        assertThat(compressed[1]).isEqualTo((byte) 0x8B);
    }

    @Test
    void shouldHandleEmptyInput() throws IOException {
        byte[] input = new byte[0];

        byte[] compressed = strategy.compress(input);

        assertThat(compressed).isNotEmpty(); // GZIP header always present
    }
}
