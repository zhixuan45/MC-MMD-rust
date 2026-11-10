# 2026-11-10 近期提交内容索引

以下依据 `feat/1.21.1-neoforge` 的实际文件变更，供检索时使用。保留原提交历史。

| 提交 | 实际内容 |
| --- | --- |
| `eddb229` | 道具多骨骼外亲切换窗口、Rust 动作轨道采样，以及 Java 时间轴与挂接。原题为 `Add effect pack import and material editing support`，该提交没有效果包文件改动。 |
| `f0e228b` | 舞台道具动画校准、模型生命周期，以及 Java 探针；没有 Rust 改动。 |
| `03a6df6` | 动作加载失败后清理陈旧外亲绑定；没有 Rust 改动。 |
| `6369540` | 挂接手持 PMX 道具、道具物理和时间轴支持。 |
| `63e482e` | 舞台道具时间轴、编辑界面和隐藏渲染场景。 |
| `7ebbd8e` | Iris 地形透射捕获状态与可见性。 |

效果包目录的前次修改锚点为 `d32551b`。继续开发时，以当前源码和文件历史为准。

JNI 源码一致性检查：`python doc/check_jni_consistency.py`。脚本核对全部 Java `native` 声明与 Rust JNI 导出的名称、重复符号、参数与返回 ABI。

本次清理删除了没有 Java native 声明和仓库调用方的 `NativeFunc` 导出：
`PlayLayer`、`StopLayer`、`PauseLayer`、`ResumeLayer`、`SetLayerFadeTimes`、`GetLayerMaxFrame`。
同名 `GetMaterialSpTextureMode` Rust 导出也已移除；Java 包装继续调用 `SphereMaterialNative.getMode`。
这些符号未由仓库的 Java API 暴露；自行链接旧符号的外部代码需要迁移到现有层控制 API。

生产道具绑定使用 `SetPropBindings` 一次提交父矩阵和切换窗口。旧 `SetPropBoneParents`、
`SetPropBoneWindows` 及 128/192 字节 `GetPropBonePose` 保留给旧调用方和兼容性探针。
新 `GetPropBonePosePacket` 固定写入 192 字节并返回矩阵数 3；失败返回负错误码。
`SetPropPhysicsAttachment` 的 native 签名包含显式字节数，原 Java 三参数调用通过包装传入 64。
