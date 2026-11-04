import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipFile;

/** Checks compiled injection descriptors against the installed Iris bytecode, without loading Minecraft. */
public final class ShadowHookProbe {
    public static void main(String[] args) throws Exception {
        ClassNode iris = new ClassNode();
        try (ZipFile zip = new ZipFile(args[0])) {
            new ClassReader(zip.getInputStream(zip.getEntry("net/irisshaders/iris/shadows/ShadowRenderer.class"))).accept(iris, 0);
            check(iris.fields.stream().anyMatch(field -> field.name.equals("targets")
                    && field.desc.equals("Lnet/irisshaders/iris/shadows/ShadowRenderTargets;")), "renderer-owned target field");
            checkMethod(zip, "net/irisshaders/iris/shadows/ShadowRenderTargets", "getDepthSourceFb",
                    "()Lnet/irisshaders/iris/gl/framebuffer/GlFramebuffer;");
            checkMethod(zip, "net/irisshaders/iris/shadows/ShadowRenderTargets", "getDepthTexture",
                    "()Lnet/irisshaders/iris/targets/DepthTexture;");
            checkMethod(zip, "net/irisshaders/iris/shadows/ShadowRenderTargets", "getResolution", "()I");
            checkMethod(zip, "net/irisshaders/iris/gl/framebuffer/GlFramebuffer", "getId", "()I");
            checkMethod(zip, "net/irisshaders/iris/targets/DepthTexture", "getTextureId", "()I");
        }
        MethodNode shadow = iris.methods.stream().filter(method -> method.name.equals("renderShadows")).findFirst().orElseThrow();
        int index = 0, capture = -1, firstEntity = Integer.MAX_VALUE, count = 0;
        String terrainTarget = null;
        for (var instruction : shadow.instructions) {
            if (instruction instanceof MethodInsnNode call) {
                if (call.name.equals("invokeRenderSectionLayer") && ++count == 3) {
                    capture = index;
                    terrainTarget = "L" + call.owner + ";" + call.name + call.desc;
                }
                if (call.name.equals("renderEntities") || call.name.equals("renderPlayerEntity"))
                    firstEntity = Math.min(firstEntity, index);
            }
            index++;
        }
        check(capture >= 0 && capture < firstEntity, "third terrain draw must precede all entity submission");
        check(iris.methods.stream().anyMatch(method -> method.name.equals("destroy")), "renderer destroy hook");
        for (String path : List.of(
                "fabric/build/classes/java/main/com/shiroha/mmdskin/mixin/fabric/compat/IrisShadowEvaluationMixin.class",
                "neoforge/build/classes/java/main/com/shiroha/mmdskin/mixin/neoforge/compat/IrisShadowEvaluationMixin.class")) {
            ClassNode mixin = new ClassNode();
            new ClassReader(Files.readAllBytes(Path.of(path))).accept(mixin, 0);
            MethodNode method = mixin.methods.stream().filter(entry -> entry.name.equals("mmdskin$captureTerrainShadow")).findFirst().orElseThrow();
            AnnotationNode injection = method.visibleAnnotations.stream().filter(annotation -> annotation.desc.endsWith("/Inject;")).findFirst().orElseThrow();
            @SuppressWarnings("unchecked")
            List<String> targets = (List<String>) value(injection, "method");
            check(targets.contains("renderShadows"), "injection target method");
            @SuppressWarnings("unchecked")
            List<AnnotationNode> locations = (List<AnnotationNode>) value(injection, "at");
            String expected = terrainTarget;
            check(locations.stream().anyMatch(location -> expected.equals(value(location, "target"))
                    && Integer.valueOf(2).equals(value(location, "ordinal"))
                    && value(location, "shift") instanceof String[] shift && shift[1].equals("AFTER")), "runtime terrain descriptor and ordinal: " + path);
            System.out.println("PASS shadow hook descriptor: " + path + " -> " + terrainTarget);
        }
        System.out.println("PASS installed Iris hook ordering: terrain capture before entities; lifecycle methods exist (bytecode check, not a game launch)");
        System.out.println("PASS installed Iris owned-source API: targets -> source framebuffer, main depth texture, shadow resolution");
    }

    private static void checkMethod(ZipFile zip, String type, String name, String descriptor) throws Exception {
        ClassNode node = new ClassNode();
        new ClassReader(zip.getInputStream(zip.getEntry(type + ".class"))).accept(node, 0);
        check(node.methods.stream().anyMatch(method -> method.name.equals(name) && method.desc.equals(descriptor)
                && (method.access & org.objectweb.asm.Opcodes.ACC_PUBLIC) != 0), "shadow source API: " + type + "." + name);
    }

    private static Object value(AnnotationNode annotation, String key) {
        for (int i = 0; i < annotation.values.size(); i += 2)
            if (annotation.values.get(i).equals(key)) return annotation.values.get(i + 1);
        return null;
    }

    private static void check(boolean value, String detail) {
        if (!value) throw new AssertionError(detail);
    }
}
