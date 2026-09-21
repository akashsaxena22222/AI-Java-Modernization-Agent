package com.company.modernizer.controller;

import java.nio.file.Path;

import com.company.modernizer.controller.dto.ScanRequest;
import com.company.modernizer.controller.dto.ScanResponse;
import com.company.modernizer.model.ProjectContext;
import com.company.modernizer.scanner.ProjectScanner;
import com.company.modernizer.scanner.ScanGuard;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Exposes the raw scan, with no analysis layered on.
 *
 * <p>Useful in its own right for a demo - "here is what the tool can see about your project" - and
 * useful during development to confirm classification and limits behave before findings depend on
 * them.
 */
@RestController
@RequestMapping("/api/v1")
public class ScanController {

    private final ScanGuard guard;
    private final ProjectScanner scanner;

    public ScanController(ScanGuard guard, ProjectScanner scanner) {
        this.guard = guard;
        this.scanner = scanner;
    }

    /**
     * Validates and scans a project directory.
     *
     * <p>Validation comes first and is unconditional: {@link ScanGuard#validate} canonicalizes the
     * path and rejects anything outside the configured allowlist before a single file is opened.
     */
    @PostMapping("/scan")
    public ScanResponse scan(@Valid @RequestBody ScanRequest request) {
        Path root = guard.validate(request.path());
        ProjectContext context = scanner.scan(root);
        return ScanResponse.from(context);
    }
}
