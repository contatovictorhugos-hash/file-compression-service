package com.file_compression_service.watcher;

import com.file_compression_service.config.WatcherProperties;
import com.file_compression_service.queue.CompressionTask;
import com.file_compression_service.queue.TaskDispatcher;
import com.file_compression_service.output.AlgorithmSelector;
import com.file_compression_service.output.OutputWriter;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.concurrent.*;

@Slf4j
@Component
public class FileSystemWatcher {

    private final WatcherProperties properties;
    private final FileValidator validator;
    private final AlgorithmSelector algorithmSelector;
    private final TaskDispatcher dispatcher;
    private final OutputWriter outputWriter;

    // Duplicate Prevention Cache (Filename -> Insertion Timestamp)
    private final ConcurrentHashMap<String, Instant> processedFilesCache = new ConcurrentHashMap<>();

    private WatchService watchService;
    private ExecutorService watcherThread;
    private ScheduledExecutorService scheduler;
    private volatile boolean running = true;

    public FileSystemWatcher(WatcherProperties properties, FileValidator validator,
            AlgorithmSelector algorithmSelector, TaskDispatcher dispatcher,
            @Lazy OutputWriter outputWriter) {
        this.properties = properties;
        this.validator = validator;
        this.algorithmSelector = algorithmSelector;
        this.dispatcher = dispatcher;
        this.outputWriter = outputWriter;
    }

    @PostConstruct
    public void start() throws IOException {
        Path inboxPath = Paths.get(properties.inbox().path());
        if (!Files.exists(inboxPath)) {
            Files.createDirectories(inboxPath);
        }

        this.watchService = FileSystems.getDefault().newWatchService();
        inboxPath.register(watchService, StandardWatchEventKinds.ENTRY_CREATE);

        this.watcherThread = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "fs-watcher");
            t.setDaemon(true);
            return t;
        });
        this.watcherThread.submit(this::watchLoop);

        this.scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "fs-watcher-tasks");
            t.setDaemon(true);
            return t;
        });

        long pollInterval = properties.inbox().pollIntervalMs();
        this.scheduler.scheduleWithFixedDelay(this::fallbackScan, pollInterval, pollInterval, TimeUnit.MILLISECONDS);
        this.scheduler.scheduleAtFixedRate(this::evictCache, 5, 5, TimeUnit.MINUTES);

        log.info("FileSystemWatcher started on: {}. Fallback scan: {}ms", inboxPath.toAbsolutePath(), pollInterval);
    }

    private void watchLoop() {
        Path inboxPath = Paths.get(properties.inbox().path());
        while (running) {
            try {
                WatchKey key = watchService.poll(1, TimeUnit.SECONDS);
                if (key == null)
                    continue;

                for (WatchEvent<?> event : key.pollEvents()) {
                    if (event.kind() == StandardWatchEventKinds.OVERFLOW)
                        continue;

                    Path filename = (Path) event.context();
                    Path fullPath = inboxPath.resolve(filename);

                    // Wait a brief moment to ensure file is fully written
                    Thread.sleep(150);
                    processFile(fullPath);
                }
                key.reset();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("Exception in WatchService loop", e);
            }
        }
    }

    private void processFile(Path filePath) {
        String filename = filePath.getFileName().toString();

        if (processedFilesCache.putIfAbsent(filename, Instant.now()) != null) {
            return;
        }

        var result = validator.validate(filePath);
        if (result.isValid()) {
            log.info("Ingesting valid file: {}", filename);
            var strategy = algorithmSelector.select(filePath);
            var task = new CompressionTask(filePath, strategy);
            if (!dispatcher.enqueue(task)) {
                log.warn("Queue full! Dropping file: {}", filename);
                processedFilesCache.remove(filename);
            }
        } else {
            // If failed magic bytes check or empty, quarantine directly to DLQ
            if (result.status() == FileValidator.ValidationStatus.ALREADY_COMPRESSED ||
                    result.status() == FileValidator.ValidationStatus.UNSUPPORTED_BINARY ||
                    result.status() == FileValidator.ValidationStatus.EMPTY_FILE) {
                log.error("Rejection triggered. File {} is invalid ({}). Quarantining to DLQ.",
                        filename, result.status());
                outputWriter.sendToDlq(filePath, new IllegalArgumentException(result.status().name()));
            } else {
                processedFilesCache.remove(filename);
            }
        }
    }

    private void fallbackScan() {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(Paths.get(properties.inbox().path()))) {
            for (Path entry : stream) {
                if (Files.isRegularFile(entry)) {
                    processFile(entry);
                }
            }
        } catch (IOException e) {
            log.error("Failed to execute fallback scan", e);
        }
    }

    private void evictCache() {
        Instant threshold = Instant.now().minusSeconds(600);
        processedFilesCache.entrySet().removeIf(entry -> entry.getValue().isBefore(threshold));
    }

    @PreDestroy
    public void stop() {
        this.running = false;
        if (watcherThread != null)
            watcherThread.shutdownNow();
        if (scheduler != null)
            scheduler.shutdownNow();
        try {
            if (watchService != null)
                watchService.close();
        } catch (IOException e) {
            log.error("Failed to close WatchService", e);
        }
    }
}
