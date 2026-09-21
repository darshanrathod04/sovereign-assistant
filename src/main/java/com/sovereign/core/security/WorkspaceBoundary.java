package com.sovereign.core.security;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;
import java.util.Set;

/**
 * <b>WorkspaceBoundary</b>
 *
 * <p>Security boundary enforcing confinement of all filesystem operations within
 * designated workspace root directories. Rejects path traversal sequences,
 * root drive escapes, and unauthorized system access.</p>
 */
public class WorkspaceBoundary {

    private final Path rootPath;
    private static final Set<String> FORBIDDEN_PREFIXES = Set.of(
            "/etc", "/root", "/bin", "/sbin", "/usr", "/var", "/proc", "/sys", "/dev",
            "C:\\Windows", "C:\\Program Files", "C:\\Program Files (x86)", "C:\\Windows\\System32"
    );

    public WorkspaceBoundary() {
        this(Paths.get("").toAbsolutePath().normalize());
    }

    public WorkspaceBoundary(Path rootPath) {
        Objects.requireNonNull(rootPath, "Root path must not be null");
        this.rootPath = rootPath.toAbsolutePath().normalize();
    }

    /**
     * Resolves and validates a relative or absolute path against the workspace boundary.
     * Throws {@link SecurityException} if path traversal or boundary escape is detected.
     */
    public Path resolve(String pathString) {
        if (pathString == null || pathString.isBlank()) {
            throw new SecurityException("Path must not be null or blank");
        }

        // Null byte injection check
        if (pathString.indexOf('\0') >= 0) {
            throw new SecurityException("Path contains illegal null byte character");
        }

        // Explicit check for system roots and known sensitive locations
        String normalizedString = pathString.replace('/', File.separatorChar).replace('\\', File.separatorChar);
        for (String forbidden : FORBIDDEN_PREFIXES) {
            String normForbidden = forbidden.replace('/', File.separatorChar).replace('\\', File.separatorChar);
            if (normalizedString.equalsIgnoreCase(normForbidden) ||
                normalizedString.toLowerCase().startsWith(normForbidden.toLowerCase() + File.separator)) {
                if (!rootPath.toString().toLowerCase().startsWith(normForbidden.toLowerCase())) {
                    throw new SecurityException("Access to system path is forbidden: " + pathString);
                }
            }
        }

        Path candidate;
        try {
            candidate = Paths.get(pathString);
        } catch (Exception e) {
            throw new SecurityException("Invalid path syntax: " + pathString, e);
        }

        Path resolved;
        if (candidate.isAbsolute()) {
            resolved = candidate.normalize();
        } else {
            resolved = rootPath.resolve(candidate).normalize();
        }

        if (!isPathContained(resolved, rootPath)) {
            throw new SecurityException("Path traversal attempt detected outside workspace boundary: " + pathString);
        }

        return resolved;
    }

    /**
     * Validates whether an existing {@link Path} is strictly confined within the workspace boundary.
     */
    public Path validate(Path path) {
        Objects.requireNonNull(path, "Path must not be null");
        Path absolute = path.toAbsolutePath().normalize();
        if (!isPathContained(absolute, rootPath)) {
            throw new SecurityException("Path is outside workspace boundary: " + path);
        }
        return absolute;
    }

    /**
     * Checks if a path is within the boundary without throwing exceptions.
     */
    public boolean isWithinBoundary(String pathString) {
        try {
            resolve(pathString);
            return true;
        } catch (SecurityException | IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Checks if a {@link Path} is within the boundary without throwing exceptions.
     */
    public boolean isWithinBoundary(Path path) {
        if (path == null) {
            return false;
        }
        try {
            validate(path);
            return true;
        } catch (SecurityException | IllegalArgumentException e) {
            return false;
        }
    }

    public Path getRootPath() {
        return rootPath;
    }

    private static boolean isPathContained(Path child, Path parent) {
        String childStr = child.toAbsolutePath().normalize().toString();
        String parentStr = parent.toAbsolutePath().normalize().toString();

        // Handle case-insensitive file systems (e.g. Windows)
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        if (isWindows) {
            childStr = childStr.toLowerCase();
            parentStr = parentStr.toLowerCase();
        }

        if (childStr.equals(parentStr)) {
            return true;
        }
        if (!parentStr.endsWith(File.separator)) {
            parentStr += File.separator;
        }
        return childStr.startsWith(parentStr);
    }
}
