package com.sovereign.core.daemon;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * <b>WorkspaceWatcherDaemon</b>
 *
 * <p>Background file-system monitoring daemon utilizing Java NIO {@link WatchService}.
 * Watches workspace trees for real-time file updates, deletions, and error log creations,
 * proactively dispatching structured alerts to listeners.</p>
 */
public class WorkspaceWatcherDaemon implements AutoCloseable {

    private static final Set<String> IGNORED_DIRECTORIES = Set.of(
            ".git", ".idea", ".vscode", ".gemini", "target", "node_modules", "build", "bin", ".gradle"
    );

    private final Path rootPath;
    private final List<Consumer<WorkspaceAlert>> listeners = new CopyOnWriteArrayList<>();
    private final ConcurrentMap<WatchKey, Path> keyPathMap = new ConcurrentHashMap<>();

    private WatchService watchService;
    private ExecutorService executor;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public WorkspaceWatcherDaemon(Path rootPath) {
        this.rootPath = rootPath != null ? rootPath.toAbsolutePath().normalize() : Path.of(".").toAbsolutePath().normalize();
    }

    public synchronized void start() throws IOException {
        if (running.get()) {
            return;
        }

        this.watchService = FileSystems.getDefault().newWatchService();
        registerTree(this.rootPath);

        this.running.set(true);
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "sovereign-workspace-watcher");
            t.setDaemon(true);
            return t;
        });

        this.executor.submit(this::processEvents);
    }

    public synchronized void stop() {
        if (!running.get()) {
            return;
        }
        running.set(false);
        try {
            if (watchService != null) {
                watchService.close();
            }
        } catch (IOException ignored) {
        }
        if (executor != null) {
            executor.shutdownNow();
        }
        keyPathMap.clear();
    }

    @Override
    public void close() {
        stop();
    }

    public boolean isRunning() {
        return running.get();
    }

    public void addListener(Consumer<WorkspaceAlert> listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    public void removeListener(Consumer<WorkspaceAlert> listener) {
        if (listener != null) {
            listeners.remove(listener);
        }
    }

    private void registerTree(Path start) throws IOException {
        if (!Files.exists(start)) {
            return;
        }

        Files.walkFileTree(start, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (isIgnored(dir)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                registerDirectory(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void registerDirectory(Path dir) throws IOException {
        WatchKey key = dir.register(
                watchService,
                StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_MODIFY,
                StandardWatchEventKinds.ENTRY_DELETE
        );
        keyPathMap.put(key, dir);
    }

    private boolean isIgnored(Path path) {
        String name = path.getFileName() != null ? path.getFileName().toString() : "";
        if (IGNORED_DIRECTORIES.contains(name)) {
            return true;
        }
        for (Path part : path) {
            if (IGNORED_DIRECTORIES.contains(part.toString())) {
                return true;
            }
        }
        return false;
    }

    private void processEvents() {
        while (running.get()) {
            WatchKey key;
            try {
                key = watchService.take();
            } catch (Exception e) {
                if (!running.get()) {
                    break;
                }
                continue;
            }

            Path dir = keyPathMap.get(key);
            if (dir == null) {
                key.reset();
                continue;
            }

            for (WatchEvent<?> event : key.pollEvents()) {
                WatchEvent.Kind<?> kind = event.kind();
                if (kind == StandardWatchEventKinds.OVERFLOW) {
                    continue;
                }

                @SuppressWarnings("unchecked")
                WatchEvent<Path> ev = (WatchEvent<Path>) event;
                Path relative = ev.context();
                Path fullPath = dir.resolve(relative);

                if (isIgnored(fullPath)) {
                    continue;
                }

                // If new directory was created, recursively register it
                if (kind == StandardWatchEventKinds.ENTRY_CREATE && Files.isDirectory(fullPath)) {
                    try {
                        registerTree(fullPath);
                    } catch (IOException ignored) {
                    }
                }

                boolean isError = checkIsErrorOrLog(fullPath);
                String description = String.format("%s: %s", kind.name(), fullPath.getFileName());
                WorkspaceAlert alert = WorkspaceAlert.of(fullPath, kind, isError, description);

                dispatchAlert(alert);
            }

            boolean valid = key.reset();
            if (!valid) {
                keyPathMap.remove(key);
            }
        }
    }

    private boolean checkIsErrorOrLog(Path path) {
        String filename = path.getFileName() != null ? path.getFileName().toString().toLowerCase(Locale.ROOT) : "";
        return filename.endsWith(".log")
                || filename.endsWith(".err")
                || filename.contains("error")
                || filename.contains("crash")
                || filename.contains("fail");
    }

    private void dispatchAlert(WorkspaceAlert alert) {
        for (Consumer<WorkspaceAlert> listener : listeners) {
            try {
                listener.accept(alert);
            } catch (Exception ignored) {
            }
        }
    }

    public Path getRootPath() {
        return rootPath;
    }
}
