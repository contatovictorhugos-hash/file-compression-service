package com.file_compression_service.worker;

import com.file_compression_service.queue.CompressionTask;
import com.file_compression_service.output.OutputWriter;
import com.file_compression_service.audit.AuditService;
import lombok.extern.slf4j.Slf4j;
import java.nio.file.Files;
import java.nio.file.Path;

@Slf4j
public class CompressionWorker implements Runnable {

    private final CompressionTask task;
    private final OutputWriter outputWriter;
    private final AuditService auditService;

    public CompressionWorker(CompressionTask task, OutputWriter outputWriter, AuditService auditService) {
        this.task = task;
        this.outputWriter = outputWriter;
        this.auditService = auditService;
    }

    @Override
    public void run() {
        Path file = task.filePath();
        String fileName = file.getFileName().toString();
        log.info("Processing {} on Virtual Thread: {}", fileName, Thread.currentThread());

        long startTime = System.currentTimeMillis();
        try {
            byte[] input = Files.readAllBytes(file);
            long originalSize = input.length;

            byte[] compressed = task.strategy().compress(input);
            long compressedSize = compressed.length;

            outputWriter.writeCompressed(compressed, fileName, task.strategy().fileExtension());
            outputWriter.archiveProcessed(file);

            long duration = System.currentTimeMillis() - startTime;
            auditService.log(fileName, originalSize, compressedSize, task.strategy().algorithmName(), duration, true);

            log.info("Compressed {} -> {} ratio: {}% in {}ms",
                    fileName, task.strategy().algorithmName(),
                    String.format("%.2f", ((double) compressedSize / originalSize) * 100), duration);

        } catch (Exception e) {
            log.error("Execution failed for: {}", fileName, e);
            outputWriter.sendToDlq(file, e);
            try {
                long originalSize = Files.exists(file) ? Files.size(file) : 0;
                auditService.log(fileName, originalSize, 0, task.strategy().algorithmName(),
                        System.currentTimeMillis() - startTime, false);
            } catch (Exception ex) {
                // Ignore fallback auditing metrics errors
            }
        }
    }
}
