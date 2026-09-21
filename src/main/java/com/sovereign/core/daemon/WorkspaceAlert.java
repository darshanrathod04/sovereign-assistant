package com.sovereign.core.daemon;

import java.nio.file.Path;
import java.nio.file.WatchEvent;
import java.time.Instant;

/**
 * <b>WorkspaceAlert</b>
 *
 * <p>Represents a real-time event detected within the active workspace by
 * the {@link WorkspaceWatcherDaemon}.</p>
 */
public record WorkspaceAlert(
        Path path,
        WatchEvent.Kind<?> kind,
        boolean isErrorOrLog,
        Instant timestamp,
        String description
) {
    public static WorkspaceAlert of(Path path, WatchEvent.Kind<?> kind, boolean isErrorOrLog, String description) {
        return new WorkspaceAlert(path, kind, isErrorOrLog, Instant.now(), description);
    }
}
