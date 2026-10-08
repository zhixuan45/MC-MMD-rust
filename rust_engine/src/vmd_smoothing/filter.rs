use glam::{Quat, Vec3};

use crate::{animation::Motion, MmdError, Result};

use super::{BoneSelection, SmoothingOptions};

const MAX_TRACK_FRAMES: u32 = 250_000;

pub(crate) struct SmoothedPose {
    pub frame: u32,
    pub translation: Vec3,
    pub orientation: Quat,
}

/// 对轨道做对称时间窗滤波。
pub(crate) fn smooth_track(
    name: &str,
    motion: &Motion,
    start_frame: u32,
    max_frame: u32,
    motion_duration: u32,
    options: &SmoothingOptions,
    warnings: &mut Vec<String>,
) -> Result<Vec<SmoothedPose>> {
    if max_frame > MAX_TRACK_FRAMES {
        return Err(MmdError::VmdParse(format!("骨骼轨道“{}”帧范围过大", name)));
    }
    if start_frame > max_frame {
        return Err(MmdError::VmdParse(format!("骨骼轨道“{}”帧范围无效", name)));
    }
    let covers_motion = start_frame == 0 && max_frame == motion_duration;
    let loop_requested = options.looped && covers_motion;
    if options.looped && !covers_motion {
        warnings.push(format!(
            "骨骼轨道“{}”未覆盖完整动作范围，改用非循环端点处理",
            name
        ));
    }
    let mut first_frame = if loop_requested { 0 } else { start_frame };
    let mut last_frame = if loop_requested {
        motion_duration
    } else {
        max_frame
    };
    if last_frame > MAX_TRACK_FRAMES {
        return Err(MmdError::VmdParse("动画总帧范围超过平滑采样限制".into()));
    }
    let mut samples = sample_range(name, motion, first_frame, last_frame)?;
    let mut frame_count = (last_frame - first_frame) as usize + 1;

    if frame_count == 1 || options.radius == 0 {
        return Ok(samples
            .into_iter()
            .enumerate()
            .map(|(frame, (translation, orientation))| SmoothedPose {
                frame: first_frame + frame as u32,
                translation,
                orientation,
            })
            .collect());
    }

    let mut period = (last_frame - first_frame) as usize;
    let mut loopable = loop_requested && period > 1;
    if loop_requested && period <= 1 {
        warnings.push(format!(
            "骨骼轨道“{}”循环帧数过少，改用非循环端点处理",
            name
        ));
        first_frame = start_frame;
        last_frame = max_frame;
        samples = sample_range(name, motion, first_frame, last_frame)?;
        frame_count = samples.len();
        period = frame_count.saturating_sub(1);
    }
    if loopable {
        let delta = samples[period].0 - samples[0].0;
        let angle = rotation_distance(samples[0].1, samples[period].1);
        if angle > 0.35 {
            warnings.push(format!(
                "骨骼轨道“{}”首尾旋转差异较大，改用非循环端点处理",
                name
            ));
            loopable = false;
        }
        if !is_root_track(name) && delta.length() > 0.5 {
            warnings.push(format!(
                "骨骼轨道“{}”首尾位置差异较大，改用非循环端点处理",
                name
            ));
            loopable = false;
        }
        if is_root_track(name) && delta.length() > 0.5 {
            warnings.push(format!("骨骼轨道“{}”有持续位移，处理时保留首尾位移", name));
        }
        if !loopable {
            first_frame = start_frame;
            last_frame = max_frame;
            samples = sample_range(name, motion, first_frame, last_frame)?;
            frame_count = samples.len();
            period = frame_count.saturating_sub(1);
        }
    }

    let output_count = frame_count;
    let mut output: Vec<SmoothedPose> = Vec::with_capacity(output_count);
    let mut filtered = Vec::with_capacity(output_count);
    let fade_frames = options.radius.min((period / 2) as u32);
    for frame in 0..output_count {
        let center_index = if loopable { frame % period } else { frame };
        let (filtered_translation, filtered_rotation) = if loopable {
            let phase = center_index as f32 / period as f32;
            let base = if is_root_track(name) {
                samples[0].0.lerp(samples[period].0, phase)
            } else {
                samples[0].0
            };
            let residual = periodic_vector_average(
                &samples,
                center_index,
                period,
                options.radius,
                is_root_track(name),
            );
            let filtered_translation = base + residual;
            let filtered_rotation =
                periodic_rotation_average(&samples, center_index, period, options.radius);
            (filtered_translation, filtered_rotation)
        } else {
            (
                clamped_vector_average(&samples, center_index, options.radius),
                clamped_rotation_average(&samples, center_index, options.radius),
            )
        };
        filtered.push((filtered_translation, filtered_rotation));
    }

    let foot_translation =
        is_foot_ik(name).then(|| compensate_foot_extrema(&samples, &filtered, period, loopable));
    for frame in 0..output_count {
        let source = samples[frame];
        let (filtered_translation, filtered_rotation) = filtered[frame];
        // 端点渐隐滤波强度，避免硬复原端点造成折痕。
        let endpoint_weight = endpoint_fade(frame, period, fade_frames);
        let blend = options.strength * endpoint_weight;
        let target = foot_translation
            .as_ref()
            .map(|poses| poses[frame])
            .unwrap_or(filtered_translation);
        let translation = source.0.lerp(target, blend);
        let orientation = source.1.slerp(filtered_rotation, blend).normalize();
        if !translation.is_finite()
            || !orientation.is_finite()
            || orientation.length_squared() < 1.0e-8
        {
            return Err(MmdError::VmdParse(format!(
                "骨骼轨道“{}”平滑后产生无效变换",
                name
            )));
        }
        output.push(SmoothedPose {
            frame: first_frame + frame as u32,
            translation,
            orientation,
        });
    }

    Ok(output)
}

/// 用 Hermite 残差补偿保留足部平移极值和端点。
fn compensate_foot_extrema(
    samples: &[(Vec3, Quat)],
    filtered: &[(Vec3, Quat)],
    period: usize,
    looped: bool,
) -> Vec<Vec3> {
    let mut corrected = filtered.iter().map(|pose| pose.0).collect::<Vec<_>>();
    let mut minimum = [f32::INFINITY; 3];
    let mut maximum = [f32::NEG_INFINITY; 3];
    for (position, _) in samples {
        for axis in 0..3 {
            let value = position[axis];
            minimum[axis] = minimum[axis].min(value);
            maximum[axis] = maximum[axis].max(value);
        }
    }
    for axis in 0..3 {
        let scale = maximum[axis].abs().max(minimum[axis].abs()).max(1.0);
        let tolerance = scale * 1.0e-5;
        let mut anchors = vec![false; samples.len()];
        for (frame, source) in samples.iter().enumerate() {
            let value = source.0[axis];
            let is_extremum = (value - minimum[axis]).abs() <= tolerance
                || (value - maximum[axis]).abs() <= tolerance;
            anchors[frame] = is_extremum || frame == 0 || frame + 1 == samples.len();
        }
        let mut residuals = vec![0.0; samples.len()];
        let mut slopes = vec![0.0; samples.len()];
        for frame in 0..samples.len() {
            if !anchors[frame] {
                continue;
            }
            residuals[frame] = samples[frame].0[axis] - filtered[frame].0[axis];
            let is_extremum = (samples[frame].0[axis] - minimum[axis]).abs() <= tolerance
                || (samples[frame].0[axis] - maximum[axis]).abs() <= tolerance;
            if is_extremum {
                slopes[frame] = -filtered_central_difference(filtered, frame, period, looped, axis);
            }
        }
        if looped && samples.len() > 1 {
            let last = samples.len() - 1;
            let seam_extremum = (samples[0].0[axis] - minimum[axis]).abs() <= tolerance
                || (samples[0].0[axis] - maximum[axis]).abs() <= tolerance
                || (samples[last].0[axis] - minimum[axis]).abs() <= tolerance
                || (samples[last].0[axis] - maximum[axis]).abs() <= tolerance;
            let seam_slope = if seam_extremum {
                -filtered_central_difference(filtered, 0, period, true, axis)
            } else {
                0.0
            };
            slopes[0] = seam_slope;
            slopes[last] = seam_slope;
        }

        let mut compensation = vec![0.0; samples.len()];
        let anchor_frames = anchors
            .iter()
            .enumerate()
            .filter_map(|(frame, anchor)| anchor.then_some(frame))
            .collect::<Vec<_>>();
        for pair in anchor_frames.windows(2) {
            let start = pair[0];
            let end = pair[1];
            let width = (end - start) as f32;
            for frame in start..=end {
                let t = (frame - start) as f32 / width;
                compensation[frame] = hermite(
                    residuals[start],
                    residuals[end],
                    slopes[start],
                    slopes[end],
                    width,
                    t,
                );
            }
        }
        for frame in 0..samples.len() {
            corrected[frame][axis] =
                (filtered[frame].0[axis] + compensation[frame]).clamp(minimum[axis], maximum[axis]);
        }
    }
    if looped && period > 0 {
        corrected[period] = corrected[0];
        for axis in 0..3 {
            let end = samples[period].0[axis];
            let start = samples[0].0[axis];
            if (end - start).abs() > 1.0e-6 {
                corrected[period][axis] = end;
            }
        }
    }
    corrected
}

fn filtered_central_difference(
    filtered: &[(Vec3, Quat)],
    frame: usize,
    period: usize,
    looped: bool,
    axis: usize,
) -> f32 {
    if looped && period > 1 {
        let index = frame % period;
        let previous = (index + period - 1) % period;
        let next = (index + 1) % period;
        (filtered[next].0[axis] - filtered[previous].0[axis]) * 0.5
    } else if frame == 0 {
        filtered[1].0[axis] - filtered[0].0[axis]
    } else if frame + 1 == filtered.len() {
        filtered[frame].0[axis] - filtered[frame - 1].0[axis]
    } else {
        (filtered[frame + 1].0[axis] - filtered[frame - 1].0[axis]) * 0.5
    }
}

fn hermite(start: f32, end: f32, start_slope: f32, end_slope: f32, width: f32, t: f32) -> f32 {
    let t2 = t * t;
    let t3 = t2 * t;
    (2.0 * t3 - 3.0 * t2 + 1.0) * start
        + (t3 - 2.0 * t2 + t) * width * start_slope
        + (-2.0 * t3 + 3.0 * t2) * end
        + (t3 - t2) * width * end_slope
}

fn endpoint_fade(frame: usize, period: usize, fade_frames: u32) -> f32 {
    if frame == 0 || frame == period {
        return 0.0;
    }
    if fade_frames == 0 {
        return 1.0;
    }
    let distance = frame.min(period.saturating_sub(frame));
    let t = (distance as f32 / fade_frames as f32).clamp(0.0, 1.0);
    t * t * (3.0 - 2.0 * t)
}

fn sample_range(name: &str, motion: &Motion, first: u32, last: u32) -> Result<Vec<(Vec3, Quat)>> {
    (first..=last)
        .map(|frame| {
            let transform = motion.find_bone_transform(name, frame, 0.0);
            let rotation = transform.orientation.normalize();
            if !transform.translation.is_finite()
                || !rotation.is_finite()
                || rotation.length_squared() < 1.0e-8
            {
                Err(MmdError::VmdParse(format!(
                    "骨骼轨道“{}”包含无效变换",
                    name
                )))
            } else {
                Ok((transform.translation, rotation))
            }
        })
        .collect()
}

pub(crate) fn selected(name: &str, selection: &BoneSelection) -> bool {
    match selection {
        BoneSelection::All => true,
        BoneSelection::Named(names) => names.iter().any(|candidate| candidate == name),
        BoneSelection::AllExceptIk => !looks_like_ik(name),
        BoneSelection::UpperBody => {
            !looks_like_lower_body(name) && !is_root_track(name) && looks_like_upper_body(name)
        }
    }
}

pub(crate) fn encode_pose(template: &[u8], pose: &SmoothedPose) -> Vec<u8> {
    let mut bytes = template.to_vec();
    bytes[15..19].copy_from_slice(&pose.frame.to_le_bytes());
    write_f32(&mut bytes, 19, pose.translation.x);
    write_f32(&mut bytes, 23, pose.translation.y);
    write_f32(&mut bytes, 27, -pose.translation.z);
    let q = pose.orientation.normalize();
    write_f32(&mut bytes, 31, q.x);
    write_f32(&mut bytes, 35, q.y);
    write_f32(&mut bytes, 39, -q.z);
    write_f32(&mut bytes, 43, -q.w);
    let linear = [20, 20, 107, 107];
    for (channel, values) in [linear, linear, linear, linear].iter().enumerate() {
        for (control, value) in values.iter().enumerate() {
            bytes[47 + control * 4 + channel] = *value;
        }
    }
    bytes
}

fn clamped_vector_average(samples: &[(Vec3, Quat)], frame: usize, radius: u32) -> Vec3 {
    let mut sum = Vec3::ZERO;
    let mut weight_sum = 0.0;
    for offset in -(radius as i64)..=radius as i64 {
        let weight = window_weight(offset, radius);
        let index = (frame as i64 + offset).clamp(0, samples.len() as i64 - 1) as usize;
        sum += samples[index].0 * weight;
        weight_sum += weight;
    }
    sum / weight_sum
}

fn periodic_vector_average(
    samples: &[(Vec3, Quat)],
    frame: usize,
    period: usize,
    radius: u32,
    preserve_drift: bool,
) -> Vec3 {
    let mut sum = Vec3::ZERO;
    let mut weight_sum = 0.0;
    for offset in -(radius as i64)..=radius as i64 {
        let weight = window_weight(offset, radius);
        let index = (frame as i64 + offset).rem_euclid(period as i64) as usize;
        let phase = index as f32 / period as f32;
        let local_base = if preserve_drift {
            samples[0].0.lerp(samples[period].0, phase)
        } else {
            samples[0].0
        };
        sum += (samples[index].0 - local_base) * weight;
        weight_sum += weight;
    }
    sum / weight_sum
}

fn clamped_rotation_average(samples: &[(Vec3, Quat)], frame: usize, radius: u32) -> Quat {
    let center = samples[frame].1;
    rotation_average(
        center,
        (-(radius as i64)..=radius as i64).map(|offset| {
            let index = (frame as i64 + offset).clamp(0, samples.len() as i64 - 1) as usize;
            (samples[index].1, window_weight(offset, radius))
        }),
    )
}

fn periodic_rotation_average(
    samples: &[(Vec3, Quat)],
    frame: usize,
    period: usize,
    radius: u32,
) -> Quat {
    let center = samples[frame].1;
    rotation_average(
        center,
        (-(radius as i64)..=radius as i64).map(|offset| {
            let index = (frame as i64 + offset).rem_euclid(period as i64) as usize;
            (samples[index].1, window_weight(offset, radius))
        }),
    )
}

fn rotation_average(center: Quat, values: impl Iterator<Item = (Quat, f32)>) -> Quat {
    let inverse = center.inverse();
    let mut tangent = Vec3::ZERO;
    let mut weight_sum = 0.0;
    for (mut rotation, weight) in values {
        rotation = rotation.normalize();
        if center.dot(rotation) < 0.0 {
            rotation = -rotation;
        }
        let mut delta = (inverse * rotation).normalize();
        if delta.w < 0.0 {
            delta = -delta;
        }
        let (axis, angle) = delta.to_axis_angle();
        if angle.is_finite() && axis.is_finite() {
            tangent += axis * (angle * weight);
        }
        weight_sum += weight;
    }
    if weight_sum <= 0.0 {
        return center;
    }
    let tangent = tangent / weight_sum;
    let angle = tangent.length();
    if angle <= 1.0e-7 {
        center
    } else {
        (center * Quat::from_axis_angle(tangent / angle, angle)).normalize()
    }
}

fn rotation_distance(a: Quat, b: Quat) -> f32 {
    2.0 * a.dot(b).abs().clamp(0.0, 1.0).acos()
}

fn window_weight(offset: i64, radius: u32) -> f32 {
    (radius as i64 + 1 - offset.abs()) as f32
}

fn looks_like_ik(name: &str) -> bool {
    let lower = name.to_lowercase();
    lower.contains("ik") || name.contains("ＩＫ") || name.contains("ｉｋ")
}

pub(crate) fn is_foot_ik(name: &str) -> bool {
    if !looks_like_ik(name) {
        return false;
    }
    let lower = name.to_lowercase();
    ["foot", "feet", "toe", "ankle"]
        .iter()
        .any(|part| lower.contains(part))
        || ["足", "脚", "つま先", "趾", "踝"]
            .iter()
            .any(|part| name.contains(part))
}

fn looks_like_lower_body(name: &str) -> bool {
    let lower = name.to_lowercase();
    ["leg", "knee", "ankle", "foot", "toe", "thigh", "ik"]
        .iter()
        .any(|part| lower.contains(part))
        || ["足", "ひざ", "膝", "つま先", "足首", "腿", "ＩＫ", "ｉｋ"]
            .iter()
            .any(|part| name.contains(part))
}

fn looks_like_upper_body(name: &str) -> bool {
    let lower = name.to_lowercase();
    [
        "upper", "chest", "neck", "head", "shoulder", "arm", "elbow", "wrist", "hand", "finger",
        "spine",
    ]
    .iter()
    .any(|part| lower.contains(part))
        || [
            "上半身",
            "首",
            "頭",
            "头",
            "肩",
            "腕",
            "ひじ",
            "肘",
            "手",
            "指",
            "胸",
        ]
        .iter()
        .any(|part| name.contains(part))
}

fn is_root_track(name: &str) -> bool {
    let lower = name.to_lowercase();
    [
        "center", "centre", "groove", "root", "hips", "waist", "pelvis",
    ]
    .iter()
    .any(|part| lower.contains(part))
        || ["センター", "グルーブ", "全ての親", "腰", "骨盤"]
            .iter()
            .any(|part| name.contains(part))
}

fn write_f32(bytes: &mut [u8], offset: usize, value: f32) {
    bytes[offset..offset + 4].copy_from_slice(&value.to_le_bytes());
}
