import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipFile;

/** Checks compiled injection descriptors against the installed Iris bytecode, without loading Minecraft. */
public final class ShadowHookProbe {
    private static final String WATER_TRANSFORM = "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;"
            + "Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;"
            + "Lnet/irisshaders/iris/gl/blending/AlphaTest;"
            + "Lit/unimi/dsi/fastutil/objects/Object2ObjectMap;)Ljava/util/Map;";
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
            checkMethod(zip, "net/irisshaders/iris/shadows/ShadowRenderTargets", "getColorTextureId", "(I)I");
            checkMethod(zip, "net/irisshaders/iris/shadows/ShadowRenderTargets", "isFlipped", "(I)Z");
            checkMethod(zip, "net/irisshaders/iris/gl/framebuffer/GlFramebuffer", "getId", "()I");
            checkMethod(zip, "net/irisshaders/iris/targets/DepthTexture", "getTextureId", "()I");
            checkMethod(zip, "net/irisshaders/iris/shaderpack/programs/ProgramSet", "get",
                    "(Lnet/irisshaders/iris/shaderpack/loading/ProgramId;)Ljava/util/Optional;");
            checkMethod(zip, "net/irisshaders/iris/pipeline/transform/TransformPatcher", "patchSodium",
                    WATER_TRANSFORM);
            checkMethod(zip, "net/irisshaders/iris/pipeline/programs/SodiumShader", "setupState", "()V");
            checkMethod(zip, "net/irisshaders/iris/pipeline/programs/SodiumShader", "resetState", "()V");
            ClassNode pipeline = new ClassNode();
            new ClassReader(zip.getInputStream(zip.getEntry(
                    "net/irisshaders/iris/pipeline/IrisRenderingPipeline.class"))).accept(pipeline, 0);
            boolean nativeShadowSource = false;
            for (MethodNode constructor : pipeline.methods) {
                if (!constructor.name.equals("<init>")) continue;
                String selected = null;
                for (var instruction : constructor.instructions) {
                    if (instruction instanceof FieldInsnNode field && field.owner.endsWith("/ProgramId"))
                        selected = field.name;
                    if (instruction instanceof MethodInsnNode call && call.name.equals("<init>")
                            && call.owner.endsWith("/ShadowRenderer")) {
                        check("ShadowSolid".equals(selected), "native shadow renderer uses ShadowSolid fallback");
                        nativeShadowSource = true;
                    }
                }
            }
            check(nativeShadowSource, "native shadow source selection inspected");
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
        check(iris.fields.stream().anyMatch(field -> field.name.equals("shouldRenderTranslucent") && field.desc.equals("Z")),
                "native translucent shadow option");
        check(iris.fields.stream().anyMatch(field -> field.name.equals("packHasVoxelization") && field.desc.equals("Z")),
                "image/voxelization replay rejection");
        check(count == 4, "original translucent terrain draw must remain after entities");
        check(iris.methods.stream().anyMatch(method -> method.name.equals("destroy")), "renderer destroy hook");
        for (String path : List.of(
                "fabric/build/classes/java/main/com/shiroha/mmdskin/mixin/fabric/compat/IrisShadowEvaluationMixin.class",
                "neoforge/build/classes/java/main/com/shiroha/mmdskin/mixin/neoforge/compat/IrisShadowEvaluationMixin.class")) {
            ClassNode mixin = new ClassNode();
            new ClassReader(Files.readAllBytes(Path.of(path))).accept(mixin, 0);
            MethodNode method = mixin.methods.stream().filter(entry -> entry.name.equals("mmdskin$captureTerrainShadow")).findFirst().orElseThrow();
            check(method.desc.startsWith("(Ljava/lang/Object;Lnet/minecraft/client/Camera;"),
                    "replay hook receives the actual shadow camera and level accessor");
            boolean replay = false;
            for (var instruction : method.instructions)
                if (instruction instanceof MethodInsnNode call && call.name.equals("captureTransmission")) replay = true;
            check(replay, "compiled hook must actually invoke terrain transmission capture");
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
        for (String platform : List.of("fabric", "neoforge")) {
            String base = platform + "/build/classes/java/main/com/shiroha/mmdskin/mixin/"
                    + platform + "/compat/";
            ClassNode sourceMixin = readClass(base + "IrisWaterShaderMixin.class");
            MethodNode source = sourceMixin.methods.stream().filter(method ->
                    method.name.equals("mmdskin$waterSource")).findFirst().orElseThrow();
            check(source.desc.equals("(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;"
                            + "Ljava/lang/String;Ljava/lang/String;Ljava/lang/Object;"
                            + "Lit/unimi/dsi/fastutil/objects/Object2ObjectMap;"
                            + "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;)V"),
                    "compiled 1.21 water transform callback descriptor: " + platform);
            checkInjection(source, "patchSodium" + WATER_TRANSFORM, "RETURN");
            ClassNode stateMixin = readClass(base + "IrisWaterStateMixin.class");
            for (String[] hook : new String[][]{
                    {"mmdskin$closePreviousWater", "setupState", "HEAD"},
                    {"mmdskin$beginWater", "setupState", "RETURN"},
                    {"mmdskin$endWater", "resetState", "HEAD"}}) {
                MethodNode state = stateMixin.methods.stream().filter(method ->
                        method.name.equals(hook[0])).findFirst().orElseThrow();
                checkInjection(state, hook[1], hook[2]);
            }
            System.out.println("PASS compiled water transform/setup/reset hooks: " + platform);
        }
        System.out.println("PASS installed Iris hook ordering: terrain capture before entities; lifecycle methods exist (bytecode check, not a game launch)");
        System.out.println("PASS installed Iris owned-source API: targets -> source framebuffer, main depth texture, shadow resolution");
    }

    private static ClassNode readClass(String path) throws Exception {
        ClassNode node = new ClassNode();
        new ClassReader(Files.readAllBytes(Path.of(path))).accept(node, 0);
        return node;
    }

    @SuppressWarnings("unchecked")
    private static void checkInjection(MethodNode method, String target, String at) {
        AnnotationNode inject = method.visibleAnnotations.stream().filter(annotation ->
                annotation.desc.endsWith("/Inject;")).findFirst().orElseThrow();
        check(((List<String>) value(inject, "method")).contains(target), "compiled injection target: " + target);
        check(((List<AnnotationNode>) value(inject, "at")).stream().anyMatch(location ->
                at.equals(value(location, "value"))), "compiled injection location: " + target + "/" + at);
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
