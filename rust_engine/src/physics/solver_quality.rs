use std::sync::atomic::{AtomicI32, Ordering};

static DEFAULT_ITERATIONS: AtomicI32 = AtomicI32::new(10);

pub fn default_iterations() -> i32 {
    DEFAULT_ITERATIONS.load(Ordering::Relaxed)
}

pub fn set_default_iterations(iterations: i32) -> bool {
    if !valid_iterations(iterations) {
        return false;
    }
    DEFAULT_ITERATIONS.store(iterations, Ordering::Relaxed);
    true
}

fn valid_iterations(iterations: i32) -> bool {
    (1..=80).contains(&iterations)
}

#[cfg(test)]
mod tests {
    use super::valid_iterations;

    #[test]
    fn solver_iteration_range_is_bounded() {
        assert!(!valid_iterations(-1));
        assert!(!valid_iterations(0));
        assert!(valid_iterations(1));
        assert!(valid_iterations(9));
        assert!(valid_iterations(10));
        assert!(valid_iterations(30));
        assert!(valid_iterations(40));
        assert!(valid_iterations(80));
        assert!(!valid_iterations(81));
    }
}
