package com.company.modernizer.upload;

import com.company.modernizer.scanner.ProjectScanner;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

@Component
public class ProjectScanAdapter {

    private final ProjectScanner projectScanner;

    public ProjectScanAdapter(ProjectScanner projectScanner) {
        this.projectScanner = projectScanner;
    }

    public Object scan(Path projectPath) {

        // Adjust this method call to match your existing ProjectScanner.
        return projectScanner.scan(projectPath);
    }
}