package com.file_compression_service.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

@Configuration
public class VirtualThreadConfig {

    @Bean(name = "compressionExecutor")
    public Executor compressionExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
