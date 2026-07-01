# Files Compression Service

> High-throughput, concurrent file compression service built on Java 21 Virtual Threads (Project Loom) featuring dynamic algorithm routing, DLQ auditing, and real-time database-backed reporting.

![Java](https://img.shields.io/badge/Java-21-ED8B00?style=flat-square&logo=openjdk&logoColor=white)
![Spring Boot](https://img.shields.io/badge/Spring_Boot-4.1.x-6DB33F?style=flat-square&logo=springboot&logoColor=white)
![Maven](https://img.shields.io/badge/Maven-C71A36?style=flat-square&logo=apachemaven&logoColor=white)
![Docker](https://img.shields.io/badge/Docker-2496ED?style=flat-square&logo=docker&logoColor=white)

---

## Table of Contents
- [Overview](#overview)
- [Architecture & Concurrency](#architecture--concurrency)
- [Tech Stack](#tech-stack)
- [Getting Started](#getting-started)
- [Project Structure](#project-structure)
- [API Reference](#api-reference)
- [Simulation & Load Testing Results](#simulation--load-testing-results)
- [Ecosystem](#ecosystem)
- [Contributing](#contributing)

---

## Overview

**Files Compression Service** is a daemon-style reactive pipeline designed to continuously ingest text-based configuration, data, and log files, compress them using optimal algorithms, archive the original inputs, and persist audit logs for downstream analysis.

### Core Objectives
1. **Dynamic Selection Strategy**: Automatically routing files to **ZSTD** (high density for CSV/LOG/JSON/TXT > 10KB), **GZIP** (smaller/slower files or YAML/MD config formats), or **PASSTHROUGH** (tiny files < 150 bytes to prevent GZIP wrapper size inflation).
2. **Virtual Threads (Loom)**: Parallelizing compression tasks across lightweight threads to maximize throughput and eliminate I/O blocking bottlenecks.
3. **Auditability & Observability**: Recording processing statistics in a local H2 database and exposing live Micrometer/Prometheus indicators.

---

## Architecture & Concurrency

The ingestion pipeline runs on an event-driven NIO.2 filesystem scanner:

```
[Host Folder /inbox]
        ↓
(NIO.2 WatchService + Fallback Directory Poller)
        ↓
    [Validator] ── (Binary or Magic Byte Mismatch) ──→ [DLQ Folder]
        ↓ (Accepts Valid Text Formats)
[LinkedBlockingQueue]
        ↓
 [TaskDispatcher] (Virtual Thread Executor per Task)
        ↓
  [CompressionWorker] 
   ├── Select Strategy (ZSTD, GZIP, PASSTHROUGH)
   ├── Perform Compression
   ├── Write Output to [/outbox]
   ├── Move Original to [/processed]
   └── Persist Database Audit & Prometheus Metrics
```

- **FileSystemWatcher**: Monitors the `/inbox` folder. Captures file events and prevents duplicate processing with a local concurrent cache.
- **FileValidator**: Asserts extensions and checks magic bytes (preventing pre-compressed formats like ZIP, BZIP2, XZ, or binaries like PDF from entering the queue).
- **TaskDispatcher**: Bounded FIFO buffer that submits compression tasks to `Executors.newVirtualThreadPerTaskExecutor()`.
- **CompressionWorker**: Runnables dispatched instantly onto lightweight Virtual Threads. They mount, run strategies, write files, and write to database concurrently.

---

## Tech Stack

| Layer | Technology | Version / Detail |
|---|---|---|
| **Language** | Java 21 | Long Term Support (Loom Virtual Threads active) |
| **Framework** | Spring Boot | 4.1.x |
| **I/O NIO** | `java.nio.file.WatchService` | JDK Native filesystem hook |
| **Queue** | `LinkedBlockingQueue` | Thread-safe, Bounded FIFO |
| **Worker Threads** | Virtual Threads | JDK 21 Thread-per-task executor |
| **Compression** | `zstd-jni` | 1.5.6-3 (Native Zstandard compression library) |
| **Compression** | `java.util.zip` | GZIP / Deflate wrapper |
| **Database** | JPA + H2 Database | Embedded SQL, schema auto-generation |
| **Metrics** | Spring Actuator + Micrometer | Exposes Prometheus formats under `/actuator/prometheus` |
| **Container** | Docker / Docker Compose | Multistage Alpine build |

---

## Getting Started

### Prerequisites
- Java 21+ JRE/JDK
- Maven 3.9+ (or use the built-in `./mvnw` script)
- Docker Desktop (active)

### Installation
```bash
# Clone the repository
git clone https://github.com/contatovictorhugos/files-compression-service.git
cd files-compression-service

# Run all test suites (includes 54 unit and integration tests)
./mvnw clean test
```

### Configuration properties (`application.yml`)
- `file-compressor.inbox.path`: Path to monitor (`./data/inbox` by default)
- `file-compressor.inbox.poll-interval-ms`: Fallback poll check frequency
- `file-compressor.algorithm.size-threshold-kb`: Size limit below which GZIP is chosen over ZSTD

### Running Locally with Docker Compose
To run the service inside a container with mapped local data folders, run:
```bash
# Clean package (generate JAR)
./mvnw package -DskipTests

# Build and boot container stack
docker compose up -d --build
```
This maps the host `./data` directory to the container workspace, exposing ports `8080:8080` for stats queries.

---

## Project Structure

```
file-compression-service/
├── src/
│   ├── main/
│   │   ├── java/com/file_compression_service/
│   │   │   ├── api/            # Stats REST Endpoints
│   │   │   ├── audit/          # JPA Entities and Audit logging
│   │   │   ├── config/         # Loom Executor & Configuration records
│   │   │   ├── output/         # OutputWriter and AlgorithmSelector
│   │   │   ├── queue/          # CompressionTask and TaskDispatcher
│   │   │   ├── watcher/        # FileSystemWatcher and FileValidator
│   │   │   └── worker/         # CompressionWorker and Strategies
│   │   └── resources/
│   │       └── application.yml
│   └── test/                   # Comprehensive Test Suite (54 test cases)
├── Dockerfile
├── docker-compose.yml
└── pom.xml
```

---

## API Reference

### 1. Compression Reports
Query per-algorithm ratios and throughput count aggregated from the database:
- **Endpoint**: `GET http://localhost:8080/api/v1/reports/stats`
- **Response**:
```json
[
  {
    "ALGO": "ZSTD",
    "COUNT": 302,
    "AVGRATIO": 0.7407991899446098
  },
  {
    "ALGO": "PASSTHROUGH",
    "COUNT": 1,
    "AVGRATIO": 100.0
  }
]
```

### 2. Prometheus Metrics
Real-time counts of processed bytes, savings, and task durations:
- **Endpoint**: `GET http://localhost:8080/actuator/prometheus`
- **Key Metrics Exposed**:
  - `file_compression_processed_total`: Cumulative counter per algorithm and status (success/failed).
  - `file_compression_bytes_saved_total`: Cumulative bytes saved per algorithm.
  - `file_compression_duration_seconds`: Histogram summary (p50, p90, p99 percentiles) of compression task latencies.

---

## Simulation & Load Testing Results

This project has been heavily optimized and load-tested against concurrent workloads using an automated PowerShell simulation. You can check the complete consolidated logs and analysis in [simulation-compiled-results.md](../brain/11692765-91ea-46a4-9e12-9b2af90a8408/simulation-compiled-results.md).

### Summary of Performance & Scale (Run 1 vs Run 2)

#### 1. Empty & Corrupted Files handling (Resiliency Fixes)
- In the initial baseline run, empty files (0 bytes) threw generic `IO_ERROR` exceptions and stayed locked in `/inbox`, blocking the pipeline.
- Following code updates, empty files are immediately flagged as `EMPTY_FILE` and quarantined to `/dlq` with a sidecar diagnostics file, maintaining a clean workspace.

#### 2. Avoiding Micro-File Inflation (PASSTHROUGH Strategy)
- In baseline tests, compressing an 89-byte file using GZIP caused it to inflate to **105 bytes** (+17.9%) due to GZIP envelope header overhead.
- Under the updated selector configuration, files under 150 bytes bypass compression via the `PASSTHROUGH` strategy, ensuring they are written unchanged (94 bytes -> 94 bytes) with **0% size overhead**.

#### 3. Loom Concurrency & Mass Ingestion Bombardment
- **Workload**: Injecting **300 log files** (15KB each) simultaneously, combined with a **5.25 MB text file** and corrupted data.
- **Execution Speed**: Under the Project Loom Virtual Thread Executor, all **307 files** were completely ingested, processed, compressed, written to `/outbox`, archived to `/processed`, and registered to database/metrics in just **4.68 seconds** (averaging 65 files/sec throughput).
- **Latencies**: 
  - ZSTD (p50): **12.5 ms**
  - ZSTD (p99): **56.0 ms**
  - Carrier thread blocking: **0%** (non-blocking JVM OS thread parking).

---

## Ecosystem

This repository is part of the **Projetcs** suite. The services in this suite collaborate as follows:

| Project | Role | Sibling Interaction |
|---|---|---|
| **key-management-service** | Cryptographic key generator API (AES key management) | Database backend |
| **mail-notifier-service** | Email dispatcher with Brevo integration | Encrypts payload via key-management-service |
| **files-compression-service** | High-throughput async files compression | Independent pipeline / Metrics daemon |
| **split-csv** | CLI tool to chunk large CSV exports | Local CLI |
| **mergeCSV** | CLI tool to combine small CSV parts into one | Local CLI |

---

## Contributing

1. Fork the repository.
2. Create your feature branch: `git checkout -b feature/cool-feature`
3. Commit with Conventional Commits structure: `git commit -m 'feat: add support for Brotli'`
4. Push to branch and open a Pull Request.

---
*Created and maintained by the Projetcs development team.*
