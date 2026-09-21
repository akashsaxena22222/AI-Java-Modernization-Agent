package com.company.modernizer.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /api/v1/scan}.
 *
 * <p>Bean validation here is a convenience that produces a tidy 400, not a security control. The
 * real boundary is {@code ScanGuard}, which canonicalizes the path and checks it against the
 * configured allowlist - never assume a validated string is a safe path.
 *
 * @param path filesystem path to the project root. Absolute, or relative to the application's
 *             working directory. Must resolve underneath a configured
 *             {@code modernizer.scan.allowed-roots} entry.
 */
public record ScanRequest(
        @NotBlank(message = "path is required")
        @Size(max = 4096, message = "path is unreasonably long")
        String path) {
}
