package com.company.modernizer.controller.dto;

import java.util.LinkedHashMap;
import java.util.Map;

import com.company.modernizer.model.FileKind;
import com.company.modernizer.model.ProjectContext;
import com.company.modernizer.model.ProjectInfo;
import com.company.modernizer.model.ScanStats;

/**
 * Response for {@code POST /api/v1/scan}: what the scan found, with no interpretation.
 *
 * <p>A dedicated DTO rather than the raw {@link ProjectContext} for two reasons. The context holds
 * the absolute project root, which section 9.2 forbids from leaving the process - {@link ProjectInfo}
 * drops it. And the full per-file inventory can run to 20 000 entries, which is useful to analyzers
 * and useless in an HTTP response.
 *
 * <p>This endpoint exists to make the scan observable before any analysis is layered on. Once
 * Step 3 lands, {@code /api/v1/analysis} is the interesting one.
 *
 * @param fileCountsByKind how many files of each {@link FileKind} were inventoried - the quickest
 *                         way to see whether a legacy project has WSDLs, JSPs, or XML config
 * @param scan             what the scan did, including any limits hit and what was dropped
 */
public record ScanResponse(
        ProjectInfo project,
        Map<FileKind, Long> fileCountsByKind,
        ScanStats scan) {

    public static ScanResponse from(ProjectContext context) {
        return new ScanResponse(
                ProjectInfo.from(context),
                orderedCounts(context),
                context.scanStats());
    }

    /** Ordered by {@link FileKind} declaration order so repeated scans render identically. */
    private static Map<FileKind, Long> orderedCounts(ProjectContext context) {
        Map<FileKind, Long> raw = context.fileCountsByKind();
        Map<FileKind, Long> ordered = new LinkedHashMap<>();
        for (FileKind kind : FileKind.values()) {
            Long count = raw.get(kind);
            if (count != null && count > 0) {
                ordered.put(kind, count);
            }
        }
        return ordered;
    }
}
