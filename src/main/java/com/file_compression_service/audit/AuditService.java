package com.file_compression_service.audit;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service
public class AuditService {

    private final AuditRepository repository;
    private final MeterRegistry meterRegistry;

    public AuditService(AuditRepository repository, MeterRegistry meterRegistry) {
        this.repository = repository;
        this.meterRegistry = meterRegistry;
    }

    @Transactional
    public void log(String filename, long originalSize, long compressedSize, String algorithm, long durationMs, boolean success) {
        double ratio = originalSize > 0 ? ((double) compressedSize / originalSize) * 100 : 100.0;
        CompressionRecord record = CompressionRecord.builder()
                .filename(filename)
                .originalSize(originalSize)
                .compressedSize(compressedSize)
                .compressionRatio(ratio)
                .algorithm(algorithm)
                .durationMs(durationMs)
                .success(success)
                .timestamp(LocalDateTime.now())
                .build();
        repository.save(record);

        // Update live metrics
        String statusStr = success ? "success" : "failed";
        Counter.builder("file_compression_processed_total")
                .description("Total number of files processed")
                .tag("algorithm", algorithm)
                .tag("status", statusStr)
                .register(meterRegistry)
                .increment();

        if (success) {
            Timer.builder("file_compression_duration_seconds")
                    .description("Duration of successful compression tasks")
                    .tag("algorithm", algorithm)
                    .publishPercentiles(0.5, 0.9, 0.99)
                    .register(meterRegistry)
                    .record(durationMs, TimeUnit.MILLISECONDS);

            long bytesSaved = originalSize - compressedSize;
            if (bytesSaved > 0) {
                Counter.builder("file_compression_bytes_saved_total")
                        .description("Total number of bytes saved by compression")
                        .tag("algorithm", algorithm)
                        .register(meterRegistry)
                        .increment(bytesSaved);
            }
        }
    }

    public List<Map<String, Object>> getStats() {
        return repository.getStatsPerAlgorithm();
    }
}
