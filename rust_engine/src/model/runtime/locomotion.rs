//! Contact foot placement and visual body height for entity locomotion.

use std::sync::Arc;

use glam::{DVec3, IVec3, Vec3, Vec3Swizzles};

use crate::animation::{AnimationLayerManager, VmdAnimation};
use crate::skeleton::BoneManager;

pub(crate) const MAX_SURFACES: usize = 256;
const FEET: [[&str; 3]; 2] = [
    ["左足ＩＫ", "左足IK", "左つま先ＩＫ"],
    ["右足ＩＫ", "右足IK", "右つま先ＩＫ"],
];
const STEP_TIME: f32 = 0.125;
const MAX_BODY_OFFSET: f32 = 0.30;

#[derive(Default)]
pub(crate) struct LocomotionInput {
    pub mode: i32,
    pub grounded: bool,
    pub terrain: bool,
    pub tick: i64,
    pub frame: i64,
    pub world: i32,
    pub block: IVec3,
    pub origin: Vec3,
    pub entity: Vec3,
    pub movement: Vec3,
    pub yaw: f32,
    pub scale: f32,
    pub surfaces: Vec<[f32; 6]>,
}

impl LocomotionInput {
    pub fn has_terrain(&self) -> bool {
        self.mode != 0 && self.terrain && self.continuous() && !self.surfaces.is_empty()
    }

    fn continuous(&self) -> bool {
        self.mode != 0
            && self.origin.is_finite()
            && self.movement.is_finite()
            && self.movement.x.hypot(self.movement.z) <= 0.85
            && self.movement.y.abs() <= 0.85
            && self.yaw.is_finite()
            && self.entity.is_finite()
            && self.scale.is_finite()
            && self.scale > 0.0001
    }

    fn to_world(&self, model: Vec3) -> DVec3 {
        self.block.as_dvec3() + (self.origin + self.rotate(model) * self.scale).as_dvec3()
    }

    fn model_vector(&self, world: Vec3) -> Vec3 {
        let (sin, cos) = self.yaw.sin_cos();
        Vec3::new(
            cos * world.x + sin * world.z,
            world.y,
            -sin * world.x + cos * world.z,
        ) / self.scale
    }

    fn rotate(&self, model: Vec3) -> Vec3 {
        let (sin, cos) = self.yaw.sin_cos();
        Vec3::new(
            cos * model.x - sin * model.z,
            model.y,
            sin * model.x + cos * model.z,
        )
    }

    /// Test the actual X/Z point against each collision face. A nearby higher box
    /// cannot become the foot's support merely because it intersects a wide probe.
    fn support(&self, world: DVec3, below: f32, above: f32) -> Option<f64> {
        let p = (world - self.block.as_dvec3()).as_vec3();
        self.surfaces
            .iter()
            .filter(|b| p.x >= b[0] && p.x < b[3] && p.z >= b[2] && p.z < b[5])
            .map(|b| b[4])
            .filter(|y| *y >= p.y - below && *y <= p.y + above)
            .max_by(f32::total_cmp)
            .map(|relative_y| f64::from(self.block.y) + f64::from(relative_y))
    }

    fn tread_near_entity(&self, y: f64) -> Option<f64> {
        let direction = Vec3::new(self.movement.x, 0.0, self.movement.z).normalize_or_zero();
        let position = self.block.as_dvec3() + self.entity.as_dvec3();
        let current = DVec3::new(position.x, y, position.z);
        // Step-up can raise the entity when its leading edge touches the riser.
        [0.0, 0.28, -0.28].into_iter().find_map(|offset| {
            self.support(current + (direction * offset).as_dvec3(), 0.035, 0.035)
        })
    }
}

#[derive(Default)]
pub(super) struct LocomotionState {
    frame: Option<i64>,
    world: i32,
    last_tick: Option<i64>,
    last_origin: DVec3,
    mode: i32,
    rate: Option<f32>,
    gait: Gait,
    tread: Option<(i64, f64)>,
    terrain_tick: Option<i64>,
    step: Option<Step>,
    pelvis: f32,
    feet: [FootContact; 2],
}

impl LocomotionState {
    pub(super) fn begin_frame(&mut self, input: &LocomotionInput, elapsed: f32) -> f32 {
        let previous_frame = self.frame;
        let origin = input.to_world(Vec3::ZERO);
        if !input.continuous()
            || self.world != input.world
            || self
                .last_tick
                .is_some_and(|t| input.tick < t || input.tick - t > 5)
            || (self.last_tick.is_some() && origin.distance_squared(self.last_origin) > 1.44)
        {
            self.clear_terrain();
            self.rate = None;
        }
        self.frame = Some(input.frame);
        self.world = input.world;
        self.last_tick = input.continuous().then_some(input.tick);
        self.last_origin = origin;
        if previous_frame == Some(input.frame) || !elapsed.is_finite() {
            return 0.0;
        }
        elapsed.clamp(0.0, 0.05)
    }

    pub(super) fn cadence(
        &mut self,
        input: &LocomotionInput,
        layers: &AnimationLayerManager,
        elapsed: f32,
    ) -> Option<(f32, f32)> {
        if self.mode != input.mode {
            self.release_feet();
            self.rate = None;
            self.mode = input.mode;
        }
        if input.mode < 2 {
            self.gait = Gait::default();
            return None;
        }
        let layer = layers.get_layer(0)?;
        let clip = layer.clip()?;
        if self
            .gait
            .clip
            .as_ref()
            .is_none_or(|last| !Arc::ptr_eq(last, clip))
        {
            self.gait = Gait::analyze(clip);
            self.release_feet();
            self.rate = None;
        }
        if !input.continuous() || (!input.grounded && self.descending_tread(input).is_none()) {
            self.rate = None;
            return Some((elapsed, 1.0));
        }
        let speed = input.movement.x.hypot(input.movement.z) * 20.0;
        let nominal = self
            .gait
            .speed
            .map_or(if input.mode == 2 { 4.3 } else { 5.6 }, |speed| {
                (speed * input.scale).max(0.5)
            });
        // Adapt cadence to movement speed while capping extremes so neither speed
        // potions nor slower custom clips turn into frantic churning.
        let target = if input.mode == 2 {
            (speed / nominal).clamp(0.35, 1.12)
        } else {
            (speed / nominal).clamp(0.45, 1.45)
        };
        let rate = self
            .rate
            .map_or(target, |old| smooth(old, target, 14.0, elapsed));
        self.rate = Some(rate);
        Some((elapsed, rate))
    }

    fn release_feet(&mut self) {
        for foot in &mut self.feet {
            foot.anchor = None;
            foot.releasing = false;
        }
    }

    pub(super) fn clear_terrain(&mut self) {
        self.tread = None;
        self.terrain_tick = None;
        self.step = None;
        self.pelvis = 0.0;
        self.feet = [FootContact::default(); 2];
    }

    fn descending_tread(&self, input: &LocomotionInput) -> Option<f64> {
        let (grounded_tick, y) = self.tread?;
        if !input.has_terrain() || input.movement.y > 0.0 || input.tick - grounded_tick > 5 {
            return None;
        }
        let current = input.block.as_dvec3() + input.entity.as_dvec3();
        let point = DVec3::new(current.x, y, current.z);
        let active_tread = self
            .step
            .as_ref()
            .filter(|step| (step.to - y).abs() < 0.01)
            .map(|_| y);
        if input.support(point, 0.035, 0.035).is_some()
            || (active_tread.is_some() && current.y > y + 0.075)
        {
            return active_tread;
        }

        // At walking speed the entity can clear one of the two stair faces before
        // gravity brings it down to that tread. Check the swept path for real faces.
        let horizontal = DVec3::new(
            f64::from(input.movement.x),
            0.0,
            f64::from(input.movement.z),
        );
        let lower = point - DVec3::Y * 0.5;
        [0.0, 0.5, 1.0]
            .into_iter()
            .find_map(|fraction| input.support(lower - horizontal * fraction, 0.05, 0.05))
            .or_else(|| active_tread.filter(|_| current.y >= y - 0.09))
    }

    fn body_offset(&mut self, input: &LocomotionInput, elapsed: f32) -> Option<f32> {
        let descending = (!input.grounded)
            .then(|| self.descending_tread(input))
            .flatten();
        if !input.grounded && descending.is_none() {
            self.clear_terrain();
            return None;
        }
        if self.terrain_tick != Some(input.tick) {
            self.terrain_tick = Some(input.tick);
            let entity_y = f64::from(input.block.y) + f64::from(input.entity.y);
            let support = if input.grounded {
                input.tread_near_entity(entity_y)
            } else {
                descending
            };
            if let Some(tread) = support {
                if let Some((tick, previous)) = self.tread {
                    let height = (tread - previous).abs();
                    let already_targeting = self
                        .step
                        .as_ref()
                        .is_some_and(|s| (s.to - tread).abs() < 0.01);
                    if !already_targeting
                        && input.tick - tick <= 5
                        && (0.45..=0.55).contains(&height)
                        && (descending.is_some()
                            || input.movement.x.hypot(input.movement.z) > 0.004)
                        && (descending.is_some()
                            || input.tread_near_entity(previous).is_some()
                            || (f64::from(input.movement.y.abs()) - height).abs() <= 0.07)
                    {
                        let (from, velocity) =
                            self.step.as_ref().map_or((previous, 0.0), Step::sample);
                        self.step = Some(Step::new(from, tread, velocity));
                    }
                }
                if input.grounded {
                    self.tread = Some((input.tick, tread));
                    if self
                        .step
                        .as_ref()
                        .is_some_and(|s| (s.to - tread).abs() > 0.55)
                    {
                        self.step = None;
                    }
                } else if let Some((grounded_tick, _)) = self.tread {
                    self.tread = Some((grounded_tick, tread));
                }
            }
        }
        let Some(step) = &mut self.step else {
            return Some(0.0);
        };
        step.elapsed = (step.elapsed + elapsed).min(STEP_TIME);
        let base_y = input.to_world(Vec3::ZERO).y;
        let (height, _) = step.sample();
        let raw_offset = (height - base_y) as f32;
        const UNCOMPRESSED_OFFSET: f32 = 0.24;
        let remaining = MAX_BODY_OFFSET - UNCOMPRESSED_OFFSET;
        let magnitude = raw_offset.abs();
        let offset = raw_offset.signum()
            * (magnitude.min(UNCOMPRESSED_OFFSET)
                + remaining * ((magnitude - UNCOMPRESSED_OFFSET).max(0.0) / remaining).tanh());
        if step.elapsed >= STEP_TIME && offset.abs() < 0.0001 {
            self.step = None;
        }
        Some(offset)
    }

    pub(super) fn set_sample_frame(&mut self, layers: &AnimationLayerManager) {
        if let Some(layer) = layers.get_layer(0) {
            self.gait.frame = layer.current_frame();
        }
    }

    pub(super) fn apply(
        &mut self,
        input: &LocomotionInput,
        bones: &mut BoneManager,
        dt: f32,
    ) -> bool {
        let Some(center) = bones
            .find_bone_by_name("センター")
            .or_else(|| bones.find_bone_by_name("グルーブ"))
            .filter(|&index| stable_frame(bones, index))
        else {
            self.clear_terrain();
            return false;
        };
        let rigs = FEET.map(|names| FootRig::find(bones, names, center));
        if rigs.iter().any(Option::is_none) {
            self.clear_terrain();
            return false;
        }
        let Some(body) = self.body_offset(input, dt) else {
            return false;
        };
        let mut goals = [None; 2];
        for (side, rig) in rigs.iter().enumerate() {
            let Some(rig) = rig else {
                self.feet[side] = FootContact::default();
                continue;
            };
            let foot = input.to_world(bones.get_global_transform(rig.control).w_axis.truncate());
            let sole = foot - DVec3::Y * f64::from(rig.rest_y * input.scale);
            let authored_stance = self
                .gait
                .stance(side)
                .unwrap_or((sole.y - input.to_world(Vec3::ZERO).y).abs() <= 0.065);
            let hip = input.to_world(bones.get_global_transform(rig.hip).w_axis.truncate());
            let reach = rig.reach * input.scale;
            let surface = input.support(sole, 0.62, if authored_stance { 0.55 } else { 0.28 });
            let terrain_y = surface
                .map(|surface| (surface - sole.y) as f32)
                .unwrap_or(0.0);
            let terrain_y = if authored_stance {
                terrain_y
            } else {
                terrain_y.max(0.0)
            };
            let movable_stance = input.mode >= 2
                && authored_stance
                && input.movement.x.hypot(input.movement.z) > 0.008;
            goals[side] = Some((
                foot,
                hip,
                reach,
                self.feet[side].correction(
                    input,
                    ContactSample {
                        foot,
                        hip,
                        reach,
                        surface,
                        terrain_y,
                        movable_stance,
                        authored_stance,
                        body,
                        dt,
                    },
                ),
                authored_stance,
                terrain_y,
            ));
        }

        // Lower the hips only when terrain actually drops or during a step transition;
        // on completely flat ground keep pelvis dip at 0 to avoid vertical micro-jitter.
        let down = goals
            .iter()
            .flatten()
            .filter(|goal| goal.4 && (goal.5 < -0.02 || body.abs() > 0.005))
            .map(|&(foot, hip, reach, correction, _, _)| {
                let separation = (hip - foot).as_vec3() + Vec3::Y * body - correction;
                let vertical = (reach * reach - separation.x.powi(2) - separation.z.powi(2))
                    .max(0.0)
                    .sqrt();
                (vertical - separation.y).clamp(-0.15, 0.0)
            })
            .fold(0.0, f32::min);
        self.pelvis = approach(self.pelvis, down, 1.5 * dt);
        let body = Vec3::Y * (body + self.pelvis);
        let mut changed = add_offset(input, bones, center, body);

        for (rig, goal) in rigs.into_iter().zip(goals) {
            let (Some(rig), Some((foot, hip, reach, correction, _, _))) = (rig, goal) else {
                continue;
            };
            let from_hip = (foot - hip).as_vec3() + correction - body;
            let clamped = from_hip.clamp_length_max(reach);
            // Preserve native animation on frames that need no terrain correction.
            let correction = if correction.length_squared() > 1e-8 {
                clamped - (foot - hip).as_vec3() + body
            } else {
                correction
            };
            let foot_offset = correction
                - if rig.control_inherits_center {
                    body
                } else {
                    Vec3::ZERO
                };
            changed |= add_offset(input, bones, rig.control, foot_offset);
            if let Some(toe) = rig.toe {
                let toe_offset = correction
                    - if ancestor(bones, center, toe) {
                        body
                    } else {
                        Vec3::ZERO
                    };
                changed |= add_offset(input, bones, toe, toe_offset);
            }
        }
        changed
    }
}

struct Step {
    from: f64,
    to: f64,
    velocity: f64,
    elapsed: f32,
}

impl Step {
    fn new(from: f64, to: f64, velocity: f64) -> Self {
        let span = to - from;
        let along = (velocity * span.signum()).clamp(0.0, 3.0 * span.abs() / f64::from(STEP_TIME));
        Self {
            from,
            to,
            velocity: along * span.signum(),
            elapsed: 0.0,
        }
    }

    fn sample(&self) -> (f64, f64) {
        const SUBDIVISIONS: f64 = 4.0;
        let t = (f64::from(self.elapsed) / f64::from(STEP_TIME)).clamp(0.0, 1.0);
        let delta = self.to - self.from;
        let tangent = self.velocity * f64::from(STEP_TIME);
        if delta.abs() <= f64::EPSILON {
            return (self.to, 0.0);
        }
        let progress = (3.0 - 2.0 * t) * t * t + (tangent / delta) * t * (1.0 - t).powi(2);
        let layer = (progress * SUBDIVISIONS).clamp(0.0, SUBDIVISIONS);
        let layer_height = delta / SUBDIVISIONS;
        let height = self.from + layer.floor() * layer_height + layer.fract() * layer_height;
        let velocity = (6.0 * t * (1.0 - t) * delta + tangent * (1.0 - t) * (1.0 - 3.0 * t))
            / f64::from(STEP_TIME);
        (height, velocity)
    }
}

#[derive(Default)]
struct Gait {
    clip: Option<Arc<VmdAnimation>>,
    feet: [Option<FootTrack>; 2],
    speed: Option<f32>,
    frame: f32,
}

struct FootTrack {
    name: &'static str,
    contact_height: f32,
}

impl Gait {
    fn analyze(clip: &Arc<VmdAnimation>) -> Self {
        let mut speeds = Vec::new();
        let feet = FEET.map(|names| {
            let name = names[..2]
                .iter()
                .copied()
                .find(|name| clip.contains_bone_track(name))?;
            let end = clip.max_frame();
            if end < 2 {
                return None;
            }
            let increment = (end / 1024 + 1).max(1);
            let samples: Vec<_> = (0..=end)
                .step_by(increment as usize)
                .map(|frame| clip.get_bone_transform(name, frame, 0.0).translation)
                .collect();
            if samples.iter().any(|position| !position.is_finite()) {
                return None;
            }
            let low = samples.iter().map(|p| p.y).fold(f32::INFINITY, f32::min);
            let high = samples
                .iter()
                .map(|p| p.y)
                .fold(f32::NEG_INFINITY, f32::max);
            if high - low < 0.01 {
                return None;
            }
            let contact_height = low + ((high - low) * 0.08).max(0.04);
            for pair in samples.windows(2) {
                if pair[0].y.max(pair[1].y) <= contact_height {
                    let delta = pair[1] - pair[0];
                    let speed = delta.x.hypot(delta.z) * 30.0 / increment as f32;
                    if speed >= 0.1 {
                        speeds.push(speed);
                    }
                }
            }
            Some(FootTrack {
                name,
                contact_height,
            })
        });
        speeds.sort_unstable_by(f32::total_cmp);
        let speed = speeds
            .get(speeds.len() / 2)
            .copied()
            .filter(|_| speeds.len() >= 2);
        Self {
            clip: Some(Arc::clone(clip)),
            feet,
            speed,
            frame: 0.0,
        }
    }

    fn stance(&self, side: usize) -> Option<bool> {
        let clip = self.clip.as_ref()?;
        let track = self.feet[side].as_ref()?;
        let foot =
            clip.get_bone_transform(track.name, self.frame.floor() as u32, self.frame.fract());
        Some(foot.translation.y <= track.contact_height)
    }
}

#[derive(Clone, Copy, Default)]
struct FootContact {
    anchor: Option<(DVec3, f64)>,
    weight: f32,
    vertical: f32,
    last_correction: Vec3,
    releasing: bool,
}

struct ContactSample {
    foot: DVec3,
    hip: DVec3,
    reach: f32,
    surface: Option<f64>,
    terrain_y: f32,
    movable_stance: bool,
    authored_stance: bool,
    body: f32,
    dt: f32,
}

impl FootContact {
    fn correction(&mut self, input: &LocomotionInput, sample: ContactSample) -> Vec3 {
        let ContactSample {
            foot,
            hip,
            reach,
            surface,
            terrain_y,
            movable_stance,
            authored_stance,
            body,
            dt,
        } = sample;
        if !authored_stance {
            self.releasing = false;
        }
        if dt > 0.0
            && self.anchor.is_none()
            && movable_stance
            && !self.releasing
            && self.weight <= 0.1
        {
            if let Some(surface) = surface {
                let ankle = foot + DVec3::Y * f64::from(terrain_y);
                self.anchor = Some((ankle, surface));
            }
        }
        if let Some((anchor, surface)) = self.anchor {
            let support = input.support(DVec3::new(anchor.x, surface, anchor.z), 0.02, 0.02);
            let offset_hip = hip + DVec3::Y * f64::from(body);
            if !movable_stance
                || support.is_none()
                || anchor.distance(offset_hip) > f64::from(reach + 0.015)
                || (anchor - foot).xz().length() > f64::from((reach * 0.28).min(0.24))
            {
                self.anchor = None;
                self.releasing = authored_stance;
            } else {
                self.last_correction = (anchor - foot).as_vec3();
            }
        }
        let weight_speed = if self.anchor.is_some() { 28.0 } else { 18.0 };
        self.weight = approach(
            self.weight,
            if self.anchor.is_some() { 1.0 } else { 0.0 },
            weight_speed * dt,
        );
        self.vertical = approach(self.vertical, terrain_y, 4.0 * dt);
        (Vec3::Y * self.vertical).lerp(self.last_correction, self.weight)
    }
}

struct FootRig {
    control: usize,
    hip: usize,
    toe: Option<usize>,
    reach: f32,
    rest_y: f32,
    control_inherits_center: bool,
}

impl FootRig {
    fn find(bones: &BoneManager, names: [&str; 3], center: usize) -> Option<Self> {
        let control = names[..2]
            .iter()
            .find_map(|name| bones.find_bone_by_name(name))?;
        if !stable_frame(bones, control) || !bones.ik_is_enabled(control) {
            return None;
        }
        let bone = bones.get_bone(control)?;
        let config = bone.ik_config.as_ref()?;
        let ankle = bones.get_bone(usize::try_from(config.target_bone).ok()?)?;
        let knee = usize::try_from(ankle.parent_index).ok()?;
        let knee_bone = bones.get_bone(knee)?;
        let hip = usize::try_from(knee_bone.parent_index).ok()?;
        if !ancestor(bones, center, hip)
            || !config
                .links
                .iter()
                .any(|link| link.bone_index == hip as i32)
            || !config
                .links
                .iter()
                .any(|link| link.bone_index == knee as i32)
            || bones.is_physics_bone(knee)
            || !stable_frame(bones, hip)
        {
            return None;
        }
        let reach = ankle.initial_position.distance(knee_bone.initial_position)
            + knee_bone
                .initial_position
                .distance(bones.get_bone(hip)?.initial_position);
        if !reach.is_finite() || reach <= 0.0001 {
            return None;
        }
        let toe = bones.find_bone_by_name(names[2]).filter(|&idx| {
            !ancestor(bones, control, idx) && stable_frame(bones, idx) && bones.ik_is_enabled(idx)
        });
        Some(Self {
            control,
            hip,
            toe,
            reach,
            rest_y: bone.initial_position.y,
            control_inherits_center: ancestor(bones, center, control),
        })
    }
}

fn stable_frame(bones: &BoneManager, mut index: usize) -> bool {
    for _ in 0..bones.bone_count() {
        let Some(bone) = bones.get_bone(index) else {
            return false;
        };
        if bones.is_physics_bone(index)
            || bones.external_parents().contains_key(&index)
            || bone.deform_after_physics()
            || bone.is_append_rotate()
            || bone.is_append_translate()
        {
            return false;
        }
        let Ok(parent) = usize::try_from(bone.parent_index) else {
            return true;
        };
        index = parent;
    }
    false
}

fn ancestor(bones: &BoneManager, ancestor: usize, mut index: usize) -> bool {
    for _ in 0..bones.bone_count() {
        if ancestor == index {
            return true;
        }
        let Some(parent) = bones
            .get_bone(index)
            .and_then(|bone| usize::try_from(bone.parent_index).ok())
        else {
            break;
        };
        index = parent;
    }
    false
}

fn add_offset(
    input: &LocomotionInput,
    bones: &mut BoneManager,
    index: usize,
    offset: Vec3,
) -> bool {
    if !offset.is_finite() || offset.length_squared() < 1e-8 {
        return false;
    }
    let bone = bones
        .get_bone_mut(index)
        .expect("validated locomotion bone");
    bone.terrain_translate = bone
        .parent_to_world
        .inverse()
        .transform_vector3(input.model_vector(offset));
    true
}

fn approach(value: f32, target: f32, limit: f32) -> f32 {
    value + (target - value).clamp(-limit, limit)
}

fn smooth(value: f32, target: f32, speed: f32, dt: f32) -> f32 {
    value + (target - value) * (1.0 - (-speed * dt).exp())
}

impl super::MmdModel {
    pub(crate) fn locomotion_diagnostic(&mut self) -> String {
        if self.locomotion_input.mode < 1 {
            return String::new();
        }
        let eye = self.get_first_person_camera_anchor_position();
        let input = &self.locomotion_input;
        let center_delta = self
            .bone_manager
            .find_bone_by_name("センター")
            .or_else(|| self.bone_manager.find_bone_by_name("グルーブ"))
            .and_then(|index| self.bone_manager.get_bone(index))
            .map_or(0.0, |bone| {
                bone.parent_to_world
                    .transform_vector3(bone.terrain_translate)
                    .y
                    * input.scale
            });
        let ankle = |side: usize| {
            FEET[side][..2]
                .iter()
                .find_map(|name| self.bone_manager.find_bone_by_name(name))
                .and_then(|control| self.bone_manager.get_bone(control)?.ik_config.as_ref())
                .and_then(|ik| usize::try_from(ik.target_bone).ok())
                .and_then(|idx| self.rendered_bone_position(idx))
                .map(|position| input.to_world(position).to_array())
        };
        let origin = input.to_world(Vec3::ZERO);
        let state = &self.locomotion;
        format!(
            "[MMD locomotion] model={} frame={} tick={} mode={} terrain={} grounded={} surfaces={} entity_y={:.4} model_y={:.4} eye_y={:.4} target_tread={:?} left_ankle={:?} right_ankle={:?} left_planted={} right_planted={} rate={:.3} origin_x={:.5} origin_z={:.5} left_early_release={} right_early_release={}",
            self.name, input.frame, input.tick, input.mode, input.terrain, input.grounded, input.surfaces.len(),
            f64::from(input.block.y) + f64::from(input.entity.y), origin.y + f64::from(center_delta),
            input.to_world(eye).y, state.step.as_ref().map(|step| step.to),
            ankle(0), ankle(1), state.feet[0].anchor.is_some(), state.feet[1].anchor.is_some(),
            state.rate.unwrap_or(1.0), origin.x, origin.z,
            state.feet[0].releasing, state.feet[1].releasing,
        )
    }
}

#[cfg(test)]
mod tests;
