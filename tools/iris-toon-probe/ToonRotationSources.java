package com.shiroha.mmdskin.render.shader;

import com.shiroha.mmdskin.util.AssetsUtil;
import java.nio.file.Files;
import java.nio.file.Path;

/** Export complete production body/outline GLSL using the normal source builders. */
public final class ToonRotationSources {
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        Path output = root.resolve("production");
        Files.createDirectories(output);
        for (String pass : new String[]{"main", "outline"}) {
            Files.writeString(output.resolve(pass + ".vert"), AssetsUtil.getAssetsAsString("shader/toon_" + pass + "_body.vert.glsl"));
            String body = AssetsUtil.getAssetsAsString("shader/toon_" + pass + "_body.frag.glsl");
            String source = pass.equals("main") ? ToonShaderBase.outputSource(body, ToonOutputProfile.vanilla())
                    : ToonShaderBase.outlineSource(body, ToonOutputProfile.vanilla());
            Files.writeString(output.resolve("vanilla." + pass + ".frag"), source);
            for (String name : new String[]{"bsl", "complementary4", "complementary5"}) {
                String lighting = Files.readString(root.resolve("fixtures/" + name + ".glsl"));
                String assembled = source.replace("vec3 applyPackLighting(vec3 raw, vec3 n, vec3 p) { return raw; }",
                        "uniform float TestVisibility, TestEnergy;\n" + lighting);
                Files.writeString(output.resolve(name + "." + pass + ".frag"), assembled);
            }
        }
    }
}
