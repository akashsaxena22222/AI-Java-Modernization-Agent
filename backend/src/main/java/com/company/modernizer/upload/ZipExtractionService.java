package com.company.modernizer.upload;

import com.company.modernizer.scanner.ScanException;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Service
public class ZipExtractionService {

    private static final long MAX_EXTRACTED_SIZE = 200L * 1024 * 1024;
    private static final int MAX_ENTRIES = 20_000;

    public Path extract(MultipartFile zipFile) {
        if (zipFile == null || zipFile.isEmpty()) {
            throw new ScanException(ScanException.Reason.NOT_FOUND,"ZIP file is empty");
        }

        String filename = zipFile.getOriginalFilename();

        if (filename == null || !filename.toLowerCase().endsWith(".zip")) {
            throw new ScanException(ScanException.Reason.NOT_FOUND,"Only ZIP files are supported");
        }

        try {
            Path extractionDirectory =
                    Files.createTempDirectory("modernizer-upload-");

            extractSafely(zipFile, extractionDirectory);

            return extractionDirectory;

        } catch (IOException exception) {
            throw new ScanException(ScanException.Reason.NOT_FOUND,
                    "Unable to extract ZIP file: " + exception.getMessage()
            );
        }
    }

    private void extractSafely(
            MultipartFile zipFile,
            Path extractionDirectory
    ) throws IOException {

        Path normalizedRoot = extractionDirectory.toRealPath();
        long extractedBytes = 0;
        int entryCount = 0;

        try (InputStream inputStream = zipFile.getInputStream();
             ZipInputStream zipInputStream = new ZipInputStream(inputStream)) {

            ZipEntry entry;

            while ((entry = zipInputStream.getNextEntry()) != null) {

                entryCount++;

                if (entryCount > MAX_ENTRIES) {
                    throw new ScanException(ScanException.Reason.NOT_FOUND,
                            "ZIP contains too many files"
                    );
                }

                String entryName = entry.getName();

                if (entryName == null || entryName.isBlank()) {
                    continue;
                }

                Path targetPath = normalizedRoot.resolve(entryName).normalize();

                // Protection against ZIP Slip attacks
                if (!targetPath.startsWith(normalizedRoot)) {
                    throw new ScanException(ScanException.Reason.NOT_FOUND,
                            "Unsafe ZIP entry detected: " + entryName
                    );
                }

                if (entry.isDirectory()) {
                    Files.createDirectories(targetPath);
                    continue;
                }

                Path parent = targetPath.getParent();

                if (parent != null) {
                    Files.createDirectories(parent);
                }

                try (var outputStream = Files.newOutputStream(targetPath)) {
                    byte[] buffer = new byte[8192];
                    int bytesRead;

                    while ((bytesRead = zipInputStream.read(buffer)) != -1) {
                        extractedBytes += bytesRead;

                        if (extractedBytes > MAX_EXTRACTED_SIZE) {
                            throw new ScanException(ScanException.Reason.NOT_FOUND,
                                    "Extracted ZIP content exceeds 200 MB"
                            );
                        }

                        outputStream.write(buffer, 0, bytesRead);
                    }
                }

                zipInputStream.closeEntry();
            }
        }
    }
}