package com.shiroha.mmdskin.render.effectpack;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** ZIP 条目、include 和纹理引用使用相同的包内路径规则。 */
final class FxPaths {
    private FxPaths() { }

    static Path resolve(Path root, Path base, String reference) throws IOException {
        String name = reference.replace('\\', '/');
        if (name.isBlank() || name.contains(":") || name.startsWith("/"))
            throw new IOException("无效的包内路径: " + reference);
        Path boundary = root.toAbsolutePath().normalize();
        Path result = base.resolve(name).toAbsolutePath().normalize();
        if (!result.startsWith(boundary)) throw new IOException("资源路径越出效果包: " + reference);
        Path realBoundary = Files.exists(boundary) ? boundary.toRealPath() : boundary;
        // 既检查已有父目录，也检查最终目标，防止目录包中的链接绕过边界。
        for (Path cursor = result; cursor != null && cursor.startsWith(boundary); cursor = cursor.getParent()) {
            if (Files.isSymbolicLink(cursor)) throw new IOException("效果包不接受符号链接: " + reference);
            if (Files.exists(cursor) && !cursor.toRealPath().startsWith(realBoundary))
                throw new IOException("资源链接越出效果包: " + reference);
        }
        return result;
    }
}
