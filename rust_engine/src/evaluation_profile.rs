//! Opt-in, thread-local evaluation sample. No clocks, allocation or JNI readback when disabled.
use std::cell::Cell;
use std::time::Instant;

#[derive(Clone, Copy, Default, Debug)]
pub(crate) struct Sample {
    pub nanos: [u64; 4], // animation, IK, physics, CPU skinning (exclusive stages)
    pub steps: u64,
    pub substeps: u64,
    pub requested_substeps: u64,
    pub matrix_parallel_batches: u64,
    pub matrix_parallel_width: u64,
    pub detail_nanos: [u64; 6], // 收集、同步、Bullet、限速诊断、回写、矩阵
    pub rigid_bodies: u64,
    pub constraints: u64,
    pub manifolds: u64,
    pub contacts: u64,
    pub thread_budget: u64,
    pub physics_parallel_requested: u64,
    pub bullet_parallel_width: u64,
    pub physics_peak: u64,
    pub physics_tasks: u64,
    pub physics_islands: u64,
    pub max_island_bodies: u64,
    pub max_island_constraints: u64,
    pub matrix_tasks: u64,
    pub matrix_peak: u64,
}

pub(crate) fn matrix_parallel(width: usize, tasks: usize, peak: usize) {
    CURRENT.with(|v| {
        if let Some(mut sample) = v.get() {
            sample.matrix_parallel_batches += 1;
            sample.matrix_parallel_width = width as u64;
            sample.matrix_tasks += tasks as u64;
            sample.matrix_peak = peak as u64;
            v.set(Some(sample));
        }
    });
}

pub(crate) fn detail_stage(start: Option<Instant>, index: usize) {
    if let Some(start) = start {
        CURRENT.with(|v| {
            if let Some(mut sample) = v.get() {
                if let Some(total) = sample.detail_nanos.get_mut(index) {
                    *total += start.elapsed().as_nanos().min(u64::MAX as u128) as u64;
                }
                v.set(Some(sample));
            }
        });
    }
}

pub(crate) fn physics_counts(bodies: usize, constraints: usize, manifolds: usize, contacts: usize) {
    CURRENT.with(|v| {
        if let Some(mut sample) = v.get() {
            sample.rigid_bodies = bodies as u64;
            sample.constraints = constraints as u64;
            sample.manifolds = manifolds as u64;
            sample.contacts = contacts as u64;
            v.set(Some(sample));
        }
    });
}

pub(crate) fn physics_parallel_stats(
    width: usize,
    peak: usize,
    tasks: usize,
    islands: usize,
    max_bodies: usize,
    max_constraints: usize,
) {
    CURRENT.with(|v| {
        if let Some(mut sample) = v.get() {
            sample.bullet_parallel_width = width as u64;
            sample.physics_peak = peak as u64;
            sample.physics_tasks = tasks as u64;
            sample.physics_islands = islands as u64;
            sample.max_island_bodies = max_bodies as u64;
            sample.max_island_constraints = max_constraints as u64;
            v.set(Some(sample));
        }
    });
}

thread_local! { static CURRENT: Cell<Option<Sample>> = const { Cell::new(None) }; }
thread_local! { static FAILED: Cell<bool> = const { Cell::new(false) }; }

pub(crate) fn mark_failed() {
    FAILED.with(|value| value.set(true));
}

pub(crate) fn has_failed() -> bool {
    FAILED.with(Cell::get)
}

pub(crate) fn failure_scope<T>(run: impl FnOnce() -> T) -> (T, bool) {
    struct Restore(bool);
    impl Drop for Restore {
        fn drop(&mut self) {
            FAILED.with(|value| value.set(self.0));
        }
    }
    let previous = FAILED.with(|value| value.replace(false));
    let restore = Restore(previous);
    let result = run();
    let failed = FAILED.with(Cell::get);
    drop(restore);
    (result, failed)
}

pub(crate) fn enabled() -> bool {
    CURRENT.with(|v| v.get().is_some())
}
pub(crate) fn timer() -> Option<Instant> {
    enabled().then(Instant::now)
}
pub(crate) fn stage(start: Option<Instant>, index: usize) {
    if let Some(start) = start {
        CURRENT.with(|v| {
            if let Some(mut sample) = v.get() {
                sample.nanos[index] += start.elapsed().as_nanos().min(u64::MAX as u128) as u64;
                v.set(Some(sample));
            }
        });
    }
}
pub(crate) fn step(substeps: i32, requested: i32) {
    CURRENT.with(|v| {
        if let Some(mut sample) = v.get() {
            sample.steps += 1;
            sample.substeps += substeps.max(0) as u64;
            sample.requested_substeps += requested.max(0) as u64;
            v.set(Some(sample));
        }
    });
}

/// Restores the previous sampler even if evaluation unwinds in a Rust test.
pub(crate) fn capture<T>(run: impl FnOnce() -> T) -> (T, Sample) {
    struct Restore(Option<Sample>);
    impl Drop for Restore {
        fn drop(&mut self) {
            CURRENT.with(|v| v.set(self.0));
        }
    }
    let previous = CURRENT.with(|v| v.replace(Some(Sample::default())));
    let _restore = Restore(previous);
    let result = run();
    (result, CURRENT.with(|v| v.get().unwrap_or_default()))
}

pub(crate) fn set_policy(budget: usize, physics_parallel: bool) {
    CURRENT.with(|v| {
        if let Some(mut sample) = v.get() {
            sample.thread_budget =
                crate::evaluation_parallelism::bounded_thread_budget(budget) as u64;
            sample.physics_parallel_requested = u64::from(physics_parallel);
            v.set(Some(sample));
        }
    });
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn sampling_is_opt_in_and_thread_local() {
        assert!(timer().is_none());
        let (_, sample) = capture(|| {
            step(2, 5);
            std::thread::spawn(|| {
                assert!(!enabled());
                step(10, 10);
            })
            .join()
            .unwrap();
            let (_, nested) = capture(|| step(1, 1));
            assert_eq!(nested.substeps, 1);
            step(0, 0);
        });
        assert_eq!(
            (sample.steps, sample.substeps, sample.requested_substeps),
            (2, 2, 5)
        );
        assert!(!enabled());
    }
    #[test]
    fn restores_sampler_after_unwind() {
        let _ = std::panic::catch_unwind(|| capture(|| panic!("test")));
        assert!(!enabled());
    }

    #[test]
    fn failure_scope_reports_and_restores_failure_state() {
        let (_, failed) = failure_scope(|| mark_failed());
        assert!(failed);
        assert!(!has_failed());
        let (_, failed) = failure_scope(|| ());
        assert!(!failed);
    }
}
