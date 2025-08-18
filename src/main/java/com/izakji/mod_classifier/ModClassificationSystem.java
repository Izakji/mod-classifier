package com.izakji.mod_classifier;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.fml.loading.LoadingModList;
import net.neoforged.fml.loading.moddiscovery.ModFileInfo;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

@EventBusSubscriber(modid = ModClassifier.MOD_ID)
public class ModClassificationSystem {
    private static final ConcurrentHashMap<String, ModType> classificationCache = new ConcurrentHashMap<>();
    private static ExecutorService executor;
    private static volatile boolean classificationComplete = false;

    @SubscribeEvent
    public static void onCommonSetup(FMLCommonSetupEvent event) {
        if (classificationComplete) {
            return;
        }
        
        try {
            if (!Config.ENABLE_MOD_CLASSIFICATION.get()) {
                ModClassifier.LOGGER.info("Mod classification system disabled in config");
                return;
            }
        } catch (Exception e) {
            ModClassifier.LOGGER.warn("Config not loaded yet, using default settings");
        }
        
        initializeExecutor();
        
        ModClassifier.LOGGER.info("Starting mod classification system...");
        
        event.enqueueWork(() -> {
            try {
                classifyAllMods();
                classificationComplete = true;
                ModClassifier.LOGGER.info("Mod classification completed");
                
                // Log statistics
                logClassificationStatistics();
            } catch (Exception e) {
                ModClassifier.LOGGER.error("Error during mod classification", e);
            }
        });
    }

    private static void initializeExecutor() {
        if (executor == null) {
            int threadCount = 4;
            try {
                threadCount = Config.CLASSIFICATION_THREAD_POOL_SIZE.get();
                if (threadCount <= 0) {
                    threadCount = Math.min(4, Runtime.getRuntime().availableProcessors());
                }
            } catch (Exception e) {
                threadCount = Math.min(4, Runtime.getRuntime().availableProcessors());
            }
            executor = Executors.newFixedThreadPool(threadCount);
        }
    }

    private static void  classifyAllMods() {
        LoadingModList modList = LoadingModList.get();
        
        if (modList == null) {
            ModClassifier.LOGGER.warn("Could not access mod list for classification");
            return;
        }
        
        // Initialize the JSON classification service
        JsonClassificationService.getInstance().initialize();
        
        modList.getModFiles().parallelStream()
            .filter(modFile -> !modFile.getMods().isEmpty())
            .filter(modFile -> !modFile.getMods().get(0).getModId().equals(ModClassifier.MOD_ID))
            .forEach(modFile -> {
                try {
                    String modId = modFile.getMods().get(0).getModId();
                    
                    try {
                        if (Config.IGNORED_MODS.get().contains(modId)) {
                            boolean verboseLogging = safeGetConfig(() -> Config.VERBOSE_LOGGING.get(), false);
                            if (verboseLogging) {
                                ModClassifier.LOGGER.debug("Skipping ignored mod: {}", modId);
                            }
                            return;
                        }
                    } catch (Exception configError) {
                        // Config not loaded, continue with classification
                    }
                    
                    ModType modType = null;
                    String classificationSource = "unknown";
                    
                    // 1. First priority: Check forced types from config
                    ModType forcedType = getForcedModType(modId);
                    if (forcedType != null) {
                        modType = forcedType;
                        classificationSource = "config";
                    }
                    
                    // 2. Second priority: Check curated JSON classifications
                    if (modType == null) {
                        ModType curatedType = JsonClassificationService.getInstance().getCuratedClassification(modId);
                        if (curatedType != null) {
                            modType = curatedType;
                            classificationSource = "curated";
                        }
                    }
                    
                    // 3. Final fallback: Use heuristic analysis
                    if (modType == null) {
                        modType = ModAnalyzer.analyzeModType(modFile);
                        classificationSource = "heuristic";
                    }
                    
                    classificationCache.put(modId, modType);
                    
                    boolean verboseLogging = safeGetConfig(() -> Config.VERBOSE_LOGGING.get(), false);
                    if (verboseLogging || modType != ModType.UNIVERSAL) {
                        ModClassifier.LOGGER.info("Classified mod {} as {} (source: {})", modId, modType, classificationSource);
                    }
                    
                    boolean enableFileRenaming = safeGetConfig(() -> Config.ENABLE_FILE_RENAMING.get(), true);
                    if (enableFileRenaming && 
                        modType != ModType.UNKNOWN && 
                        modType != ModType.UNIVERSAL) {
                        
                        boolean shouldPrevent = ModFileManager.shouldPreventLoading(modType);
                        if (shouldPrevent) {
                            ModFileManager.renameModFile(modFile.getFile().getFilePath(), modType);
                            ModClassifier.LOGGER.warn("Mod {} classified as {} and will not load on this side", 
                                modId, modType);
                        }
                    }
                    
                } catch (Exception e) {
                    ModClassifier.LOGGER.error("Failed to classify mod: {}", 
                        modFile.getMods().get(0).getModId(), e);
                }
            });
    }

    public static ModType getModType(String modId) {
        return classificationCache.getOrDefault(modId, ModType.UNKNOWN);
    }

    public static boolean isClassificationComplete() {
        return classificationComplete;
    }

    private static <T> T safeGetConfig(Supplier<T> configGetter, T defaultValue) {
        try {
            return configGetter.get();
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private static ModType getForcedModType(String modId) {
        try {
            if (Config.FORCED_CLIENT_MODS.get().contains(modId)) {
                return ModType.CLIENT_ONLY;
            }
            if (Config.FORCED_SERVER_MODS.get().contains(modId)) {
                return ModType.SERVER_ONLY;
            }
            if (Config.FORCED_UNIVERSAL_MODS.get().contains(modId)) {
                return ModType.UNIVERSAL;
            }
        } catch (Exception e) {
            // Config not loaded, no forced types
        }
        return null;
    }

    private static void logClassificationStatistics() {
        try {
            // Log curated classification statistics
            JsonClassificationService.getInstance().logCurationStatistics();
            
            // Log manual curation statistics
            ManualCuration.getInstance().logCurationSummary();
            
            // Log accuracy tracking statistics if enabled
            if (safeGetConfig(() -> Config.ENABLE_ACCURACY_TRACKING.get(), true)) {
                AccuracyTracker.getInstance().logSummary();
            }
            
            // Log classification distribution
            Map<ModType, Long> distribution = classificationCache.values().stream()
                    .collect(java.util.stream.Collectors.groupingBy(
                            java.util.function.Function.identity(),
                            java.util.stream.Collectors.counting()));
            
            ModClassifier.LOGGER.info("=== Final Classification Distribution ===");
            ModClassifier.LOGGER.info("Total classified mods: {}", classificationCache.size());
            distribution.forEach((type, count) -> 
                    ModClassifier.LOGGER.info("{}: {} mods", type, count));
                    
        } catch (Exception e) {
            ModClassifier.LOGGER.warn("Failed to log classification statistics", e);
        }
    }

    public static void shutdown() {
        if (executor != null) {
            executor.shutdown();
        }
    }
}