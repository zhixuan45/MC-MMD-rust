package com.shiroha.mmdskin.render.effectpack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 有向无环的单帧效果图；图编译与实际图形后端分离。 */
public final class FxRenderGraph {
    public record Resource(String name, int width, int height, String format) {
        public Resource {
            if (name == null || name.isBlank() || width <= 0 || height <= 0 || format == null || format.isBlank())
                throw new IllegalArgumentException("无效渲染资源");
        }
    }

    public record Pass(String name, List<String> inputs, String output, String shader) {
        public Pass {
            if (name == null || name.isBlank() || output == null || output.isBlank() || shader == null || shader.isBlank())
                throw new IllegalArgumentException("无效渲染阶段");
            inputs = List.copyOf(inputs);
        }
    }

    /** 后端负责保存并恢复状态，资源句柄和输入输出由宿主提供。 */
    public interface Backend<H> {
        H create(Resource resource);
        void draw(Pass pass, List<H> inputs, H output);
        void present(H output);
        void release(H resource);
    }

    private final List<Resource> resources;
    private final List<Pass> passes;
    private final Set<String> external;
    private final String output;

    private FxRenderGraph(List<Resource> resources, List<Pass> passes, Set<String> external, String output) {
        this.resources = List.copyOf(resources);
        this.passes = List.copyOf(passes);
        this.external = Set.copyOf(external);
        this.output = output;
    }

    /** 外部资源由宿主拥有；内部资源必须恰好由一个 pass 写入。 */
    public static FxRenderGraph compile(List<Resource> resources, List<Pass> passes, Set<String> external, String output) {
        if (resources.size() > 32 || passes.size() > 32) throw new IllegalArgumentException("渲染图规模超限");
        Map<String, Resource> declared = new HashMap<>();
        for (Resource resource : resources)
            if (declared.putIfAbsent(resource.name(), resource) != null || external.contains(resource.name()))
                throw new IllegalArgumentException("重复渲染资源: " + resource.name());
        Set<String> available = new HashSet<>(external);
        Set<String> passNames = new HashSet<>();
        for (Pass pass : passes) {
            if (!passNames.add(pass.name())) throw new IllegalArgumentException("重复渲染阶段: " + pass.name());
            if (!declared.containsKey(pass.output()) || available.contains(pass.output()))
                throw new IllegalArgumentException("渲染目标未声明或重复写入: " + pass.output());
            for (String input : pass.inputs())
                if (!available.contains(input)) throw new IllegalArgumentException("输入未生成: " + input);
            available.add(pass.output());
        }
        if (!available.containsAll(declared.keySet())) throw new IllegalArgumentException("存在未写入的渲染资源");
        if (!declared.containsKey(output)) throw new IllegalArgumentException("输出必须是内部渲染目标");
        return new FxRenderGraph(resources, passes, external, output);
    }

    /** 执行期间创建的目标无论成功或失败均由后端释放。 */
    public <H> void execute(Map<String, H> inputs, Backend<H> backend) {
        if (!inputs.keySet().containsAll(external)) throw new IllegalArgumentException("缺少外部渲染资源");
        for (String name : external)
            if (inputs.get(name) == null) throw new IllegalArgumentException("外部渲染资源为空: " + name);
        Map<String, H> handles = new HashMap<>(inputs);
        List<H> allocated = new ArrayList<>();
        Throwable failure = null;
        try {
            for (Resource resource : resources) {
                H handle = java.util.Objects.requireNonNull(backend.create(resource), "创建渲染资源失败: " + resource.name());
                allocated.add(handle);
                handles.put(resource.name(), handle);
            }
            for (Pass pass : passes) {
                List<H> sources = pass.inputs().stream().map(handles::get).toList();
                backend.draw(pass, sources, handles.get(pass.output()));
            }
            backend.present(handles.get(output));
        } catch (RuntimeException | Error e) {
            failure = e;
            throw e;
        } finally {
            RuntimeException releaseFailure = null;
            for (int i = allocated.size() - 1; i >= 0; i--) {
                try { backend.release(allocated.get(i)); }
                catch (RuntimeException e) {
                    if (failure != null) failure.addSuppressed(e);
                    else if (releaseFailure == null) releaseFailure = e;
                    else releaseFailure.addSuppressed(e);
                }
            }
            if (failure == null && releaseFailure != null) throw releaseFailure;
        }
    }
}
