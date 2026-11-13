package com.shiroha.mmdskin.render.shader;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Iris 光影程序的 Toon 多渲染目标输出约定。 */
public final class ToonOutputProfile {
    private static final Pattern DRAW_BUFFERS = Pattern.compile("DRAWBUFFERS\\s*:\\s*([0-9]+)|RENDERTARGETS\\s*:\\s*([0-9]+(?:\\s*,\\s*[0-9]+)*)");
    private static final Pattern FRAG_DATA = Pattern.compile("gl_FragData\\s*\\[\\s*(\\d+)\\s*]\\s*=\\s*([^;]+);");
    private static final Pattern COMMENTS = Pattern.compile("/\\*.*?\\*/|//[^\\r\\n]*", Pattern.DOTALL);
    private static final String VANILLA_ID = "vanilla";
    private static final ToonOutputProfile VANILLA = new ToonOutputProfile(
            true, VANILLA_ID, "", legacyDeclarations(), legacyWriter());

    private final boolean supported;
    private final String id;
    private final String reason;
    private final String fragmentDeclarations;
    private final String fragmentWriter;
    private final String lightingSource;

    private ToonOutputProfile(boolean supported, String id, String reason,
                              String fragmentDeclarations, String fragmentWriter) {
        this(supported, id, reason, fragmentDeclarations, fragmentWriter, "");
    }

    private ToonOutputProfile(boolean supported, String id, String reason,
                              String fragmentDeclarations, String fragmentWriter, String lightingSource) {
        this.supported = supported;
        this.id = id;
        this.reason = reason;
        this.fragmentDeclarations = fragmentDeclarations;
        this.fragmentWriter = fragmentWriter;
        this.lightingSource = lightingSource;
    }

    public static ToonOutputProfile vanilla() {
        return VANILLA;
    }

    public static ToonOutputProfile unsupported(String reason) {
        return new ToonOutputProfile(false, "unsupported", Objects.requireNonNullElse(reason, "未知输出协议"), "", "");
    }

    public static ToonOutputProfile resolve(String packName, String programName,
                                            String fragmentSource, int[] drawBuffers) {
        return resolve(packName, programName, fragmentSource, null, drawBuffers);
    }

    public static ToonOutputProfile resolve(String packName, String programName,
                                            String fragmentSource, String vertexSource, int[] drawBuffers) {
        if (programName == null || fragmentSource == null || drawBuffers == null) {
            return unsupported("缺少程序源码或 DRAWBUFFERS 信息");
        }
        if (drawBuffers.length == 0 || java.util.Arrays.stream(drawBuffers).noneMatch(value -> value == 0)) {
            return unsupported("DRAWBUFFERS 缺少 colortex0 主颜色槽");
        }
        boolean[] requestedTargets = new boolean[32];
        for (int target : drawBuffers) {
            if (target < 0 || target >= requestedTargets.length || requestedTargets[target]) {
                return unsupported("DRAWBUFFERS 包含重复或无效的 colortex 槽");
            }
            requestedTargets[target] = true;
        }
        String program = programName.toLowerCase(Locale.ROOT);
        // 注释中的示例代码不能作为缓冲语义的证据。
        String source = COMMENTS.matcher(fragmentSource).replaceAll(" ");
        Program kind;
        if (program.equals("gbuffers_entities_glowing")) kind = Program.GLOWING;
        else if (program.equals("gbuffers_entities") || program.equals("gbuffers_entities_translucent")) kind = Program.ENTITIES;
        else if (program.equals("gbuffers_hand")) kind = Program.HAND;
        else return unsupported("当前仅验证了 entities 与 hand 程序");

        if (!hasDrawBufferEvidence(fragmentSource, drawBuffers)) return unsupported("DRAWBUFFERS 与光影源码中的活动输出布局不匹配");
        List<List<String>> assignments = new ArrayList<>();
        Matcher assignmentMatcher = FRAG_DATA.matcher(source);
        while (assignmentMatcher.find()) {
            int slot = Integer.parseInt(assignmentMatcher.group(1));
            if (slot >= drawBuffers.length) return unsupported("片段程序写入了 DRAWBUFFERS 以外的输出槽");
            while (assignments.size() <= slot) assignments.add(new ArrayList<>());
            assignments.get(slot).add(assignmentMatcher.group(2).trim());
        }
        Family family = detectFamily(source, vertexSource, kind, drawBuffers, assignments);
        if (family == null) return unsupported("光照函数、颜色编码或缓冲输出协议未确认");
        List<String> outputs = new ArrayList<>();
        for (int i = 0; i < drawBuffers.length; i++) {
            int target = drawBuffers[i];
            if (i >= assignments.size() || assignments.get(i).isEmpty()) {
                return unsupported("colortex" + target + " 的写入语义未知或源码证据不完整");
            }
            List<String> slotAssignments = assignments.get(i);
            // 发光实体先写默认材质，再覆盖 PBR 材质；每次写入都必须有已验证的语义。
            if (slotAssignments.stream().anyMatch(value -> !knownAssignment(family, kind, target, value, source))) {
                return unsupported("colortex" + target + " 的写入语义未知或源码证据不完整");
            }
            outputs.add(outputExpression(family, kind, target, source));
        }
        if (outputs.size() != drawBuffers.length) return unsupported("输出槽数量与 DRAWBUFFERS 不一致");

        String layout = String.join(",", java.util.Arrays.stream(drawBuffers).mapToObj(String::valueOf).toList());
        if (java.util.Arrays.stream(drawBuffers).allMatch(value -> value < 10)) layout = layout.replace(",", "");
        String colorEncoding = family == Family.COMPLEMENTARY_MODERN
                || family == Family.COMPLEMENTARY_MODERN_LEGACY ? "gamma"
                : family == Family.BSL && hasSqrtColorEncoding(source) ? "sqrt-linear" : "linear";
        String familyId = family == Family.COMPLEMENTARY_MODERN_LEGACY
                ? "complementary_modern" : family.name().toLowerCase(Locale.ROOT);
        String id = familyId + ":" + kind.name().toLowerCase(Locale.ROOT)
                + ":" + colorEncoding + ":" + layout;
        if (family == Family.COMPLEMENTARY_MODERN_LEGACY) id += ":protocol-r511";
        String lighting = family == Family.COMPLEMENTARY_MODERN
                ? ComplementaryToonLighting.createModern(fragmentSource, vertexSource)
                : family == Family.COMPLEMENTARY_MODERN_LEGACY
                ? ComplementaryToonLighting.createModernLegacy(fragmentSource, vertexSource)
                : family == Family.COMPLEMENTARY ? ComplementaryToonLighting.create(fragmentSource, vertexSource)
                : BslToonLighting.create(fragmentSource, vertexSource, kind == Program.HAND);
        // 输出协议已确认时，光照提取失败只降级照明，不丢弃 Toon 风格。
        // 设置和维度改变后，光照源码也必须参与变体缓存键。
        id += ":out-" + Integer.toUnsignedString(outputs.hashCode(), 16);
        if (!lighting.isEmpty()) id += ":light-" + Integer.toUnsignedString(lighting.hashCode(), 16);
        String outputDeclarations = declarations(drawBuffers.length);
        if (family == Family.COMPLEMENTARY_MODERN
                || family == Family.COMPLEMENTARY_MODERN_LEGACY) {
            outputDeclarations += "uniform mat4 ToonViewToWorld;\n";
            if (requestedTargets[13]) outputDeclarations += "uniform float ToonFar;\n";
        }
        return new ToonOutputProfile(true, id, "", outputDeclarations, writer(outputs, colorEncoding), lighting);
    }

    /** 包名只用于上层显示；选择适配器必须有活动光照/输出协议的证据。 */
    private static Family detectFamily(String source, String vertex, Program program,
                                       int[] targets, List<List<String>> assignments) {
        if (hasModernComplementaryContract(source)) return hasModernLegacyComplementaryContract(source)
                ? Family.COMPLEMENTARY_MODERN_LEGACY : Family.COMPLEMENTARY_MODERN;
        if (!hasVerifiedColorSource(source)) return null;
        // GetLighting 的入参契约不同，不能根据目录名选择不同的调用或颜色编码。
        Matcher lighting = Pattern.compile("voidgetlighting\\(([^)]*)\\)").matcher(compact(source));
        if (lighting.find()) {
            String[] parameters = lighting.group(1).split(",");
            if (parameters.length == 15 && parameters[0].startsWith("inoutvec3")
                    && (parameters[2].startsWith("inoutvec3") || parameters[2].startsWith("outvec3"))) {
                return Family.COMPLEMENTARY;
            }
            if (parameters.length == 13 && parameters[0].startsWith("inoutvec3")
                    && parameters[2].startsWith("vec3")) return Family.BSL;
        }
        // 原包光照签名未知时仍可逐槽验证输出，随后使用通用环境光。
        for (Family candidate : new Family[]{Family.BSL, Family.COMPLEMENTARY}) {
            boolean matches = true;
            for (int slot = 0; slot < targets.length; slot++) {
                if (slot >= assignments.size() || assignments.get(slot).isEmpty()) { matches = false; break; }
                for (String value : assignments.get(slot)) {
                    if (!knownAssignment(candidate, program, targets[slot], value, source)) { matches = false; break; }
                }
                if (!matches) break;
            }
            if (matches) return candidate;
        }
        return null;
    }

    private static boolean hasDrawBufferEvidence(String source, int[] buffers) {
        Matcher matcher = DRAW_BUFFERS.matcher(source);
        int[] lastDirective = null;
        while (matcher.find()) {
            lastDirective = matcher.group(1) != null
                    ? matcher.group(1).chars().map(value -> value - '0').toArray()
                    : java.util.Arrays.stream(matcher.group(2).split(","))
                            .map(String::trim).mapToInt(Integer::parseInt).toArray();
        }
        // Iris 有时剥掉注释；活动赋值仍须逐槽验证。
        return lastDirective == null || java.util.Arrays.equals(lastDirective, buffers);
    }

    private static boolean knownAssignment(Family family, Program program, int target, String value, String source) {
        String compact = value.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        if (target == 0) return compact.equals(family == Family.COMPLEMENTARY_MODERN
                || family == Family.COMPLEMENTARY_MODERN_LEGACY ? "color" : "albedo");
        if (family == Family.COMPLEMENTARY_MODERN) {
            return switch (target) {
                case 3 -> compact.equals("vec4(1.0-translucentmult,1.0)") && hasModernTranslucencyContract(source);
                case 6 -> hasModernTargetSixAssignment(program, compact, source)
                        && hasModernMaterialBufferContract(source);
                case 4 -> hasModernNormalAssignment(program, compact, source);
                case 9 -> hasModernSsblAssignment(program, compact, source);
                case 13 -> (compact.equals("vec4(entitylineardepth,1.0,1.0,1.0)")
                        || compact.equals("vec4(0.0,1.0,1.0,entitylineardepth)"))
                        && compact(source).contains("entitylineardepth=sqrt(lviewpos/far)")
                        && compact(source).contains("lviewpos=length(viewpos)");
                default -> false;
            };
        }
        if (family == Family.COMPLEMENTARY_MODERN_LEGACY) {
            return switch (target) {
                case 5 -> compact.equals("vec4(mat3(gbuffermodelviewinverse)*normalm,1.0)")
                        && compact(source).contains("vec3normalm=normal;");
                case 6 -> compact.equals("vec4(smoothnessd,materialmask,skylightfactor,1.0)")
                        && hasModernLegacyTargetSixContract(source);
                case 8 -> compact.equals("vec4(0.0,0.0,0.0,1.0)")
                        || (program == Program.HAND && compact.equals("vec4(lightalbedo,1.0)")
                        && hasModernLegacyColoredLightContract(source));
                default -> false;
            };
        }
        if (family == Family.COMPLEMENTARY) {
            if (target == 3) return compact.startsWith("vec4(smoothness,metaldata,skymapmod,") && compact.endsWith(",1.0)")
                    && hasComplementaryNeutralDefaults(source);
            if (program == Program.ENTITIES && target == 7) return compact.equals("vec4(1.0)");
            if (target == 6) return compact.equals("vec4(encodenormal(newnormal),0.0,1.0)") && hasSpheremapEncoder(source);
            if (target == 1) return compact.equals("vec4(rawalbedo,1.0)") && hasRawAlbedoContract(source);
        } else {
            if (target == 8) return compact.equals("vec4(0.0,0.0,0.0,1.0)") || compact.equals("vec4(lightalbedo,1.0)");
            if (target == 1) return compact.equals("vec4(vlalbedo,1.0)") && hasBslTranslucencyContract(source);
            if (target == 3) {
                String mask = bslMask(program, source);
                return mask != null && ((compact.equals("vec4(smoothness,skyocclusion," + mask + ",1.0)")
                        && hasBslNeutralDefaults(source)) || compact.equals("vec4(0.0,0.0," + mask + ",1.0)"));
            }
            if (target == 6) return compact.equals("vec4(encodenormal(newnormal),float(gl_fragcoord.z<1.0),1.0)")
                    && hasSpheremapEncoder(source);
            if (target == 7) return compact.equals("vec4(fresnel3,1.0)") && hasBslNeutralDefaults(source);
        }
        return false;
    }

    private static String compact(String source) {
        return source.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    private static boolean hasModernComplementaryContract(String source) {
        String code = compact(source);
        return code.contains("voiddolighting(inoutvec4color,inoutvec3shadowmult,")
                && code.contains("color.rgb*=finaldiffuse") && code.contains("finaldiffuse=sqrt(max(finaldiffuse,vec3(0.0)))")
                && Pattern.compile("gl_FragData\\s*\\[\\s*\\d+\\s*]\\s*=\\s*color\\s*;").matcher(source).find();
    }

    private static boolean hasModernLegacyComplementaryContract(String source) {
        String code = compact(source);
        return lightingParameterCount(code, "voiddolighting(") == 15
                && code.contains("smoothnessd=0.0,skylightfactor=0.0,materialmask=osiebca*254.0")
                && hasModernLegacySkyLightContract(code)
                && !code.contains("skylightfactor=getskylightfactor(")
                && code.contains("gl_fragdata[1]=vec4(smoothnessd,materialmask,skylightfactor,1.0);");
    }

    private static boolean hasModernLegacyTargetSixContract(String source) {
        String code = compact(source);
        return code.contains("constfloatosiebca=1.0/255.0;")
                && code.contains("floatsmoothnessd=0.0,skylightfactor=0.0,materialmask=osiebca*254.0")
                && hasModernLegacySkyLightContract(code);
    }

    private static boolean hasModernLegacySkyLightContract(String source) {
        String mutations = source.replace(
                "floatsmoothnessd=0.0,skylightfactor=0.0,materialmask=osiebca*254.0;",
                "floatsmoothnessd=0.0;");
        if (Pattern.compile("(?<![a-z0-9_])(?:\\+\\+|--)skylightfactor\\b")
                .matcher(mutations).find()) return false;
        Matcher assignments = Pattern.compile(
                "(?<![a-z0-9_])skylightfactor(\\+\\+|--|[+\\-*/%]?=)([^;]*);").matcher(mutations);
        while (assignments.find()) {
            String operator = assignments.group(1);
            String value = assignments.group(2);
            if (!operator.equals("=")
                    || (!value.equals("pow2(max(lmcoord.y-0.7,0.0)*3.33333)")
                    && !value.equals("dot(shadowmult,shadowmult)/3.0"))) return false;
        }
        // The zero-initialized value is the complete contract when the active
        // program has no later sky-light assignment (for example PBR disabled).
        return true;
    }

    private static boolean hasModernLegacyColoredLightContract(String source) {
        String code = compact(source);
        return code.contains("vec3lightalbedo=vec3(0.0);")
                && code.contains("lightalbedo=normalize(color.rgb)*min1(emission);");
    }

    private static int lightingParameterCount(String compactSource, String signature) {
        int start = compactSource.indexOf(signature);
        if (start < 0) return -1;
        int parametersStart = start + signature.length();
        int end = compactSource.indexOf(')', parametersStart);
        if (end < 0) return -1;
        return compactSource.substring(parametersStart, end).split(",").length;
    }

    private static boolean hasModernMaterialBufferContract(String source) {
        String code = compact(source);
        if (!code.contains("constfloatosiebca=1.0/255.0;")) return false;
        boolean modernDefaults = code.contains("smoothnessd=0.0,materialmask=osiebca*254.0")
                || code.contains("floatsmoothnessd=0.0,enderdragondead=1.0,materialmask=osiebca*254.0");
        return modernDefaults && code.contains("skylightfactor=getskylightfactor(lmcoordm,shadowmult)");
    }

    private static boolean hasModernTargetSixAssignment(Program program, String assignment, String source) {
        if (assignment.equals("vec4(smoothnessd,materialmask,skylightfactor,1.0)")) return true;
        String code = compact(source);
        if (program == Program.HAND) {
            return assignment.equals("vec4(smoothnessd,materialmask,skylightfactor,purkinjedata)")
                    && code.contains("floatpurkinjedata=1.0;");
        }
        return assignment.equals(
                "vec4(smoothnessd,materialmask,skylightfactor,lmcoord.x+clamp01(purkinjeoverwrite)+clamp01(emissionold)+maxof(entitycolor))")
                && code.contains("purkinjeoverwrite=0.0")
                && code.contains("emissionold=0.0");
    }

    private static boolean hasModernPurkinjeTargetSix(String source) {
        String code = compact(source);
        return code.contains("vec4(smoothnessd,materialmask,skylightfactor,purkinjedata)")
                || code.contains("vec4(smoothnessd,materialmask,skylightfactor,lmcoord.x+clamp01(purkinjeoverwrite)+clamp01(emissionold)+maxof(entitycolor))");
    }

    private static boolean hasModernNormalAssignment(Program program, String assignment, String source) {
        if (assignment.equals("vec4(mat3(gbuffermodelviewinverse)*normalm,1.0)")) return true;
        String code = compact(source);
        if (program == Program.HAND) {
            return assignment.equals("vec4(mat3(gbuffermodelviewinverse)*normalm,albedodata)")
                    && code.contains("floatalbedodata=1.0;");
        }
        return assignment.equals("vec4(mat3(gbuffermodelviewinverse)*normalm,reflectnormalalpha)")
                && code.contains("floatreflectnormalalpha=1.0;");
    }

    private static boolean hasModernReflectiveAlbedoAlpha(String source) {
        String code = compact(source);
        return !code.contains("vec4(mat3(gbuffermodelviewinverse)*normalm,1.0)")
                && (code.contains("reflectnormalalpha=clamp(maxof(rawalbedom),0.02,0.99)*2.0-1.0")
                || code.contains("albedodata=clamp(maxof(rawalbedom),0.02,0.99)*2.0-1.0"));
    }

    private static boolean hasModernSsblAssignment(Program program, String assignment, String source) {
        String code = compact(source);
        if (!code.contains("vec3lightalbedo=vec3(0.0)")) return false;
        if (program == Program.HAND) {
            return assignment.equals("vec4(lightalbedo,handssblmask)")
                    && code.contains("floathandssblmask=0.0;");
        }
        return assignment.equals("vec4(lightalbedo,entityssblmask)")
                && code.contains("floatentityssblmask=1.0;");
    }

    private static boolean hasModernTranslucencyContract(String source) {
        String code = compact(source);
        return code.contains("vec3translucentmult=vec3(1.0)")
                || code.contains("vec3translucentmult=mix(vec3(0.666),color.rgb*(1.0-pow2(pow2(color.a))),color.a)");
    }

    private static boolean hasBslTranslucencyContract(String source) {
        return compact(source).contains("vlalbedo=mix(vec3(1.0),albedo.rgb,sqrt(albedo.a))*(1.0-pow(albedo.a,64.0))");
    }

    private static String bslMask(Program program, String source) {
        if (program == Program.GLOWING) return "1.0";
        if (compact(source).contains("entitymask=")) {
            return compact(source).contains("floatentitymask=(color.a<0.999||entityid==10104)?0.0:0.25;")
                    ? "entitymask" : null;
        }
        return "0.25";
    }

    private static boolean hasComplementaryNeutralDefaults(String source) {
        String compact = source.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        return compact.contains("smoothness=0.0,metaldata=0.0") && compact.contains("skymapmod=0.0");
    }

    private static boolean hasBslNeutralDefaults(String source) {
        String compact = source.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        return compact.contains("smoothness=0.0") && compact.contains("skyocclusion=0.0")
                && compact.contains("fresnel3=vec3(0.0)");
    }

    private static boolean hasRawAlbedoContract(String source) {
        String compact = source.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        int linearized = compact.indexOf("albedo.rgb=pow(albedo.rgb,vec3(2.2))");
        int rawAlbedo = compact.indexOf("rawalbedo=albedo.rgb*0.999+0.001");
        return linearized >= 0 && rawAlbedo > linearized;
    }

    private static boolean hasLinearColorContract(String source) {
        String compact = source.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        return compact.contains("albedo.rgb=pow(albedo.rgb,vec3(2.2))");
    }

    private static boolean hasVerifiedColorSource(String source) {
        return hasLinearColorContract(source)
                && Pattern.compile("gl_FragData\\s*\\[\\s*\\d+\\s*]\\s*=\\s*albedo\\s*;").matcher(source).find();
    }

    private static boolean hasSpheremapEncoder(String source) {
        String compact = source.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        return compact.contains("vec2encodenormal(vec3n){floatf=sqrt(n.z*8.0+8.0);returnn.xy/f+0.5;}");
    }

    private static boolean hasSqrtColorEncoding(String source) {
        String compact = source.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        return compact.contains("albedo.rgb=sqrt(max(albedo.rgb,vec3(0.0)))");
    }

    private static String outputExpression(Family family, Program program, int target, String source) {
        if (target == 0) return "__COLOR__";
        if (family == Family.COMPLEMENTARY_MODERN) {
            return switch (target) {
                case 3 -> compact(source).contains("vec3translucentmult=vec3(1.0)")
                        ? "vec4(0.0, 0.0, 0.0, 1.0)"
                        : "vec4(vec3(1.0) - mix(vec3(0.666), color * (1.0 - pow(alpha, 4.0)), alpha), 1.0)";
                // 非 PBR 模型沿用原包实体的中性材质标记，禁用 SSAO/TAA 和镜面反射。
                case 6 -> hasModernPurkinjeTargetSix(source)
                        ? "vec4(0.0, 254.0 / 255.0, 0.0, clamp(ToonLightmap.x, 0.0, 0.9))"
                        : "vec4(0.0, 254.0 / 255.0, 0.0, 1.0)";
                case 4 -> hasModernReflectiveAlbedoAlpha(source)
                        ? "vec4(normalize(mat3(ToonViewToWorld) * normal), clamp(max(rawAlbedo.r, max(rawAlbedo.g, rawAlbedo.b)), 0.02, 0.99) * 2.0 - 1.0)"
                        : "vec4(normalize(mat3(ToonViewToWorld) * normal), 1.0)";
                case 9 -> program == Program.HAND ? "vec4(0.0, 0.0, 0.0, 0.0)" : "vec4(0.0, 0.0, 0.0, 1.0)";
                case 13 -> compact(source).contains("vec4(0.0,1.0,1.0,entitylineardepth)")
                        ? "vec4(0.0, 1.0, 1.0, sqrt(length(viewPos) / max(ToonFar, 1.0)))"
                        : "vec4(sqrt(length(viewPos) / max(ToonFar, 1.0)), 1.0, 1.0, 1.0)";
                default -> throw new IllegalArgumentException("unsupported target");
            };
        }
        if (family == Family.COMPLEMENTARY_MODERN_LEGACY) {
            return switch (target) {
                case 5 -> "vec4(normalize(mat3(ToonViewToWorld) * normal), 1.0)";
                case 6 -> "vec4(0.0, 254.0 / 255.0, 0.0, 1.0)";
                case 8 -> "vec4(0.0, 0.0, 0.0, 1.0)";
                default -> throw new IllegalArgumentException("unsupported target");
            };
        }
        if (family == Family.COMPLEMENTARY) {
            return switch (target) {
                case 1 -> "vec4(pow(max(rawAlbedo, vec3(0.0)), vec3(2.2)) * 0.999 + 0.001, 1.0)";
                case 3 -> "vec4(0.0, 0.0, 0.0, 1.0)";
                case 6 -> "vec4(encodeComplementaryNormal(normal), 0.0, 1.0)";
                case 7 -> "vec4(1.0)";
                default -> throw new IllegalArgumentException("unsupported target");
            };
        }
        return switch (target) {
            case 1 -> "vec4(mix(vec3(1.0), pow(max(rawAlbedo, vec3(0.0)), vec3(2.2)), sqrt(alpha)) * (1.0 - pow(alpha, 64.0)), 1.0)";
            case 3 -> "vec4(0.0, 0.0, " + (program == Program.GLOWING ? "1.0"
                    : bslMask(program, source).equals("entitymask") ? "(alpha < 0.999 ? 0.0 : 0.25)" : "0.25") + ", 1.0)";
            case 6 -> "vec4(encodeBslNormal(normal), float(gl_FragCoord.z < 1.0), 1.0)";
            case 7 -> "vec4(0.0, 0.0, 0.0, 1.0)";
            case 8 -> "vec4(0.0, 0.0, 0.0, 1.0)";
            default -> throw new IllegalArgumentException("unsupported target");
        };
    }

    private static String declarations(int count) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < count; i++) result.append("layout(location = ").append(i).append(") out vec4 toonOutput").append(i).append(";\n");
        return result.toString();
    }

    private static String writer(List<String> outputs, String colorEncoding) {
        String color = colorEncoding.equals("gamma") ? "vec4(color, alpha)"
                : colorEncoding.equals("sqrt-linear")
                ? "vec4(sqrt(pow(max(color, vec3(0.0)), vec3(2.2))), alpha)"
                : "vec4(pow(max(color, vec3(0.0)), vec3(2.2)), alpha)";
        StringBuilder result = new StringBuilder("vec2 encodeComplementaryNormal(vec3 n) { float f = sqrt(max(n.z * 8.0 + 8.0, 0.0)); if (f < 1e-6) return vec2(1.0, 0.5); return n.xy / f + 0.5; }\n")
                .append("vec2 encodeBslNormal(vec3 n) { float f = sqrt(max(n.z * 8.0 + 8.0, 0.0)); if (f < 1e-6) return vec2(1.0, 0.5); return n.xy / f + 0.5; }\n")
                .append("void writeToonOutputs(vec3 color, vec3 rawAlbedo, vec3 normal, float alpha) {\n");
        for (int i = 0; i < outputs.size(); i++) {
            String expression = outputs.get(i).equals("__COLOR__") ? color : outputs.get(i);
            result.append("    toonOutput").append(i).append(" = ").append(expression).append(";\n");
        }
        return result.append("}\n").toString();
    }

    private static String legacyDeclarations() {
        return "layout(location = 0) out vec4 toonOutput0;\nlayout(location = 1) out vec4 toonOutput1;\n"
                + "layout(location = 2) out vec4 toonOutput2;\nlayout(location = 3) out vec4 toonOutput3;\n";
    }

    private static String legacyWriter() {
        return "void writeToonOutputs(vec3 color, vec3 rawAlbedo, vec3 normal, float alpha) {\n"
                + "    toonOutput0 = vec4(color, alpha);\n    toonOutput1 = vec4(normal * 0.5 + 0.5, 1.0);\n"
                + "    toonOutput2 = vec4(0.0, 0.0, 0.0, 1.0);\n    toonOutput3 = vec4(0.0, 0.0, 0.0, 1.0);\n}\n";
    }

    public int colorOutputSlot() {
        if (id.equals(VANILLA_ID)) return 0;
        if (!supported) return -1;
        String layout = id.split(":")[3];
        if (!layout.contains(",")) return layout.indexOf('0');
        String[] targets = layout.split(",");
        for (int slot = 0; slot < targets.length; slot++) {
            if (targets[slot].equals("0")) return slot;
        }
        return -1;
    }

    public String outlineWriter() {
        String encoded = id.equals(VANILLA_ID) || id.contains(":gamma:") ? "color"
                : id.contains(":sqrt-linear:") ? "pow(max(color, vec3(0.0)), vec3(1.1))"
                : "pow(max(color, vec3(0.0)), vec3(2.2))";
        return "void writeToonOutputs(vec3 color, vec3 rawAlbedo, vec3 normal, float alpha) {\n"
                + "    vec3 encoded = " + encoded + ";\n"
                + "    outlineColor = vec4(Premultiplied ? encoded * alpha : encoded, alpha);\n}\n";
    }

    public boolean isSupported() { return supported; }
    public String id() { return id; }
    public String reason() { return reason; }
    public String fragmentDeclarations() { return fragmentDeclarations; }
    public String fragmentWriter() { return fragmentWriter; }
    public String lightingSource() { return lightingSource; }

    public ToonOutputProfile withoutPackLighting() {
        if (!supported || lightingSource.isEmpty()) return this;
        return new ToonOutputProfile(true, id + ":generic-light", reason,
                fragmentDeclarations, fragmentWriter, "");
    }

    private enum Family { COMPLEMENTARY, COMPLEMENTARY_MODERN, COMPLEMENTARY_MODERN_LEGACY, BSL }
    private enum Program { ENTITIES, HAND, GLOWING }
}
