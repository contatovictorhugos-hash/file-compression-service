package com.file_compression_service.queue;

import com.file_compression_service.worker.strategy.CompressionStrategy;
import java.nio.file.Path;

public record CompressionTask(Path filePath, CompressionStrategy strategy) {
}
