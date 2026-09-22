package com.company.modernizer.controller;
import com.company.modernizer.upload.ProjectScanAdapter;
import com.company.modernizer.upload.TemporaryDirectoryService;
import com.company.modernizer.upload.ZipExtractionService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;

@RestController
@RequestMapping("/api/v1/scan")
public class ZipScanController {

    private final ZipExtractionService zipExtractionService;
    private final TemporaryDirectoryService temporaryDirectoryService;
    private final ProjectScanAdapter projectScanAdapter;

    public ZipScanController(
            ZipExtractionService zipExtractionService,
            TemporaryDirectoryService temporaryDirectoryService,
            ProjectScanAdapter projectScanAdapter
    ) {
        this.zipExtractionService = zipExtractionService;
        this.temporaryDirectoryService = temporaryDirectoryService;
        this.projectScanAdapter = projectScanAdapter;
    }

    @PostMapping(value = "/upload",consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Object uploadAndScan(@RequestPart("file") MultipartFile file) {

        Path extractedDirectory = zipExtractionService.extract(file);

        try {
            return projectScanAdapter.scan(extractedDirectory);

        } finally {
            temporaryDirectoryService.deleteRecursively(
                    extractedDirectory
            );
        }
    }
}