package com.file_compression_service.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "file-compressor")
public record WatcherProperties(
        FolderConfig inbox,
        FolderConfig outbox,
        FolderConfig processed,
        FolderConfig dlq,
        QueueConfig queue,
        AlgorithmConfig algorithm) {
    public record FolderConfig(
            String path,
            long pollIntervalMs,
            long maxFileSizeMb) {
    }

    public record QueueConfig(
            int capacity) {
    }

    public record AlgorithmConfig(
            String defaultValue,
            int sizeThresholdKb) {
    }
}
