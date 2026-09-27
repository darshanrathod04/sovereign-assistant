package com.sovereign.core.daemon;

import com.sovereign.core.tools.ShellExecutionTool;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * <b>ProactiveIntelligenceDaemon</b>
 *
 * <p>Autonomous background intelligence daemon running at 100% zero cost.
 * Periodically monitors workspace health, uncommitted Git changes, and failed surefire
 * test reports without consuming any Gemini API quota during idle state.</p>
 */
public class ProactiveIntelligenceDaemon {

    private static final Logger LOG = Logger.getLogger(ProactiveIntelligenceDaemon.class.getName());

    private final ShellExecutionTool shellTool;
    private final Path workspaceRoot;
    private final long pollIntervalMs;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final List<Consumer<String>> alertListeners = new CopyOnWriteArrayList<>();
    private Thread workerThread;

    public ProactiveIntelligenceDaemon() {
        this(new ShellExecutionTool(), Paths.get("").toAbsolutePath(), 30_000L);
    }

    public ProactiveIntelligenceDaemon(ShellExecutionTool shellTool, Path workspaceRoot, long pollIntervalMs) {
        this.shellTool = shellTool != null ? shellTool : new ShellExecutionTool();
        this.workspaceRoot = workspaceRoot != null ? workspaceRoot : Paths.get("").toAbsolutePath();
        this.pollIntervalMs = Math.max(500L, pollIntervalMs);
    }

    public void addAlertListener(Consumer<String> listener) {
        if (listener != null) {
            alertListeners.add(listener);
        }
    }

    public synchronized void start() {
        if (running.compareAndSet(false, true)) {
            workerThread = new Thread(this::runLoop, "sovereign-proactive-daemon");
            workerThread.setDaemon(true);
            workerThread.start();
            LOG.info("[PROACTIVE DAEMON] Started proactive workspace intelligence daemon (poll: "
                    + pollIntervalMs + "ms, zero-cost).");
        }
    }

    public synchronized void stop() {
        if (running.compareAndSet(true, false)) {
            if (workerThread != null) {
                workerThread.interrupt();
                workerThread = null;
            }
            LOG.info("[PROACTIVE DAEMON] Stopped proactive intelligence daemon.");
        }
    }

    public boolean isRunning() {
        return running.get();
    }

    /**
     * Inspects workspace status deterministically without blocking or consuming API quota.
     *
     * @return list of actionable proactive suggestions or health notices
     */
    public List<String> inspectWorkspace() {
        List<String> notices = new ArrayList<>();

        // 1. Git uncommitted changes check
        try {
            ShellExecutionTool.ShellResult gitResult = shellTool.execute("git status --porcelain", Duration.ofSeconds(5));
            if (gitResult.exitCode() == 0 && !gitResult.stdout().isBlank()) {
                long count = gitResult.stdout().lines().filter(l -> !l.isBlank()).count();
                notices.add(String.format("[PROACTIVE ADVICE] You have %d uncommitted change%s in git. Consider committing frequently.",
                        count, count == 1 ? "" : "s"));
            }
        } catch (Exception ignored) {}

        // 2. Surefire build failures check
        Path surefireReports = workspaceRoot.resolve("target").resolve("surefire-reports");
        if (Files.isDirectory(surefireReports)) {
            File[] dumps = surefireReports.toFile().listFiles((dir, name) -> name.endsWith(".dump") || name.endsWith(".dumpstream"));
            if (dumps != null && dumps.length > 0) {
                notices.add("[PROACTIVE NOTICE] Surefire test dump files detected in target/. Run 'analyze-log' to investigate.");
            }
        }

        // 3. Git branch check
        try {
            ShellExecutionTool.ShellResult branchResult = shellTool.execute("git branch --show-current", Duration.ofSeconds(3));
            if (branchResult.exitCode() == 0 && !branchResult.stdout().isBlank()) {
                String branch = branchResult.stdout().trim();
                if ("main".equalsIgnoreCase(branch) || "master".equalsIgnoreCase(branch)) {
                    notices.add("[PROACTIVE ADVICE] Working directly on production branch '" + branch + "'.");
                }
            }
        } catch (Exception ignored) {}

        return notices;
    }

    private void runLoop() {
        while (running.get()) {
            try {
                List<String> notices = inspectWorkspace();
                for (String notice : notices) {
                    for (Consumer<String> listener : alertListeners) {
                        try {
                            listener.accept(notice);
                        } catch (Exception ignored) {}
                    }
                }
                Thread.sleep(pollIntervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                LOG.warning("[PROACTIVE DAEMON] Poll cycle error: " + e.getMessage());
            }
        }
    }
}
