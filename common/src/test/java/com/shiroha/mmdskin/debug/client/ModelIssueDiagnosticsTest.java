package com.shiroha.mmdskin.debug.client;

import com.shiroha.mmdskin.bridge.runtime.NativeRenderBackendPort;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class ModelIssueDiagnosticsTest {
    @Test
    void summaryKeepsHotMetricsAndManualSnapshotRestoresDetails() {
        AtomicLong now = new AtomicLong();
        List<Integer> requests = new ArrayList<>();
        List<String> logs = new ArrayList<>();
        String records = "[MMD诊断][SIDE_PHYSICS] body=1 name=skirt\n"
                + "[MMD诊断][SIDE_PHYSICS] peak_velocity body=1 speed=50.0\n"
                + "[MMD诊断][COLLISION] worst_anchor error=7.15\n"
                + "[MMD诊断][NEW_CATEGORY] diagnostic=keep";
        ModelIssueDiagnostics diagnostics = new ModelIssueDiagnostics(() -> 2, now::get, logs::add, logs::add);
        NativeRenderBackendPort backend = backend(requests, false, records);

        diagnostics.sample(backend, 42, "model", "GPU");
        logs.clear();
        now.set(2_000_000_000L);
        diagnostics.sample(backend, 42, "model", "GPU");
        assertTrue(logs.stream().noneMatch(line -> line.startsWith("[MMD诊断][SIDE_PHYSICS] body=")));
        assertTrue(logs.stream().anyMatch(line -> line.contains("peak_velocity")));
        assertTrue(logs.stream().anyMatch(line -> line.contains("worst_anchor")));
        assertTrue(logs.stream().anyMatch(line -> line.contains("NEW_CATEGORY")));
        assertTrue(logs.stream().anyMatch(line -> line.contains("omitted_detail_records=1")));

        diagnostics.requestSnapshot();
        diagnostics.sample(backend, 42, "model", "GPU");
        assertTrue(logs.stream().anyMatch(line -> line.startsWith("[MMD诊断][SIDE_PHYSICS] body=")));
        now.set(32_000_000_000L);
        diagnostics.sample(backend, 42, "model", "GPU");
        assertEquals(List.of(0x102, 2, 0x102, 0x102), requests);
        // 同一个诊断对象换 native handle 时也必须重新取 manifest。
        diagnostics.sample(backend, 43, "new-model", "GPU");
        assertEquals(0x102, requests.getLast());
        assertEquals(5, requests.size());
    }

    @Test
    void fullDetailOptionKeepsPreviousTwoSecondDetailCadence() {
        AtomicLong now = new AtomicLong();
        List<Integer> requests = new ArrayList<>();
        List<String> logs = new ArrayList<>();
        ModelIssueDiagnostics diagnostics = new ModelIssueDiagnostics(() -> 2, now::get,
                logs::add, logs::add, () -> true);
        NativeRenderBackendPort backend = backend(requests, false, "[MMD诊断][SIDE_PHYSICS] body=1");
        diagnostics.sample(backend, 42, "model", "GPU");
        now.set(2_000_000_000L);
        diagnostics.sample(backend, 42, "model", "GPU");
        assertEquals(List.of(0x102, 0x102), requests);
        assertEquals(2, logs.stream().filter(line -> line.startsWith("[MMD诊断][SIDE_PHYSICS] body=")).count());
    }

    @Test
    void independentSwitchesThrottleAndReenableManifestWithoutPhysicsMasterSwitch() {
        AtomicInteger enabled = new AtomicInteger();
        AtomicLong now = new AtomicLong();
        List<Integer> requests = new ArrayList<>();
        List<String> logs = new ArrayList<>();
        NativeRenderBackendPort backend = backend(requests, false);
        ModelIssueDiagnostics diagnostics = new ModelIssueDiagnostics(enabled::get, now::get,
                logs::add, logs::add);

        diagnostics.sample(backend, 42, "model", "CPU");
        enabled.set(8); // 只开描边日志时不读取原生物理快照。
        diagnostics.sample(backend, 42, "model", "CPU");
        assertTrue(requests.isEmpty());
        assertTrue(logs.isEmpty());

        enabled.set(2);
        diagnostics.sample(backend, 42, "model\nother", "CPU");
        now.set(1_999_999_999L);
        diagnostics.sample(backend, 42, "model", "CPU");
        assertEquals(List.of(0x102), requests);
        now.set(2_000_000_000L);
        diagnostics.sample(backend, 42, "model", "CPU");
        assertEquals(List.of(0x102, 2), requests);
        enabled.set(6);
        diagnostics.sample(backend, 42, "model", "CPU");
        assertEquals(0x106, requests.getLast());
        enabled.set(0);
        diagnostics.sample(backend, 42, "model", "CPU");
        enabled.set(1);
        diagnostics.sample(backend, 42, "model", "CPU");
        assertEquals(0x101, requests.getLast());
        assertTrue(logs.stream().anyMatch(line -> line.contains("model=model\\nother")));
        assertTrue(logs.stream().noneMatch(line -> line.contains("\n")));
    }

    @Test
    void oldNativeLibraryDoesNotBreakRenderingOrSpamEveryFrame() {
        AtomicInteger enabled = new AtomicInteger(1);
        AtomicLong now = new AtomicLong();
        List<Integer> requests = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        NativeRenderBackendPort backend = backend(requests, true);
        ModelIssueDiagnostics diagnostics = new ModelIssueDiagnostics(enabled::get, now::get,
                ignored -> {}, warnings::add);

        assertDoesNotThrow(() -> diagnostics.sample(backend, 42, "model", "GPU"));
        now.set(20_000_000_000L);
        diagnostics.sample(backend, 42, "model", "GPU");
        assertEquals(1, requests.size());
        assertEquals(1, warnings.size());
        assertTrue(warnings.getFirst().contains("原生诊断接口缺失"));
    }

    private static NativeRenderBackendPort backend(List<Integer> requests, boolean missingNative) {
        return backend(requests, missingNative, "[MMD诊断][SIDE_PHYSICS] body=1\n[MMD诊断][COLLISION] mode=Strict");
    }

    private static NativeRenderBackendPort backend(List<Integer> requests, boolean missingNative, String records) {
        return (NativeRenderBackendPort) Proxy.newProxyInstance(
                NativeRenderBackendPort.class.getClassLoader(),
                new Class<?>[]{NativeRenderBackendPort.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("getIssueDebugDiagnostic")) {
                        requests.add((int) arguments[1]);
                        if (missingNative) throw new UnsatisfiedLinkError("old DLL");
                        return records;
                    }
                    throw new AssertionError("诊断不应调用其它后端方法: " + method.getName());
                });
    }
}
