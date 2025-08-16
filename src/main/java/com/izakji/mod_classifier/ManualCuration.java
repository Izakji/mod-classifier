package com.izakji.mod_classifier;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ManualCuration {
    private static final ManualCuration INSTANCE = new ManualCuration();
    
    // Manually curated mod classifications - Priority 1 (highest confidence)
    private static final Map<String, CuratedModEntry> CURATED_MODS = new ConcurrentHashMap<>();
    private static final Gson gson = new Gson();
    
    static {
        loadCuratedMods();
    }
    
    public static ManualCuration getInstance() {
        return INSTANCE;
    }
    
    private static void loadCuratedMods() {
        try {
            // Try to load from external file first
            Path externalFile = Paths.get("config", "mod-classifier", "curated-mods.json");
            
            List<CuratedModEntry> entries = null;
            
            if (Files.exists(externalFile)) {
                ModClassifier.LOGGER.info("Loading curated mods from: {}", externalFile);
                String jsonContent = Files.readString(externalFile, StandardCharsets.UTF_8);
                entries = parseJsonEntries(jsonContent);
            } else {
                // Fallback to built-in resource
                ModClassifier.LOGGER.info("External curated mods file not found, using built-in fallback");
                entries = loadBuiltinFallback();
            }
            
            if (entries != null) {
                for (CuratedModEntry entry : entries) {
                    String modId = entry.getModId();
                    if (modId != null && !modId.isEmpty()) {
                        CURATED_MODS.put(modId, entry);
                        ModClassifier.LOGGER.debug("Loaded curated mod: {}", entry);
                    } else {
                        ModClassifier.LOGGER.warn("Skipping entry with invalid mod ID: {}", entry.getModName());
                    }
                }
                
                ModClassifier.LOGGER.info("Successfully loaded {} curated mod classifications", CURATED_MODS.size());
            } else {
                ModClassifier.LOGGER.warn("No curated mods loaded - classification will rely entirely on algorithmic analysis");
            }
            
        } catch (Exception e) {
            ModClassifier.LOGGER.error("Failed to load curated mods, using minimal fallback", e);
            loadMinimalFallback();
        }
    }
    
    private static List<CuratedModEntry> parseJsonEntries(String jsonContent) {
        try {
            Type listType = new TypeToken<List<CuratedModEntry>>(){}.getType();
            return gson.fromJson(jsonContent, listType);
        } catch (Exception e) {
            ModClassifier.LOGGER.error("Failed to parse JSON curated mods", e);
            return null;
        }
    }
    
    private static List<CuratedModEntry> loadBuiltinFallback() {
        try {
            InputStream resourceStream = ManualCuration.class.getResourceAsStream("/curated-mods.json");
            if (resourceStream != null) {
                String jsonContent = new String(resourceStream.readAllBytes(), StandardCharsets.UTF_8);
                return parseJsonEntries(jsonContent);
            }
        } catch (IOException e) {
            ModClassifier.LOGGER.debug("No built-in curated mods resource found");
        }
        return null;
    }
    
    private static void loadMinimalFallback() {
        // Create some basic entries as absolute fallback
        CuratedModEntry jei = new CuratedModEntry();
        jei.setModName("Just Enough Items");
        jei.setPageUrl("https://modrinth.com/mod/jei");
        jei.setClassification("CLIENT_ONLY");
        jei.setConfidence(0.95);
        jei.setSource("hardcoded_fallback");
        CURATED_MODS.put("jei", jei);
        
        CuratedModEntry sodium = new CuratedModEntry();
        sodium.setModName("Sodium");
        sodium.setPageUrl("https://modrinth.com/mod/sodium");
        sodium.setClassification("CLIENT_ONLY");
        sodium.setConfidence(0.99);
        sodium.setSource("hardcoded_fallback");
        CURATED_MODS.put("sodium", sodium);
        
        ModClassifier.LOGGER.info("Loaded {} minimal fallback curations", CURATED_MODS.size());
    }
    
    public ModType getManualClassification(String modId) {
        CuratedModEntry entry = CURATED_MODS.get(modId.toLowerCase());
        return entry != null ? entry.getModType() : null;
    }
    
    public CuratedModEntry getCuratedEntry(String modId) {
        return CURATED_MODS.get(modId.toLowerCase());
    }
    
    public boolean hasManualClassification(String modId) {
        return CURATED_MODS.containsKey(modId.toLowerCase());
    }
    
    public void addManualOverride(String modId, ModType type, String reason) {
        CuratedModEntry entry = new CuratedModEntry();
        entry.setModName(modId);
        entry.setClassification(type.toString());
        entry.setConfidence(1.0);
        entry.setSource("runtime_override");
        
        CURATED_MODS.put(modId.toLowerCase(), entry);
        ModClassifier.LOGGER.info("Added manual override: {} -> {} ({})", modId, type, reason);
    }
    
    public void removeManualOverride(String modId) {
        CuratedModEntry removed = CURATED_MODS.remove(modId.toLowerCase());
        if (removed != null) {
            ModClassifier.LOGGER.info("Removed manual override for {}", modId);
        }
    }
    
    public Map<String, ModType> getAllCurations() {
        Map<String, ModType> result = new ConcurrentHashMap<>();
        CURATED_MODS.forEach((modId, entry) -> result.put(modId, entry.getModType()));
        return result;
    }
    
    public int getCurationCount() {
        return CURATED_MODS.size();
    }
    
    public void reloadCurations() {
        CURATED_MODS.clear();
        loadCuratedMods();
        ModClassifier.LOGGER.info("Reloaded curated mods");
    }
    
    public void logCurationSummary() {
        Map<ModType, Long> counts = CURATED_MODS.values().stream()
                .map(CuratedModEntry::getModType)
                .collect(java.util.stream.Collectors.groupingBy(
                        java.util.function.Function.identity(),
                        java.util.stream.Collectors.counting()));
        
        Map<String, Long> sourceCounts = CURATED_MODS.values().stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        entry -> entry.getSource() != null ? entry.getSource() : "unknown",
                        java.util.stream.Collectors.counting()));
        
        ModClassifier.LOGGER.info("=== Manual Curation Summary ===");
        ModClassifier.LOGGER.info("Total curated mods: {}", CURATED_MODS.size());
        
        ModClassifier.LOGGER.info("By classification:");
        counts.forEach((type, count) -> 
                ModClassifier.LOGGER.info("  {}: {} mods", type, count));
        
        ModClassifier.LOGGER.info("By source:");
        sourceCounts.forEach((source, count) -> 
                ModClassifier.LOGGER.info("  {}: {} mods", source, count));
        
        double avgConfidence = CURATED_MODS.values().stream()
                .mapToDouble(CuratedModEntry::getConfidence)
                .average()
                .orElse(0.0);
        
        ModClassifier.LOGGER.info("Average confidence: {:.2f}", avgConfidence);
    }
}