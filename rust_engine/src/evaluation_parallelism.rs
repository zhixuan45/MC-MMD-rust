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

}
