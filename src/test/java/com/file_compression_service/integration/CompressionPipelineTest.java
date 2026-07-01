package com.file_compression_service.integration;

import com.file_compression_service.audit.AuditRepository;
import com.file_compression_service.audit.AuditService;
import com.file_compression_service.audit.CompressionRecord;
import com.file_compression_service.config.WatcherProperties;
import com.file_compression_service.output.AlgorithmSelector;
import com.file_compression_service.output.OutputWriter;
import com.file_compression_service.queue.CompressionTask;
import com.file_compression_service.watcher.FileValidator;
import com.file_compression_service.worker.CompressionWorker;
import com.file_compression_service.worker.strategy.CompressionStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end integration test that exercises the full compression pipeline:
 * validate → select algorithm → compress → write output → archive → audit.
 *
 * Uses a real Spring context with H2 in-memory database.
 */
@SpringBootTest
@ActiveProfiles("test")
class CompressionPipelineTest {

    @Autowired
    private FileValidator fileValidator;

    @Autowired
    private AlgorithmSelector algorithmSelector;

    @Autowired
    private OutputWriter outputWriter;

    @Autowired
    private AuditService auditService;

    @Autowired
    private AuditRepository auditRepository;

    @Autowired
    private WatcherProperties properties;

    @BeforeEach
    void setUp() throws IOException {
        // Ensure all directories exist
        Files.createDirectories(Path.of(properties.inbox().path()));
        Files.createDirectories(Path.of(properties.outbox().path()));
        Files.createDirectories(Path.of(properties.processed().path()));
        Files.createDirectories(Path.of(properties.dlq().path()));

        // Clean the DB between tests
        auditRepository.deleteAll();
    }

    @Test
    void shouldCompressTextFileEndToEnd() throws Exception {
        // 1. Create a text file in inbox
        Path inboxFile = Path.of(properties.inbox().path()).resolve("pipeline-test.txt");
        String content = "This is a test file for the compression pipeline integration test.\n".repeat(200);
        Files.writeString(inboxFile, content);

        // 2. Validate
        var result = fileValidator.validate(inboxFile);
        assertThat(result.isValid()).isTrue();

        // 3. Select algorithm
        CompressionStrategy strategy = algorithmSelector.select(inboxFile);
        assertThat(strategy).isNotNull();

        // 4. Create task and run worker synchronously
        CompressionTask task = new CompressionTask(inboxFile, strategy);
        CompressionWorker worker = new CompressionWorker(task, outputWriter, auditService);
        worker.run();

        // 5. Verify compressed file exists in outbox
        Path outboxFile = Path.of(properties.outbox().path()).resolve("pipeline-test.txt" + strategy.fileExtension());
        assertThat(outboxFile).exists();
        long compressedSize = Files.size(outboxFile);
        assertThat(compressedSize).isLessThan(content.length());

        // 6. Verify original was moved to processed
        Path processedFile = Path.of(properties.processed().path()).resolve("pipeline-test.txt");
        assertThat(processedFile).exists();
        assertThat(inboxFile).doesNotExist();

        // 7. Verify audit record was persisted
        List<CompressionRecord> records = auditRepository.findAll();
        assertThat(records).hasSize(1);

        CompressionRecord record = records.get(0);
        assertThat(record.getFilename()).isEqualTo("pipeline-test.txt");
        assertThat(record.getOriginalSize()).isEqualTo(content.length());
        assertThat(record.getCompressedSize()).isEqualTo(compressedSize);
        assertThat(record.getAlgorithm()).isEqualTo(strategy.algorithmName());
        assertThat(record.isSuccess()).isTrue();
        assertThat(record.getDurationMs()).isGreaterThanOrEqualTo(0);
        assertThat(record.getCompressionRatio()).isLessThan(100.0);
    }

    @Test
    void shouldRejectBinaryFileAndQuarantineToDlq() throws Exception {
        // Create a file with ZIP magic bytes disguised as .txt
        Path inboxFile = Path.of(properties.inbox().path()).resolve("sneaky.txt");
        byte[] zipContent = new byte[]{0x50, 0x4B, 0x03, 0x04, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00};
        Files.write(inboxFile, zipContent);

        // Validate should reject
        var result = fileValidator.validate(inboxFile);
        assertThat(result.isValid()).isFalse();
        assertThat(result.status()).isEqualTo(FileValidator.ValidationStatus.ALREADY_COMPRESSED);

        // Manually send to DLQ (in the real flow, FileSystemWatcher does this)
        outputWriter.sendToDlq(inboxFile, new IllegalArgumentException(result.status().name()));

        // Verify file was moved to DLQ
        Path dlqFile = Path.of(properties.dlq().path()).resolve("sneaky.txt");
        assertThat(dlqFile).exists();
        assertThat(inboxFile).doesNotExist();

        // Verify error sidecar
        Path errorSidecar = Path.of(properties.dlq().path()).resolve("sneaky.txt.error.json");
        assertThat(errorSidecar).exists();
        String errorContent = Files.readString(errorSidecar);
        assertThat(errorContent).contains("ALREADY_COMPRESSED");
    }

    @Test
    void shouldCompressCsvWithZstd() throws Exception {
        // CSV with repetitive content > 10KB should route to ZSTD
        Path inboxFile = Path.of(properties.inbox().path()).resolve("report.csv");
        String content = "id,name,email,department,salary\n"
                + "1,John Doe,john@example.com,Engineering,95000\n".repeat(300);
        Files.writeString(inboxFile, content);

        // Validate and select
        assertThat(fileValidator.validate(inboxFile).isValid()).isTrue();
        CompressionStrategy strategy = algorithmSelector.select(inboxFile);
        assertThat(strategy.algorithmName()).isEqualTo("ZSTD");

        // Compress
        CompressionTask task = new CompressionTask(inboxFile, strategy);
        new CompressionWorker(task, outputWriter, auditService).run();

        // Verify
        Path outboxFile = Path.of(properties.outbox().path()).resolve("report.csv.zst");
        assertThat(outboxFile).exists();
        assertThat(Files.size(outboxFile)).isLessThan(content.length());
    }

    @Test
    void shouldCompressYamlWithGzip() throws Exception {
        // YAML > 10KB should still route to GZIP
        Path inboxFile = Path.of(properties.inbox().path()).resolve("config.yaml");
        String content = "spring:\n  application:\n    name: test-service\n  profiles:\n    active: dev\n".repeat(250);
        Files.writeString(inboxFile, content);

        assertThat(fileValidator.validate(inboxFile).isValid()).isTrue();
        CompressionStrategy strategy = algorithmSelector.select(inboxFile);
        assertThat(strategy.algorithmName()).isEqualTo("GZIP");

        CompressionTask task = new CompressionTask(inboxFile, strategy);
        new CompressionWorker(task, outputWriter, auditService).run();

        Path outboxFile = Path.of(properties.outbox().path()).resolve("config.yaml.gz");
        assertThat(outboxFile).exists();
    }

    @Test
    void shouldTrackMultipleCompressionRecordsInDatabase() throws Exception {
        // Process multiple files and verify all audit records
        String[] filenames = {"batch1.txt", "batch2.log", "batch3.json"};
        for (String filename : filenames) {
            Path file = Path.of(properties.inbox().path()).resolve(filename);
            Files.writeString(file, ("Content for " + filename + "\n").repeat(200));

            CompressionStrategy strategy = algorithmSelector.select(file);
            new CompressionWorker(new CompressionTask(file, strategy), outputWriter, auditService).run();
        }

        List<CompressionRecord> records = auditRepository.findAll();
        assertThat(records).hasSize(3);
        assertThat(records).allMatch(CompressionRecord::isSuccess);
    }
}
