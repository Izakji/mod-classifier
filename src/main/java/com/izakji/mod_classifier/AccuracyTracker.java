package com.izakji.mod_classifier;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class AccuracyTracker {
    private static final AccuracyTracker INSTANCE = new AccuracyTracker();
    
    private final Map<String, AccuracyStats> evidenceAccuracy = new ConcurrentHashMap<>();
    
    public static AccuracyTracker getInstance() {
        return INSTANCE;
    }
    
    public static class AccuracyStats {
        private final AtomicLong totalPredictions = new AtomicLong(0);
        private final AtomicLong correctPredictions = new AtomicLong(0);
        private final AtomicInteger consecutiveCorrect = new AtomicInteger(0);
        private final AtomicInteger consecutiveIncorrect = new AtomicInteger(0);
        
        public void recordPrediction(boolean wasCorrect) {
            totalPredictions.incrementAndGet();
            if (wasCorrect) {
                correctPredictions.incrementAndGet();
                consecutiveCorrect.incrementAndGet();
                consecutiveIncorrect.set(0);
            } else {
                consecutiveIncorrect.incrementAndGet();
                consecutiveCorrect.set(0);
            }
        }
        
        public double getAccuracy() {
            long total = totalPredictions.get();
            return total == 0 ? 0.5 : (double) correctPredictions.get() / total;
        }
        
        public long getTotalPredictions() {
            return totalPredictions.get();
        }
        
        public boolean hasReliableData() {
            return totalPredictions.get() >= 10; // Need at least 10 samples
        }
        
        public double getConfidenceAdjustment() {
            if (!hasReliableData()) return 1.0; // No adjustment with insufficient data
            
            double accuracy = getAccuracy();
            
            // If accuracy is very high, boost confidence slightly
            if (accuracy > 0.9) return 1.1;
            
            // If accuracy is poor, reduce confidence
            if (accuracy < 0.6) return 0.7;
            
            // If recent predictions are consistently wrong, reduce confidence more
            if (consecutiveIncorrect.get() >= 3) return 0.5;
            
            // If recent predictions are consistently right, boost confidence
            if (consecutiveCorrect.get() >= 5) return 1.2;
            
            return 1.0; // No adjustment
        }
        
        @Override
        public String toString() {
            return String.format("Accuracy: %.2f%% (%d/%d predictions, adjustment: %.2f)",
                    getAccuracy() * 100, correctPredictions.get(), totalPredictions.get(),
                    getConfidenceAdjustment());
        }
    }
    
    public void recordResult(String evidenceSource, boolean wasCorrect) {
        evidenceAccuracy.computeIfAbsent(evidenceSource, k -> new AccuracyStats())
                      .recordPrediction(wasCorrect);
        
        ModClassifier.LOGGER.debug("Recorded {} result for evidence source '{}'. Stats: {}",
                wasCorrect ? "correct" : "incorrect", evidenceSource, 
                evidenceAccuracy.get(evidenceSource));
    }
    
    public double getAdjustedConfidence(String evidenceSource, double baseConfidence) {
        AccuracyStats stats = evidenceAccuracy.get(evidenceSource);
        if (stats == null || !stats.hasReliableData()) {
            return baseConfidence; // No adjustment without reliable data
        }
        
        double adjustment = stats.getConfidenceAdjustment();
        double adjusted = baseConfidence * adjustment;
        
        // Clamp to valid range
        return Math.max(0.1, Math.min(1.0, adjusted));
    }
    
    public AccuracyStats getStats(String evidenceSource) {
        return evidenceAccuracy.get(evidenceSource);
    }
    
    public Map<String, AccuracyStats> getAllStats() {
        return new ConcurrentHashMap<>(evidenceAccuracy);
    }
    
    public void logSummary() {
        ModClassifier.LOGGER.info("=== Evidence Source Accuracy Summary ===");
        evidenceAccuracy.entrySet().stream()
                .filter(entry -> entry.getValue().hasReliableData())
                .sorted((a, b) -> Double.compare(b.getValue().getAccuracy(), a.getValue().getAccuracy()))
                .forEach(entry -> {
                    ModClassifier.LOGGER.info("{}: {}", entry.getKey(), entry.getValue());
                });
    }
}