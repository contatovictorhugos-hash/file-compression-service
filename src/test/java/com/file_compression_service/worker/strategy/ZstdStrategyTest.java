package com.file_compression_service.worker.strategy;

import com.github.luben.zstd.Zstd;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

class ZstdStrategyTest {

    private final ZstdStrategy strategy = new ZstdStrategy();

    @Test
    void shouldCompressAndProduceSmallerOutput() throws IOException {
        // Repetitive content compresses well
        String content = "Hello World! ".repeat(1000);
        byte[] input = content.getBytes();

        byte[] compressed = strategy.compress(input);

        assertThat(compressed.length).isLessThan(input.length);
    }

    @Test
    void shouldProduceValidZstdOutput() throws IOException {
        String content = "Test data for ZSTD compression validation";
        byte[] input = content.getBytes();

        byte[] compressed = strategy.compress(input);

        // Verify ZSTD magic bytes (28 B5 2F FD)
        assertThat(compressed[0]).isEqualTo((byte) 0x28);
        assertThat(compressed[1]).isEqualTo((byte) 0xB5);
        assertThat(compressed[2]).isEqualTo((byte) 0x2F);
        assertThat(compressed[3]).isEqualTo((byte) 0xFD);

        // Verify decompression roundtrip
        byte[] decompressed = Zstd.decompress(compressed, input.length);
        assertThat(new String(decompressed)).isEqualTo(content);
    }

    @Test
    void shouldReturnCorrectFileExtension() {
        assertThat(strategy.fileExtension()).isEqualTo(".zst");
    }

    @Test
    void shouldReturnCorrectAlgorithmName() {
        assertThat(strategy.algorithmName()).isEqualTo("ZSTD");
    }

    @Test
    void shouldHandleSmallInput() throws IOException {
        byte[] input = "Hi".getBytes();

        byte[] compressed = strategy.compress(input);

        assertThat(compressed).isNotEmpty();
        // ZSTD magic bytes
        assertThat(compressed[0]).isEqualTo((byte) 0x28);
        assertThat(compressed[1]).isEqualTo((byte) 0xB5);
    }

    @Test
    void shouldAchieveBetterRatioThanGzipOnRepetitiveContent() throws IOException {
        // ZSTD should generally beat GZIP on highly repetitive content
        String content = "timestamp=2026-07-01T10:00:00,level=INFO,message=Request processed successfully\n".repeat(500);
        byte[] input = content.getBytes();

        GzipStrategy gzip = new GzipStrategy();
        byte[] gzipCompressed = gzip.compress(input);
        byte[] zstdCompressed = strategy.compress(input);

        // ZSTD should produce equal or smaller output for log-like content
        assertThat(zstdCompressed.length).isLessThanOrEqualTo(gzipCompressed.length);
    }
}
