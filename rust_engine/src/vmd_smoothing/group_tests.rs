use super::{
    batch::process_directory_grouped, parse_group_config, serialize_group_config, smooth_bytes,
    smooth_grouped_bytes, BoneSelection, SmoothingGroup, SmoothingOptions, VmdDocument,
};
use std::{
    fs,
    path::PathBuf,
    time::{SystemTime, UNIX_EPOCH},
};

fn record(name: &str, frame: u32, x: f32) -> Vec<u8> {
    let mut bytes = vec![0_u8; 111];
    let (encoded, _, _) = encoding_rs::SHIFT_JIS.encode(name);
    let len = encoded.len().min(15);
    bytes[..len].copy_from_slice(&encoded[..len]);
    bytes[15..19].copy_from_slice(&frame.to_le_bytes());
    bytes[19..23].copy_from_slice(&x.to_le_bytes());
    bytes[43..47].copy_from_slice(&(-1.0_f32).to_le_bytes());
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

fn opts(name: &str, strength: f32, radius: u32) -> SmoothingOptions {
    SmoothingOptions {
        strength,
        radius,
        looped: false,
        selection: BoneSelection::Named(vec![name.into()]),
    }
}

fn group(name: &str, enabled: bool, options: SmoothingOptions) -> SmoothingGroup {
    SmoothingGroup {
        name: name.into(),
        enabled,
        options,
    }
}

fn track_records(bytes: &[u8], name: &str) -> Vec<Vec<u8>> {
    let count = u32::from_le_bytes(bytes[50..54].try_into().unwrap()) as usize;
    (0..count)
        .map(|index| bytes[54 + index * 111..54 + (index + 1) * 111].to_vec())
        .filter(|record| {
            let (decoded, _, _) = encoding_rs::SHIFT_JIS.decode(&record[..15]);
            decoded.trim_end_matches('\0') == name
        })
        .collect()
}

fn temp_dir() -> PathBuf {
    let id = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap()
        .as_nanos();
    let path = std::env::temp_dir().join(format!("mmd-group-{}-{id}", std::process::id()));
    fs::create_dir_all(&path).unwrap();
    path
}

#[test]
fn tracks_use_independent_group_parameters_and_later_owner() {
    let values = [0.0, 0.0, 10.0, 0.0, 0.0];
    let mut records = Vec::new();
    for (name, scale) in [("spine", 1.0), ("hips", 2.0)] {
        records.extend(
            values
                .iter()
                .enumerate()
                .map(|(frame, value)| record(name, frame as u32, value * scale)),
        );
    }
    let source = vmd(&records);
    let groups = [
        group("first", true, opts("spine", 1.0, 1)),
        group("hips", true, opts("hips", 0.4, 2)),
        group("last spine", true, opts("spine", 0.0, 5)),
    ];
    let result = smooth_grouped_bytes(&source, &groups).unwrap();
    let expected_hips = smooth_bytes(&source, &groups[1].options).unwrap();
    assert_eq!(
        track_records(&result.bytes, "hips"),
        track_records(&expected_hips.bytes, "hips")
    );
    assert_eq!(
        track_records(&result.bytes, "spine"),
        track_records(&source, "spine")
    );
    assert_eq!(result.report.selected_tracks, 2);
    assert_eq!(result.report.original_keys, 10);
}


#[test]
fn all_selection_includes_ik_and_empty_groups_preserve_bytes() {
    let source = vmd(&[
        record("left foot IK", 0, 0.0),
        record("left foot IK", 1, 9.0),
        record("left foot IK", 2, 0.0),
    ]);
    assert_eq!(smooth_grouped_bytes(&source, &[]).unwrap().bytes, source);
    let result = smooth_grouped_bytes(
        &source,
        &[group(
            "all",
            true,
            SmoothingOptions {
                selection: BoneSelection::All,
                ..opts("unused", 1.0, 1)
            },
        )],
    )
    .unwrap();
    assert_eq!(result.report.selected_tracks, 1);
    assert_ne!(result.bytes, source);
}


#[test]
fn group_config_roundtrips_and_rejects_bad_fields() {
    let groups = vec![
        group("upper", true, SmoothingOptions::default()),
        group(
            "all",
            false,
            SmoothingOptions {
                selection: BoneSelection::All,
                ..SmoothingOptions::default()
            },
        ),
        group("named", true, opts("spine", 0.75, 7)),
    ];
    let encoded = serialize_group_config(&groups).unwrap();
    let decoded = parse_group_config(&encoded).unwrap();
    assert_eq!(decoded.len(), groups.len());
    for (actual, expected) in decoded.iter().zip(groups.iter()) {
        assert_eq!(actual.name, expected.name);
        assert_eq!(actual.enabled, expected.enabled);
        assert_eq!(actual.options.strength, expected.options.strength);
        assert_eq!(actual.options.radius, expected.options.radius);
        assert_eq!(actual.options.looped, expected.options.looped);
    }
    assert!(parse_group_config(r#"{"groups":[{"name":"x","enabled":"yes"}]}"#).is_err());
    assert!(parse_group_config(r#"{"groups":[{"name":"x","selection":"other"}]}"#).is_err());
    assert!(parse_group_config(r#"{"groups":[{"name":" "}]}"#).is_err());
    assert!(parse_group_config(r#"{"group":[]}"#).is_err());
    assert!(parse_group_config(r#"{"groups":[],"typo":true}"#).is_err());
    assert!(parse_group_config(r#"{"groups":[{"nam":"x"}]}"#).is_err());
    assert!(
        VmdDocument::from_bytes(&vmd(&[]))
            .unwrap()
            .smooth_grouped(&[])
            .unwrap()
            .bytes
            == vmd(&[])
    );
}
