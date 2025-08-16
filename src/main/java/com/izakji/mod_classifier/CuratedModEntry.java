package com.izakji.mod_classifier;

import java.time.LocalDateTime;
import java.util.List;

public class CuratedModEntry {
    private String mod_name;
    private String page_url;
    private String source;
    private String classification;
    private double confidence;
    private List<ReasonEntry> reason;
    private String scanned_at;

    public static class ReasonEntry {
        private String label;
        private String snippet;

        public ReasonEntry() {}

        public ReasonEntry(String label, String snippet) {
            this.label = label;
            this.snippet = snippet;
        }

        public String getLabel() {
            return label;
        }

        public void setLabel(String label) {
            this.label = label;
        }

        public String getSnippet() {
            return snippet;
        }

        public void setSnippet(String snippet) {
            this.snippet = snippet;
        }

        @Override
        public String toString() {
            return String.format("%s: %s", label, snippet);
        }
    }

    public CuratedModEntry() {}

    public String getModName() {
        return mod_name;
    }

    public void setModName(String mod_name) {
        this.mod_name = mod_name;
    }

    public String getPageUrl() {
        return page_url;
    }

    public void setPageUrl(String page_url) {
        this.page_url = page_url;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getClassification() {
        return classification;
    }

    public void setClassification(String classification) {
        this.classification = classification;
    }

    public double getConfidence() {
        return confidence;
    }

    public void setConfidence(double confidence) {
        this.confidence = confidence;
    }

    public List<ReasonEntry> getReason() {
        return reason;
    }

    public void setReason(List<ReasonEntry> reason) {
        this.reason = reason;
    }

    public String getScannedAt() {
        return scanned_at;
    }

    public void setScannedAt(String scanned_at) {
        this.scanned_at = scanned_at;
    }

    public ModType getModType() {
        try {
            return ModType.valueOf(classification.toUpperCase());
        } catch (Exception e) {
            return ModType.UNKNOWN;
        }
    }

    public String getModId() {
        // Extract mod ID from the mod name or URL
        if (page_url != null) {
            // Extract from URL like "https://modrinth.com/mod/jei" -> "jei"
            String[] parts = page_url.split("/");
            if (parts.length > 0) {
                return parts[parts.length - 1].toLowerCase();
            }
        }
        
        // Fallback to mod name converted to mod ID format
        if (mod_name != null) {
            return mod_name.toLowerCase()
                    .replaceAll("[^a-z0-9_]", "_")
                    .replaceAll("_{2,}", "_")
                    .replaceAll("^_+|_+$", "");
        }
        
        return null;
    }

    public String getReasonSummary() {
        if (reason == null || reason.isEmpty()) {
            return "No specific reason provided";
        }
        
        return reason.stream()
                .map(ReasonEntry::toString)
                .reduce((a, b) -> a + "; " + b)
                .orElse("No reason");
    }

    @Override
    public String toString() {
        return String.format("%s (%s) -> %s (confidence: %.2f) - %s",
                mod_name, getModId(), classification, confidence, getReasonSummary());
    }
}