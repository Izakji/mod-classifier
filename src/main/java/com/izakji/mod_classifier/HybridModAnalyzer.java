package com.izakji.mod_classifier;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.fml.loading.moddiscovery.ModFile;
import net.neoforged.fml.loading.moddiscovery.ModFileInfo;
import net.neoforged.neoforgespi.language.IModInfo;
import org.objectweb.asm.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;

public class HybridModAnalyzer {
    private static final ManualCuration manualCuration = ManualCuration.getInstance();
    private static final AccuracyTracker accuracyTracker = AccuracyTracker.getInstance();
    
    // Evidence source constants for accuracy tracking
    private static final String EVIDENCE_MANUAL = "manual_curation";
    private static final String EVIDENCE_DISPLAY_TEST = "display_test_registration";
    private static final String EVIDENCE_CLIENT_SUBSCRIBERS = "client_event_subscribers";
    private static final String EVIDENCE_SERVER_SUBSCRIBERS = "server_event_subscribers";
    private static final String EVIDENCE_UNGATED_CLIENT = "ungated_client_references";
    private static final String EVIDENCE_GATED_CLIENT = "gated_client_references";
    private static final String EVIDENCE_SERVER_CLASSES = "server_class_references";
    private static final String EVIDENCE_DEPENDENCIES = "dependency_analysis";
    private static final String EVIDENCE_FABRIC_MANIFEST = "fabric_manifest";
    private static final String EVIDENCE_FILE_STRUCTURE = "file_structure";
    
    // Base confidence values (will be adjusted by accuracy tracker)
    private static final Map<String, Double> BASE_CONFIDENCE = Map.of(
        EVIDENCE_MANUAL, 1.0,
        EVIDENCE_DISPLAY_TEST, 0.85,
        EVIDENCE_CLIENT_SUBSCRIBERS, 0.75,
        EVIDENCE_SERVER_SUBSCRIBERS, 0.75,
        EVIDENCE_UNGATED_CLIENT, 0.70,
        EVIDENCE_GATED_CLIENT, 0.60,
        EVIDENCE_SERVER_CLASSES, 0.65,
        EVIDENCE_DEPENDENCIES, 0.50,
        EVIDENCE_FABRIC_MANIFEST, 0.80,
        EVIDENCE_FILE_STRUCTURE, 0.30
    );
    
    // Base weights (importance of each evidence type)
    private static final Map<String, Double> BASE_WEIGHT = Map.of(
        EVIDENCE_MANUAL, 1.0,
        EVIDENCE_DISPLAY_TEST, 0.95,
        EVIDENCE_CLIENT_SUBSCRIBERS, 0.70,
        EVIDENCE_SERVER_SUBSCRIBERS, 0.70,
        EVIDENCE_UNGATED_CLIENT, 0.85,
        EVIDENCE_GATED_CLIENT, 0.60,
        EVIDENCE_SERVER_CLASSES, 0.75,
        EVIDENCE_DEPENDENCIES, 0.40,
        EVIDENCE_FABRIC_MANIFEST, 0.80,
        EVIDENCE_FILE_STRUCTURE, 0.20
    );
    
    public static ModType analyzeModType(ModFileInfo modFileInfo) {
        try {
            ModFile modFile = modFileInfo.getFile();
            Path jarPath = modFile.getFilePath();
            IModInfo modInfo = modFileInfo.getMods().get(0);
            String modId = modInfo.getModId();
            
            ModClassifier.LOGGER.debug("Starting hybrid analysis for mod: {}", modId);
            
            List<Evidence> evidence = collectEvidence(modId, modInfo, jarPath);
            ModType result = classifyBasedOnEvidence(evidence, modId);
            
            logClassificationResult(modId, result, evidence);
            return result;
            
        } catch (Exception e) {
            ModClassifier.LOGGER.error("Failed to analyze mod with hybrid approach", e);
            return ModType.UNIVERSAL; // Safe fallback
        }
    }
    
    private static List<Evidence> collectEvidence(String modId, IModInfo modInfo, Path jarPath) {
        List<Evidence> evidence = new ArrayList<>();
        
        // Priority 1: Manual curation (highest priority)
        collectManualEvidence(modId, evidence);
        
        // Priority 2: Dependency analysis (fast and often reliable)
        collectDependencyEvidence(modInfo, evidence);
        
        // Priority 3: Jar file analysis (more expensive)
        if (jarPath != null && jarPath.toFile().exists()) {
            collectJarEvidence(jarPath, modId, evidence);
        }
        
        return evidence;
    }
    
    private static void collectManualEvidence(String modId, List<Evidence> evidence) {
        CuratedModEntry curatedEntry = manualCuration.getCuratedEntry(modId);
        if (curatedEntry != null) {
            ModType manualType = curatedEntry.getModType();
            double confidence = curatedEntry.getConfidence();
            String reason = String.format("Manually curated (%s, confidence: %.2f): %s", 
                    curatedEntry.getSource(), confidence, curatedEntry.getReasonSummary());
            
            // Use the curated confidence as the evidence confidence
            Evidence manualEvidence = new Evidence(EVIDENCE_MANUAL, manualType, 1.0, confidence, reason);
            evidence.add(manualEvidence);
        }
    }
    
    private static void collectDependencyEvidence(IModInfo modInfo, List<Evidence> evidence) {
        Set<String> dependencies = modInfo.getDependencies().stream()
                .map(dep -> dep.getModId())
                .collect(Collectors.toSet());
        
        // Check for known client-only dependencies
        Set<String> clientDeps = Set.of("optifine", "sodium", "iris", "rubidium", "jei");
        Set<String> serverDeps = Set.of("servercore", "spark", "chunky");
        
        long clientDepCount = dependencies.stream()
                .mapToLong(dep -> clientDeps.contains(dep) ? 1 : 0)
                .sum();
        
        long serverDepCount = dependencies.stream()
                .mapToLong(dep -> serverDeps.contains(dep) ? 1 : 0)
                .sum();
        
        if (clientDepCount > 0) {
            evidence.add(createEvidence(EVIDENCE_DEPENDENCIES, ModType.CLIENT_ONLY,
                    String.format("Depends on %d known client-only mods", clientDepCount)));
        }
        
        if (serverDepCount > 0) {
            evidence.add(createEvidence(EVIDENCE_DEPENDENCIES, ModType.SERVER_ONLY,
                    String.format("Depends on %d known server-only mods", serverDepCount)));
        }
    }
    
    private static void collectJarEvidence(Path jarPath, String modId, List<Evidence> evidence) {
        try (JarFile jarFile = new JarFile(jarPath.toFile())) {
            // Check Fabric manifest
            collectFabricManifestEvidence(jarFile, evidence);
            
            // Analyze class files
            collectBytecodeEvidence(jarFile, evidence);
            
            // Analyze file structure
            collectFileStructureEvidence(jarFile, evidence);
            
        } catch (IOException e) {
            ModClassifier.LOGGER.debug("Could not analyze jar file for mod {}: {}", modId, e.getMessage());
        }
    }
    
    private static void collectFabricManifestEvidence(JarFile jarFile, List<Evidence> evidence) {
        try {
            JarEntry fabricEntry = jarFile.getJarEntry("fabric.mod.json");
            if (fabricEntry != null) {
                try (InputStream is = jarFile.getInputStream(fabricEntry)) {
                    String content = new String(is.readAllBytes());
                    
                    // Simple JSON-like parsing (not a full parser)
                    if (content.contains("\"environment\": \"client\"") || 
                        content.contains("\"client\"") && !content.contains("\"server\"")) {
                        evidence.add(createEvidence(EVIDENCE_FABRIC_MANIFEST, ModType.CLIENT_ONLY,
                                "Fabric manifest indicates client-only"));
                    } else if (content.contains("\"environment\": \"server\"") ||
                               content.contains("\"server\"") && !content.contains("\"client\"")) {
                        evidence.add(createEvidence(EVIDENCE_FABRIC_MANIFEST, ModType.SERVER_ONLY,
                                "Fabric manifest indicates server-only"));
                    }
                }
            }
        } catch (IOException e) {
            ModClassifier.LOGGER.debug("Could not read Fabric manifest", e);
        }
    }
    
    private static void collectBytecodeEvidence(JarFile jarFile, List<Evidence> evidence) {
        Set<String> clientReferences = new HashSet<>();
        Set<String> serverReferences = new HashSet<>();
        Set<String> clientSubscribers = new HashSet<>();
        Set<String> serverSubscribers = new HashSet<>();
        boolean hasUngatedClientRefs = false;
        
        // Limit class scanning for performance
        List<JarEntry> classEntries = jarFile.stream()
                .filter(entry -> entry.getName().endsWith(".class"))
                .filter(entry -> !entry.getName().contains("$"))
                .limit(safeGetConfigValue())
                .collect(Collectors.toList());
        
        for (JarEntry entry : classEntries) {
            try (InputStream is = jarFile.getInputStream(entry)) {
                ClassReader classReader = new ClassReader(is);
                ClassAnalysisVisitor visitor = new ClassAnalysisVisitor(entry.getName());
                classReader.accept(visitor, ClassReader.SKIP_DEBUG);
                
                clientReferences.addAll(visitor.getClientReferences());
                serverReferences.addAll(visitor.getServerReferences());
                clientSubscribers.addAll(visitor.getClientSubscribers());
                serverSubscribers.addAll(visitor.getServerSubscribers());
                
                if (visitor.hasUngatedClientReferences()) {
                    hasUngatedClientRefs = true;
                }
                
            } catch (IOException e) {
                ModClassifier.LOGGER.debug("Could not read class: {}", entry.getName(), e);
            }
        }
        
        // Generate evidence based on analysis
        if (!clientSubscribers.isEmpty()) {
            evidence.add(createEvidence(EVIDENCE_CLIENT_SUBSCRIBERS, ModType.CLIENT_ONLY,
                    String.format("Has %d client event subscribers", clientSubscribers.size())));
        }
        
        if (!serverSubscribers.isEmpty()) {
            evidence.add(createEvidence(EVIDENCE_SERVER_SUBSCRIBERS, ModType.SERVER_ONLY,
                    String.format("Has %d server event subscribers", serverSubscribers.size())));
        }
        
        if (hasUngatedClientRefs) {
            evidence.add(createEvidence(EVIDENCE_UNGATED_CLIENT, ModType.CLIENT_ONLY,
                    "Contains ungated client-only class references"));
        }
        
        if (!clientReferences.isEmpty() && !hasUngatedClientRefs) {
            evidence.add(createEvidence(EVIDENCE_GATED_CLIENT, ModType.UNIVERSAL,
                    String.format("Has %d properly gated client references", clientReferences.size())));
        }
        
        if (!serverReferences.isEmpty()) {
            evidence.add(createEvidence(EVIDENCE_SERVER_CLASSES, ModType.SERVER_ONLY,
                    String.format("References %d server-only classes", serverReferences.size())));
        }
    }
    
    private static void collectFileStructureEvidence(JarFile jarFile, List<Evidence> evidence) {
        long clientFiles = jarFile.stream()
                .filter(entry -> entry.getName().contains("/client/") || 
                               entry.getName().contains("Client.class"))
                .count();
        
        long serverFiles = jarFile.stream()
                .filter(entry -> entry.getName().contains("/server/") || 
                               entry.getName().contains("Server.class"))
                .count();
        
        if (clientFiles > 0 && serverFiles == 0) {
            evidence.add(createEvidence(EVIDENCE_FILE_STRUCTURE, ModType.CLIENT_ONLY,
                    String.format("Has %d client-specific files, no server files", clientFiles)));
        } else if (serverFiles > 0 && clientFiles == 0) {
            evidence.add(createEvidence(EVIDENCE_FILE_STRUCTURE, ModType.SERVER_ONLY,
                    String.format("Has %d server-specific files, no client files", serverFiles)));
        } else if (clientFiles > 0 && serverFiles > 0) {
            evidence.add(createEvidence(EVIDENCE_FILE_STRUCTURE, ModType.UNIVERSAL,
                    String.format("Has both client (%d) and server (%d) files", clientFiles, serverFiles)));
        }
    }
    
    private static Evidence createEvidence(String source, ModType type, String reason) {
        double baseConfidence = BASE_CONFIDENCE.getOrDefault(source, 0.5);
        double baseWeight = BASE_WEIGHT.getOrDefault(source, 0.5);
        
        // Adjust confidence based on historical accuracy
        double adjustedConfidence = accuracyTracker.getAdjustedConfidence(source, baseConfidence);
        
        return new Evidence(source, type, baseWeight, adjustedConfidence, reason);
    }
    
    private static ModType classifyBasedOnEvidence(List<Evidence> evidence, String modId) {
        if (evidence.isEmpty()) {
            ModClassifier.LOGGER.debug("No evidence found for {}, defaulting to UNIVERSAL", modId);
            return ModType.UNIVERSAL;
        }
        
        // Check for definitive evidence first (high confidence + high weight)
        Optional<Evidence> definitiveEvidence = evidence.stream()
                .filter(Evidence::isDefinitive)
                .findFirst();
        
        if (definitiveEvidence.isPresent()) {
            Evidence def = definitiveEvidence.get();
            ModClassifier.LOGGER.debug("Definitive evidence for {}: {}", modId, def);
            return def.getSuggestedType();
        }
        
        // Calculate weighted scores for each mod type
        Map<ModType, Double> scores = new EnumMap<>(ModType.class);
        for (ModType type : ModType.values()) {
            scores.put(type, 0.0);
        }
        
        for (Evidence ev : evidence) {
            ModType type = ev.getSuggestedType();
            double score = ev.getScore();
            scores.put(type, scores.get(type) + score);
        }
        
        // Find the highest scoring type
        ModType bestType = scores.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(ModType.UNIVERSAL);
        
        double bestScore = scores.get(bestType);
        double secondBestScore = scores.entrySet().stream()
                .filter(entry -> entry.getKey() != bestType)
                .mapToDouble(Map.Entry::getValue)
                .max()
                .orElse(0.0);
        
        // If scores are too close, default to UNIVERSAL for safety
        if (bestScore - secondBestScore < 0.3) {
            ModClassifier.LOGGER.debug("Scores too close for {} (best: {:.2f}, second: {:.2f}), defaulting to UNIVERSAL",
                    modId, bestScore, secondBestScore);
            return ModType.UNIVERSAL;
        }
        
        ModClassifier.LOGGER.debug("Classification for {}: {} (score: {:.2f})", modId, bestType, bestScore);
        return bestType;
    }
    
    private static void logClassificationResult(String modId, ModType result, List<Evidence> evidence) {
        ModClassifier.LOGGER.info("Classified {} as {} based on {} pieces of evidence",
                modId, result, evidence.size());
        
        if (Config.VERBOSE_LOGGING.get()) {
            evidence.forEach(ev -> ModClassifier.LOGGER.debug("  - {}", ev));
        }
    }
    
    private static int safeGetConfigValue() {
        try {
            return Config.MAX_CLASSES_TO_SCAN.get();
        } catch (Exception e) {
            return 50;
        }
    }
    
    // Method to record feedback for accuracy tracking
    public static void recordClassificationFeedback(String modId, ModType actualType, ModType predictedType) {
        boolean wasCorrect = actualType == predictedType;
        
        // For now, we'll record this as feedback for the overall system
        // In the future, we could track which specific evidence sources were used
        accuracyTracker.recordResult("hybrid_classification", wasCorrect);
        
        ModClassifier.LOGGER.info("Recorded feedback for {}: predicted={}, actual={}, correct={}",
                modId, predictedType, actualType, wasCorrect);
    }
}