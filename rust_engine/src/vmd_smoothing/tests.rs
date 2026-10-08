use super::{smooth_bytes, BoneSelection, SmoothingOptions, VmdDocument};
use crate::animation::VmdFile;
use glam::Vec3;

const SPRINT: &[u8] =
    include_bytes!("../../../common/src/main/resources/assets/mmdskin/default_anim/sprint.vmd");
const WALK: &[u8] =
    include_bytes!("../../../common/src/main/resources/assets/mmdskin/default_anim/walk.vmd");

fn options(selection: BoneSelection) -> SmoothingOptions {
    SmoothingOptions {
        strength: 1.0,
        radius: 1,
        looped: false,
        selection,
    }
}

fn record(name: &str, frame: u32, x: f32, q: [f32; 4]) -> Vec<u8> {
    let mut bytes = vec![0_u8; 111];
    let (name, _, _) = encoding_rs::SHIFT_JIS.encode(name);
    bytes[..name.len().min(15)].copy_from_slice(&name[..name.len().min(15)]);
    bytes[15..19].copy_from_slice(&frame.to_le_bytes());
    bytes[19..23].copy_from_slice(&x.to_le_bytes());
    bytes[43..47].copy_from_slice(&q[3].to_le_bytes());
    bytes[31..35].copy_from_slice(&q[0].to_le_bytes());
    bytes[35..39].copy_from_slice(&q[1].to_le_bytes());
    bytes[39..43].copy_from_slice(&q[2].to_le_bytes());
    for channel in 0..4 {
        for (index, value) in [20, 20, 107, 107].iter().enumerate() {
            bytes[47 + index * 4 + channel] = *value;
        }
    }
    bytes
}

fn vmd(records: &[Vec<u8>]) -> Vec<u8> {
    let mut bytes = vec![0; 50];
    bytes[..25].copy_from_slice(b"Vocaloid Motion Data 0002");
    bytes.extend_from_slice(&(records.len() as u32).to_le_bytes());
    for record in records {
        bytes.extend_from_slice(record);
    }
    for _ in 0..5 {
        bytes.extend_from_slice(&0_u32.to_le_bytes());
    }
    bytes
}

fn f32_at(bytes: &[u8], offset: usize) -> f32 {
    f32::from_le_bytes(bytes[offset..offset + 4].try_into().unwrap())
}

fn output_records(bytes: &[u8]) -> Vec<Vec<u8>> {
    let count = u32::from_le_bytes(bytes[50..54].try_into().unwrap()) as usize;
    (0..count)
        .map(|index| {
            let start = 54 + index * 111;
            bytes[start..start + 111].to_vec()
        })
        .collect()
}

fn records_for(bytes: &[u8], name: &str) -> Vec<Vec<u8>> {
    output_records(bytes)
        .into_iter()
        .filter(|record| {
            let raw = &record[..15];
            let end = raw.iter().position(|byte| *byte == 0).unwrap_or(15);
            let (decoded, _, _) = encoding_rs::SHIFT_JIS.decode(&raw[..end]);
            decoded == name
        })
        .collect()
}

fn raw_position(record: &[u8]) -> Vec3 {
    Vec3::new(f32_at(record, 19), f32_at(record, 23), -f32_at(record, 27))
}

fn second_difference_energy(positions: &[Vec3]) -> f32 {
    positions
        .windows(3)
        .map(|triple| (triple[2] - triple[1] * 2.0 + triple[0]).length())
        .sum()
}

fn assert_extrema_and_range_preserved(original: &[Vec3], output: &[Vec3]) {
    assert_eq!(original.len(), output.len());
    for axis in 0..3 {
        let minimum = original
            .iter()
            .map(|position| position[axis])
            .fold(f32::INFINITY, f32::min);
        let maximum = original
            .iter()
            .map(|position| position[axis])
            .fold(f32::NEG_INFINITY, f32::max);
        let tolerance = minimum.abs().max(maximum.abs()).max(1.0) * 1.0e-5;
        let output_min = output
            .iter()
            .map(|position| position[axis])
            .fold(f32::INFINITY, f32::min);
        let output_max = output
            .iter()
            .map(|position| position[axis])
            .fold(f32::NEG_INFINITY, f32::max);
        assert!((output_min - minimum).abs() <= tolerance);
        assert!((output_max - maximum).abs() <= tolerance);
        for (frame, position) in original.iter().enumerate() {
            if (position[axis] - minimum).abs() <= tolerance
                || (position[axis] - maximum).abs() <= tolerance
            {
                assert!((output[frame][axis] - position[axis]).abs() <= tolerance);
            }
        }
    }
}

#[test]
fn zero_strength_returns_exact_original_bytes() {
    let mut source = vmd(&[record("spine", 0, 3.0, [0.0, 0.0, 0.0, -1.0])]);
    source.extend_from_slice(b"opaque tail");
    let mut opts = options(BoneSelection::AllExceptIk);
    opts.strength = 0.0;
    let result = smooth_bytes(&source, &opts).unwrap();
    assert_eq!(result.bytes, source);
    assert_eq!(result.report.original_keys, result.report.output_keys);
}




#[test]
fn symmetric_filter_reduces_a_translation_spike() {
    let xs = [0.0, 0.0, 10.0, 0.0, 0.0];
    let records = xs
        .iter()
        .enumerate()
        .map(|(frame, x)| record("spine", frame as u32, *x, [0.0, 0.0, 0.0, -1.0]))
        .collect::<Vec<_>>();
    let result = smooth_bytes(&vmd(&records), &options(BoneSelection::AllExceptIk)).unwrap();
    let poses = output_records(&result.bytes);
    assert!(f32_at(&poses[2], 19) < 10.0);
    assert!(f32_at(&poses[2], 19) > 0.0);
}






#[test]
fn looped_foot_compensation_preserves_seam_and_stride_extrema() {
    let values = [0.0, 2.0, 5.0, 2.0, 0.0];
    let records = values
        .iter()
        .enumerate()
        .map(|(frame, value)| record("左足ＩＫ", frame as u32, *value, [0.0, 0.0, 0.0, -1.0]))
        .collect::<Vec<_>>();
    let mut opts = options(BoneSelection::Named(vec!["左足ＩＫ".into()]));
    opts.looped = true;
    let result = smooth_bytes(&vmd(&records), &opts).unwrap();
    let poses = records_for(&result.bytes, "左足ＩＫ");
    let original = records
        .iter()
        .map(|record| raw_position(record))
        .collect::<Vec<_>>();
    let output = poses
        .iter()
        .map(|record| raw_position(record))
        .collect::<Vec<_>>();
    assert_extrema_and_range_preserved(&original, &output);
    assert!(
        (output[1].x - output[3].x).abs() < 1e-5,
        "循环接缝中央差分应为零"
    );
}

#[test]
fn looped_processing_closes_a_cyclic_pose() {
    let xs = [12.0, 13.0, 12.0, 11.0, 12.0];
    let records = xs
        .iter()
        .enumerate()
        .map(|(frame, x)| record("spine", frame as u32, *x, [0.0, 0.0, 0.0, -1.0]))
        .collect::<Vec<_>>();
    let mut opts = options(BoneSelection::AllExceptIk);
    opts.looped = true;
    let result = smooth_bytes(&vmd(&records), &opts).unwrap();
    let poses = output_records(&result.bytes);
    assert_eq!(poses.len(), 5);
    let mean = poses.iter().map(|pose| f32_at(pose, 19)).sum::<f32>() / poses.len() as f32;
    assert!((mean - 12.0).abs() < 1e-5);
    assert!((f32_at(&poses[0], 19) - f32_at(&poses[4], 19)).abs() < 1e-5);
    let first_step = f32_at(&poses[1], 19) - f32_at(&poses[0], 19);
    let seam_step = f32_at(&poses[0], 19) - f32_at(&poses[3], 19);
    assert!((first_step - seam_step).abs() < 1e-5);
}








#[test]
fn unselected_records_and_opaque_tail_are_preserved() {
    let unselected = record("left foot", 1, 7.0, [0.0, 0.0, 0.0, -1.0]);
    let selected = record("spine", 1, 2.0, [0.0, 0.0, 0.0, -1.0]);
    let source = vmd(&[unselected.clone(), selected]);
    let tail = &source[54 + 222..];
    let result = smooth_bytes(
        &source,
        &options(BoneSelection::Named(vec!["spine".into()])),
    )
    .unwrap();
    let records = output_records(&result.bytes);
    assert_eq!(records[0], unselected);
    let out_tail = &result.bytes[result.bytes.len() - tail.len()..];
    assert_eq!(out_tail, tail);
}

#[test]
fn malformed_sections_non_finite_values_and_bad_quaternions_are_rejected() {
    let mut truncated = vmd(&[record("spine", 0, 0.0, [0.0, 0.0, 0.0, -1.0])]);
    truncated.pop();
    assert!(VmdDocument::from_bytes(&truncated).is_err());

    let mut non_finite = vmd(&[record("spine", 0, f32::NAN, [0.0, 0.0, 0.0, -1.0])]);
    assert!(VmdDocument::from_bytes(&non_finite).is_err());

    let zero_q = vmd(&[record("spine", 0, 0.0, [0.0; 4])]);
    assert!(VmdDocument::from_bytes(&zero_q).is_err());

    non_finite[..25].copy_from_slice(b"Vocaloid Motion Data file");
    assert!(VmdDocument::from_bytes(&non_finite)
        .unwrap_err()
        .to_string()
        .contains("VMD1"));
}
