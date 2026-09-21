package com.sovereign.core.tools;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * <b>ProcessControlTool</b>
 *
 * <p>Host OS process management tool enabling query of system processes and
 * controlled termination of processes spawned by the Sovereign Assistant session.</p>
 */
public class ProcessControlTool {

    public record ProcessInfo(
            long pid,
            String name,
            String commandLine,
            boolean isAlive,
            boolean isSessionProcess,
            Optional<Instant> startTime,
            Optional<Duration> cpuDuration
    ) {}

    private final Set<Long> sessionPids = ConcurrentHashMap.newKeySet();

    private static final Set<Long> CRITICAL_PIDS = Set.of(0L, 1L, 4L);

    /**
     * Registers a PID as spawned by the current assistant session.
     */
    public void registerSessionProcess(long pid) {
        sessionPids.add(pid);
    }

    /**
     * Checks if a PID was spawned by the current assistant session.
     */
    public boolean isSessionProcess(long pid) {
        return sessionPids.contains(pid);
    }

    /**
     * Returns an unmodifiable snapshot of registered session PIDs.
     */
    public Set<Long> getSessionPids() {
        return Collections.unmodifiableSet(sessionPids);
    }

    /**
     * Queries active system processes, optionally filtered by executable name or command.
     */
    public List<ProcessInfo> queryProcesses(String nameFilter) {
        return ProcessHandle.allProcesses()
                .filter(ProcessHandle::isAlive)
                .map(this::toProcessInfo)
                .filter(info -> {
                    if (nameFilter == null || nameFilter.isBlank()) {
                        return true;
                    }
                    String filter = nameFilter.toLowerCase();
                    return info.name().toLowerCase().contains(filter)
                            || info.commandLine().toLowerCase().contains(filter);
                })
                .collect(Collectors.toList());
    }

    /**
     * Retrieves process information for a specific PID.
     */
    public Optional<ProcessInfo> getProcess(long pid) {
        return ProcessHandle.of(pid).map(this::toProcessInfo);
    }

    /**
     * Terminates a process spawned by this assistant session.
     * Rejects termination of non-session or critical OS processes.
     */
    public boolean terminateSessionProcess(long pid) {
        if (CRITICAL_PIDS.contains(pid)) {
            throw new SecurityException("Cannot terminate critical system process (PID: " + pid + ")");
        }

        if (!sessionPids.contains(pid)) {
            throw new SecurityException("Refusing to terminate non-session process (PID: " + pid + "). Only session-spawned processes may be terminated.");
        }

        Optional<ProcessHandle> handleOpt = ProcessHandle.of(pid);
        if (handleOpt.isPresent()) {
            ProcessHandle handle = handleOpt.get();
            // Terminate child descendants first
            handle.descendants().forEach(ProcessHandle::destroyForcibly);
            boolean destroyed = handle.destroyForcibly();
            sessionPids.remove(pid);
            return destroyed;
        }
        sessionPids.remove(pid);
        return false;
    }

    /**
     * Terminates all processes spawned by this assistant session.
     */
    public int terminateAllSessionProcesses() {
        int terminatedCount = 0;
        List<Long> pids = new ArrayList<>(sessionPids);
        for (Long pid : pids) {
            try {
                if (terminateSessionProcess(pid)) {
                    terminatedCount++;
                }
            } catch (Exception ignored) {
            }
        }
        return terminatedCount;
    }

    private ProcessInfo toProcessInfo(ProcessHandle handle) {
        ProcessHandle.Info info = handle.info();
        String command = info.command().orElse("");
        String name = command.isEmpty() ? "unknown" : command.substring(Math.max(command.lastIndexOf('/'), command.lastIndexOf('\\')) + 1);
        String commandLine = info.commandLine().orElse("");
        return new ProcessInfo(
                handle.pid(),
                name,
                commandLine,
                handle.isAlive(),
                sessionPids.contains(handle.pid()),
                info.startInstant(),
                info.totalCpuDuration()
        );
    }
}
