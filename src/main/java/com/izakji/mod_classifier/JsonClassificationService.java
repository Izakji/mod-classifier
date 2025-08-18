package com.izakji.mod_classifier;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public class JsonClassificationService {
    private static final Gson gson = new Gson();
    private static JsonClassificationService instance;
    private Map<String, ModType> curatedClassifications;
    private boolean initialized = false;

    private JsonClassificationService() {
        curatedClassifications = new HashMap<>();
    }

    public static JsonClassificationService getInstance() {
        if (instance == null) {
            instance = new JsonClassificationService();
        }
        return instance;
    }

    /**
     * Initializes the service by loading the curated mod classifications from the JSON file
     */
    public void initialize() {
        if (initialized) {
            return;
        }

        try {
            loadCuratedClassifications();
            initialized = true;
            ModClassifier.LOGGER.info("Loaded {} curated mod classifications from JSON", 
                curatedClassifications.size());
        } catch (Exception e) {
            ModClassifier.LOGGER.error("Failed to load curated mod classifications from JSON", e);
        }
    }

    /**
     * Gets the curated classification for a mod ID, if available
     * @param modId The mod ID to look up
     * @return The ModType if found in curated list, null otherwise
     */
    public ModType getCuratedClassification(String modId) {
        if (!initialized) {
            initialize();
        }
        return curatedClassifications.get(modId);
    }

    /**
     * Checks if a mod ID has a curated classification
     * @param modId The mod ID to check
     * @return true if the mod has a curated classification
     */
    public boolean hasCuratedClassification(String modId) {
        if (!initialized) {
            initialize();
        }
        return curatedClassifications.containsKey(modId);
    }

    /**
     * Gets the total number of curated classifications loaded
     * @return The count of curated classifications
     */
    public int getCuratedClassificationCount() {
        if (!initialized) {
            initialize();
        }
        return curatedClassifications.size();
    }

    private void loadCuratedClassifications() throws IOException, JsonSyntaxException {
        InputStream inputStream = getClass().getResourceAsStream("/mod_classifications.json");
        
        if (inputStream == null) {
            ModClassifier.LOGGER.warn("Could not find mod_classifications.json in resources folder");
            return;
        }

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            
            CuratedModData curatedData = gson.fromJson(reader, CuratedModData.class);
            
            if (curatedData == null || curatedData.getData() == null) {
                ModClassifier.LOGGER.warn("Invalid or empty mod_classifications.json file");
                return;
            }

            // Process each mod entry
            for (CuratedModData.ModEntry entry : curatedData.getData()) {
                if (entry.getModId() != null && !entry.getModId().trim().isEmpty()) {
                    ModType modType = entry.toModType();
                    curatedClassifications.put(entry.getModId().trim(), modType);
                    
                    // Log with verbose logging if enabled
                    try {
                        if (Config.VERBOSE_LOGGING.get()) {
                            ModClassifier.LOGGER.debug("Loaded curated classification: {} -> {}", 
                                entry.getModId(), modType);
                        }
                    } catch (Exception e) {
                        // Config might not be loaded yet, ignore
                    }
                } else {
                    ModClassifier.LOGGER.warn("Skipping mod entry with empty modId: {}", 
                        entry.getName());
                }
            }

        } catch (IOException | JsonSyntaxException e) {
            ModClassifier.LOGGER.error("Error parsing mod_classifications.json", e);
            throw e;
        }
    }

    /**
     * Gets statistics about the curated classifications
     */
    public void logCurationStatistics() {
        if (!initialized) {
            initialize();
        }

        Map<ModType, Long> distribution = curatedClassifications.values().stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        java.util.function.Function.identity(),
                        java.util.stream.Collectors.counting()));

        ModClassifier.LOGGER.info("=== Curated Classification Statistics ===");
        ModClassifier.LOGGER.info("Total curated entries: {}", curatedClassifications.size());
        distribution.forEach((type, count) -> 
                ModClassifier.LOGGER.info("Curated {}: {} mods", type, count));
    }
}