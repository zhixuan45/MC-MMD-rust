package com.shiroha.mmdskin.model.runtime;

import com.shiroha.mmdskin.asset.catalog.ModelCatalogEntry;
import com.shiroha.mmdskin.model.port.ModelRuntimeAccessPort;
import com.shiroha.mmdskin.scene.client.SceneModelCatalog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ModelRepositorySceneTest {
    @Test
    void sceneLoadsFromItsOwnCatalogWithoutCharacterAnimationOrSettings(@TempDir Path root) throws Exception {
        // Seed the scanned catalog and animation paths so the test needs no Minecraft client or GPU.
        SceneModelCatalog catalog = SceneModelCatalog.getInstance();
        Field models = field(SceneModelCatalog.class, "cachedModels");
        Field timestamp = field(SceneModelCatalog.class, "cacheTimestamp");
        Field defaultAnimations = field(AnimationLibrary.class, "defaultAnimDir");
        Field customAnimations = field(AnimationLibrary.class, "customAnimDir");
        Object previousModels = models.get(catalog);
        Object previousTimestamp = timestamp.get(catalog);
        Object previousDefault = defaultAnimations.get(null);
        Object previousCustom = customAnimations.get(null);
        try {
            defaultAnimations.set(null, root.toString());
            customAnimations.set(null, root.toString());
            for (String format : List.of("pmx", "pmd", "vrm")) {
                Path folder = Files.createDirectories(root.resolve("SceneModel/stage-" + format));
                Path file = Files.writeString(folder.resolve("stage." + format), "native load is stubbed");
                Files.writeString(folder.resolve("model.properties"), "source=scene\n");
                ModelCatalogEntry entry = new ModelCatalogEntry(folder.getFileName().toString(),
                        folder.toString(), file.toString(), file.getFileName().toString(),
                        format.equals("pmd"), format.equals("vrm"), Files.size(file));
                models.set(catalog, List.of(entry));
                timestamp.set(catalog, System.currentTimeMillis());
                assertSceneLoad(entry, format);
            }
        } finally {
            models.set(catalog, previousModels);
            timestamp.set(catalog, previousTimestamp);
            defaultAnimations.set(null, previousDefault);
            customAnimations.set(null, previousCustom);
        }
    }

    private void assertSceneLoad(ModelCatalogEntry entry, String format) {
        AtomicInteger resets = new AtomicInteger();
        AtomicInteger poseConfigurations = new AtomicInteger();
        AtomicInteger disposals = new AtomicInteger();
        AtomicReference<String> nativeLoader = new AtomicReference<>();
        ModelInstance instance = (ModelInstance) Proxy.newProxyInstance(ModelInstance.class.getClassLoader(),
                new Class<?>[]{ModelInstance.class}, (proxy, method, args) -> {
                    return switch (method.getName()) {
                        case "getModelHandle" -> 42L;
                        case "getModelDir" -> entry.getFolderPath();
                        case "resetPhysics" -> { resets.incrementAndGet(); yield null; }
                        case "dispose" -> { disposals.incrementAndGet(); yield null; }
                        default -> throw new AssertionError("Scene must not receive character animation: " + method.getName());
                    };
                });
        ModelRuntimeAccessPort port = (ModelRuntimeAccessPort) Proxy.newProxyInstance(
                ModelRuntimeAccessPort.class.getClassLoader(), new Class<?>[]{ModelRuntimeAccessPort.class},
                (proxy, method, args) -> {
                    return switch (method.getName()) {
                        case "isRenderingShadows" -> false;
                        case "loadPmxModel", "loadPmdModel", "loadVrmModel" -> {
                            assertEquals(entry.getModelFilePath(), args[0]);
                            assertEquals(entry.getFolderPath(), args[1]);
                            nativeLoader.set(method.getName());
                            yield 42L;
                        }
                        case "createModelFromHandle" -> {
                            assertEquals(42L, args[0]);
                            assertEquals(entry.getFolderPath(), args[1]);
                            assertEquals(entry.isPMD(), args[2]);
                            yield instance;
                        }
                        case "getMaterialCount" -> 0;
                        case "preloadTexture", "clearPreloadedTextures" -> null;
                        case "configurePropPose" -> { poseConfigurations.incrementAndGet(); yield null; }
                        default -> throw new AssertionError("Unexpected character setting or native call: " + method.getName());
                    };
                });
        ModelRepository repository = new ModelRepository(port);
        ModelRequestKey request = ModelRequestKey.scene("placement", entry.getFolderName());
        try {
            assertNull(repository.acquire(request));
            assertTrue(repository.isPending(request), "A recognized scene must queue a native load");
            ManagedModel loaded = assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
                ManagedModel result;
                while ((result = repository.acquire(request)) == null) Thread.sleep(5);
                return result;
            });
            assertEquals("load" + format.substring(0, 1).toUpperCase() + format.substring(1) + "Model", nativeLoader.get());
            assertEquals("scene", loaded.properties.getProperty("source"));
            assertSame(loaded, repository.acquire(request));
            assertFalse(repository.isPending(request));
            assertEquals(1, resets.get(), "Keep the scene's native physics initialization");
            assertEquals(1, poseConfigurations.get(), "Disable character blink/look automation");
        } finally {
            repository.reloadAll();
        }
        assertEquals(1, disposals.get());
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
