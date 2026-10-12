# 暂停续接记录（2026-10-11，用户主动暂停）

用户最终范围：整个眼瞳含黑瞳、高光、眼瞳外圈；不含眼白、不含镜片。用户因限额要求暂停；不要自动继续。

上一轮 Complementary Unbound r5.9.3 shadow2D 的 330 core 兼容修复、眼骨遮罩和真实洛天依离线核验记录在 TOON_UNBOUND_EYE_FIX.md 与 Logs/iris-toon-unbound-20261011。未替换运行实例 Jar，未完成游戏视觉验收。

本轮新生产修改：EyeMaterialMask.classify（-1明确眼白镜片优先排除，0按眼骨，1明确瞳/iris/pupil/eye_hi材质整材质补足）、ModelMaterial缓存mode、CPU/GPU draw材质callback、ToonShaderBase uniform、BaseModelInstance允许无bonebuffer的明确材质fallback、fragment组合mode和bone mask。EyeEmissionBuffer缺骨日志已改准确说明fallback。EyeMaterialMaskTest与ToonControlsProbe新增fallback/负材质覆盖骨遮罩测试。

最新 Gradle 本轮通过：Zulu21，:common:test :fabric:compileJava :neoforge:compileJava :neoforge:shadowJar --offline。日志 Logs/iris-toon-unbound-20261011/gradle-material-validation.log。最新材质GL测试和真实资产复验未运行；不能将上一轮GL结果归为本轮新规则的结果。

多模型审计13PMX：Logs/iris-toon-unbound-20261011/multi-model-eye-audit.md/json；tools/iris-toon-probe/multi_model_eye_audit.py。牛肉式洛天依AI的#3瞳208tri全部头骨/无眼权重，#4眼白170tri同为0；因此新增明确瞳材质fallback。Uma8款独立eye都有眼骨；TDA绫/言和眼内高光有眼骨。

洛天依尚未决定的外圈：材#2目 UV连通岛#7 120tri，UV[.5156..7393,.3011..5248] 黑环白心岛，82顶点全部眼骨0；source XYZ[-.7293...7293,17.6589..18.0704,-.6371..-.4632]。绿虹膜岛#5/#6各188tri全眼骨，source z[-.941..-.834]（runtime翻Z），在黑环前面。XY虹膜95.7%重叠，黑环岛约29.4%投影露边。只能证明它是虹膜后方网格，不能整岛全亮以免白心发光，也不能未经确认称镜片/眼白。材#1目縁220tri全0、UV[.1288..2502,.3552..4658]灰白椭圆边缘岛，不应整材质全亮。已展示 eye-after.png 说明这个歧义，尚未发送进一步提问。

后续：先确定用户所指外圈，补明确外圈/高光材质词（目前iris_rim和美瞳外圈已支持，但裸独立眼圈/eye_ring/眼睛高光等未覆盖，勿误把眼线/镜片算进去）。子代理eye_emission_trace已interrupt，可能 exporter/probe存在半成品，先git diff/文件核对。它原任务为修 export_eye_asset.py 面索引unsigned（原有signed bug）、可选materials/texture，并用生产mask+shader真实核验牛肉式/TDA/Grass Wonder，不要自动假定已完成。trace_toon_compat已完成接线及只读审查，无主路径state漏洞。

现存代理Luna：/root/eye_emission_trace（已中断）、/root/trace_toon_compat（完成）；禁止派生新对话和代理。不要重置任何dirtywork。index.md已完整读过；报告与index最终需更新为本轮结果，Jar hash需重新记录。每代码文件<1000行。仅C:/tmp/mc-mmd-rust可写；实例只读。沙箱Gradle缓存lock需require_escalated，已有批准。
