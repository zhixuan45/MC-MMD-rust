//! Read-only PMX diagnosis through the complete model runtime.
//! The neutral-name control changes only the in-memory copy and disables collisions.
use std::{env, fs, path::Path};

use glam::{Mat4, Vec3};
use mmd::pmx::rigid_body::RigidBodyMode;
use mmd_engine::{
    model::load_pmx,
    physics::{reset_config, set_config, MmdRigidBodyData, PhysicsConfig},
};
use serde_json::{json, Value};

fn hair(local: &str, universal: &str) -> bool {
    let text = format!("{} {}", local.to_lowercase(), universal.to_lowercase());
    [
        "髪",
        "髮",
        "发",
        "hair",
        "ponytail",
        "twintail",
        "braid",
        "bangs",
        "アホ毛",
        "毛",
    ]
    .iter()
    .any(|part| text.contains(part))
}

fn category(name: &str, universal: &str) -> Option<&'static str> {
    if name.contains('袖') || name.to_lowercase().contains("sleeve") {
        Some("sleeve")
    } else if hair(name, universal) {
        Some("hair")
    } else if ["穗", "穂", "飾", "饰", "リボン", "蝴蝶", "ribbon", "bow"]
        .iter()
        .any(|part| name.to_lowercase().contains(part))
    {
        Some("accessory")
    } else {
        None
    }
}

fn inv_z(m: Mat4) -> Mat4 {
    let z = Mat4::from_scale(Vec3::new(1.0, 1.0, -1.0));
    z * m * z
}

struct Watch {
    body: usize,
    bone: usize,
    name: String,
    category: &'static str,
    mode: String,
    offset: Mat4,
    previous: Mat4,
    max_delta: f32,
    sum_delta: f64,
    max_angle: f32,
    samples: usize,
}

fn run(path: &Path, collision: bool, neutral: bool, pitch: f32) -> Value {
    assert!(
        !neutral || !collision,
        "Neutral-name causality control requires collisions off"
    );
    set_config(PhysicsConfig {
        collision_enabled: collision,
        inertia_strength: 0.0,
        debug_log: false,
        ..PhysicsConfig::default()
    });
    let mut model = load_pmx(path).unwrap();
    model.update_node_animation(false);
    let mut watched: Vec<_> = model
        .rigid_bodies
        .iter()
        .enumerate()
        .filter_map(|(index, b)| {
            if b.mode == RigidBodyMode::Static {
                return None;
            }
            let bone = usize::try_from(b.bone_index).ok()?;
            let cat = category(&b.local_name, &b.universal_name)?;
            let bind = model.bone_manager.get_global_transform(bone);
            let rb = MmdRigidBodyData::from_pmx(b, Some(bind), b.size);
            Some(Watch {
                body: index,
                bone,
                name: b.local_name.clone(),
                category: cat,
                mode: format!("{:?}", b.mode),
                offset: rb.body_offset_matrix,
                previous: bind,
                max_delta: 0.0,
                sum_delta: 0.0,
                max_angle: 0.0,
                samples: 0,
            })
        })
        .collect();
    let mut renamed = Vec::new();
    if neutral {
        for (index, b) in model.rigid_bodies.iter_mut().enumerate() {
            if b.mode != RigidBodyMode::Static && hair(&b.local_name, &b.universal_name) {
                renamed.push(json!({"body":index,"name":b.local_name}));
                b.local_name = format!("neutral_part_{index}");
                b.universal_name.clear();
            }
        }
    }
    assert!(model.init_physics());
    let initial_joints = model.physics_joint_snapshots_matching("");
    let limits: Vec<_> = initial_joints
        .iter()
        .filter_map(|s| {
            let original = model.joints.iter().find(|j| j.local_name == s.name)?;
            let a = model
                .rigid_bodies
                .get(original.rigid_body_a_index as usize)?;
            let b = model
                .rigid_bodies
                .get(original.rigid_body_b_index as usize)?;
            let relevant = watched.iter().any(|w| {
                w.category == "hair"
                    && (w.body as i32 == original.rigid_body_a_index
                        || w.body as i32 == original.rigid_body_b_index)
            });
            if !relevant {
                return None;
            }
            Some(json!({"name":s.name,"a":s.body_a_name,"b":s.body_b_name,
                "mode_a":format!("{:?}",a.mode),"mode_b":format!("{:?}",b.mode),
                "universal_a":a.universal_name,"universal_b":b.universal_name,
                "raw_lower_deg":original.rotation_min.map(f32::to_degrees),
                "raw_upper_deg":original.rotation_max.map(f32::to_degrees),
                "raw_spring":original.rotation_spring,
                "effective_lower_deg":s.diagnostic.angular_lower.to_array().map(f32::to_degrees),
                "effective_upper_deg":s.diagnostic.angular_upper.to_array().map(f32::to_degrees),
                "effective_spring":s.diagnostic.stiffness,
            }))
        })
        .collect();
    let mut worst_anchor = 0.0_f32;
    let mut worst_angular = 0.0_f32;
    for frame in 0..900 {
        let t = ((frame as f32 - 300.0) / 120.0).clamp(0.0, 1.0);
        model.set_head_angle(pitch * t, 0.0, 0.0);
        model.tick_animation_no_skinning(1.0 / 60.0);
        for w in &mut watched {
            let current = model.bone_manager.get_global_transform(w.bone);
            assert!(current.is_finite());
            if frame >= 600 {
                let (_, q0, p0) = w.previous.to_scale_rotation_translation();
                let (_, q1, p1) = current.to_scale_rotation_translation();
                let delta = p0.distance(p1);
                w.max_delta = w.max_delta.max(delta);
                w.sum_delta += delta as f64;
                w.max_angle = w.max_angle.max(2.0 * q0.dot(q1).abs().min(1.0).acos());
                w.samples += 1;
            }
            w.previous = current;
        }
        if frame >= 600 {
            for j in model.physics_joint_snapshots_matching("") {
                worst_anchor = worst_anchor.max(j.anchor_error);
                worst_angular = worst_angular.max(j.diagnostic.angular_violation.length());
            }
        }
    }
    let bodies: Vec<_> = watched
        .iter()
        .map(|w| {
            let displayed_body = inv_z(w.previous) * w.offset;
            let (_, q, p) = displayed_body.to_scale_rotation_translation();
            json!({"body":w.body,"bone":w.bone,"name":w.name,"category":w.category,
            "mode":w.mode,"max_bone_delta":w.max_delta,
            "mean_bone_delta":w.sum_delta/w.samples as f64,
            "max_angle_deg":w.max_angle.to_degrees(),
            "display_body_center_left":p.to_array(),
            "display_body_down_left":(q * -Vec3::Y).to_array(),
            "display_body_rotation":q.to_array()})
        })
        .collect();
    json!({"pmx":path.to_string_lossy(),"collision":collision,"neutral_hair_names":neutral,
        "head_pitch_right_deg":pitch.to_degrees(),"physics_hz":60,
        "warmup_s":5,"ramp_s":2,"sample_begin_s":10,"sample_s":5,
        "pmx_bodies":model.rigid_bodies.len(),"pmx_joints":model.joints.len(),
        "physics_joints":initial_joints.len(),"renamed":renamed,"hair_limits":limits,
        "worst_anchor":worst_anchor,"worst_angular_violation_deg":worst_angular.to_degrees(),
        "body_pose_kind":"reconstructed_from_displayed_bone_mode2_not_solver_pose",
        "bodies":bodies})
}

fn main() {
    let args: Vec<_> = env::args_os().collect();
    assert_eq!(
        args.len(),
        3,
        "probe_drive_cloth_hair <model.pmx> <output.json>"
    );
    let path = Path::new(&args[1]);
    let mut result = Vec::new();
    for (collision, neutral) in [(true, false), (false, false), (false, true)] {
        for pitch in [0.0, -50.0_f32.to_radians()] {
            result.push(run(path, collision, neutral, pitch));
        }
    }
    reset_config();
    fs::write(&args[2], serde_json::to_string_pretty(&result).unwrap()).unwrap();
    println!("6 complete runtime cases saved");
}
