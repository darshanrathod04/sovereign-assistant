package com.shreeai.os.platform;

import com.shreeai.os.platform.runtime.RuntimeState;
import com.shreeai.os.platform.runtime.service.DefaultRuntimeService;

import java.util.Objects;

/**
 * <b>ShreePlatformRuntime</b>
 *
 * <p>Bridge and platform runtime wrapper providing lifecycle management around
 * {@link DefaultRuntimeService} within the Sovereign Assistant host engine.</p>
 */
public class ShreePlatformRuntime implements AutoCloseable {

    private final DefaultRuntimeService runtimeService;
    private volatile boolean initialized = false;

    public ShreePlatformRuntime(DefaultRuntimeService runtimeService) {
        this.runtimeService = Objects.requireNonNull(runtimeService, "DefaultRuntimeService must not be null");
    }

    /**
     * Initializes the underlying runtime service if not already initialized.
     */
    public synchronized void initialize() {
        if (!initialized) {
            runtimeService.initialize();
            initialized = true;
        }
    }

    /**
     * Starts the underlying runtime service.
     */
    public synchronized void start() {
        if (!initialized) {
            initialize();
        }
        runtimeService.start();
    }

    /**
     * Stops the underlying runtime service.
     */
    public synchronized void stop() {
        runtimeService.stop();
    }

    /**
     * Shuts down the underlying runtime service.
     */
    public synchronized void shutdown() {
        runtimeService.shutdown();
    }

    /**
     * Retrieves the active runtime state.
     */
    public RuntimeState getState() {
        return runtimeService.getRuntimeState();
    }

    /**
     * Checks if the runtime service is running.
     */
    public boolean isRunning() {
        RuntimeState state = runtimeService.getRuntimeState();
        return state == RuntimeState.STARTED || state == RuntimeState.VERIFIED;
    }

    /**
     * Checks if the runtime has been initialized.
     */
    public boolean isInitialized() {
        return initialized;
    }

    /**
     * Accesses the underlying {@link DefaultRuntimeService}.
     */
    public DefaultRuntimeService getRuntimeService() {
        return runtimeService;
    }

    @Override
    public void close() {
        shutdown();
    }
}
