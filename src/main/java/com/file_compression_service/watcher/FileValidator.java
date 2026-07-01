package com.file_compression_service.watcher;

import com.file_compression_service.config.WatcherProperties;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

@Component
public class FileValidator {

    private final WatcherProperties properties;
    private final Set<String> allowedExtensions;

    public enum ValidationStatus {
        VALID,
        NOT_A_FILE,
        NO_EXTENSION,
        INVALID_EXTENSION,
        TOO_LARGE,
        ALREADY_COMPRESSED,
        UNSUPPORTED_BINARY,
        EMPTY_FILE,
        IO_ERROR
    }

    public record ValidationResult(boolean isValid, ValidationStatus status) {}

    public FileValidator(WatcherProperties properties) {
        this.properties = properties;
        String rawExtensions = properties.validator().supportedExtensions();
        if (rawExtensions != null && !rawExtensions.isBlank()) {
            this.allowedExtensions = java.util.Arrays.stream(rawExtensions.split(","))
                    .map(String::trim)
                    .map(ext -> ext.startsWith(".") ? ext.substring(1) : ext)
                    .map(String::toLowerCase)
                    .collect(java.util.stream.Collectors.toSet());
        } else {
            this.allowedExtensions = Set.of("txt", "csv", "log", "json", "xml", "yaml", "yml", "sql", "md");
        }
    }

    public boolean isValid(Path path) {
        return validate(path).isValid();
    }

    public ValidationResult validate(Path path) {
        if (!Files.isRegularFile(path)) {
            return new ValidationResult(false, ValidationStatus.NOT_A_FILE);
        }

        String fileName = path.getFileName().toString();
        int lastDot = fileName.lastIndexOf('.');
        if (lastDot == -1) {
            return new ValidationResult(false, ValidationStatus.NO_EXTENSION);
        }

        String ext = fileName.substring(lastDot + 1).toLowerCase();
        if (!allowedExtensions.contains(ext)) {
            return new ValidationResult(false, ValidationStatus.INVALID_EXTENSION);
        }

        try {
            long size = Files.size(path);
            if (size == 0) {
                return new ValidationResult(false, ValidationStatus.EMPTY_FILE); // Distinct EMPTY_FILE status
            }

            long maxBytes = properties.validator().maxFileSizeMb() * 1024 * 1024;
            if (size > maxBytes) {
                return new ValidationResult(false, ValidationStatus.TOO_LARGE);
            }
        } catch (IOException e) {
            return new ValidationResult(false, ValidationStatus.IO_ERROR);
        }

        // Magic Bytes Check
        if (properties.validator().magicBytesCheck()) {
            try (java.io.InputStream is = Files.newInputStream(path)) {
                byte[] header = new byte[5];
                int bytesRead = is.read(header);
                if (bytesRead > 0) {
                    // Check Zip (50 4B 03 04)
                    if (bytesRead >= 4 && header[0] == 0x50 && header[1] == 0x4B && header[2] == 0x03 && header[3] == 0x04) {
                        return new ValidationResult(false, ValidationStatus.ALREADY_COMPRESSED);
                    }
                    // Check Gzip (1F 8B)
                    if (bytesRead >= 2 && header[0] == (byte) 0x1F && header[1] == (byte) 0x8B) {
                        return new ValidationResult(false, ValidationStatus.ALREADY_COMPRESSED);
                    }
                    // Check Zstd (28 B5 2F FD)
                    if (bytesRead >= 4 && header[0] == 0x28 && header[1] == (byte) 0xB5 && header[2] == 0x2F && header[3] == (byte) 0xFD) {
                        return new ValidationResult(false, ValidationStatus.ALREADY_COMPRESSED);
                    }
                    // Check XZ (FD 37 7A 58 5A)
                    if (bytesRead >= 5 && header[0] == (byte) 0xFD && header[1] == 0x37 && header[2] == 0x7A && header[3] == 0x58 && header[4] == 0x5A) {
                        return new ValidationResult(false, ValidationStatus.ALREADY_COMPRESSED);
                    }
                    // Check Bzip2 (42 5A 68)
                    if (bytesRead >= 3 && header[0] == 0x42 && header[1] == 0x5A && header[2] == 0x68) {
                        return new ValidationResult(false, ValidationStatus.ALREADY_COMPRESSED);
                    }
                    // Check PDF (25 50 44 46)
                    if (bytesRead >= 4 && header[0] == 0x25 && header[1] == 0x50 && header[2] == 0x44 && header[3] == 0x46) {
                        return new ValidationResult(false, ValidationStatus.UNSUPPORTED_BINARY);
                    }
                }
            } catch (IOException e) {
                return new ValidationResult(false, ValidationStatus.IO_ERROR);
            }
        }

        return new ValidationResult(true, ValidationStatus.VALID);
    }
}
