package com.company.modernizer.controller;

import java.util.List;

import com.company.modernizer.controller.dto.ApiError;
import com.company.modernizer.scanner.ScanException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Translates exceptions into the {@link ApiError} body.
 *
 * <p>Introduced at Step 2 rather than Step 7 as originally planned, because {@code ScanGuard}
 * rejections are a normal, expected outcome - "that path is outside the allowlist" deserves a clean
 * 400 with a reason code, not a stack trace and a 500. Step 7 extends this with LLM provider and
 * timeout mapping.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /**
     * Maps a scan failure using {@link ScanException.Reason#isClientError()}.
     *
     * <p>The distinction matters: a path outside the allowlist is the caller's problem (400), while
     * an empty allowlist or an I/O failure is ours (500). Collapsing both into 400 would hide a
     * server misconfiguration behind what looks like a bad request.
     */
    @ExceptionHandler(ScanException.class)
    public ResponseEntity<ApiError> handleScanException(ScanException e) {
        HttpStatus status = e.reason().isClientError()
                ? HttpStatus.BAD_REQUEST
                : HttpStatus.INTERNAL_SERVER_ERROR;

        if (status.is5xxServerError()) {
            log.error("Scan failed [{}]: {}", e.reason(), e.getMessage(), e);
        } else {
            log.warn("Scan rejected [{}]: {}", e.reason(), e.getMessage());
        }

        return ResponseEntity.status(status)
                .body(ApiError.of(e.reason().name(), e.getMessage()));
    }

    /** Bean validation failures on a request body. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException e) {
        List<String> details = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .toList();

        return ResponseEntity.badRequest()
                .body(ApiError.of("VALIDATION_FAILED", "Request body is invalid.", details));
    }
}
