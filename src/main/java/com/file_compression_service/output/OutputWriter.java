package com.file_compression_service.output;

import com.file_compression_service.config.WatcherProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

@Slf4j
@Component
public class OutputWriter {

    private final WatcherProperties properties;

    public OutputWriter(WatcherProperties properties) {
        this.properties = properties;
    }

    public void writeCompressed(byte[] data, String originalFileName, String extension) throws IOException {
        Path outboxPath = Paths.get(properties.outbox().path());
        if (!Files.exists(outboxPath)) {
            Files.createDirectories(outboxPath);
        }
        Path outputFile = outboxPath.resolve(originalFileName + extension);
        Files.write(outputFile, data);
    }

    public void archiveProcessed(Path originalFile) throws IOException {
        Path processedPath = Paths.get(properties.processed().path());
        if (!Files.exists(processedPath)) {
            Files.createDirectories(processedPath);
        }
        Path target = processedPath.resolve(originalFile.getFileName());
        Files.move(originalFile, target, StandardCopyOption.REPLACE_EXISTING);
    }

    public void sendToDlq(Path file, Exception e) {
        try {
            Path dlqPath = Paths.get(properties.dlq().path());
            if (!Files.exists(dlqPath)) {
                Files.createDirectories(dlqPath);
            }
            Path targetFile = dlqPath.resolve(file.getFileName());
            Files.move(file, targetFile, StandardCopyOption.REPLACE_EXISTING);

            Path errorLog = dlqPath.resolve(file.getFileName().toString() + ".error.json");
            String jsonErr = String.format("{\n  \"file\": \"%s\",\n  \"error\": \"%s\",\n  \"timestamp\": \"%s\"\n}",
                    file.getFileName(), e.getMessage() != null ? e.getMessage().replace("\"", "\\\"") : "Unknown Error",
                    java.time.Instant.now());
            Files.write(errorLog, jsonErr.getBytes());
        } catch (IOException ex) {
            log.error("Failed to send file {} to DLQ: {}", file.getFileName(), ex.getMessage(), ex);
        }
    }
}

