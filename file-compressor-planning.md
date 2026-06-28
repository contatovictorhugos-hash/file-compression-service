# File Compression Service — Planning Analysis

**Status:** Pre-development · Planning phase
**Author:** Victor Hugo Moreira
**Date:** June 2026
**Stack:** Java 21 · Spring Boot 3.3+ · Virtual Threads (Project Loom)

---

## Table of Contents

- [Project Overview](#project-overview)
- [Architecture](#architecture)
- [Technical Decisions](#technical-decisions)
- [Recommended Stack](#recommended-stack)
- [Package Structure](#package-structure)
- [Risks and Mitigations](#risks-and-mitigations)
- [Portfolio Value Assessment](#portfolio-value-assessment)
- [Suggested Enhancements](#suggested-enhancements)
- [Implementation Roadmap](#implementation-roadmap)

---

## Project Overview

The File Compression Service is a filesystem-event-driven daemon that monitors a designated inbox folder, detects incoming text-based files, and dispatches compression jobs to workers backed by Java 21 Virtual Threads (Project Loom). The compressed output is written to an outbox folder, leaving the original file quarantined in a processed archive.

**Original scope defined by the developer:**

> "A system that monitors a folder. When text files arrive, we initialize workers using virtual threads and return the compressed file."

**Scope after analysis enhancements:**

The core is sound. The additions recommended below — multi-algorithm strategy pattern, audit log with persistence, REST stats endpoint, and Actuator observability — elevate the project from a concurrency exercise to a production-grade document processing service.

---

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│  WATCHER LAYER                                                   │
│  WatchService (NIO.2) — monitors /inbox, filters .txt .csv .log │
│  ┌──────────────────────┐      ┌──────────────────────┐         │
│  │  FileSystemWatcher   │ ───► │   FileValidator       │         │
│  └──────────────────────┘      └──────────────────────┘         │
└────────────────────────────────────┬────────────────────────────┘
                                     │ enqueue
┌────────────────────────────────────▼────────────────────────────┐
│  QUEUE LAYER                                                     │
│  LinkedBlockingQueue — decouples ingestion from compression      │
│  ┌──────────────────────┐      ┌──────────────────────┐         │
│  │  CompressionTask     │ ───► │   TaskDispatcher      │         │
│  └──────────────────────┘      └──────────────────────┘         │
└────────────────────────────────────┬────────────────────────────┘
                                     │ submit
┌────────────────────────────────────▼────────────────────────────┐
│  WORKER LAYER  (Virtual Threads — Executors.newVirtualThread…)  │
│  ┌────────────┐ ┌────────────┐ ┌────────────┐ ┌─────────────┐  │
│  │  Worker 1  │ │  Worker 2  │ │  Worker 3  │ │  Worker N…  │  │
│  │   GZIP     │ │  DEFLATE   │ │   ZSTD     │ │             │  │
│  └────────────┘ └────────────┘ └────────────┘ └─────────────┘  │
└────────────────────────────────────┬────────────────────────────┘
                                     │ write
┌────────────────────────────────────▼────────────────────────────┐
│  OUTPUT LAYER                                                    │
│  Writes to /outbox — moves originals to /processed              │
│  ┌──────────────────────┐      ┌──────────────────────┐         │
│  │    OutputWriter      │ ───► │  CompressionAuditLog  │         │
│  └──────────────────────┘      └──────────────────────┘         │
└────────────────────────────────────┬────────────────────────────┘
                                     │
┌────────────────────────────────────▼────────────────────────────┐
│  OBSERVABILITY LAYER                                             │
│  Spring Boot Actuator + Micrometer                               │
│  ┌────────────────┐  ┌─────────────────┐  ┌──────────────────┐  │
│  │ MetricsCollect │  │  HealthEndpoint │  │ AuditRepository  │  │
│  └────────────────┘  └─────────────────┘  └──────────────────┘  │
└─────────────────────────────────────────────────────────────────┘
```

**Data flow (nominal path):**

1. `FileSystemWatcher` receives `ENTRY_CREATE` event from the OS via `WatchService`.
2. `FileValidator` checks extension (`.txt`, `.csv`, `.log`) and minimum file size (> 0 bytes).
3. Valid file is wrapped in a `CompressionTask` record and offered to the `LinkedBlockingQueue`.
4. `TaskDispatcher` polls the queue and submits each task to `Executors.newVirtualThreadPerTaskExecutor()`.
5. `CompressionWorker` selects the algorithm via `AlgorithmSelector` (based on file extension and size), reads the file, compresses it, and hands off bytes to `OutputWriter`.
6. `OutputWriter` writes the `.gz` / `.zst` file to `/outbox` and moves the original to `/processed`.
7. `CompressionAuditLog` persists a `CompressionRecord` entity with: filename, size before, size after, ratio, algorithm, duration ms, timestamp.
8. Micrometer counters and histograms are updated on each successful/failed compression.

---

## Technical Decisions

### Virtual Threads over Platform Thread Pool — why it matters here

The compression workload is **I/O-bound**: the bottleneck is reading from and writing to disk, not CPU cycles spent on the compression algorithm. This is precisely the scenario Virtual Threads (Project Loom, GA since Java 21) were designed for.

| Approach | Platform thread pool (20 threads) | Virtual Thread executor |
|---|---|---|
| Threads created per 500 files | 20 (reused) | 500 (virtual, ~2 KB each) |
| Platform threads consumed | 20 | ~handful (carrier threads) |
| Blocking I/O behavior | Platform thread blocks waiting for disk | Virtual thread unmounts; carrier thread is freed |
| Memory overhead (500 files) | ~20 MB (platform threads) | ~1 MB (virtual threads) |
| Code complexity | `ExecutorService + queue management` | `Executors.newVirtualThreadPerTaskExecutor()` |

**Decision:** Use `Executors.newVirtualThreadPerTaskExecutor()`. Do not use `@Async` with a standard thread pool for the compression path — it defeats the purpose of Loom.

```java
// VirtualThreadConfig.java
@Configuration
public class VirtualThreadConfig {

    @Bean(name = "compressionExecutor")
    public Executor compressionExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
```

### WatchService over polling — why it matters here

A polling loop (`Thread.sleep(1000)` + `Files.list()`) works but has two problems: it burns CPU between events and introduces latency proportional to the poll interval. `WatchService` registers with the OS kernel (inotify on Linux, FSEvents on macOS, ReadDirectoryChangesW on Windows) and delivers events synchronously when a file is created — zero polling overhead, sub-millisecond latency.

```java
// FileSystemWatcher.java — the key pattern
WatchService watchService = FileSystems.getDefault().newWatchService();
inboxPath.register(watchService, StandardWatchEventKinds.ENTRY_CREATE);

while (true) {
    WatchKey key = watchService.take(); // blocks — no CPU burn
    for (WatchEvent<?> event : key.pollEvents()) {
        Path file = inboxPath.resolve((Path) event.context());
        if (validator.isValid(file)) {
            dispatcher.enqueue(new CompressionTask(file, algorithmSelector.select(file)));
        }
    }
    key.reset();
}
```

### LinkedBlockingQueue as the buffer — why it matters here

The watcher and the workers run at different speeds. A burst of 200 files arriving simultaneously should not create 200 virtual threads before the first one finishes. The `LinkedBlockingQueue` with a bounded capacity (e.g. 500) acts as a backpressure valve: if the queue is full, `offer()` returns false and the watcher logs a backpressure event instead of accepting files it cannot process. This is the correct pattern for any production ingestion pipeline.

### Strategy Pattern for compression algorithms — why it matters here

Different file types compress differently. GZIP is ubiquitous and fast. DEFLATE gives similar ratio with lower memory. ZSTD (Facebook, 2016) achieves superior compression ratios at comparable speed and is the modern default for text-heavy workloads. Encoding the choice as a `CompressionStrategy` interface keeps `CompressionWorker` closed to modification when a new algorithm is added.

```java
public interface CompressionStrategy {
    byte[] compress(byte[] input) throws IOException;
    String fileExtension();       // ".gz", ".zst", ".deflate"
    String algorithmName();       // for audit log
}
```

`AlgorithmSelector` maps file characteristics to strategies:

| File size | Extension | Selected algorithm | Rationale |
|---|---|---|---|
| < 10 KB | any | GZIP | Overhead of ZSTD dict not worth it on small files |
| ≥ 10 KB | `.csv` | ZSTD | Repetitive tabular data — ZSTD ratio dominates |
| ≥ 10 KB | `.txt` `.log` | ZSTD | Same rationale |
| any | override via config | configurable | `application.yml` allows forcing algorithm |

---

## Recommended Stack

| Layer | Technology | Version | Notes |
|---|---|---|---|
| Language | Java | 21 LTS | Virtual Threads GA — no preview flags needed |
| Framework | Spring Boot | 3.3.x | Loom-compatible async executor support |
| Watcher | `java.nio.file.WatchService` | JDK built-in | No extra dependency |
| Queue | `java.util.concurrent.LinkedBlockingQueue` | JDK built-in | Bounded, thread-safe |
| Virtual Threads | `Executors.newVirtualThreadPerTaskExecutor()` | JDK 21 built-in | The core differentiator |
| Compression — GZIP/DEFLATE | `java.util.zip` | JDK built-in | Zero extra dependency |
| Compression — ZSTD | `com.github.luben:zstd-jni` | 1.5.6+ | Native binding, best ratio |
| Persistence | Spring Data JPA + H2 (dev) | Spring Boot managed | Switch to PostgreSQL in prod via profile |
| Metrics | Spring Boot Actuator + Micrometer | Spring Boot managed | Exposes `/actuator/health`, custom counters |
| Build | Maven | 3.9.x | Consistent with existing portfolio |
| Container | Docker + docker-compose | latest | One command to run full stack |
| Testing | JUnit 5 + AssertJ + Testcontainers | Spring Boot managed | Integration tests with real filesystem |

---

## Package Structure

```
file-compressor/
├── src/main/java/com/victorhm/filecompressor/
│   ├── FileCompressorApplication.java
│   │
│   ├── watcher/
│   │   ├── FileSystemWatcher.java        # WatchService loop, @Component
│   │   └── FileValidator.java            # extension + size filter
│   │
│   ├── queue/
│   │   ├── CompressionTask.java          # record(Path file, Algorithm algo)
│   │   └── TaskDispatcher.java           # polls queue, submits to executor
│   │
│   ├── worker/
│   │   ├── CompressionWorker.java        # Runnable on virtual thread
│   │   └── strategy/
│   │       ├── CompressionStrategy.java  # interface
│   │       ├── GzipStrategy.java
│   │       ├── DeflateStrategy.java
│   │       └── ZstdStrategy.java
│   │
│   ├── output/
│   │   ├── OutputWriter.java             # writes /outbox, moves /processed
│   │   └── AlgorithmSelector.java        # maps file → strategy
│   │
│   ├── audit/
│   │   ├── CompressionRecord.java        # @Entity
│   │   ├── AuditRepository.java          # JpaRepository<CompressionRecord, Long>
│   │   └── AuditService.java             # persistence logic, decoupled from worker
│   │
│   ├── api/
│   │   └── CompressionStatsController.java  # GET /api/v1/reports/stats
│   │
│   └── config/
│       ├── VirtualThreadConfig.java      # @Bean Executor (Loom)
│       └── WatcherProperties.java        # @ConfigurationProperties inbox/outbox paths
│
├── src/main/resources/
│   ├── application.yml                   # inbox.path, outbox.path, algorithm defaults
│   └── application-prod.yml             # PostgreSQL datasource override
│
├── src/test/java/com/victorhm/filecompressor/
│   ├── watcher/FileValidatorTest.java
│   ├── worker/CompressionWorkerTest.java
│   ├── strategy/ZstdStrategyTest.java
│   └── integration/CompressionPipelineIT.java  # Testcontainers + real files
│
├── Dockerfile
├── docker-compose.yml                    # service + postgres profile
└── pom.xml
```

---

## Risks and Mitigations

| Risk | Probability | Impact | Mitigation |
|---|---|---|---|
| File arrives before write is complete (partial read) | Medium | High — corrupted compression | Use `StandardOpenOption.READ` after `ENTRY_MODIFY` stabilizes, or apply a `ENTRY_CREATE` + small delay heuristic with size stability check |
| Queue overflow under burst load | Low-Medium | Medium — files dropped silently | Bounded queue with `offer()` returning false; log backpressure event; consider dead-letter folder |
| ZSTD native library not found in Docker image | Medium | High — runtime `UnsatisfiedLinkError` | Pin `zstd-jni` version; test in Docker during development, not just locally |
| Same file processed twice (duplicate `ENTRY_CREATE`) | Low | Medium — duplicate output files | Maintain a `ConcurrentHashMap<String, Instant>` of recently seen filenames with TTL eviction |
| Large file causes virtual thread to hold heap for long period | Low | Low-Medium | Add configurable max file size in `application.yml`; reject oversized files to dead-letter folder |
| `WatchService` misses events under very high volume (OS kernel limit) | Low | Medium | Document the inotify limit (`/proc/sys/fs/inotify/max_queued_events`); add fallback periodic scan every 60s |

---

## Portfolio Value Assessment

### What this project adds that the current portfolio does not have

Reviewing the existing projects (Mail Notifier Service, Key Management Service, Backoffice):

| Capability | Mail Notifier | Key Mgmt | Backoffice | **File Compressor** |
|---|---|---|---|---|
| Request-response API | ✓ | ✓ | ✓ | ✓ (stats endpoint) |
| Filesystem event-driven | — | — | — | **✓** |
| Concurrent worker pool | — | — | — | **✓** |
| Java 21 Virtual Threads | — | — | — | **✓** |
| Strategy Pattern | — | — | — | **✓** |
| Audit log with persistence | — | ✓ (keys) | — | **✓** |
| Actuator + custom metrics | — | — | — | **✓** |
| Docker Compose multi-service | — | ✓ | — | **✓** |

**Net addition:** This is the first project in the portfolio that demonstrates asynchronous, event-driven backend processing — a pattern that appears in document management systems, ETL pipelines, and enterprise integration at companies like Pluxee, Itaú, and EY.

### Specific talking points for interviews

Three technical decisions that differentiate this project and are worth explaining in detail:

1. **Why Virtual Threads over a fixed thread pool** — the I/O-bound nature of compression (disk read + write dominates CPU) means carrier threads spend most of their time parked. A fixed pool of 20 threads would queue tasks; 500 virtual threads handle 500 simultaneous files with OS-level efficiency. This shows awareness of when Loom is the right tool vs when a `ForkJoinPool` is better (CPU-bound parallelism).

2. **Why `WatchService` over polling** — kernel-level event notification vs active polling. The distinction matters for CPU utilization at scale and for latency sensitivity. Mentioning inotify internals demonstrates OS-level awareness beyond typical Java developer scope.

3. **Why the `LinkedBlockingQueue` as explicit buffer** — decouples ingestion speed from processing speed and provides natural backpressure. This is the same pattern used in Kafka consumers, Spring Batch readers, and any production ingestion system. Naming this pattern explicitly in an interview signals familiarity with enterprise integration design.

---

## Suggested Enhancements

The following are optional additions ordered by portfolio impact:

### High impact, low effort

- **REST endpoint `GET /api/v1/reports/stats`** — returns aggregated compression statistics (total files processed, average ratio per algorithm, total bytes saved). Adds API surface without significant complexity.
- **`application.yml` configuration** — externalise inbox path, outbox path, queue capacity, max file size, and default algorithm. Demonstrates production-mindset configuration management.
- **JMH micro-benchmark** — compare throughput with and without virtual threads (platform thread pool baseline). Include results in the README. This single addition makes the README significantly more credible to senior engineers.

### Medium impact, medium effort

- **Dead-letter folder `/dlq`** — files that fail compression after 3 retries are moved here with a `.error.json` sidecar file containing the exception message and stack trace. Demonstrates resilience thinking.
- **Testcontainers integration test** — `CompressionPipelineIT` drops a real file into a temp inbox folder and asserts that the outbox file is smaller and the audit record was persisted. Demonstrates testing discipline for async pipelines.
- **Compression ratio dashboard** — a simple `index.html` served by Spring MVC that reads from `AuditRepository` and renders a table of recent compressions. Adds a visual surface to the project for GitHub screenshots.

### Lower priority

- **Spring Batch integration** — replace the manual queue with a Spring Batch `ItemReader` (folder scan) → `ItemProcessor` (compression) → `ItemWriter` (output + audit). This connects the project to your existing Spring ecosystem knowledge but adds significant complexity. Consider as a v2 scope.
- **Encryption integration** — call the Key Management Service REST API to encrypt the compressed file before writing to outbox. This creates an explicit link between two portfolio projects and demonstrates microservice integration.

---

## Implementation Roadmap

Estimated scope for a developer working part-time alongside a full-time role.

| Phase | Scope | Estimated effort |
|---|---|---|
| **Phase 1 — Core pipeline** | WatchService + Queue + Virtual Thread executor + GZIP only + OutputWriter | 2–3 evenings |
| **Phase 2 — Multi-algorithm** | Strategy pattern + DEFLATE + ZSTD + AlgorithmSelector | 1–2 evenings |
| **Phase 3 — Persistence** | CompressionRecord entity + AuditRepository + AuditService | 1 evening |
| **Phase 4 — Observability** | Actuator + Micrometer counters + `/api/v1/reports/stats` | 1 evening |
| **Phase 5 — Resilience** | Dead-letter folder + retry logic + backpressure logging | 1–2 evenings |
| **Phase 6 — Tests** | Unit tests per strategy + `CompressionPipelineIT` | 2 evenings |
| **Phase 7 — Container + README** | Dockerfile + docker-compose + README with benchmark results | 1 evening |

**Recommended start:** Phase 1 end-to-end first, even with hardcoded GZIP. A working pipeline with one algorithm is more valuable to commit than a skeleton with three algorithms and no running code.

---

## Key Configuration Reference

```yaml
# application.yml
file-compressor:
  inbox:
    path: /data/inbox
    poll-interval-ms: 60000        # fallback scan interval
    max-file-size-mb: 100
  outbox:
    path: /data/outbox
  processed:
    path: /data/processed
  dlq:
    path: /data/dlq
  queue:
    capacity: 500
  algorithm:
    default: ZSTD
    size-threshold-kb: 10          # files below this use GZIP

spring:
  datasource:
    url: jdbc:h2:mem:compressordb  # override in application-prod.yml
  jpa:
    hibernate:
      ddl-auto: update

management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus
```

---

*This document was generated during the planning phase. It should be updated after each implementation phase to reflect decisions made during development.*
