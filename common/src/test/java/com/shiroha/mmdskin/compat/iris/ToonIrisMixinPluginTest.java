package com.shiroha.mmdskin.compat.iris;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import static org.junit.jupiter.api.Assertions.*;

class ToonIrisMixinPluginTest {
    @Test void fabricIntermediaryAndMojmapEntriesAreBothRecognizedWithoutAmbiguity() {
        var node = new ClassNode();
        node.methods.add(new MethodNode(Opcodes.ACC_PUBLIC, "method_34586", "()V", null, null));
        assertTrue(ToonIrisMixinPlugin.hasShaderEntry(node));
        node.methods.add(new MethodNode(Opcodes.ACC_PUBLIC, "apply", "()V", null, null));
        assertFalse(ToonIrisMixinPlugin.hasShaderEntry(node));
        node.methods.removeFirst();
        assertTrue(ToonIrisMixinPlugin.hasShaderEntry(node));
    }

    @Test void changedOrOverloadedEntriesAreRejectedBeforeMixinApplication() {
        var node = new ClassNode();
        assertFalse(ToonIrisMixinPlugin.hasVoidEntries(node, java.util.List.of("apply")));
        node.methods.add(new MethodNode(Opcodes.ACC_PUBLIC, "apply", "()V", null, null));
        assertTrue(ToonIrisMixinPlugin.hasVoidEntries(node, java.util.List.of("apply")));
        node.methods.add(new MethodNode(Opcodes.ACC_PUBLIC, "apply", "()Z", null, null));
        assertFalse(ToonIrisMixinPlugin.hasVoidEntries(node, java.util.List.of("apply")));
    }

    @Test void handlerDefinitionAloneDoesNotVerifyAnOptionalHook() {
        var node = new ClassNode(); node.name = "fixture/Pipeline";
        node.methods.add(new MethodNode(Opcodes.ACC_PUBLIC, "finalizeLevelRendering", "()V", null, null));
        node.methods.add(new MethodNode(Opcodes.ACC_PRIVATE, "handler$mmdskin$toonAfterFinal", "()V", null, null));
        assertFalse(ToonIrisMixinPlugin.calls(node, "finalizeLevelRendering", "mmdskin$toonAfterFinal"));
    }

    @Test void actualInjectedCallMustMatchTheTargetOwnerAndMethod() {
        var node = new ClassNode(); node.name = "fixture/Pipeline";
        var method = new MethodNode(Opcodes.ACC_PUBLIC, "finalizeLevelRendering", "()V", null, null);
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "fixture/WrongOwner", "handler$mmdskin$toonAfterFinal", "()V", false));
        node.methods.add(method);
        assertFalse(ToonIrisMixinPlugin.calls(node, "finalizeLevelRendering", "mmdskin$toonAfterFinal"));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, node.name, "handler$xyz$mmdskin$toonAfterFinal", "()V", false));
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        assertTrue(ToonIrisMixinPlugin.calls(node, "finalizeLevelRendering", "mmdskin$toonAfterFinal"));
        assertFalse(ToonIrisMixinPlugin.calls(node, "beginLevelRendering", "mmdskin$toonAfterFinal"));
        assertFalse(ToonIrisMixinPlugin.calls(node, "finalizeLevelRendering", "mmdskin$toonBeforeFinal"));
    }
}
