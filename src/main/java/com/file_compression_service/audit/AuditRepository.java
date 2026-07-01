package com.file_compression_service.audit;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Map;

@Repository
public interface AuditRepository extends JpaRepository<CompressionRecord, Long> {

    @Query("SELECT r.algorithm as algo, COUNT(r) as count, AVG(r.compressionRatio) as avgRatio FROM CompressionRecord r WHERE r.success = true GROUP BY r.algorithm")
    List<Map<String, Object>> getStatsPerAlgorithm();
}
