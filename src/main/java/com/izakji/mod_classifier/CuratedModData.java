package com.izakji.mod_classifier;

import java.util.List;

public class CuratedModData {
    private List<ModEntry> data;

    public List<ModEntry> getData() {
        return data;
    }

    public void setData(List<ModEntry> data) {
        this.data = data;
    }

    public static class ModEntry {
        private String name;
        private String side;
        private List<String> type;
        private String modId;
        private String version;
        private List<String> incompatibleMods;
        private String source;
        private String website;
        private String contributor;
        private String observations;

        // Getters and setters
        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getSide() {
            return side;
        }

        public void setSide(String side) {
            this.side = side;
        }

        public List<String> getType() {
            return type;
        }

        public void setType(List<String> type) {
            this.type = type;
        }

        public String getModId() {
            return modId;
        }

        public void setModId(String modId) {
            this.modId = modId;
        }

        public String getVersion() {
            return version;
        }

        public void setVersion(String version) {
            this.version = version;
        }

        public List<String> getIncompatibleMods() {
            return incompatibleMods;
        }

        public void setIncompatibleMods(List<String> incompatibleMods) {
            this.incompatibleMods = incompatibleMods;
        }

        public String getSource() {
            return source;
        }

        public void setSource(String source) {
            this.source = source;
        }

        public String getWebsite() {
            return website;
        }

        public void setWebsite(String website) {
            this.website = website;
        }

        public String getContributor() {
            return contributor;
        }

        public void setContributor(String contributor) {
            this.contributor = contributor;
        }

        public String getObservations() {
            return observations;
        }

        public void setObservations(String observations) {
            this.observations = observations;
        }

        /**
         * Converts the side string to ModType enum
         */
        public ModType toModType() {
            if (side == null || side.trim().isEmpty()) {
                return ModType.UNKNOWN;
            }
            
            switch (side.trim()) {
                case "Client":
                    return ModType.CLIENT_ONLY;
                case "Server":
                    return ModType.SERVER_ONLY;
                case "Universal":
                    return ModType.UNIVERSAL;
                case "Unknown":
                default:
                    return ModType.UNKNOWN;
            }
        }
    }
}