package com.shiroha.mmdskin.debug.client;

import com.shiroha.mmdskin.bridge.runtime.NativeRenderBackendPort;
import com.shiroha.mmdskin.config.ConfigManager;
import com.shiroha.mmdskin.config.IssueDiagnosticOptions;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.function.Consumer;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;

/** 按模型限频读取诊断，关闭时不调用 JNI，不依赖旧物理日志。 */
public final class ModelIssueDiagnostics {
    private static final Logger LOGGER = LogManager.getLogger();
    private static final int NATIVE_FLAGS = IssueDiagnosticOptions.MODEL_DEFORMATION
            | IssueDiagnosticOptions.SIDE_PHYSICS | IssueDiagnosticOptions.COLLISION_MODE;
    private static final int MANIFEST = 0x100;
    private static final long INTERVAL_NS = 2_000_000_000L;
    private static final long DETAIL_INTERVAL_NS = 30_000_000_000L;
    private final IntSupplier flagsSource;
    private final LongSupplier clock;
    private final Consumer<String> output;
    private final Consumer<String> warning;
    private final BooleanSupplier fullDetailSource;
    private int previousFlags;
    private long previousSample;
    private boolean sampled;
    private boolean unavailable;
    private long previousDetail;
    private long previousHandle;

    public ModelIssueDiagnostics() {
        this(ConfigManager::getIssueDiagnosticFlags, System::nanoTime,
                message -> LOGGER.info("{}", message), message -> LOGGER.warn("{}", message),
                () -> Boolean.getBoolean("mmdskin.diagnostics.full"));
    }

    ModelIssueDiagnostics(IntSupplier flagsSource, LongSupplier clock,
                          Consumer<String> output, Consumer<String> warning) {
        this(flagsSource, clock, output, warning, () -> false);
    }

    ModelIssueDiagnostics(IntSupplier flagsSource, LongSupplier clock,
                          Consumer<String> output, Consumer<String> warning, BooleanSupplier fullDetailSource) {
        this.flagsSource = flagsSource;
        this.clock = clock;
        this.output = output;
        this.warning = warning;
        this.fullDetailSource = fullDetailSource;
    }

    /** 下一次已开启的诊断采样导出完整快照；不改变配置。 */
    public void requestSnapshot() {
        sampled = false;
    }

    public void sample(NativeRenderBackendPort backend, long modelHandle,
                       String modelName, String backendName) {
        int flags = flagsSource.getAsInt() & NATIVE_FLAGS;
        boolean changed = flags != previousFlags || modelHandle != previousHandle;
        if (changed) {
            previousFlags = flags;
            previousHandle = modelHandle;
            sampled = false;
            unavailable = false;
        }
        if (flags == 0 || modelHandle == 0 || unavailable) return;
        long now = clock.getAsLong();
        if (sampled && now - previousSample < INTERVAL_NS) return;
        boolean details = !sampled || fullDetailSource.getAsBoolean() || now - previousDetail >= DETAIL_INTERVAL_NS;
        int request = flags | (details ? MANIFEST : 0);
        if (details) previousDetail = now;
        previousSample = now;
        sampled = true;
        String context = "handle=" + modelHandle + " model=" + safe(modelName)
                + " backend=" + safe(backendName);
        try {
            String records = backend.getIssueDebugDiagnostic(modelHandle, request);
            output.accept("[MMD诊断][SAMPLE] " + context + " flags=" + flags
                    + " interval_seconds=2 snapshot=true full_details=" + details + " detail_interval_seconds=30");
            if (records == null || records.isBlank()) {
                output.accept("[MMD诊断][UNAVAILABLE] " + context + " native_snapshot=empty");
            } else {
                // 每行附加实例来源，避免多人/多模型日志相互混淆。
                long omitted = records.lines().filter(ModelIssueDiagnostics::isDetail).count();
                records.lines().filter(line -> !line.isBlank() && (details || !isDetail(line)))
                        .forEach(line -> output.accept(line + " " + context));
                if (!details && omitted > 0) output.accept("[MMD诊断][SUMMARY] " + context
                        + " omitted_detail_records=" + omitted + " manual_snapshot=toggle_diagnostic_or_requestSnapshot");
            }
        } catch (UnsatisfiedLinkError error) {
            unavailable = true;
            warning.accept("[MMD诊断][UNAVAILABLE] " + context
                    + " 原生诊断接口缺失，请安装同一构建中的 Jar 和原生库并重启游戏。");
        } catch (RuntimeException error) {
            // 取证失败不能中断游戏绘制；下一个采样窗口仍可恢复。
            warning.accept("[MMD诊断][UNAVAILABLE] " + context + " error="
                    + safe(error.getClass().getSimpleName()));
        }
    }

    private static boolean isDetail(String line) {
        // 只收敛已知明细；新类型和异常摘要默认保留。
        return line.startsWith("[MMD诊断][DEFORMATION] candidate_bone ")
                || line.startsWith("[MMD诊断][DEFORMATION] candidate_raw_body ")
                || line.startsWith("[MMD诊断][SIDE_PHYSICS] body=")
                || line.startsWith("[MMD诊断][COLLISION] filtered_pair ")
                || line.startsWith("[MMD诊断][COLLISION] garment_forced_ignore_pair ");
    }

    private static String safe(String text) {
        return text == null ? "unknown" : text.replace("\r", "\\r")
                .replace("\n", "\\n").replace("\t", "\\t");
    }
}
