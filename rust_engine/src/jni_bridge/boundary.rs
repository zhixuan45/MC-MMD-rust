//! Shared panic and lock-poison handling for JNI exports.

use std::any::Any;
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::sync::PoisonError;

use jni::JNIEnv;

const MAX_PANIC_MESSAGE_CHARS: usize = 4096;

/// Executes a JNI body, converting any Rust panic into a Java exception and
/// returning the ABI-specific failure value.
pub(crate) fn guard<'local, R, F>(env: &mut JNIEnv<'local>, default: R, body: F) -> R
where
    F: FnOnce(&mut JNIEnv<'local>) -> R,
{
    guard_with_reporter(env, default, body, |env, message| {
        report_panic(env, message)
    })
}

fn guard_with_reporter<C, R, F, P>(context: &mut C, default: R, body: F, reporter: P) -> R
where
    F: FnOnce(&mut C) -> R,
    P: FnOnce(&mut C, String),
{
    match catch_unwind(AssertUnwindSafe(|| body(context))) {
        Ok(value) => value,
        Err(payload) => {
            reporter(context, panic_message(payload.as_ref()));
            default
        }
    }
}

/// Refuses to use a poisoned mutex. The enclosing JNI guard reports the panic
/// as a Java exception and returns the export's failure value.
pub(crate) fn reject_poison<T>(_: PoisonError<T>) -> T {
    panic!("JNI boundary lock poisoned; refusing possibly partial state")
}

fn report_panic(env: &mut JNIEnv<'_>, message: String) {
    let reported = catch_unwind(AssertUnwindSafe(|| report_panic_inner(env, &message)));
    if reported.is_err() {
        log::error!("JNI panic reporting failed while reporting: {message}");
    }
}

fn report_panic_inner(env: &mut JNIEnv<'_>, message: &str) {
    log::error!("JNI export panicked: {message}");

    match env.exception_check() {
        Ok(true) => {
            log::error!("JNI export panic preserved an already pending Java exception");
            return;
        }
        Ok(false) => {}
        Err(error) => {
            log::error!("JNI exception_check failed while reporting panic: {error}");
        }
    }

    let java_message = format!("Rust JNI panic: {message}");
    if let Err(error) = env.throw_new("java/lang/RuntimeException", java_message) {
        log::error!("JNI throw_new(RuntimeException) failed: {error}");
    }
}

fn panic_message(payload: &(dyn Any + Send)) -> String {
    let message = if let Some(message) = payload.downcast_ref::<&str>() {
        (*message).to_owned()
    } else if let Some(message) = payload.downcast_ref::<String>() {
        message.clone()
    } else {
        "non-string panic payload".to_owned()
    };

    message.chars().take(MAX_PANIC_MESSAGE_CHARS).collect()
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::{Arc, Mutex, MutexGuard, RwLock, RwLockReadGuard, RwLockWriteGuard};

    #[test]
    fn guard_helper_returns_value_without_reporting() {
        let mut reports: Vec<String> = Vec::new();

        let value = guard_with_reporter(
            &mut reports,
            42,
            |_| 7,
            |_, _| panic!("reporter must not run"),
        );

        assert_eq!(value, 7);
        assert!(reports.is_empty());
    }

    #[test]
    fn guard_helper_maps_panic_to_failure_and_reports_once() {
        let mut reports = Vec::new();

        let value = guard_with_reporter(
            &mut reports,
            -7,
            |_| -> i32 { panic!("boundary panic") },
            |reports: &mut Vec<String>, message| reports.push(message),
        );

        assert_eq!(value, -7);
        assert_eq!(reports, ["boundary panic"]);
    }

    #[test]
    fn guard_helper_maps_poison_to_failure_and_reports_once() {
        let mutex = Arc::new(Mutex::new(0));
        let poisoner = Arc::clone(&mutex);
        let _ = std::thread::spawn(move || {
            let _guard = poisoner.lock().expect("initial mutex lock");
            panic!("poison mutex");
        })
        .join();

        let mut reports = Vec::new();
        let value = guard_with_reporter(
            &mut reports,
            -7,
            |_| {
                let _guard: MutexGuard<'_, i32> = mutex.lock().unwrap_or_else(reject_poison);
                0
            },
            |reports: &mut Vec<String>, message| reports.push(message),
        );

        assert_eq!(value, -7);
        assert_eq!(reports.len(), 1);
        assert!(reports[0].contains("lock poisoned"));
        assert!(mutex.is_poisoned());
    }

    #[test]
    fn rwlock_poison_is_rejected_instead_of_reused() {
        let lock = Arc::new(RwLock::new(0));
        let poisoner = Arc::clone(&lock);
        let _ = std::thread::spawn(move || {
            let _guard = poisoner.write().expect("initial rwlock write");
            panic!("poison rwlock");
        })
        .join();

        let mut reports = Vec::new();
        let read_value = guard_with_reporter(
            &mut reports,
            -7,
            |_| {
                let _guard: RwLockReadGuard<'_, i32> = lock.read().unwrap_or_else(reject_poison);
                0
            },
            |reports: &mut Vec<String>, message| reports.push(message),
        );
        let write_value = guard_with_reporter(
            &mut reports,
            -7,
            |_| {
                let _guard: RwLockWriteGuard<'_, i32> = lock.write().unwrap_or_else(reject_poison);
                0
            },
            |reports: &mut Vec<String>, message| reports.push(message),
        );

        assert_eq!(read_value, -7);
        assert_eq!(write_value, -7);
        assert_eq!(reports.len(), 2);
        assert!(lock.is_poisoned());
    }
}
