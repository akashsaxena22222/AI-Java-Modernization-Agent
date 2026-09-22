package com.company.modernizer.upload;

public record ZipScanResponse(
        String filename,
        String extractedPath,
        Object result
) {
}