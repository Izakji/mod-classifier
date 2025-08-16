package com.izakji.mod_classifier;

import org.objectweb.asm.*;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public class ClassAnalysisVisitor extends ClassVisitor {
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
    
    private static final Set<String> EVENT_SUBSCRIBER_ANNOTATIONS = Set.of(
        "Lnet/neoforged/bus/api/EventBusSubscriber;",
        "Lnet/minecraftforge/eventbus/api/EventBusSubscriber;"
    );
    
    private final String className;
    private final Set<String> clientReferences = new HashSet<>();
    private final Set<String> serverReferences = new HashSet<>();
    private final Set<String> clientSubscribers = new HashSet<>();
    private final Set<String> serverSubscribers = new HashSet<>();
    private boolean hasUngatedClientReferences = false;
    private boolean isClientOnlyClass = false;
    private boolean isServerOnlyClass = false;
    
    public ClassAnalysisVisitor(String className) {
        super(Opcodes.ASM9);
        this.className = className;
    }
    
    @Override
    public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
        if (EVENT_SUBSCRIBER_ANNOTATIONS.contains(descriptor)) {
            return new AnnotationVisitor(Opcodes.ASM9) {
                @Override
                public void visitEnum(String name, String descriptor, String value) {
                    if (name.equals("value") && descriptor.contains("Dist")) {
                        if (value.equals("CLIENT")) {
                            clientSubscribers.add(className);
                            isClientOnlyClass = true;
                        } else if (value.equals("DEDICATED_SERVER")) {
                            serverSubscribers.add(className);
                            isServerOnlyClass = true;
                        }
                    }
                }
            };
        }
        return super.visitAnnotation(descriptor, visible);
    }
    
    @Override
    public void visit(int version, int access, String name, String signature, 
                     String superName, String[] interfaces) {
        // Check class hierarchy for client/server references
        if (superName != null) {
            checkClassReference(superName, false);
        }
        if (interfaces != null) {
            Arrays.stream(interfaces).forEach(iface -> checkClassReference(iface, false));
        }
        super.visit(version, access, name, signature, superName, interfaces);
    }
    
    @Override
    public FieldVisitor visitField(int access, String name, String descriptor, 
                                  String signature, Object value) {
        checkDescriptorForReferences(descriptor, false);
        return super.visitField(access, name, descriptor, signature, value);
    }
    
    @Override
    public MethodVisitor visitMethod(int access, String name, String descriptor, 
                                   String signature, String[] exceptions) {
        checkDescriptorForReferences(descriptor, false);
        
        return new MethodVisitor(Opcodes.ASM9) {
            private boolean hasDistCheck = false;
            private boolean isClientGatedMethod = false;
            
            @Override
            public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                if (EVENT_SUBSCRIBER_ANNOTATIONS.contains(desc)) {
                    return new AnnotationVisitor(Opcodes.ASM9) {
                        @Override
                        public void visitEnum(String name, String descriptor, String value) {
                            if (name.equals("value") && descriptor.contains("Dist")) {
                                if (value.equals("CLIENT")) {
                                    isClientGatedMethod = true;
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
                // Check for FMLEnvironment.dist calls (indicates gating)
                if (owner.contains("FMLEnvironment") && methodName.equals("dist")) {
                    hasDistCheck = true;
                }
                
                // Check the method call target for client/server references
                boolean isGated = hasDistCheck || isClientGatedMethod || isClientOnlyClass;
                checkClassReference(owner, isGated);
                
                super.visitMethodInsn(opcode, owner, methodName, methodDesc, isInterface);
            }
            
            @Override
            public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
                boolean isGated = hasDistCheck || isClientGatedMethod || isClientOnlyClass;
                checkClassReference(owner, isGated);
                super.visitFieldInsn(opcode, owner, name, descriptor);
            }
            
            @Override
            public void visitTypeInsn(int opcode, String type) {
                boolean isGated = hasDistCheck || isClientGatedMethod || isClientOnlyClass;
                checkClassReference(type, isGated);
                super.visitTypeInsn(opcode, type);
            }
            
            @Override
            public void visitLdcInsn(Object value) {
                // Look for string patterns that might indicate Display Test registration
                if (value instanceof String) {
                    String str = (String) value;
                    if (str.contains("IGNORESERVERONLY") || str.contains("NetworkConstants")) {
                        // This is likely server-only DisplayTest registration
                        serverReferences.add("DisplayTest.IGNORESERVERONLY");
                    }
                }
                super.visitLdcInsn(value);
            }
        };
    }
    
    private void checkClassReference(String className, boolean isGated) {
        if (className == null) return;
        
        String dottedName = className.replace('/', '.');
        
        // Check for client-only class references
        for (String clientClass : CLIENT_ONLY_CLASSES) {
            if (dottedName.startsWith(clientClass)) {
                clientReferences.add(className);
                if (!isGated && !isClientOnlyClass) {
                    hasUngatedClientReferences = true;
                }
                return;
            }
        }
        
        // Check for server-only class references
        for (String serverClass : SERVER_ONLY_CLASSES) {
            if (dottedName.startsWith(serverClass)) {
                serverReferences.add(className);
                return;
            }
        }
    }
    
    private void checkDescriptorForReferences(String descriptor, boolean isGated) {
        if (descriptor == null) return;
        
        try {
            Type type = Type.getType(descriptor);
            if (type.getSort() == Type.OBJECT) {
                checkClassReference(type.getInternalName(), isGated);
            } else if (type.getSort() == Type.METHOD) {
                for (Type argType : type.getArgumentTypes()) {
                    if (argType.getSort() == Type.OBJECT) {
                        checkClassReference(argType.getInternalName(), isGated);
                    }
                }
                Type returnType = type.getReturnType();
                if (returnType.getSort() == Type.OBJECT) {
                    checkClassReference(returnType.getInternalName(), isGated);
                }
            }
        } catch (Exception e) {
            // Invalid descriptor - skip
        }
    }
    
    public Set<String> getClientReferences() {
        return new HashSet<>(clientReferences);
    }
    
    public Set<String> getServerReferences() {
        return new HashSet<>(serverReferences);
    }
    
    public Set<String> getClientSubscribers() {
        return new HashSet<>(clientSubscribers);
    }
    
    public Set<String> getServerSubscribers() {
        return new HashSet<>(serverSubscribers);
    }
    
    public boolean hasUngatedClientReferences() {
        return hasUngatedClientReferences;
    }
    
    public boolean isClientOnlyClass() {
        return isClientOnlyClass;
    }
    
    public boolean isServerOnlyClass() {
        return isServerOnlyClass;
    }
}