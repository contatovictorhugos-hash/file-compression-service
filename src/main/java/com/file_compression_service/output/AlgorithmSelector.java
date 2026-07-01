package com.file_compression_service.output;

import com.file_compression_service.config.WatcherProperties;
import com.file_compression_service.worker.strategy.CompressionStrategy;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class AlgorithmSelector {

    private final WatcherProperties properties;
    private final Map<String, CompressionStrategy> strategies;

    public AlgorithmSelector(WatcherProperties properties, List<CompressionStrategy> strategyList) {
        this.properties = properties;
        this.strategies = strategyList.stream()
                .collect(Collectors.toMap(CompressionStrategy::algorithmName, Function.identity()));
    }

    public CompressionStrategy select(Path file) {
        try {
            long sizeBytes = Files.size(file);
            if (sizeBytes < 150) {
                return strategies.getOrDefault("PASSTHROUGH", strategies.get("GZIP"));
            }

            long sizeKb = sizeBytes / 1024;
            int threshold = properties.algorithm().sizeThresholdKb();

            if (sizeKb < threshold) {
                return strategies.get("GZIP");
            }

            String fileName = file.getFileName().toString();
            int lastDot = fileName.lastIndexOf('.');
            String ext = lastDot != -1 ? fileName.substring(lastDot).toLowerCase() : "";

            String defaultAlgo = properties.algorithm().defaultValue();
            return switch (ext) {
                case ".log", ".csv", ".json", ".xml", ".sql", ".txt" ->
                    strategies.getOrDefault("ZSTD", strategies.get("GZIP"));
                case ".yaml", ".yml", ".md" -> strategies.get("GZIP");
                default -> strategies.getOrDefault(defaultAlgo, strategies.get("GZIP"));
            };
        } catch (IOException e) {
            return strategies.get("GZIP");
        }
    }
}
