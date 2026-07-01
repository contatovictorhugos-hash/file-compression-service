package com.file_compression_service.worker.strategy;

import org.springframework.stereotype.Component;
import java.io.IOException;

@Component
public class PassthroughStrategy implements CompressionStrategy {
    @Override
    public byte[] compress(byte[] input) throws IOException {
        return input; // Return data unchanged (no-op)
    }

    @Override
    public String fileExtension() {
        return ""; // No suffix added, original extension stays
    }

    @Override
    public String algorithmName() {
        return "PASSTHROUGH";
    }
}
