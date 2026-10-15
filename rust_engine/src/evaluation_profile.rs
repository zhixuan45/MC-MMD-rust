//! Opt-in, thread-local evaluation sample. No clocks, allocation or JNI readback when disabled.
use std::cell::Cell;
use std::time::Instant;

#[derive(Clone, Copy, Default, Debug)]
pub(crate) struct Sample {
    pub nanos: [u64; 4], // animation, IK, physics, CPU skinning (exclusive stages)
    pub steps: u64,
    pub substeps: u64,
    pub requested_substeps: u64,
}

thread_local! { static CURRENT: Cell<Option<Sample>> = const { Cell::new(None) }; }

pub(crate) fn enabled() -> bool { CURRENT.with(|v| v.get().is_some()) }
pub(crate) fn timer() -> Option<Instant> { enabled().then(Instant::now) }
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
    impl Drop for Restore { fn drop(&mut self) { CURRENT.with(|v| v.set(self.0)); } }
    let previous = CURRENT.with(|v| v.replace(Some(Sample::default())));
    let _restore = Restore(previous);
    let result = run();
    (result, CURRENT.with(|v| v.get().unwrap_or_default()))
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn sampling_is_opt_in_and_thread_local() {
        assert!(timer().is_none());
        let (_, sample) = capture(|| {
            step(2, 5);
            std::thread::spawn(|| { assert!(!enabled()); step(10, 10); }).join().unwrap();
            let (_, nested) = capture(|| step(1, 1));
            assert_eq!(nested.substeps, 1);
            step(0, 0);
        });
        assert_eq!((sample.steps, sample.substeps, sample.requested_substeps), (2, 2, 5));
        assert!(!enabled());
    }
    #[test]
    fn restores_sampler_after_unwind() {
        let _ = std::panic::catch_unwind(|| capture(|| panic!("test")));
        assert!(!enabled());
    }
}
