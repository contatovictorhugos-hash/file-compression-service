package com.file_compression_service.worker.strategy;

import org.springframework.stereotype.Component;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.GZIPOutputStream;

@Component
public class GzipStrategy implements CompressionStrategy {
    @Override
    public byte[] compress(byte[] input) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gzos = new GZIPOutputStream(baos)) {
            gzos.write(input);
        }
        return baos.toByteArray();
    }

    @Override
    public String fileExtension() {
        return ".gz";
    }

    @Override
    public String algorithmName() {
        return "GZIP";
    }
}
