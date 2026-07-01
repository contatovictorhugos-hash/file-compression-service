package com.file_compression_service.worker.strategy;

import com.github.luben.zstd.Zstd;
import org.springframework.stereotype.Component;
import java.io.IOException;

@Component
public class ZstdStrategy implements CompressionStrategy {
    @Override
    public byte[] compress(byte[] input) throws IOException {
        try {
            return Zstd.compress(input);
        } catch (Exception e) {
            throw new IOException("ZSTD compression failed", e);
        }
    }

    @Override
    public String fileExtension() {
        return ".zst";
    }

    @Override
    public String algorithmName() {
        return "ZSTD";
    }
}
