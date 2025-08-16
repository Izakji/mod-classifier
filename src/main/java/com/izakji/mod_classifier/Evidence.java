package com.izakji.mod_classifier;

public class Evidence {
    private final String source;
    private final ModType suggestedType;
    private final double weight;
    private final double confidence;
    private final String reason;
    private final long timestamp;

    public Evidence(String source, ModType suggestedType, double weight, double confidence, String reason) {
        this.source = source;
        this.suggestedType = suggestedType;
        this.weight = Math.max(0.0, Math.min(1.0, weight)); // Clamp to [0,1]
        this.confidence = Math.max(0.0, Math.min(1.0, confidence)); // Clamp to [0,1]
        this.reason = reason;
        this.timestamp = System.currentTimeMillis();
    }

    public String getSource() {
        return source;
    }

    public ModType getSuggestedType() {
        return suggestedType;
    }

    public double getWeight() {
        return weight;
    }

    public double getConfidence() {
        return confidence;
    }

    public String getReason() {
        return reason;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public double getScore() {
        return weight * confidence;
    }

    public boolean isDefinitive() {
        return confidence > 0.95 && weight > 0.9;
    }

    @Override
    public String toString() {
        return String.format("%s -> %s (weight=%.2f, confidence=%.2f, score=%.2f): %s",
                source, suggestedType, weight, confidence, getScore(), reason);
    }
}