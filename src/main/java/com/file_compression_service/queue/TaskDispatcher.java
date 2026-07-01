package com.file_compression_service.queue;

import com.file_compression_service.config.WatcherProperties;
import com.file_compression_service.worker.CompressionWorker;
import com.file_compression_service.output.OutputWriter;
import com.file_compression_service.audit.AuditService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.concurrent.*;

@Slf4j
@Component
public class TaskDispatcher {

    private final WatcherProperties properties;
    private final Executor compressionExecutor;
    private final OutputWriter outputWriter;
    private final AuditService auditService;

    private BlockingQueue<CompressionTask> queue;
    private ExecutorService dispatcherThread;
    private volatile boolean running = true;

    public TaskDispatcher(WatcherProperties properties,
            @Qualifier("compressionExecutor") Executor compressionExecutor,
            OutputWriter outputWriter,
            @Lazy AuditService auditService) {
        this.properties = properties;
        this.compressionExecutor = compressionExecutor;
        this.outputWriter = outputWriter;
        this.auditService = auditService;
    }

    @PostConstruct
    public void init() {
        this.queue = new LinkedBlockingQueue<>(properties.queue().capacity());
        this.dispatcherThread = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "task-dispatcher");
            t.setDaemon(true);
            return t;
        });
        this.dispatcherThread.submit(this::dispatchLoop);
        log.info("TaskDispatcher initialized with queue size: {}", properties.queue().capacity());
    }

    public boolean enqueue(CompressionTask task) {
        return queue.offer(task);
    }

    private void dispatchLoop() {
        while (running) {
            try {
                CompressionTask task = queue.poll(1, TimeUnit.SECONDS);
                if (task == null)
                    continue;

                // Fire and forget run executing on Java 21 Virtual Threads
                compressionExecutor.execute(new CompressionWorker(task, outputWriter, auditService));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("Error in dispatch loop", e);
            }
        }
    }

    @PreDestroy
    public void stop() {
        this.running = false;
        if (dispatcherThread != null) {
            dispatcherThread.shutdownNow();
        }
    }
}
