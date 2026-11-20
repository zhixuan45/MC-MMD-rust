//! 环境碰撞体管理（地面厚盒体 + 周围方块刚体池）
//!
//! 将 Minecraft 世界空间中的地面高度与周围方块 AABB 转换至模型局部左手系，
//! 供 Bullet3 物理世界进行真实地面托举与方块阻挡。

use glam::{Mat3, Mat4, Vec3};

use super::bullet_ffi::{BulletRigidBody, BulletShape, BulletWorld, RigidBodyInfo};

/// 周围方块刚体池上限
pub const MAX_BLOCK_COLLIDERS: usize = 16;

const GROUND_HALF_EXTENTS: Vec3 = Vec3::new(500.0, 50.0, 500.0);
const ENV_COLLISION_GROUP: i32 = 0xFFFF;
const ENV_COLLISION_MASK: i32 = 0xFFFF;
const ENV_FRICTION: f32 = 0.6;
const ENV_RESTITUTION: f32 = 0.05;
const MIN_SCALE: f32 = 1e-6;
const MIN_HALF_EXTENT: f32 = 1e-4;
const CACHE_EPS: f32 = 1e-6;
const SHAPE_REUSE_EPS: f32 = 1e-4;

/// 单个环境刚体槽位（Drop 顺序：先 body 后 shape）
struct EnvBodySlot {
    body: BulletRigidBody,
    _shape: BulletShape,
    last_half_extents: Vec3,
}

#[derive(Clone, PartialEq)]
struct CachedEnvState {
    model_transform: Mat4,
    total_scale: f32,
    ground_y: Option<f32>,
    block_aabbs: Vec<[f32; 6]>,
}

impl CachedEnvState {
    fn matches(
        &self,
        model_transform: Mat4,
        total_scale: f32,
        ground_y: Option<f32>,
        block_aabbs: &[[f32; 6]],
    ) -> bool {
        if (self.total_scale - total_scale).abs() > CACHE_EPS {
            return false;
        }
        match (self.ground_y, ground_y) {
            (None, None) => {}
            (Some(a), Some(b)) if (a - b).abs() <= CACHE_EPS => {}
            _ => return false,
        }
        if !self.model_transform.abs_diff_eq(model_transform, CACHE_EPS) {
            return false;
        }
        if self.block_aabbs.len() != block_aabbs.len() {
            return false;
        }
        for (a, b) in self.block_aabbs.iter().zip(block_aabbs.iter()) {
            for i in 0..6 {
                if (a[i] - b[i]).abs() > CACHE_EPS {
                    return false;
                }
            }
        }
        true
    }
}

/// 将世界空间（右手系）AABB `[minX, minY, minZ, maxX, maxY, maxZ]`
/// 转换到模型局部物理空间（左手系）下的 `(transform, half_extents)`。
pub fn world_to_bullet_box(
    model_transform: Mat4,
    total_scale: f32,
    aabb: [f32; 6],
) -> Option<(Mat4, Vec3)> {
    if !total_scale.is_finite() || total_scale <= MIN_SCALE {
        return None;
    }
    if aabb.iter().any(|v| !v.is_finite()) {
        return None;
    }
    let [min_x, min_y, min_z, max_x, max_y, max_z] = aabb;
    if max_x <= min_x || max_y <= min_y || max_z <= min_z {
        return None;
    }

    let center_world = Vec3::new(
        (min_x + max_x) * 0.5,
        (min_y + max_y) * 0.5,
        (min_z + max_z) * 0.5,
    );
    let half_extents = Vec3::new(
        (max_x - min_x) * 0.5,
        (max_y - min_y) * 0.5,
        (max_z - min_z) * 0.5,
    ) / total_scale;

    if half_extents.min_element() <= MIN_HALF_EXTENT {
        return None;
    }

    let model_pos = model_transform.w_axis.truncate();
    let rot_inv = Mat3::from_mat4(model_transform).transpose();
    let local_right = (rot_inv * (center_world - model_pos)) / total_scale;
    let bullet_pos = Vec3::new(local_right.x, local_right.y, -local_right.z);

    let mut bullet_transform = super::inv_z(Mat4::from_mat3(rot_inv));
    bullet_transform.w_axis = bullet_pos.extend(1.0);

    Some((bullet_transform, half_extents))
}

fn create_kinematic_box_slot(transform: Mat4, half_extents: Vec3) -> Option<EnvBodySlot> {
    let shape = BulletShape::r#box(half_extents.x, half_extents.y, half_extents.z)?;
    let info = RigidBodyInfo {
        mass: 0.0,
        linear_damping: 0.0,
        angular_damping: 0.0,
        friction: ENV_FRICTION,
        restitution: ENV_RESTITUTION,
        additional_damping: false,
        is_kinematic: true,
        disable_deactivation: true,
        no_contact_response: false,
        initial_transform: transform,
    };
    let body = BulletRigidBody::new(&info, &shape)?;
    Some(EnvBodySlot {
        body,
        _shape: shape,
        last_half_extents: half_extents,
    })
}

/// 管理单个 Bullet3 物理世界中的地面与周围方块碰撞体
#[derive(Default)]
pub struct EnvironmentColliders {
    ground_body: Option<EnvBodySlot>,
    block_pool: Vec<EnvBodySlot>,
    cached_state: Option<CachedEnvState>,
}

impl EnvironmentColliders {
    /// 同步地面与周围方块碰撞体至指定物理世界
    pub fn sync(
        &mut self,
        world: &BulletWorld,
        model_transform: Mat4,
        total_scale: f32,
        ground_y: Option<f32>,
        block_aabbs: &[[f32; 6]],
    ) {
        if !total_scale.is_finite() || total_scale <= MIN_SCALE {
            self.clear(world);
            return;
        }

        let valid_ground_y = ground_y.filter(|y| y.is_finite());
        let clamped_aabbs = &block_aabbs[..block_aabbs.len().min(MAX_BLOCK_COLLIDERS)];

        if let Some(ref cached) = self.cached_state {
            if cached.matches(model_transform, total_scale, valid_ground_y, clamped_aabbs) {
                return;
            }
        }

        let model_pos = model_transform.w_axis.truncate();
        self.sync_ground(world, model_pos.y, total_scale, valid_ground_y);
        self.sync_blocks(world, model_transform, total_scale, clamped_aabbs);

        self.cached_state = Some(CachedEnvState {
            model_transform,
            total_scale,
            ground_y: valid_ground_y,
            block_aabbs: clamped_aabbs.to_vec(),
        });
    }

    fn sync_ground(
        &mut self,
        world: &BulletWorld,
        model_pos_y: f32,
        total_scale: f32,
        ground_y: Option<f32>,
    ) {
        let Some(gy) = ground_y else {
            if let Some(slot) = self.ground_body.take() {
                world.remove_rigid_body(&slot.body);
            }
            return;
        };

        let local_ground_y = (gy - model_pos_y) / total_scale;
        let transform =
            Mat4::from_translation(Vec3::new(0.0, local_ground_y - GROUND_HALF_EXTENTS.y, 0.0));

        if let Some(ref slot) = self.ground_body {
            slot.body.set_transform(transform);
        } else if let Some(slot) = create_kinematic_box_slot(transform, GROUND_HALF_EXTENTS) {
            world.add_rigid_body(&slot.body, ENV_COLLISION_GROUP, ENV_COLLISION_MASK);
            self.ground_body = Some(slot);
        }
    }

    fn sync_blocks(
        &mut self,
        world: &BulletWorld,
        model_transform: Mat4,
        total_scale: f32,
        block_aabbs: &[[f32; 6]],
    ) {
        let mut active_count = 0usize;
        for &aabb in block_aabbs {
            let Some((transform, half_extents)) =
                world_to_bullet_box(model_transform, total_scale, aabb)
            else {
                continue;
            };

            if active_count < self.block_pool.len() {
                let slot = &mut self.block_pool[active_count];
                if slot.last_half_extents.abs_diff_eq(half_extents, SHAPE_REUSE_EPS) {
                    slot.body.set_transform(transform);
                } else {
                    world.remove_rigid_body(&slot.body);
                    if let Some(new_slot) = create_kinematic_box_slot(transform, half_extents) {
                        world.add_rigid_body(&new_slot.body, ENV_COLLISION_GROUP, ENV_COLLISION_MASK);
                        *slot = new_slot;
                    } else {
                        continue;
                    }
                }
            } else if let Some(new_slot) = create_kinematic_box_slot(transform, half_extents) {
                world.add_rigid_body(&new_slot.body, ENV_COLLISION_GROUP, ENV_COLLISION_MASK);
                self.block_pool.push(new_slot);
            } else {
                continue;
            }

            active_count += 1;
        }

        while self.block_pool.len() > active_count {
            if let Some(slot) = self.block_pool.pop() {
                world.remove_rigid_body(&slot.body);
            }
        }
    }

    /// 从物理世界中移除并释放所有环境刚体（必须在世界销毁前调用）
    pub fn clear(&mut self, world: &BulletWorld) {
        if let Some(slot) = self.ground_body.take() {
            world.remove_rigid_body(&slot.body);
        }
        for slot in self.block_pool.drain(..) {
            world.remove_rigid_body(&slot.body);
        }
        self.cached_state = None;
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use glam::Quat;
    use std::sync::Mutex;

    // 串行化 Bullet3 分配统计测试，避免同进程内多线程测试互相干扰计数
    static ALLOC_TEST_LOCK: Mutex<()> = Mutex::new(());

    fn create_dynamic_sphere(world: &BulletWorld, pos: Vec3, radius: f32) -> (BulletRigidBody, BulletShape) {
        let shape = BulletShape::sphere(radius).expect("sphere shape");
        let info = RigidBodyInfo {
            mass: 1.0,
            linear_damping: 0.0,
            angular_damping: 0.0,
            friction: 0.5,
            restitution: 0.0,
            additional_damping: false,
            is_kinematic: false,
            disable_deactivation: true,
            no_contact_response: false,
            initial_transform: Mat4::from_translation(pos),
        };
        let body = BulletRigidBody::new(&info, &shape).expect("dynamic sphere");
        world.add_rigid_body(&body, 1, 0xFFFF);
        (body, shape)
    }

    #[test]
    fn world_to_bullet_box_transforms_position_scale_and_yaw() {
        // 模型位于 (10, 64, 20)，绕 Y 轴顺时针转 90° (yaw = +90° 右手系)
        let model_pos = Vec3::new(10.0, 64.0, 20.0);
        let rot = Quat::from_rotation_y(std::f32::consts::FRAC_PI_2);
        let model_transform = Mat4::from_rotation_translation(rot, model_pos);
        let total_scale = 0.1;

        // 方块中心在世界 (10, 65, 22)，即在模型 +Z_world 方向 2 格、+Y 1 格
        let aabb = [9.5, 64.5, 21.5, 10.5, 65.5, 22.5];
        let (bullet_tf, half_extents) =
            world_to_bullet_box(model_transform, total_scale, aabb).expect("valid aabb");

        // 半尺寸：0.5 / 0.1 = 5.0
        assert!(half_extents.abs_diff_eq(Vec3::splat(5.0), 1e-4));

        // 绕 Y 转 +90° 后，模型局部 +X_right 指向 -Z_world，局部 +Z_right 指向 +X_world
        // 因此 delta_world = (0, 1, 2) 在局部右手系中为 (-20, 10, 0)
        // 转到左手系 (Z 取反) 后仍为 (-20, 10, 0)
        let pos = bullet_tf.w_axis.truncate();
        assert!(pos.abs_diff_eq(Vec3::new(-20.0, 10.0, 0.0), 1e-3), "pos={pos:?}");

        // 验证 Box 旋转矩阵保持世界轴对齐方块在局部空间中的逆旋转朝向
        let local_x_in_bullet = bullet_tf.transform_vector3(Vec3::X);
        // 世界 +X 在模型局部右手系中为 (0, 0, 1)，左手系 Z 取反后为 (0, 0, -1)
        assert!(
            local_x_in_bullet.abs_diff_eq(Vec3::new(0.0, 0.0, -1.0), 1e-3),
            "local_x={local_x_in_bullet:?}"
        );
    }

    #[test]
    fn ground_box_supports_falling_dynamic_body() {
        let _guard = ALLOC_TEST_LOCK.lock().unwrap_or_else(|e| e.into_inner());
        {
            let world = BulletWorld::new(0.0, -98.0, 0.0).expect("world");
            let mut env = EnvironmentColliders::default();

            // 模型脚底在世界 y = 64.0，地面在 y = 64.0 -> local_ground_y = 0.0
            let model_tf = Mat4::from_translation(Vec3::new(0.0, 64.0, 0.0));
            env.sync(&world, model_tf, 0.09, Some(64.0), &[]);

            let (sphere, _shape) = create_dynamic_sphere(&world, Vec3::new(0.0, 3.0, 0.0), 1.0);

            for _ in 0..120 {
                world.step(1.0 / 60.0, 5, 1.0 / 60.0);
            }

            let final_y = sphere.get_position().y;
            // 球体半径 1.0，应稳定停在 local_ground_y + 1.0 = 1.0 附近，绝不穿透到地面以下
            assert!(
                final_y > 0.85 && final_y < 1.25,
                "falling sphere should rest on ground plane, got y={final_y}"
            );

            world.remove_rigid_body(&sphere);
            env.clear(&world);
        }
        assert!(super::super::bullet_ffi::get_alloc_stats().is_clean());
    }

    #[test]
    fn block_collider_blocks_moving_dynamic_body() {
        let _guard = ALLOC_TEST_LOCK.lock().unwrap_or_else(|e| e.into_inner());
        {
            let world = BulletWorld::new(0.0, 0.0, 0.0).expect("world");
            let mut env = EnvironmentColliders::default();

            let model_tf = Mat4::IDENTITY;
            let total_scale = 0.1;
            // 在世界 x in [0.5, 1.5], y in [-1.0, 2.0], z in [-1.0, 1.0] 放一块墙
            // 对应模型局部 x in [5.0, 15.0]
            let wall = [0.5, -1.0, -1.0, 1.5, 2.0, 1.0];
            env.sync(&world, model_tf, total_scale, None, &[wall]);

            let (sphere, _shape) = create_dynamic_sphere(&world, Vec3::new(0.0, 0.0, 0.0), 1.0);
            sphere.set_linear_velocity(30.0, 0.0, 0.0);

            for _ in 0..60 {
                world.step(1.0 / 60.0, 5, 1.0 / 60.0);
            }

            let final_x = sphere.get_position().x;
            // 球体半径 1.0，墙左面在 x = 5.0，球心应被挡在 x <= 4.1 附近
            assert!(
                final_x < 4.5,
                "moving sphere should be blocked by wall AABB, got x={final_x}"
            );

            world.remove_rigid_body(&sphere);
            env.clear(&world);
        }
        assert!(super::super::bullet_ffi::get_alloc_stats().is_clean());
    }

    #[test]
    fn pool_caps_at_sixteen_and_reuses_slots_when_stationary() {
        let _guard = ALLOC_TEST_LOCK.lock().unwrap_or_else(|e| e.into_inner());
        {
            let world = BulletWorld::new(0.0, -98.0, 0.0).expect("world");
            let mut env = EnvironmentColliders::default();
            let aabbs: Vec<[f32; 6]> = (0..24)
                .map(|i| {
                    let x = i as f32 * 2.0;
                    [x, 0.0, 0.0, x + 1.0, 1.0, 1.0]
                })
                .collect();

            env.sync(&world, Mat4::IDENTITY, 0.09, Some(0.0), &aabbs);
            assert_eq!(env.block_pool.len(), MAX_BLOCK_COLLIDERS);

            // 静止重复同步命中缓存，不重建槽位
            let first_ptr = env.block_pool[0].body.as_ptr();
            env.sync(&world, Mat4::IDENTITY, 0.09, Some(0.0), &aabbs);
            assert_eq!(env.block_pool[0].body.as_ptr(), first_ptr);

            env.clear(&world);
            assert!(env.ground_body.is_none());
            assert!(env.block_pool.is_empty());
        }
        assert!(super::super::bullet_ffi::get_alloc_stats().is_clean());
    }

    #[test]
    fn mmd_physics_sync_and_drop_cleans_alloc_stats() {
        let _guard = ALLOC_TEST_LOCK.lock().unwrap_or_else(|e| e.into_inner());
        crate::physics::config::reset_config();
        {
            let mut physics =
                crate::physics::upstream_adapter::MMDPhysics::new().expect("MMDPhysics");
            let wall = [0.5, 0.0, -0.5, 1.5, 1.0, 0.5];
            physics.sync_environment_colliders(Mat4::IDENTITY, 0.09, Some(0.0), &[wall]);
            physics.step_simulation(1.0 / 60.0);

            // 关闭开关后同步应安全移除环境碰撞体
            let mut cfg = crate::physics::config::get_config();
            cfg.ground_collision_enabled = false;
            cfg.block_collision_enabled = false;
            crate::physics::config::set_config(cfg);
            physics.sync_environment_colliders(Mat4::IDENTITY, 0.09, Some(0.0), &[wall]);
        }
        crate::physics::config::reset_config();
        assert!(super::super::bullet_ffi::get_alloc_stats().is_clean());
    }
}
