package com.file_compression_service.api;

import com.file_compression_service.audit.AuditService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/reports")
public class CompressionStatsController {

    private final AuditService auditService;

    public CompressionStatsController(AuditService auditService) {
        this.auditService = auditService;
    }

    @GetMapping("/stats")
    public List<Map<String, Object>> getStats() {
        return auditService.getStats();
    }
}
