package com.sovereign.core.client;

import com.shreeai.os.platform.ShreePlatformRuntime;
import com.shreeai.os.platform.runtime.config.RuntimeConfiguration;
import com.shreeai.os.platform.runtime.contracts.RuntimeContract;
import com.shreeai.os.platform.runtime.service.DefaultRuntimeService;
import com.shreeai.os.platform.sdk.MemorySDK;
import com.shreeai.os.platform.sdk.PlanningSDK;
import com.shreeai.os.platform.sdk.ShreeAI;
import com.shreeai.os.platform.sdk.ProjectSDK;
import com.sovereign.core.sdk.DeveloperSDK;
import com.sovereign.core.sdk.ReasoningSDK;

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

    private SovereignClient(DefaultRuntimeService runtimeService,
                            ShreePlatformRuntime platformRuntime,
                            ShreeAI shreeAI,
                            ReasoningSDK reasoningSdk,
                            DeveloperSDK developerSdk) {
        this.runtimeService = runtimeService;
        this.platformRuntime = platformRuntime;
        this.shreeAI = shreeAI;
        this.planningSdk = shreeAI.planning();
        this.memorySdk = shreeAI.memory();
        this.reasoningSdk = reasoningSdk;
        this.developerSdk = developerSdk;
        this.projectSdk = shreeAI.project();
    }

    /**
     * Bootstraps a new {@link SovereignClient} instance using default runtime configuration.
     */
    public static SovereignClient create() {
        return createDefault();
    }

    /**
     * Bootstraps a new {@link SovereignClient} instance with deterministic fallback for unset API keys.
     */
    public static SovereignClient createDefault() {
        // Resolve API keys or establish deterministic fallback
        String geminiKey = resolveEnvOrProperty("GEMINI_API_KEY", "gemini.api.key");
        String openAiKey = resolveEnvOrProperty("OPENAI_API_KEY", "openai.api.key");

        String apiKey;
        if (geminiKey != null && !geminiKey.isBlank()) {
            apiKey = geminiKey;
        } else if (openAiKey != null && !openAiKey.isBlank()) {
            apiKey = openAiKey;
        } else {
            // Fallback deterministic provider (in-memory)
            apiKey = "deterministic-fallback-key";
            if (System.getProperty("shree.llm.chain") == null) {
                System.setProperty("shree.llm.chain", "in-memory");
            }
            LOGGER.info("No LLM API keys detected; utilizing deterministic in-memory provider.");
        }

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

        ReasoningSDK reasoningSdk = new ReasoningSDK();
        DeveloperSDK developerSdk = new DeveloperSDK();

        return new SovereignClient(runtimeService, platformRuntime, shreeAI, reasoningSdk, developerSdk);
    }

    private static String resolveEnvOrProperty(String envName, String propName) {
        String envVal = System.getenv(envName);
        if (envVal != null && !envVal.isBlank()) {
            return envVal;
        }
        String propVal = System.getProperty(propName);
        if (propVal != null && !propVal.isBlank()) {
            return propVal;
        }
        return null;
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

    public DeveloperSDK getDeveloperSdk() {
        return developerSdk;
    }

    public ProjectSDK getProjectSdk() {
        return projectSdk;
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
