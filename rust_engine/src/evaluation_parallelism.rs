//! 单模型内部并行策略；不改变全局 Rayon 池。
use once_cell::sync::Lazy;
use rayon::ThreadPool;
use std::cell::Cell;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Arc, RwLock};

#[derive(Clone, Copy, Default)]
pub(crate) struct EvaluationPolicy {
    pub thread_budget: usize,
    pub physics_parallel: bool,
}

thread_local! {
    static CURRENT: Cell<EvaluationPolicy> = const { Cell::new(EvaluationPolicy {
        thread_budget: 1,
        physics_parallel: false,
    }) };
}

static MATRIX_POOL: Lazy<RwLock<Option<(usize, Arc<ThreadPool>)>>> =
    Lazy::new(|| RwLock::new(None));

pub(crate) fn bounded_thread_budget(requested: usize) -> usize {
    let available = std::thread::available_parallelism()
        .map(usize::from)
        .unwrap_or(1);
    requested.min(available)
}

pub(crate) fn with_pool_for_budget<T: Send>(
    budget: usize,
    run: impl FnOnce() -> T + Send,
) -> Option<T> {
    let width = bounded_thread_budget(budget);
    if width < 2 {
        return None;
    }
    let Ok(current) = MATRIX_POOL.read() else {
        return None;
    };
    if let Some((_threads, pool)) = current.as_ref().filter(|(threads, _)| *threads == width) {
        return Some(pool.install(run));
    }
    drop(current);

    // 切换预算时排空旧池调用，再原位替换，常态不持有写锁。
    let Ok(mut current) = MATRIX_POOL.write() else {
        return None;
    };
    if current.as_ref().map(|(threads, _)| *threads) != Some(width) {
        let pool = rayon::ThreadPoolBuilder::new()
            .num_threads(width)
            .thread_name(|index| format!("mmd-eval-{index}"))
            .build()
            .ok()
            .map(Arc::new)?;
        *current = Some((width, pool));
    }
    let (_, pool) = current.as_ref()?;
    Some(pool.install(run))
}

pub(crate) fn with_policy<T>(policy: EvaluationPolicy, run: impl FnOnce() -> T) -> T {
    struct Restore(EvaluationPolicy);
    impl Drop for Restore {
        fn drop(&mut self) {
            CURRENT.with(|v| v.set(self.0));
        }
    }
    let previous = CURRENT.with(|v| v.replace(policy));
    let _restore = Restore(previous);
    run()
}

pub(crate) fn current_policy() -> EvaluationPolicy {
    CURRENT.with(Cell::get)
}

/// 在完整姿态生成后并行写独立矩阵槽。
pub(crate) fn update_skinning_matrices(
    links: &[crate::skeleton::BoneLink],
    output: &mut [glam::Mat4],
    budget: usize,
) -> Option<(usize, usize)> {
    const MIN_BONES: usize = 128;
    if budget < 2 || links.len() < MIN_BONES || output.len() != links.len() {
        return None;
    }
    with_pool_for_budget(budget, || {
        if crate::evaluation_profile::enabled() {
            let tasks = AtomicUsize::new(0);
            let active = AtomicUsize::new(0);
            let peak = AtomicUsize::new(0);
            use rayon::prelude::*;
            output.par_iter_mut().zip(links.par_iter()).for_each_init(
                || {
                    tasks.fetch_add(1, Ordering::Relaxed);
                    let now = active.fetch_add(1, Ordering::Relaxed) + 1;
                    peak.fetch_max(now, Ordering::Relaxed);
                    WorkerGuard(&active)
                },
                |_, (dst, bone)| *dst = bone.get_skinning_matrix(),
            );
            (tasks.load(Ordering::Relaxed), peak.load(Ordering::Relaxed))
        } else {
            use rayon::prelude::*;
            output
                .par_iter_mut()
                .zip(links.par_iter())
                .for_each(|(dst, bone)| *dst = bone.get_skinning_matrix());
            (0, 0)
        }
    })
}

struct WorkerGuard<'a>(&'a AtomicUsize);
impl Drop for WorkerGuard<'_> {
    fn drop(&mut self) {
        self.0.fetch_sub(1, Ordering::Relaxed);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use glam::{Mat4, Vec3};
    use std::path::Path;
    use std::time::Instant;

    fn evaluate(
        model: &mut crate::model::MmdModel,
        dt: f32,
        policy: EvaluationPolicy,
    ) -> (u64, crate::evaluation_profile::Sample, bool) {
        let started = Instant::now();
        let ((_, sample), failed) = crate::evaluation_profile::failure_scope(|| {
            crate::evaluation_profile::capture(|| {
                crate::evaluation_profile::set_policy(
                    policy.thread_budget,
                    policy.physics_parallel,
                );
                model.tick_animation_with_policy(dt, false, None, policy);
            })
        });
        (started.elapsed().as_nanos() as u64, sample, failed)
    }

    #[test]
    fn matrix_kernel_matches_sequential_output_and_obeys_threshold() {
        let mut links: Vec<_> = (0..128)
            .map(|i| {
                let mut bone = crate::skeleton::BoneLink::new(format!("bone-{i}"));
                bone.local_to_world = Mat4::from_translation(Vec3::new(i as f32, 2.0, -3.0));
                bone
            })
            .collect();
        for (index, bone) in links.iter_mut().enumerate() {
            bone.inverse_init = Mat4::from_scale(Vec3::splat(1.0 + index as f32 * 0.001));
        }
        let expected: Vec<_> = links
            .iter()
            .map(|bone| bone.get_skinning_matrix())
            .collect();
        let mut actual = vec![Mat4::IDENTITY; links.len()];
        assert!(update_skinning_matrices(&links, &mut actual, 4).is_some());
        assert_eq!(actual, expected);
        assert!(update_skinning_matrices(&links, &mut actual, 1).is_none());
        assert!(update_skinning_matrices(&links[..32], &mut actual[..32], 4).is_none());
    }

    #[test]
    #[ignore = "需要 MMD_PARALLEL_PROBE_MODEL 指向只读杏辞 PMX"]
    fn real_asset_parallel_ab_probe() {
        let path = std::env::var("MMD_PARALLEL_PROBE_MODEL").expect("设置杏辞 PMX 路径");
        let mut config = crate::physics::config::PhysicsConfig::default();
        config.debug_log = false;
        crate::physics::config::set_config(config);
        let mut serial = crate::model::load_pmx(Path::new(&path)).expect("加载顺序参照");
        let mut parallel = crate::model::load_pmx(Path::new(&path)).expect("加载并行模型");
        for model in [&mut serial, &mut parallel] {
            model.update_node_animation(false);
            assert!(model.init_physics(), "真实模型 Bullet 初始化");
        }
        let cores = std::thread::available_parallelism()
            .map(usize::from)
            .unwrap_or(1);
        let budget = cores.saturating_sub(1).max(1);
        let serial_policy = EvaluationPolicy {
            thread_budget: 1,
            physics_parallel: false,
        };
        let parallel_policy = EvaluationPolicy {
            thread_budget: budget,
            physics_parallel: true,
        };
        let mut serial_total = Vec::new();
        let mut parallel_total = Vec::new();
        let mut serial_matrix = Vec::new();
        let mut parallel_matrix = Vec::new();
        let mut serial_bullet = Vec::new();
        let mut parallel_bullet = Vec::new();
        let mut matrix_tasks = 0;
        let mut matrix_peak = 0;
        let mut physics_tasks = 0;
        let mut physics_peak = 0;
        let mut bullet_width = 1;
        let mut max_position_error = 0.0_f32;
        let mut max_angle_error = 0.0_f32;
        let mut max_body_error = 0.0_f32;
        let mut max_anchor_delta = 0.0_f32;
        let mut max_linear_limit_delta = 0.0_f32;
        let mut max_angular_limit_delta = 0.0_f32;
        let mut serial_contacts = 0;
        let mut parallel_contacts = 0;
        let dt = 1.0 / 60.0;
        for frame in 0..180 {
            let angle = 0.15 * (std::f32::consts::TAU * frame as f32 / 240.0).sin();
            serial.set_head_angle(0.0, angle, 0.0);
            parallel.set_head_angle(0.0, angle, 0.0);
            let (serial_ns, sample, sample_parallel_ns, sample_parallel) =
                if frame % 2 == 0 {
                    let (serial_ns, sample, failed) = evaluate(&mut serial, dt, serial_policy);
                    assert!(!failed, "顺序物理步失败");
                    let (parallel_ns, sample_parallel, failed) =
                        evaluate(&mut parallel, dt, parallel_policy);
                    assert!(!failed, "并行物理步失败");
                    (serial_ns, sample, parallel_ns, sample_parallel)
                } else {
                    let (parallel_ns, sample_parallel, failed) =
                        evaluate(&mut parallel, dt, parallel_policy);
                    assert!(!failed, "并行物理步失败");
                    let (serial_ns, sample, failed) = evaluate(&mut serial, dt, serial_policy);
                    assert!(!failed, "顺序物理步失败");
                    (serial_ns, sample, parallel_ns, sample_parallel)
                };
            if frame >= 60 {
                serial_total.push(serial_ns);
                parallel_total.push(parallel_ns);
                serial_matrix.push(sample.detail_nanos[5]);
                parallel_matrix.push(sample_parallel.detail_nanos[5]);
                serial_bullet.push(sample.detail_nanos[2]);
                parallel_bullet.push(sample_parallel.detail_nanos[2]);
                matrix_tasks += sample_parallel.matrix_tasks;
                matrix_peak = matrix_peak.max(sample_parallel.matrix_peak);
                physics_tasks += sample_parallel.physics_tasks;
                physics_peak = physics_peak.max(sample_parallel.physics_peak);
                bullet_width = bullet_width.max(sample_parallel.bullet_parallel_width);
            }
            let count = serial.bone_manager.bone_count();
            assert_eq!(count, parallel.bone_manager.bone_count());
            for index in 0..count {
                let a = serial.bone_manager.get_global_transform(index);
                let b = parallel.bone_manager.get_global_transform(index);
                let (_, qa, pa) = a.to_scale_rotation_translation();
                let (_, qb, pb) = b.to_scale_rotation_translation();
                max_position_error = max_position_error.max(pa.distance(pb));
                max_angle_error =
                    max_angle_error.max(2.0 * qa.dot(qb).abs().clamp(0.0, 1.0).acos());
            }
            if frame % 10 == 0 {
                let serial_bodies = serial.physics_body_snapshots_matching("");
                let parallel_bodies = parallel.physics_body_snapshots_matching("");
                for (a, b) in serial_bodies.iter().zip(&parallel_bodies) {
                    max_body_error = max_body_error.max(
                        a.simulation_transform
                            .w_axis
                            .truncate()
                            .distance(b.simulation_transform.w_axis.truncate()),
                    );
                }
                let serial_joints = serial.physics_joint_snapshots_matching("");
                let parallel_joints = parallel.physics_joint_snapshots_matching("");
                for (a, b) in serial_joints.iter().zip(&parallel_joints) {
                    max_anchor_delta =
                        max_anchor_delta.max((a.anchor_error - b.anchor_error).abs());
                    max_linear_limit_delta = max_linear_limit_delta.max(
                        (a.diagnostic.linear_violation.length()
                            - b.diagnostic.linear_violation.length())
                        .abs(),
                    );
                    max_angular_limit_delta = max_angular_limit_delta.max(
                        (a.diagnostic.angular_violation.length()
                            - b.diagnostic.angular_violation.length())
                        .abs(),
                    );
                }
                serial_contacts = serial.physics_contact_points_matching("", 1).total_points;
                parallel_contacts = parallel.physics_contact_points_matching("", 1).total_points;
            }
        }
        fn median(values: &mut [u64]) -> u64 {
            values.sort_unstable();
            values[values.len() / 2]
        }
        let serial_bodies = serial.physics_body_snapshots_matching("");
        let parallel_bodies = parallel.physics_body_snapshots_matching("");
        let joints = serial.physics_joint_snapshots_matching("").len();
        eprintln!(
            "REAL_ASSET_AB bones={} bodies={}/{} constraints={} contacts={}/{} budget={} matrix_p50_ns={}/{} bullet_p50_ns={}/{} total_p50_ns={}/{} matrix_tasks={} matrix_peak={} bullet_width={} physics_tasks={} physics_peak={} max_pose_pos={:.6} max_pose_angle={:.6} max_body_pos={:.6} max_anchor_delta={:.6} max_linear_limit_delta={:.6} max_angular_limit_delta={:.6}",
            serial.bone_manager.bone_count(), serial_bodies.len(), parallel_bodies.len(),
            joints, serial_contacts, parallel_contacts, budget,
            median(&mut serial_matrix), median(&mut parallel_matrix),
            median(&mut serial_bullet), median(&mut parallel_bullet),
            median(&mut serial_total), median(&mut parallel_total),
            matrix_tasks, matrix_peak, bullet_width, physics_tasks, physics_peak,
            max_position_error, max_angle_error, max_body_error, max_anchor_delta,
            max_linear_limit_delta, max_angular_limit_delta,
        );
        // 这些空间误差阈值只用于探针筛出明显回归，不代表游戏视觉验收。
        assert!(max_position_error.is_finite() && max_angle_error.is_finite());
        assert!(max_body_error.is_finite());
        assert!(max_anchor_delta.is_finite());
        assert!(max_linear_limit_delta.is_finite() && max_angular_limit_delta.is_finite());
        assert!(max_position_error <= 0.05, "探针骨骼位置差超限");
        assert!(max_angle_error <= 0.25, "探针骨骼角度差超限");
        assert!(max_body_error <= 0.05, "探针刚体位置差超限");
        assert!(max_anchor_delta <= 0.05, "探针锚点差超限");
        assert!(max_linear_limit_delta <= 0.05, "探针线性限位差超限");
        assert!(max_angular_limit_delta <= 0.25, "探针角度限位差超限");
    }
}
