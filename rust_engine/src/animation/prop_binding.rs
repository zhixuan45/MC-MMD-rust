use std::collections::HashMap;

/// VMD outside-parent switch window for one prop bone.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct OutsideParentWindow {
    pub previous: u32,
    pub next: u32,
}

impl OutsideParentWindow {
    #[inline]
    pub fn is_active(self) -> bool {
        self.previous != 0 || self.next != 0
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
#[repr(i32)]
pub enum PropBindingError {
    MissingModel = -1,
    MissingBone = -2,
    DynamicBone = -3,
    InvalidMatrix = -4,
    InvalidWindow = -5,
    InvalidPacket = -6,
    Internal = -7,
}

/// Immutable animation sampling inputs for one atomic prop binding update.
#[derive(Clone, Debug, Default)]
pub struct PropBindingState {
    pub parents: HashMap<usize, glam::Mat4>,
    pub windows: HashMap<usize, OutsideParentWindow>,
}

impl PropBindingState {
    pub fn from_slices(
        parents: &[(usize, glam::Mat4)],
        windows: &[(usize, OutsideParentWindow)],
    ) -> Result<Self, PropBindingError> {
        let mut state = Self::default();
        for &(index, matrix) in parents {
            if state.parents.insert(index, matrix).is_some() {
                return Err(PropBindingError::InvalidPacket);
            }
        }
        for &(index, window) in windows {
            if window.next != 0 && window.previous >= window.next {
                return Err(PropBindingError::InvalidWindow);
            }
            if state.windows.insert(index, window).is_some() {
                return Err(PropBindingError::InvalidPacket);
            }
        }
        Ok(state)
    }

    #[inline]
    pub fn window(&self, index: usize) -> Option<OutsideParentWindow> {
        self.windows.get(&index).copied()
    }
}
