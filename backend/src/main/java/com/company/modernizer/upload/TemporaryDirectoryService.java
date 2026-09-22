package com.company.modernizer.upload;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

@Service
public class TemporaryDirectoryService {

    public void deleteRecursively(Path directory) {
        if (directory == null || !Files.exists(directory)) {
            return;
        }

        try (var paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException exception) {
                            // Log this in production
                            System.err.println(
                                    "Unable to delete temporary file: " + path
                            );
                        }
                    });
        } catch (IOException exception) {
            System.err.println(
                    "Unable to clean temporary directory: " + directory
            );
        }
    }
}