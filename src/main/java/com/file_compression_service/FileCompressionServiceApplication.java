package com.file_compression_service;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class FileCompressionServiceApplication {
	public static void main(String[] args) {
		SpringApplication.run(FileCompressionServiceApplication.class, args);
	}
}
