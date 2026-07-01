package com.file_compression_service.audit;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

@Entity
@Table(name = "compression_records")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CompressionRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String filename;
    private long originalSize;
    private long compressedSize;
    private double compressionRatio;
    private String algorithm;
    private long durationMs;
    private boolean success;
    private LocalDateTime timestamp;
}
