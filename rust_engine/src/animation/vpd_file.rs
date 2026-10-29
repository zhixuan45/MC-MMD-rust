//! VPD 文件加载器


use std::path::Path;

use glam::{Quat, Vec3};

use crate::{MmdError, Result};

/// VPD 骨骼数据
#[derive(Debug, Clone)]
pub struct VpdBone {
    /// 骨骼名称
    pub name: String,
    /// 平移
    pub translation: Vec3,
    /// 旋转（四元数）
    pub rotation: Quat,
}

/// VPD Morph 数据
#[derive(Debug, Clone)]
pub struct VpdMorph {
    /// Morph 名称
    pub name: String,
    /// 权重
    pub weight: f32,
}

/// VPD 文件数据
#[derive(Debug, Clone)]
pub struct VpdFile {
    /// 模型名称
    pub model_name: String,
    /// 骨骼数据
    pub bones: Vec<VpdBone>,
    /// Morph 数据
    pub morphs: Vec<VpdMorph>,
}

impl VpdFile {
    pub fn load<P: AsRef<Path>>(path: P) -> Result<Self> {
        Self::load_from_bytes(&std::fs::read(path).map_err(MmdError::Io)?)
    }

    pub fn load_from_bytes(bytes: &[u8]) -> Result<Self> {
        let text = match std::str::from_utf8(bytes) {
            Ok(text) => std::borrow::Cow::Borrowed(text),
            Err(_) => {
                let (text, _, errors) = encoding_rs::SHIFT_JIS.decode(bytes);
                if errors { return Err(MmdError::VpdParse("Invalid text encoding".into())); }
                text
            }
        };
        let mut lines = text.trim_start_matches('\u{feff}').lines()
            .map(|line| line.split("//").next().unwrap_or("").trim())
            .filter(|line| !line.is_empty());
        let error = |message: &str| MmdError::VpdParse(message.to_string());
        if lines.next() != Some("Vocaloid Pose Data file") { return Err(error("Invalid VPD header")); }
        let model_name = lines.next().ok_or_else(|| error("Missing model name"))?
            .trim_end_matches(';').to_string();
        let expected_bones: usize = lines.next().ok_or_else(|| error("Missing bone count"))?
            .trim_end_matches(';').trim().parse().map_err(|_| error("Invalid bone count"))?;
        let mut bones = Vec::new();
        let mut morphs = Vec::new();
        let mut block: Option<(bool, String)> = None;
        let mut values = Vec::<f32>::new();
        for line in lines {
            if let Some((prefix, name)) = line.split_once('{') {
                if block.is_some() { return Err(error("Unclosed VPD block")); }
                let bone = prefix.starts_with("Bone");
                if !bone && !prefix.starts_with("Morph") { return Err(error("Unknown VPD block")); }
                if name.trim().is_empty() { return Err(error("Missing bone or morph name")); }
                block = Some((bone, name.trim().to_string()));
                values.clear();
            } else if line.trim_end_matches(';') == "}" {
                let (bone, name) = block.take().ok_or_else(|| error("Unexpected block end"))?;
                if values.iter().any(|v| !v.is_finite()) { return Err(error("Non-finite VPD value")); }
                if bone {
                    if values.len() != 7 { return Err(error("Expected 7 bone values")); }
                    let rotation = Quat::from_xyzw(values[3], values[4], -values[5], -values[6]);
                    if rotation.length_squared() < 1e-12 { return Err(error("Invalid bone rotation")); }
                    bones.push(VpdBone { name,
                        translation: Vec3::new(values[0], values[1], -values[2]),
                        rotation: rotation.normalize() });
                } else {
                    if values.len() != 1 { return Err(error("Expected one morph weight")); }
                    morphs.push(VpdMorph { name, weight: values[0] });
                }
            } else if block.is_some() {
                for value in line.trim_end_matches(';').split(',').map(str::trim).filter(|s| !s.is_empty()) {
                    values.push(value.parse().map_err(|_| error("Invalid numeric VPD value"))?);
                }
            } else {
                return Err(error("Unexpected data outside VPD block"));
            }
        }
        if block.is_some() || bones.len() != expected_bones { return Err(error("Incomplete VPD pose")); }
        Ok(Self { model_name, bones, morphs })
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    const POSE: &str = "Vocaloid Pose Data file\r\n\r\nmodel.osm;\r\n1; // count\r\n\r\nBone0{右腕\r\n1,2,3; // translation\r\n0,0,0,1;\r\n}\r\nMorph0{笑い\r\n0.5;\r\n}\r\n";

    #[test]
    fn utf8_bom_and_shift_jis_poses_have_identical_bone_names() {
        let utf8 = VpdFile::load_from_bytes(format!("\u{feff}{POSE}").as_bytes()).unwrap();
        let (encoded, _, errors) = encoding_rs::SHIFT_JIS.encode(POSE);
        assert!(!errors);
        let sjis = VpdFile::load_from_bytes(&encoded).unwrap();
        assert_eq!(utf8.bones[0].name, "右腕");
        assert_eq!(sjis.bones[0].name, utf8.bones[0].name);
        assert_eq!(sjis.bones[0].translation, Vec3::new(1.0,2.0,-3.0));
        assert_eq!(sjis.morphs[0].weight, 0.5);
    }

    #[test]
    fn rejects_truncated_and_non_finite_poses_before_application() {
        assert!(VpdFile::load_from_bytes(POSE.replace("0,0,0,1", "0,0,0,0").as_bytes()).is_err());
        assert!(VpdFile::load_from_bytes(POSE.replace("1,2,3", "NaN,2,3").as_bytes()).is_err());
        assert!(VpdFile::load_from_bytes(POSE.split("Morph0").next().unwrap().trim_end_matches(['\r','\n','}']).as_bytes()).is_err());
    }
}
