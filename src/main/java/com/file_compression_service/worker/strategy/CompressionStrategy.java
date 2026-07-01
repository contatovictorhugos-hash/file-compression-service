package com.file_compression_service.worker.strategy;

import java.io.IOException;

public interface CompressionStrategy {
    byte[] compress(byte[] input) throws IOException;

    String fileExtension();

    String algorithmName();
}
