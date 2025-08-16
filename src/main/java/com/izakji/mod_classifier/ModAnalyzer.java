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
import java.util.regex.Pattern;

public class ModAnalyzer {
    private static final Set<String> CLIENT_ONLY_CLASSES = Set.of(
        "net.minecraft.client",
        "net.neoforged.neoforge.client",
        "net.minecraftforge.client",
        "com.mojang.blaze3d",
        "net.minecraft.client.gui",
        "net.minecraft.client.renderer",
        "net.minecraft.client.model",
        "net.minecraft.client.sounds",
        "net.minecraft.client.particle",
        "net.minecraft.client.resources",
        "net.minecraft.client.multiplayer",
        "net.minecraft.client.player",
        "net.minecraft.client.animation"
    );

    private static final Set<String> SERVER_ONLY_CLASSES = Set.of(
        "net.minecraft.server.dedicated",
        "net.minecraft.server.commands",
        "net.minecraft.server.level",
        "net.minecraft.server.players"
    );

    private static final Set<String> SIDED_ANNOTATIONS = Set.of(
        "Lnet/neoforged/api/distmarker/OnlyIn;",
        "Lnet/minecraftforge/api/distmarker/OnlyIn;",
        "Lnet/neoforged/bus/api/EventBusSubscriber;",
        "Lnet/minecraftforge/eventbus/api/EventBusSubscriber;"
    );

    private static final Pattern DISPLAY_TEST_PATTERN = Pattern.compile(
        "ModLoadingContext\\..*registerExtensionPoint.*DisplayTest"
    );
    
    private static final Pattern IGNORE_SERVER_ONLY_PATTERN = Pattern.compile(
        "(IGNORESERVERONLY|NetworkConstants\\.IGNORESERVERONLY)"
    );
    
    private static final Pattern FML_ENVIRONMENT_PATTERN = Pattern.compile(
        "FMLEnvironment\\.dist\\s*==\\s*Dist\\.(CLIENT|DEDICATED_SERVER)"
    );

    private static class AnalysisResult {
        boolean hasDisplayTestRegistration = false;
        boolean isServerOnlyDisplayTest = false;
        boolean isClientOnlyDisplayTest = false;
        Set<String> clientOnlyEventSubscribers = new HashSet<>();
        Set<String> serverOnlyEventSubscribers = new HashSet<>();
        Set<String> ungatedClientReferences = new HashSet<>();
        Set<String> gatedClientReferences = new HashSet<>();
        Set<String> serverReferences = new HashSet<>();
        boolean hasFMLEnvironmentChecks = false;
        boolean hasClientDistChecks = false;
        boolean hasServerDistChecks = false;
    }

    public static ModType analyzeModType(ModFileInfo modFileInfo) {
        try {
            ModFile modFile = modFileInfo.getFile();
            Path jarPath = modFile.getFilePath();
            
            IModInfo modInfo = modFileInfo.getMods().get(0);
            String modId = modInfo.getModId();
            
            ModClassifier.LOGGER.debug("Analyzing mod: {} at {}", modId, jarPath);
            
            ModType result;
            boolean useEnhanced = safeGetBooleanConfig();
            if (useEnhanced) {
                // Use new hybrid evidence-based approach
                result = HybridModAnalyzer.analyzeModType(modFileInfo);
                ModClassifier.LOGGER.info("Classified mod {} as {} (hybrid analysis)", modId, result);
            } else {
                // Fallback to basic analysis
                result = analyzeJarFile(jarPath, modId);
                ModClassifier.LOGGER.info("Classified mod {} as {} (basic analysis)", modId, result);
            }
            
            return result;
        } catch (Exception e) {
            ModClassifier.LOGGER.error("Failed to analyze mod", e);
            return ModType.UNIVERSAL; // Safe fallback
        }
    }

    private static ModType analyzeJarFileEnhanced(Path jarPath, String modId) {
        if (jarPath == null || !jarPath.toFile().exists() || !jarPath.toFile().isFile()) {
            ModClassifier.LOGGER.debug("Skipping analysis for mod {} - invalid or non-existent jar path: {}", modId, jarPath);
            return ModType.UNKNOWN;
        }

        try (JarFile jarFile = new JarFile(jarPath.toFile())) {
            AnalysisResult analysis = performComprehensiveAnalysis(jarFile, modId);
            return classifyBasedOnAnalysis(analysis, modId);
        } catch (IOException e) {
            ModClassifier.LOGGER.debug("Could not analyze jar file for mod {}: {}", modId, e.getMessage());
            return ModType.UNKNOWN;
        }
    }
    
    private static AnalysisResult performComprehensiveAnalysis(JarFile jarFile, String modId) {
        AnalysisResult result = new AnalysisResult();
        
        // Step 1: Check for explicit DisplayTest registration
        analyzeDisplayTestRegistration(jarFile, result);
        
        // Step 2: Analyze manifest for metadata hints
        analyzeManifestEnhanced(jarFile, result, modId);
        
        // Step 3: Comprehensive bytecode analysis
        analyzeClassesEnhanced(jarFile, result);
        
        return result;
    }
    
    private static ModType classifyBasedOnAnalysis(AnalysisResult analysis, String modId) {
        ModClassifier.LOGGER.debug("Analysis results for {}: DisplayTest={}, ServerOnly={}, ClientOnly={}, " +
            "ClientSubscribers={}, ServerSubscribers={}, UngatedClient={}, GatedClient={}, Server={}",
            modId, analysis.hasDisplayTestRegistration, analysis.isServerOnlyDisplayTest, 
            analysis.isClientOnlyDisplayTest, analysis.clientOnlyEventSubscribers.size(),
            analysis.serverOnlyEventSubscribers.size(), analysis.ungatedClientReferences.size(),
            analysis.gatedClientReferences.size(), analysis.serverReferences.size());
        
        // Rule 1: Explicit DisplayTest registration (highest priority)
        if (analysis.hasDisplayTestRegistration) {
            if (analysis.isServerOnlyDisplayTest) {
                return ModType.SERVER_ONLY;
            }
            if (analysis.isClientOnlyDisplayTest) {
                return ModType.CLIENT_ONLY;
            }
        }
        
        // Rule 2: Event subscribers pattern analysis
        boolean hasClientSubscribers = !analysis.clientOnlyEventSubscribers.isEmpty();
        boolean hasServerSubscribers = !analysis.serverOnlyEventSubscribers.isEmpty();
        
        // Rule 3: Ungated client references (strong indicator of client-only)
        boolean hasUngatedClientCode = !analysis.ungatedClientReferences.isEmpty();
        
        // Rule 4: Properly gated client code with server code (universal)
        boolean hasGatedClientCode = !analysis.gatedClientReferences.isEmpty();
        boolean hasServerCode = !analysis.serverReferences.isEmpty() || hasServerSubscribers;
        
        // Classification logic
        if (hasUngatedClientCode) {
            // Ungated client references = client-only (or poorly structured)
            return ModType.CLIENT_ONLY;
        }
        
        if (hasClientSubscribers && !hasServerSubscribers && !hasServerCode) {
            // Only client-side event subscribers = client-only
            return ModType.CLIENT_ONLY;
        }
        
        if (hasServerSubscribers && !hasClientSubscribers && !hasGatedClientCode) {
            // Only server-side event subscribers with no client code = server-only
            return ModType.SERVER_ONLY;
        }
        
        if ((hasGatedClientCode || hasClientSubscribers) && hasServerCode) {
            // Properly gated client code with server code = universal
            return ModType.UNIVERSAL;
        }
        
        if (hasServerCode && !hasGatedClientCode && !hasClientSubscribers) {
            // Server code with no client references = server-only
            return ModType.SERVER_ONLY;
        }
        
        // Default to universal if we have mixed indicators or can't determine
        return ModType.UNIVERSAL;
    }

    private static void analyzeManifestEnhanced(JarFile jarFile, AnalysisResult result, String modId) {
        try {
            // Check NeoForge/Forge mods.toml
            JarEntry modsTomlEntry = jarFile.getJarEntry("META-INF/neoforge.mods.toml");
            if (modsTomlEntry == null) {
                modsTomlEntry = jarFile.getJarEntry("META-INF/mods.toml");
            }
            
            if (modsTomlEntry != null) {
                try (InputStream is = jarFile.getInputStream(modsTomlEntry)) {
                    String content = new String(is.readAllBytes());
                    
                    // Look for explicit dist declarations (rare but definitive)
                    if (content.contains("dist=\"CLIENT\"") || content.contains("dist = \"CLIENT\"")) {
                        result.isClientOnlyDisplayTest = true;
                        result.hasDisplayTestRegistration = true;
                    }
                    if (content.contains("dist=\"DEDICATED_SERVER\"") || content.contains("dist = \"DEDICATED_SERVER\"")) {
                        result.isServerOnlyDisplayTest = true;
                        result.hasDisplayTestRegistration = true;
                    }
                }
            }

            // Check Fabric mod.json (for Fabric mods)
            JarEntry fabricModJson = jarFile.getJarEntry("fabric.mod.json");
            if (fabricModJson != null) {
                try (InputStream is = jarFile.getInputStream(fabricModJson)) {
                    String content = new String(is.readAllBytes());
                    if (content.contains("\"client\"") && !content.contains("\"server\"")) {
                        result.isClientOnlyDisplayTest = true;
                        result.hasDisplayTestRegistration = true;
                    }
                    if (content.contains("\"server\"") && !content.contains("\"client\"")) {
                        result.isServerOnlyDisplayTest = true;
                        result.hasDisplayTestRegistration = true;
                    }
                }
            }
        } catch (IOException e) {
            ModClassifier.LOGGER.debug("Could not read manifest for mod: {}", modId, e);
        }
    }

    private static void analyzeDisplayTestRegistration(JarFile jarFile, AnalysisResult result) {
        jarFile.stream()
            .filter(entry -> entry.getName().endsWith(".class"))
            .limit(safeGetConfigValue())
            .forEach(entry -> {
                try (InputStream is = jarFile.getInputStream(entry)) {
                    ClassReader classReader = new ClassReader(is);
                    ClassVisitor visitor = new ClassVisitor(Opcodes.ASM9) {
                        @Override
                        public MethodVisitor visitMethod(int access, String name, String descriptor, 
                                                       String signature, String[] exceptions) {
                            return new MethodVisitor(Opcodes.ASM9) {
                                @Override
                                public void visitMethodInsn(int opcode, String owner, String methodName, 
                                                           String methodDesc, boolean isInterface) {
                                    // Look for ModLoadingContext.registerExtensionPoint calls
                                    if (owner.contains("ModLoadingContext") && methodName.equals("registerExtensionPoint")) {
                                        result.hasDisplayTestRegistration = true;
                                        ModClassifier.LOGGER.debug("Found DisplayTest registration in {}", entry.getName());
                                    }
                                }
                                
                                @Override
                                public void visitLdcInsn(Object value) {
                                    if (value instanceof String) {
                                        String str = (String) value;
                                        if (IGNORE_SERVER_ONLY_PATTERN.matcher(str).find()) {
                                            result.isServerOnlyDisplayTest = true;
                                        }
                                        // Client-only mods often use predicates that always return true
                                        if (str.contains("true") && result.hasDisplayTestRegistration) {
                                            result.isClientOnlyDisplayTest = true;
                                        }
                                    }
                                }
                            };
                        }
                    };
                    classReader.accept(visitor, ClassReader.SKIP_DEBUG);
                } catch (IOException e) {
                    ModClassifier.LOGGER.debug("Could not analyze DisplayTest in class: {}", entry.getName(), e);
                }
            });
    }
    
    private static void analyzeClassesEnhanced(JarFile jarFile, AnalysisResult result) {
        jarFile.stream()
            .filter(entry -> entry.getName().endsWith(".class"))
            .filter(entry -> !entry.getName().contains("$"))
            .limit(safeGetConfigValue())
            .forEach(entry -> {
                try (InputStream is = jarFile.getInputStream(entry)) {
                    ClassReader classReader = new ClassReader(is);
                    ClassVisitor visitor = new EnhancedClassVisitor(entry.getName(), result);
                    classReader.accept(visitor, ClassReader.SKIP_DEBUG);
                } catch (IOException e) {
                    ModClassifier.LOGGER.debug("Could not read class: {}", entry.getName(), e);
                }
            });
    }
    
    private static class EnhancedClassVisitor extends ClassVisitor {
        private final String className;
        private final AnalysisResult result;
        private boolean isInClientGatedMethod = false;
        private boolean isClientOnlyClass = false;
        private boolean isServerOnlyClass = false;
        
        public EnhancedClassVisitor(String className, AnalysisResult result) {
            super(Opcodes.ASM9);
            this.className = className;
            this.result = result;
        }
        
        @Override
        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            if (SIDED_ANNOTATIONS.contains(descriptor)) {
                return new AnnotationVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitEnum(String name, String descriptor, String value) {
                        if (name.equals("value") && descriptor.contains("Dist")) {
                            if (value.equals("CLIENT")) {
                                result.clientOnlyEventSubscribers.add(className);
                                isClientOnlyClass = true;
                            } else if (value.equals("DEDICATED_SERVER")) {
                                result.serverOnlyEventSubscribers.add(className);
                                isServerOnlyClass = true;
                            }
                        }
                    }
                };
            }
            return super.visitAnnotation(descriptor, visible);
        }
        
        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, 
                                       String signature, String[] exceptions) {
            return new MethodVisitor(Opcodes.ASM9) {
                private boolean methodHasFMLEnvironmentCheck = false;
                
                @Override
                public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                    if (SIDED_ANNOTATIONS.contains(desc)) {
                        return new AnnotationVisitor(Opcodes.ASM9) {
                            @Override
                            public void visitEnum(String name, String descriptor, String value) {
                                if (name.equals("value") && descriptor.contains("Dist")) {
                                    if (value.equals("CLIENT")) {
                                        isInClientGatedMethod = true;
                                    }
                                }
                            }
                        };
                    }
                    return super.visitAnnotation(desc, visible);
                }
                
                @Override
                public void visitMethodInsn(int opcode, String owner, String methodName, 
                                           String methodDesc, boolean isInterface) {
                    // Check for FMLEnvironment.dist calls
                    if (owner.contains("FMLEnvironment") && methodName.equals("dist")) {
                        methodHasFMLEnvironmentCheck = true;
                        result.hasFMLEnvironmentChecks = true;
                    }
                    
                    // Check class references in method calls
                    checkEnhancedClassReferences(owner, methodHasFMLEnvironmentCheck || isInClientGatedMethod);
                }
                
                @Override
                public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
                    checkEnhancedClassReferences(owner, methodHasFMLEnvironmentCheck || isInClientGatedMethod);
                }
                
                @Override
                public void visitTypeInsn(int opcode, String type) {
                    checkEnhancedClassReferences(type, methodHasFMLEnvironmentCheck || isInClientGatedMethod);
                }
            };
        }
        
        @Override
        public void visit(int version, int access, String name, String signature, 
                        String superName, String[] interfaces) {
            // Check class hierarchy for client/server references
            if (superName != null) {
                checkEnhancedClassReferences(superName, isClientOnlyClass);
            }
            if (interfaces != null) {
                Arrays.stream(interfaces).forEach(iface -> 
                    checkEnhancedClassReferences(iface, isClientOnlyClass));
            }
        }
        
        private void checkEnhancedClassReferences(String className, boolean isGated) {
            if (className == null) return;
            
            String dottedName = className.replace('/', '.');
            
            // Check for client-only class references
            for (String clientClass : CLIENT_ONLY_CLASSES) {
                if (dottedName.startsWith(clientClass)) {
                    if (isGated || isClientOnlyClass) {
                        result.gatedClientReferences.add(className);
                    } else {
                        result.ungatedClientReferences.add(className);
                    }
                    return;
                }
            }
            
            // Check for server-only class references
            for (String serverClass : SERVER_ONLY_CLASSES) {
                if (dottedName.startsWith(serverClass)) {
                    result.serverReferences.add(className);
                    return;
                }
            }
        }
    }

    // Keep the old analysis method as fallback
    private static ModType analyzeJarFile(Path jarPath, String modId) {
        if (jarPath == null || !jarPath.toFile().exists() || !jarPath.toFile().isFile()) {
            ModClassifier.LOGGER.debug("Skipping analysis for mod {} - invalid or non-existent jar path: {}", modId, jarPath);
            return ModType.UNKNOWN;
        }

        try (JarFile jarFile = new JarFile(jarPath.toFile())) {
            ModType manifestResult = analyzeManifest(jarFile, modId);
            if (manifestResult != ModType.UNKNOWN) {
                return manifestResult;
            }

            ModType classResult = analyzeClasses(jarFile);
            if (classResult != ModType.UNKNOWN) {
                return classResult;
            }

            return ModType.UNIVERSAL;
        } catch (IOException e) {
            ModClassifier.LOGGER.debug("Could not analyze jar file for mod {}: {}", modId, e.getMessage());
            return ModType.UNKNOWN;
        }
    }
    
    private static ModType analyzeManifest(JarFile jarFile, String modId) {
        try {
            JarEntry modsTomlEntry = jarFile.getJarEntry("META-INF/neoforge.mods.toml");
            if (modsTomlEntry != null) {
                try (InputStream is = jarFile.getInputStream(modsTomlEntry)) {
                    String content = new String(is.readAllBytes());
                    
                    if (content.contains("dist=\"CLIENT\"") || content.contains("dist = \"CLIENT\"")) {
                        return ModType.CLIENT_ONLY;
                    }
                    if (content.contains("dist=\"DEDICATED_SERVER\"") || content.contains("dist = \"DEDICATED_SERVER\"")) {
                        return ModType.SERVER_ONLY;
                    }
                }
            }

            JarEntry fabricModJson = jarFile.getJarEntry("fabric.mod.json");
            if (fabricModJson != null) {
                try (InputStream is = jarFile.getInputStream(fabricModJson)) {
                    String content = new String(is.readAllBytes());
                    if (content.contains("\"client\"") && !content.contains("\"server\"")) {
                        return ModType.CLIENT_ONLY;
                    }
                    if (content.contains("\"server\"") && !content.contains("\"client\"")) {
                        return ModType.SERVER_ONLY;
                    }
                }
            }
        } catch (IOException e) {
            ModClassifier.LOGGER.debug("Could not read manifest for mod: {}", modId, e);
        }
        
        return ModType.UNKNOWN;
    }
    
    private static ModType analyzeClasses(JarFile jarFile) {
        Set<String> clientOnlyImports = new HashSet<>();
        Set<String> serverOnlyImports = new HashSet<>();

        jarFile.stream()
            .filter(entry -> entry.getName().endsWith(".class"))
            .filter(entry -> !entry.getName().contains("$"))
            .limit(safeGetConfigValue())
            .forEach(entry -> {
                try (InputStream is = jarFile.getInputStream(entry)) {
                    ClassReader classReader = new ClassReader(is);
                    ClassVisitor visitor = new ClassVisitor(Opcodes.ASM9) {
                        @Override
                        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                            if (SIDED_ANNOTATIONS.contains(descriptor)) {
                                if (descriptor.contains("CLIENT") || 
                                    (descriptor.contains("OnlyIn") && checkForClientDist())) {
                                    clientOnlyImports.add(entry.getName());
                                }
                            }
                            return super.visitAnnotation(descriptor, visible);
                        }

                        @Override
                        public void visit(int version, int access, String name, String signature, 
                                        String superName, String[] interfaces) {
                            checkClassReferences(name, clientOnlyImports, serverOnlyImports);
                            if (superName != null) {
                                checkClassReferences(superName, clientOnlyImports, serverOnlyImports);
                            }
                            if (interfaces != null) {
                                Arrays.stream(interfaces).forEach(iface -> 
                                    checkClassReferences(iface, clientOnlyImports, serverOnlyImports));
                            }
                        }

                        @Override
                        public FieldVisitor visitField(int access, String name, String descriptor, 
                                                     String signature, Object value) {
                            checkDescriptor(descriptor, clientOnlyImports, serverOnlyImports);
                            return super.visitField(access, name, descriptor, signature, value);
                        }

                        @Override
                        public MethodVisitor visitMethod(int access, String name, String descriptor, 
                                                       String signature, String[] exceptions) {
                            checkDescriptor(descriptor, clientOnlyImports, serverOnlyImports);
                            return new MethodVisitor(Opcodes.ASM9, 
                                super.visitMethod(access, name, descriptor, signature, exceptions)) {
                                @Override
                                public void visitMethodInsn(int opcode, String owner, String name, 
                                                           String descriptor, boolean isInterface) {
                                    checkClassReferences(owner, clientOnlyImports, serverOnlyImports);
                                    super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                                }
                            };
                        }
                    };
                    
                    classReader.accept(visitor, ClassReader.SKIP_DEBUG);
                } catch (IOException e) {
                    ModClassifier.LOGGER.debug("Could not read class: {}", entry.getName(), e);
                }
            });

        if (!clientOnlyImports.isEmpty() && serverOnlyImports.isEmpty()) {
            return ModType.CLIENT_ONLY;
        }
        if (clientOnlyImports.isEmpty() && !serverOnlyImports.isEmpty()) {
            return ModType.SERVER_ONLY;
        }
        
        return ModType.UNKNOWN;
    }
    
    private static void checkClassReferences(String className, Set<String> clientRefs, Set<String> serverRefs) {
        if (className == null) return;
        
        String dottedName = className.replace('/', '.');
        
        for (String clientClass : CLIENT_ONLY_CLASSES) {
            if (dottedName.startsWith(clientClass)) {
                clientRefs.add(className);
                return;
            }
        }
        
        for (String serverClass : SERVER_ONLY_CLASSES) {
            if (dottedName.startsWith(serverClass)) {
                serverRefs.add(className);
                return;
            }
        }
    }

    private static void checkDescriptor(String descriptor, Set<String> clientRefs, Set<String> serverRefs) {
        if (descriptor == null) return;
        
        Type type = Type.getType(descriptor);
        if (type.getSort() == Type.OBJECT) {
            checkClassReferences(type.getInternalName(), clientRefs, serverRefs);
        } else if (type.getSort() == Type.METHOD) {
            for (Type argType : type.getArgumentTypes()) {
                if (argType.getSort() == Type.OBJECT) {
                    checkClassReferences(argType.getInternalName(), clientRefs, serverRefs);
                }
            }
            Type returnType = type.getReturnType();
            if (returnType.getSort() == Type.OBJECT) {
                checkClassReferences(returnType.getInternalName(), clientRefs, serverRefs);
            }
        }
    }

    private static int safeGetConfigValue() {
        try {
            return Config.MAX_CLASSES_TO_SCAN.get();
        } catch (Exception e) {
            return 50; // default value
        }
    }
    
    private static boolean safeGetBooleanConfig() {
        try {
            return Config.USE_ENHANCED_ANALYSIS.get();
        } catch (Exception e) {
            return true; // default to enhanced analysis
        }
    }

    private static boolean checkForClientDist() {
        return FMLLoader.getDist() == Dist.CLIENT;
    }
}