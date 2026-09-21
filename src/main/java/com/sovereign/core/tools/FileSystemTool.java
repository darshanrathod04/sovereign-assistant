package com.sovereign.core.tools;

import com.sovereign.core.security.WorkspaceBoundary;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * <b>FileSystemTool</b>
 *
 * <p>Structured host filesystem operations enclosed within a {@link WorkspaceBoundary}.
 * Provides atomic file writing, recursive directory walking, and checksum verification.</p>
 */
public class FileSystemTool {

    public record FileInfo(
            String relativePath,
            String absolutePath,
            boolean isDirectory,
            long sizeBytes,
            Instant lastModified
    ) {}

    private final WorkspaceBoundary boundary;

    public FileSystemTool() {
        this(new WorkspaceBoundary());
    }

    public FileSystemTool(WorkspaceBoundary boundary) {
        this.boundary = Objects.requireNonNull(boundary, "WorkspaceBoundary must not be null");
    }

    /**
     * Reads the entire contents of a file as a UTF-8 string.
     */
    public String readFile(String pathString) throws IOException {
        Path target = boundary.resolve(pathString);
        if (!Files.exists(target)) {
            throw new NoSuchFileException(target.toString());
        }
        if (Files.isDirectory(target)) {
            throw new IllegalArgumentException("Cannot read directory as file: " + target);
        }
        return Files.readString(target, StandardCharsets.UTF_8);
    }

    /**
     * Reads file contents as raw bytes.
     */
    public byte[] readBytes(String pathString) throws IOException {
        Path target = boundary.resolve(pathString);
        if (!Files.exists(target)) {
            throw new NoSuchFileException(target.toString());
        }
        return Files.readAllBytes(target);
    }

    /**
     * Writes content to a file atomically via a temporary file in the same directory.
     */
    public Path atomicWriteFile(String pathString, String content) throws IOException {
        Path target = boundary.resolve(pathString);
        Path parent = target.getParent();
        if (parent != null && !Files.exists(parent)) {
            Files.createDirectories(parent);
        }

        Path tempFile = Files.createTempFile(parent != null ? parent : boundary.getRootPath(), ".tmp-sovereign-", ".tmp");
        try {
            Files.writeString(tempFile, content != null ? content : "", StandardCharsets.UTF_8,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);

            // Atomic move replaces target file atomically if supported by OS/filesystem
            try {
                Files.move(tempFile, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tempFile, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return target;
        } finally {
            try {
                Files.deleteIfExists(tempFile);
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * Standard file write shortcut using atomic write.
     */
    public Path writeFile(String pathString, String content) throws IOException {
        return atomicWriteFile(pathString, content);
    }

    /**
     * Traverses a directory tree up to a specified maximum depth.
     */
    public List<FileInfo> walkDirectory(String pathString, int maxDepth) throws IOException {
        Path target = boundary.resolve(pathString);
        if (!Files.exists(target)) {
            throw new NoSuchFileException(target.toString());
        }

        List<FileInfo> result = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(target, maxDepth)) {
            for (Path path : stream.toList()) {
                BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class);
                Path relative = boundary.getRootPath().relativize(path);
                result.add(new FileInfo(
                        relative.toString(),
                        path.toAbsolutePath().toString(),
                        attrs.isDirectory(),
                        attrs.size(),
                        attrs.lastModifiedTime().toInstant()
                ));
            }
        }
        return result;
    }

    /**
     * Computes the cryptographic checksum (e.g., SHA-256, MD5) of a file.
     */
    public String computeChecksum(String pathString, String algorithm) throws IOException {
        Path target = boundary.resolve(pathString);
        if (!Files.exists(target)) {
            throw new NoSuchFileException(target.toString());
        }

        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalArgumentException("Unsupported hashing algorithm: " + algorithm, e);
        }

        try (InputStream inputStream = Files.newInputStream(target)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = inputStream.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /**
     * Checks if a path exists within the workspace.
     */
    public boolean exists(String pathString) {
        try {
            Path target = boundary.resolve(pathString);
            return Files.exists(target);
        } catch (SecurityException | IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Deletes a file within the workspace.
     */
    public boolean delete(String pathString) throws IOException {
        Path target = boundary.resolve(pathString);
        return Files.deleteIfExists(target);
    }

    public WorkspaceBoundary getBoundary() {
        return boundary;
    }
}
