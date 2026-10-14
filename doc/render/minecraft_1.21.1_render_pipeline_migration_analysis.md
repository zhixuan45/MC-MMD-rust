# Minecraft 1.21.1 渲染管线迁移与模型原点偏离根因分析

## 1. 现象复盘与背景

在将 MMD 渲染模组迁移至 Minecraft 1.21.1（NeoForge 21.1.240）后，出现了严重的模型渲染错位问题：
- 在第三人称（F5）视角下，MMD 模型未渲染在玩家实体的正下方，而是整体脱离了地面阴影以及 F3+B 显示的原版白色实体碰撞箱，偏离约 1.5 个方块；
- 当转动鼠标视角或实体转向移动时，模型绕着地面阴影与碰撞箱做圆周运动，且相对镜头的旋转方向发生反向/异常位移。

---

## 2. 核心根本原因深度剖析

### 2.1 镜头视图矩阵与实体局部 PoseStack 分开维护

在 Minecraft 1.20.1 及更早版本中：
- 渲染管线依赖全局 `RenderSystem.getModelViewStack()` 统一管理整个世界、相机与实体的层级矩阵栈；
- 自定义渲染器在执行渲染时，通过继承或直接读取当前 ModelViewStack 即可自然获得完整的「相机视图旋转 + 实体世界相对位移 + 实体局部旋转」变换。

在 Minecraft 1.21.1 中：
- Mojang 彻底废弃了 `ModelViewStack`，重构了 `LevelRenderer` 与实体调度流程；
- 镜头的视图变换（Camera View Matrix）与实体的局部变换（Entity Local PoseStack）被分开维护；
- 原版 `ShaderInstance.apply()` 在激活着色器程序时，会自动调用：
  ```java
  if (this.MODEL_VIEW_MATRIX != null) {
      this.MODEL_VIEW_MATRIX.set(RenderSystem.getModelViewMatrix());
  }
  ```
  而在 1.21.1 中，`RenderSystem.getModelViewMatrix()` 仅保留了相机的基础视图矩阵，不再包含实体的局部平移、朝向与缩放。
- 如果自定义渲染后端在调用 `shader.apply()` 后，直接上传实体局部矩阵或未正确将镜头视图旋转与实体局部变换完整应用到 GPU 的 `ModelViewMat` Uniform 中，会导致顶点变换漏掉镜头视图旋转或产生坐标系错乱，使模型随着鼠标移动反向漂移，并与 F3+B 的实体包围盒彻底分离。

### 2.2 实体朝向公式与坐标系基准

在 Minecraft 原版 `LivingEntityRenderer.setupRotations` 中，实体的身体朝向旋转公式为：
$$\text{Rotation}_Y = 180^\circ - \text{yBodyRot}$$
- MMD 模型在转换为 OpenGL 右手坐标系（翻转 Z 轴）后，默认正面朝向为 $-Z$ 方向；
- Minecraft 坐标系中，$+Z$ 为正南方（`yaw = 0`），$+X$ 为正东方（`yaw = 270`），$-X$ 为正西方（`yaw = 90`），$-Z$ 为正北方（`yaw = 180`）；
- 若仅使用 `-entityYaw` 进行旋转，缺少了 $180^\circ$ 的基准偏置，会导致模型正反面颠倒，且旋转符号相反。任何骨骼或几何中心的细微偏置在相反旋转角度的叠加下，都会被放大为绕原点画圆的圆周运动。

### 2.3 身体朝向未插值与重复缩放

- 原版实体渲染使用 `Mth.rotLerp(tickDelta, player.yBodyRotO, player.yBodyRot)` 平滑插值计算朝向，而此前的姿态计算直接使用了离散的 `player.yBodyRot`，导致平滑插值的位置与离散的角度在帧内时序不同步；
- 渲染协调器外部执行了 `matrixStack.scale`，而模型根变换内部又重复执行了 `scale(getModelScale())`，造成了二次乘法缩放，放大了所有几何偏置。

---

## 3. 解决与修复措施

1. **着色器矩阵 Uniform 显式上传与同步**：
   - 在 [OpenGlModelRenderer.java](file:///c:/tmp/mc-mmd-rust/common/src/main/java/com/shiroha/mmdskin/render/backend/opengl/OpenGlModelRenderer.java) 与 [GpuSkinningModelRenderer.java](file:///c:/tmp/mc-mmd-rust/common/src/main/java/com/shiroha/mmdskin/render/backend/gpu/GpuSkinningModelRenderer.java) 中，确保在 `shader.apply()` 执行后，立即将包含了完整镜头与实体变换的 `PoseStack` 矩阵（`modelViewMatBuff`）和投影矩阵（`projMatBuff`）显式上传至 GPU 着色器 Uniform，避免被原版静态相机矩阵覆盖。
2. **对齐原版身体旋转公式**：
   - 在 [BaseModelInstance.java](file:///c:/tmp/mc-mmd-rust/common/src/main/java/com/shiroha/mmdskin/render/backend/BaseModelInstance.java) 中将身体朝向旋转修正为 `(180.0f - entityYaw) * degreesToRadians`，并在 [LivingEntityModelStateHelper.java](file:///c:/tmp/mc-mmd-rust/common/src/main/java/com/shiroha/mmdskin/render/pipeline/LivingEntityModelStateHelper.java) 中同步对齐下发给 Rust 物理引擎的 `bodyYaw`。
3. **帧内朝向平滑插值**：
   - 在 [PlayerRenderHelper.java](file:///c:/tmp/mc-mmd-rust/common/src/main/java/com/shiroha/mmdskin/player/render/PlayerRenderHelper.java) 中使用 `Mth.rotLerp` 对身体朝向进行平滑插值，保证位置与旋转同帧步调一致。
4. **消除多余的二次缩放**：
   - 清理 [PlayerModelRenderCoordinator.java](file:///c:/tmp/mc-mmd-rust/common/src/main/java/com/shiroha/mmdskin/player/render/PlayerModelRenderCoordinator.java) 中冗余的外部 `matrixStack.scale` 调用，统一收拢在模型根变换中进行标准缩放。
5. **轮式面板背景模糊阻断与高对比度增强**：
   - 重写 [AbstractWheelScreen.java](file:///c:/tmp/mc-mmd-rust/common/src/main/java/com/shiroha/mmdskin/ui/wheel/AbstractWheelScreen.java) 中的 `renderBackground`，阻断 1.21.1 全局虚化 Shader，提升面板可读性。
