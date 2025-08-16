package com.izakji.mod_classifier;

import java.util.List;

import net.neoforged.neoforge.common.ModConfigSpec;

public class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue ENABLE_MOD_CLASSIFICATION = BUILDER
            .comment("Enable automatic mod classification system")
            .define("enableModClassification", true);

    public static final ModConfigSpec.BooleanValue ENABLE_FILE_RENAMING = BUILDER
            .comment("Enable automatic renaming of classified mod files")
            .define("enableFileRenaming", true);

    public static final ModConfigSpec.BooleanValue VERBOSE_LOGGING = BUILDER
            .comment("Enable verbose logging for classification process")
            .define("verboseLogging", false);

    public static final ModConfigSpec.IntValue CLASSIFICATION_THREAD_POOL_SIZE = BUILDER
            .comment("Number of threads to use for mod classification (0 = auto)")
            .defineInRange("classificationThreadPoolSize", 0, 0, 16);

    public static final ModConfigSpec.IntValue MAX_CLASSES_TO_SCAN = BUILDER
            .comment("Maximum number of classes to scan per mod for performance")
            .defineInRange("maxClassesToScan", 50, 10, 500);

    public static final ModConfigSpec.BooleanValue USE_ENHANCED_ANALYSIS = BUILDER
            .comment("Use enhanced analysis algorithm with DisplayTest detection and proper sidedness checking")
            .define("useEnhancedAnalysis", true);

    public static final ModConfigSpec.BooleanValue ENABLE_ACCURACY_TRACKING = BUILDER
            .comment("Enable accuracy tracking and algorithm self-improvement")
            .define("enableAccuracyTracking", true);

    public static final ModConfigSpec.BooleanValue LOG_EVIDENCE_DETAILS = BUILDER
            .comment("Log detailed evidence for each classification decision")
            .define("logEvidenceDetails", false);

    public static final ModConfigSpec.ConfigValue<List<? extends String>> FORCED_CLIENT_MODS = BUILDER
            .comment("List of mod IDs to force classify as client-only")
            .defineListAllowEmpty("forcedClientMods", List.of(), () -> "", Config::validateModId);

    public static final ModConfigSpec.ConfigValue<List<? extends String>> FORCED_SERVER_MODS = BUILDER
            .comment("List of mod IDs to force classify as server-only")
            .defineListAllowEmpty("forcedServerMods", List.of(), () -> "", Config::validateModId);

    public static final ModConfigSpec.ConfigValue<List<? extends String>> FORCED_UNIVERSAL_MODS = BUILDER
            .comment("List of mod IDs to force classify as universal (both sides)")
            .defineListAllowEmpty("forcedUniversalMods", List.of(), () -> "", Config::validateModId);

    public static final ModConfigSpec.ConfigValue<List<? extends String>> IGNORED_MODS = BUILDER
            .comment("List of mod IDs to ignore during classification")
            .defineListAllowEmpty("ignoredMods", List.of("minecraft", "neoforge"), () -> "", Config::validateModId);

    static final ModConfigSpec SPEC = BUILDER.build();

    private static boolean validateModId(final Object obj) {
        return obj instanceof String modId && modId.matches("[a-z][a-z0-9_]{1,63}");
    }
}
