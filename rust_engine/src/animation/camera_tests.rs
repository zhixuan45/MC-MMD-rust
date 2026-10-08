use super::keyframe::CameraKeyframe;
use super::motion_track::{CameraFrameTransform, CameraMotionTrack};
use super::vmd_loader::VmdFile;
use super::BezierCurveCache;
use glam::{Quat, Vec3};

// 测试从完整 VMD 字节进入生产加载器，避免漏测文件布局。
fn camera_vmd(frames: &[(CameraKeyframe, [u8; 24])]) -> Vec<u8> {
    let mut out = b"Vocaloid Motion Data 0002".to_vec();
    out.resize(50, 0);
    out.extend_from_slice(&0u32.to_le_bytes());
    out.extend_from_slice(&0u32.to_le_bytes());
    out.extend_from_slice(&(frames.len() as u32).to_le_bytes());
    for (frame, interpolation) in frames {
        out.extend_from_slice(&frame.frame_index.to_le_bytes());
        out.extend_from_slice(&frame.distance.to_le_bytes());
        for value in frame
            .look_at
            .to_array()
            .into_iter()
            .chain(frame.angle.to_array())
        {
            out.extend_from_slice(&value.to_le_bytes());
        }
        out.extend_from_slice(interpolation);
        out.extend_from_slice(&(frame.fov as u32).to_le_bytes());
        out.push(u8::from(!frame.is_perspective));
    }
    out.extend_from_slice(&[0; 12]);
    out
}

fn channels(raw: [u8; 4]) -> [u8; 24] {
    let mut bytes = [0; 24];
    for channel in bytes.chunks_exact_mut(4) {
        channel.copy_from_slice(&raw);
    }
    bytes
}

fn single_transform(look_at: Vec3, distance: f32, angle: Vec3) -> CameraFrameTransform {
    let mut track = CameraMotionTrack::new();
    track.insert_keyframe(CameraKeyframe {
        look_at,
        angle,
        distance,
        ..Default::default()
    });
    track.seek(0, &BezierCurveCache::default())
}

fn reflect_z(v: Vec3) -> Vec3 {
    Vec3::new(v.x, v.y, -v.z)
}

fn vmd_world_rotation(angle: Vec3) -> Quat {
    (Quat::from_rotation_z(angle.z)
        * Quat::from_rotation_x(angle.x)
        * Quat::from_rotation_y(angle.y))
    .inverse()
}

// 对照实际 Minecraft/NeoForge Camera 的三轴旋转约定。
fn minecraft_rotation(pose: CameraFrameTransform) -> Quat {
    Quat::from_rotation_y(std::f32::consts::PI - pose.rotation.y)
        * Quat::from_rotation_x(-pose.rotation.x)
        * Quat::from_rotation_z(-pose.rotation.z)
}

fn assert_vector(actual: Vec3, expected: Vec3, epsilon: f32) {
    assert!(
        actual.abs_diff_eq(expected, epsilon),
        "actual={actual:?}, expected={expected:?}"
    );
}

fn assert_orientation(pose: CameraFrameTransform, angle: Vec3) {
    assert!(pose.rotation.is_finite(), "angle={angle:?}, pose={pose:?}");
    let original = vmd_world_rotation(angle);
    let rebuilt = minecraft_rotation(pose);
    assert_vector(rebuilt * -Vec3::Z, reflect_z(original * Vec3::Z), 3.0e-5);
    assert_vector(rebuilt * Vec3::Y, reflect_z(original * Vec3::Y), 3.0e-5);
}

#[test]
fn camera_vmd_reorders_every_interpolation_channel() {
    let mut bytes = [0; 24];
    for (i, channel) in bytes.chunks_exact_mut(4).enumerate() {
        let i = i as u8;
        channel.copy_from_slice(&[13 + i * 5, 80 + i * 3, 2 + i * 7, 118 - i * 4]);
    }
    let file =
        VmdFile::load_from_bytes(&camera_vmd(&[(CameraKeyframe::default(), bytes)])).unwrap();
    let interp = &file.motion.camera_track.keyframes[&0].interpolation;
    let actual = [
        interp.lookat_x,
        interp.lookat_y,
        interp.lookat_z,
        interp.angle,
        interp.distance,
        interp.fov,
    ];
    for (channel, raw) in actual.iter().zip(bytes.chunks_exact(4)) {
        assert_eq!(*channel, [raw[0], raw[2], raw[1], raw[3]]);
    }
}

// 独立二分解曲线，避免用生产插值器作为自己的判定标准。
fn reference_curve(raw: [u8; 4], time: f64) -> f32 {
    let value = |t: f64, a: u8, b: u8| {
        3.0 * (1.0 - t).powi(2) * t * f64::from(a) / 127.0
            + 3.0 * (1.0 - t) * t * t * f64::from(b) / 127.0
            + t.powi(3)
    };
    let (mut low, mut high) = (0.0, 1.0);
    for _ in 0..60 {
        let t = (low + high) * 0.5;
        if value(t, raw[0], raw[1]) < time {
            low = t;
        } else {
            high = t;
        }
    }
    value((low + high) * 0.5, raw[2], raw[3]) as f32
}

#[test]
fn camera_vmd_easing_reaches_runtime_for_all_six_channels() {
    let start = CameraKeyframe {
        distance: -45.0,
        ..Default::default()
    };
    let end = CameraKeyframe {
        frame_index: 100,
        look_at: Vec3::new(100.0, 50.0, -80.0),
        angle: Vec3::new(0.5, 0.7, -0.8),
        distance: -35.0,
        fov: 60.0,
        ..Default::default()
    };
    let raw = [64, 64, 0, 0];
    let file = VmdFile::load_from_bytes(&camera_vmd(&[
        (start.clone(), channels([20, 107, 20, 107])),
        (end.clone(), channels(raw)),
    ]))
    .unwrap();
    let pose = file.motion.find_camera_transform(50, 0.0);
    let weight = reference_curve(raw, 0.5);
    assert!((weight - 0.12207067).abs() < 1.0e-6);
    let angle = end.angle * weight;
    let expected_position = reflect_z(
        end.look_at * weight
            + vmd_world_rotation(angle) * Vec3::Z * (start.distance + 10.0 * weight),
    );
    assert_vector(pose.position, expected_position, 0.015);
    assert!((pose.fov - (start.fov + (end.fov - start.fov) * weight)).abs() < 0.003);
    let expected = single_transform(Vec3::ZERO, -45.0, angle);
    assert_vector(
        minecraft_rotation(pose) * Vec3::Y,
        minecraft_rotation(expected) * Vec3::Y,
        0.0002,
    );
    assert_vector(
        minecraft_rotation(pose) * -Vec3::Z,
        minecraft_rotation(expected) * -Vec3::Z,
        0.0002,
    );
}

#[test]
fn camera_vmd_linear_curve_preserves_halfway_position() {
    let start = CameraKeyframe {
        distance: -45.0,
        ..Default::default()
    };
    let end = CameraKeyframe {
        frame_index: 10,
        look_at: Vec3::new(100.0, 0.0, 0.0),
        ..start.clone()
    };
    let raw = channels([20, 107, 20, 107]);
    let file = VmdFile::load_from_bytes(&camera_vmd(&[(start, raw), (end, raw)])).unwrap();
    assert_vector(
        file.motion.find_camera_transform(5, 0.0).position,
        Vec3::new(50.0, 0.0, 45.0),
        1.0e-5,
    );
}

#[test]
fn camera_default_pose_still_faces_negative_z() {
    let pose = single_transform(Vec3::ZERO, -45.0, Vec3::ZERO);
    assert_vector(pose.position, Vec3::new(0.0, 0.0, 45.0), 1.0e-5);
    assert_orientation(pose, Vec3::ZERO);
}

#[test]
fn camera_orientation_is_independent_of_distance_and_target() {
    let angle = Vec3::new(0.2, -1.1, 0.4);
    for distance in [-45.0, -1.0e-8, 0.0, 1.0e-8, 45.0] {
        for target in [Vec3::ZERO, Vec3::new(1.0e8, -2.0e8, 3.0e8)] {
            assert_orientation(single_transform(target, distance, angle), angle);
        }
    }
}

#[test]
fn camera_orientation_reconstructs_vmd_through_vertical_views() {
    for pitch in [
        -180.0f32, -120.0, -95.0, -90.001, -90.0, -89.999, -88.0, 0.0, 30.0, 80.0, 87.0, 88.0,
        89.999, 90.0, 90.001, 95.0, 120.0, 180.0,
    ] {
        for yaw in [-2.0, 0.0, 1.3] {
            for roll in [-2.4, 0.0, 0.6, 3.1] {
                let angle = Vec3::new(pitch.to_radians(), yaw, roll);
                assert_orientation(single_transform(Vec3::ZERO, -45.0, angle), angle);
            }
        }
    }
}

#[test]
fn camera_subframe_crossing_yaw_boundary_keeps_small_rotation() {
    let mut track = CameraMotionTrack::new();
    let start = CameraKeyframe {
        angle: Vec3::new(0.3, 179.0f32.to_radians(), 0.2),
        distance: -45.0,
        ..Default::default()
    };
    let end = CameraKeyframe {
        frame_index: 1,
        angle: Vec3::new(0.3, 181.0f32.to_radians(), 0.2),
        ..start.clone()
    };
    track.insert_keyframe(start);
    track.insert_keyframe(end);
    assert_orientation(
        track.seek_precisely(0, 0.5, &BezierCurveCache::default()),
        Vec3::new(0.3, std::f32::consts::PI, 0.2),
    );
}

#[test]
fn camera_authored_multiple_turns_are_preserved() {
    let mut track = CameraMotionTrack::new();
    let start = CameraKeyframe {
        distance: -45.0,
        ..Default::default()
    };
    track.insert_keyframe(start.clone());
    track.insert_keyframe(CameraKeyframe {
        frame_index: 100,
        angle: Vec3::new(0.0, 0.0, 4.0 * std::f32::consts::PI),
        ..start
    });
    assert_orientation(
        track.seek(25, &BezierCurveCache::default()),
        Vec3::new(0.0, 0.0, std::f32::consts::PI),
    );
}
