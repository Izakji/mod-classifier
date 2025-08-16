package com.izakji.mod_classifier;

import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public class ModFileManager {
    
    public static boolean renameModFile(Path originalPath, ModType modType) {
        if (modType == ModType.UNIVERSAL) {
            return true;
        }
        
        try {
            String originalName = originalPath.getFileName().toString();
            String newName = getNewFileName(originalName, modType);
            
            if (newName.equals(originalName)) {
                return true;
            }
            
            Path newPath = originalPath.getParent().resolve(newName);
            
            if (Files.exists(newPath)) {
                ModClassifier.LOGGER.warn("Target file already exists: {}", newPath);
                return false;
            }
            
            Files.move(originalPath, newPath, StandardCopyOption.ATOMIC_MOVE);
            ModClassifier.LOGGER.info("Renamed {} to {}", originalName, newName);
            
            return true;
            
        } catch (IOException e) {
            ModClassifier.LOGGER.error("Failed to rename mod file: {}", originalPath, e);
            return false;
        }
    }
    
    private static String getNewFileName(String originalName, ModType modType) {
        if (originalName.endsWith(".CLIENT") || originalName.endsWith(".SERVER")) {
            return originalName;
        }
        
        switch (modType) {
            case CLIENT_ONLY:
                return originalName + ".CLIENT";
            case SERVER_ONLY:
                return originalName + ".SERVER";
            case UNIVERSAL:
            default:
                return originalName;
        }
    }
    
    public static boolean shouldPreventLoading(ModType modType) {
        if (modType == ModType.UNIVERSAL || modType == ModType.UNKNOWN) {
            return false;
        }
        
        boolean isClient = FMLLoader.getDist().isClient();
        
        return (modType == ModType.CLIENT_ONLY && !isClient) || 
               (modType == ModType.SERVER_ONLY && isClient);
    }
}