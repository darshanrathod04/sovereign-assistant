package com.sovereign.core.client;

import com.shreeai.os.platform.ShreePlatformRuntime;
import com.shreeai.os.platform.runtime.config.RuntimeConfiguration;
import com.shreeai.os.platform.runtime.contracts.RuntimeContract;
import com.shreeai.os.platform.runtime.service.DefaultRuntimeService;
import com.shreeai.os.platform.sdk.MemorySDK;
import com.shreeai.os.platform.sdk.PlanningSDK;
import com.shreeai.os.platform.sdk.ShreeAI;
import com.shreeai.os.platform.sdk.ProjectSDK;
import com.sovereign.core.config.ProviderConfig;
import com.sovereign.core.sdk.DeveloperSDK;
import com.sovereign.core.sdk.ReasoningSDK;

import java.util.Objects;
import java.util.logging.Logger;

/**
 * <b>SovereignClient</b>
 *
 * <p>Central client facade initializing and coordinating the Shree AI OS runtime,
 * SDK facades (Planning, Reasoning, Memory, Developer, Project), and host engine services.</p>
 */
public class SovereignClient implements AutoCloseable {

    private static final Logger LOGGER = Logger.getLogger(SovereignClient.class.getName());

    private final DefaultRuntimeService runtimeService;
    private final ShreePlatformRuntime platformRuntime;
    private final ShreeAI shreeAI;

    private final PlanningSDK planningSdk;
    private final MemorySDK memorySdk;
    private final ReasoningSDK reasoningSdk;
    private final DeveloperSDK developerSdk;
    private final ProjectSDK projectSdk;
    private final ProviderConfig providerConfig;

    private SovereignClient(DefaultRuntimeService runtimeService,
                            ShreePlatformRuntime platformRuntime,
                            ShreeAI shreeAI,
                            ReasoningSDK reasoningSdk,
                            DeveloperSDK developerSdk,
                            ProviderConfig providerConfig) {
        this.runtimeService = runtimeService;
        this.platformRuntime = platformRuntime;
        this.shreeAI = shreeAI;
        this.planningSdk = shreeAI.planning();
        this.memorySdk = shreeAI.memory();
        this.reasoningSdk = reasoningSdk;
        this.developerSdk = developerSdk;
        this.projectSdk = shreeAI.project();
        this.providerConfig = providerConfig;
    }

    /**
     * Bootstraps a new {@link SovereignClient} instance using default runtime configuration.
     */
    public static SovereignClient create() {
        return createDefault();
    }

    /**
     * Bootstraps a new {@link SovereignClient} instance with resolved credentials.
     */
    public static SovereignClient createDefault() {
        return create(ProviderConfig.load());
    }

    /**
     * Bootstraps a new {@link SovereignClient} instance with explicit {@link ProviderConfig}.
     */
    public static SovereignClient create(ProviderConfig providerConfig) {
        Objects.requireNonNull(providerConfig, "providerConfig must not be null");

        // 1. Dynamically apply system properties and active LLM chain
        providerConfig.applySystemProperties();

        // 2. Display executive JARVIS neural link startup banner
        System.out.println(providerConfig.getBanner());

        // 3. Resolve client facade bootstrap key
        String apiKey = providerConfig.resolvePrimaryApiKey();

        // Configure RuntimeConfiguration and RuntimeContract
        RuntimeConfiguration configuration = RuntimeConfiguration.builder()
                .runtimeName("SovereignAssistant")
                .maxConcurrentSessions(16)
                .sessionTimeoutMillis(300_000L)
                .autoStartEnabled(true)
                .build();

        RuntimeContract contract = RuntimeContract.builder()
                .contractVersion("1.0.6")
                .supportsSessions(true)
                .supportsPipelines(true)
                .maxPipelineStageDepth(10)
                .build();

        DefaultRuntimeService runtimeService = new DefaultRuntimeService(configuration, contract);
        ShreePlatformRuntime platformRuntime = new ShreePlatformRuntime(runtimeService);

        // Initialize and start the platform runtime
        platformRuntime.initialize();
        platformRuntime.start();

        // Build ShreeAI client
        ShreeAI shreeAI = ShreeAI.builder()
                .apiKey(apiKey)
                .runtime(runtimeService)
                .build();

        ReasoningSDK reasoningSdk = new ReasoningSDK(
                new com.shreeai.os.platform.kernels.cognitive.engine.DefaultReasoningEngine(),
                shreeAI,
                runtimeService
        );
        DeveloperSDK developerSdk = new DeveloperSDK();

        return new SovereignClient(runtimeService, platformRuntime, shreeAI, reasoningSdk, developerSdk, providerConfig);
    }

    public ShreePlatformRuntime getPlatformRuntime() {
        return platformRuntime;
    }

    public DefaultRuntimeService getRuntimeService() {
        return runtimeService;
    }

    public ShreeAI getShreeAI() {
        return shreeAI;
    }

    public PlanningSDK getPlanningSdk() {
        return planningSdk;
    }

    public MemorySDK getMemorySdk() {
        return memorySdk;
    }

    public ReasoningSDK getReasoningSdk() {
        return reasoningSdk;
    }

    public ReasoningSDK reasoning() {
        return reasoningSdk;
    }

    public DeveloperSDK getDeveloperSdk() {
        return developerSdk;
    }

    public ProjectSDK getProjectSdk() {
        return projectSdk;
    }

    public ProviderConfig getProviderConfig() {
        return providerConfig;
    }

    public boolean isInitialized() {
        return platformRuntime.isInitialized();
    }

    public boolean isRunning() {
        return platformRuntime.isRunning();
    }

    @Override
    public void close() {
        shutdown();
    }

    public void shutdown() {
        try {
            platformRuntime.shutdown();
        } catch (Exception e) {
            LOGGER.warning("Error during runtime shutdown: " + e.getMessage());
        }
    }
}
