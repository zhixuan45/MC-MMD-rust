//! Shared semantic classification. Template aliases never override a named body part.
use mmd::pmx::rigid_body::RigidBody as PmxRigidBody;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(super) enum HairKind {
    Front,
    Side,
    Back,
    Unspecified,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(super) enum BodyPart {
    Garment,
    Hair(HairKind),
    Accessory,
    Sleeve,
    Tail,
    HeadNeck,
    Other,
    Unknown,
}

impl BodyPart {
    pub fn is_hair(self) -> bool {
        matches!(self, Self::Hair(_))
    }
}

pub(super) fn classify(body: &PmxRigidBody, bone_name: Option<&str>) -> BodyPart {
    let local = body.local_name.to_lowercase();
    let local_part = classify_name(&local);
    let bone_part = bone_name
        .map(str::to_lowercase)
        .map(|n| classify_name(&n))
        .unwrap_or(BodyPart::Unknown);
    if local_part != BodyPart::Unknown && !is_skirt_template(&local) {
        // Head/torso bone names describe attachment location, not an override of an explicit part.
        if is_dynamic_part(local_part) && is_dynamic_part(bone_part) && local_part != bone_part {
            if local_part.is_hair() && bone_part.is_hair() {
                if local_part == BodyPart::Hair(HairKind::Unspecified) {
                    return bone_part;
                }
                if bone_part == BodyPart::Hair(HairKind::Unspecified) {
                    return local_part;
                }
            }
            return BodyPart::Unknown;
        }
        if local_part == BodyPart::Garment && bone_part == BodyPart::HeadNeck {
            return BodyPart::Unknown;
        }
        return local_part;
    }
    if bone_part != BodyPart::Unknown {
        return bone_part;
    }
    if local_part != BodyPart::Unknown {
        return local_part;
    }
    classify_name(&body.universal_name.to_lowercase())
}

fn is_dynamic_part(part: BodyPart) -> bool {
    matches!(
        part,
        BodyPart::Garment
            | BodyPart::Hair(_)
            | BodyPart::Accessory
            | BodyPart::Sleeve
            | BodyPart::Tail
    )
}

fn is_skirt_template(name: &str) -> bool {
    name.strip_prefix("skirt").is_some_and(|rest| {
        rest.chars()
            .all(|c| c.is_ascii_digit() || matches!(c, '_' | '-' | ' '))
    })
}

fn contains(name: &str, words: &[&str]) -> bool {
    words.iter().any(|word| name.contains(word))
}

// Accept numbered names such as 1hair_FC_01, but not chair, elbow, or rainbow.
fn token(name: &str, word: &str) -> bool {
    name.match_indices(word).any(|(index, _)| {
        let before = name[..index].chars().next_back();
        let after = name[index + word.len()..].chars().next();
        before.is_none_or(|c| !c.is_ascii_alphabetic())
            && after.is_none_or(|c| !c.is_ascii_alphabetic())
    })
}

pub(super) fn classify_name(name: &str) -> BodyPart {
    if contains(
        name,
        &[
            "蝴蝶结",
            "蝴蝶結",
            "リボン",
            "飾",
            "饰",
            "装飾",
            "配件",
            "附件",
            "穗",
            "穂",
            "飘带",
            "飄帶",
            "衣带",
            "衣帶",
            "发带",
            "髪飾",
            "ヘアアクセ",
            "hairaccessory",
            "hairornament",
            "hairbow",
            "hairclip",
            "hairribbon",
            "髮帶",
            "发夹",
            "アクセサリー",
        ],
    ) || [
        "accessory",
        "accessories",
        "ribbon",
        "bow",
        "tassel",
        "hairband",
        "headband",
        "hairpin",
    ]
    .iter()
    .any(|w| token(name, w))
    {
        return BodyPart::Accessory;
    }
    if contains(name, &["袖", "sleeve"]) {
        return BodyPart::Sleeve;
    }
    if contains(name, &["頭", "头", "首", "neck"]) || token(name, "head") {
        // Explicit hair names below must not be consumed by 頭髪/头发.
        if !contains(name, &["髪", "髮", "头发", "頭髪"]) {
            return BodyPart::HeadNeck;
        }
    }
    let pony = contains(
        name,
        &[
            "馬尾",
            "马尾",
            "ツインテール",
            "ポニーテール",
            "ponytail",
            "twintail",
            "tail_hair",
            "tailhair",
        ],
    );
    if !pony
        && (contains(name, &["尻尾", "しっぽ", "尾巴"])
            || ["尾", "左尾", "右尾", "后尾", "後尾"].iter().any(|prefix| {
                name.strip_prefix(prefix).is_some_and(|suffix| {
                    suffix.is_empty()
                        || suffix
                            .starts_with(|c: char| c.is_ascii_digit() || matches!(c, '_' | '-'))
                })
            })
            || token(name, "tail"))
    {
        return BodyPart::Tail;
    }
    let is_hair =
        pony || contains(
            name,
            &[
                "髪",
                "髮",
                "辮",
                "辫",
                "長髮",
                "长发",
                "后发",
                "後发",
                "前发",
                "侧发",
                "侧髮",
                "头发",
                "呆毛",
                "アホ毛",
                "おさげ",
                "bangs",
                "ahoge",
                "longhair",
                "backhair",
                "hairback",
                "fronthair",
                "hairfront",
                "sidehair",
                "hairside",
            ],
        ) || token(name, "hair")
            || token(name, "braid");
    if is_hair {
        let kind = if contains(
            name,
            &["前髪", "前髮", "前发", "bangs", "まえがみ", "fronthair"],
        ) || token(name, "front")
        {
            HairKind::Front
        } else if contains(
            name,
            &[
                "横髪",
                "横髮",
                "横发",
                "側髮",
                "侧髮",
                "侧发",
                "鬓",
                "サイド",
                "おさげ",
                "よこがみ",
                "sidehair",
            ],
        ) || token(name, "side")
            || token(name, "braid")
        {
            HairKind::Side
        } else if pony
            || contains(
                name,
                &[
                    "後髪",
                    "後髮",
                    "后髮",
                    "後ろ",
                    "うしろ",
                    "长发",
                    "長髮",
                    "后发",
                    "backhair",
                    "longhair",
                ],
            )
            || token(name, "back")
            || token(name, "rear")
        {
            HairKind::Back
        } else {
            HairKind::Unspecified
        };
        return BodyPart::Hair(kind);
    }
    if contains(
        name,
        &[
            "胸",
            "乳",
            "バスト",
            "おっぱい",
            "shoe",
            "heel",
            "靴",
            "くつ",
            "鞋",
            "手臂",
            "上臂",
            "前臂",
            "腕",
            "肘",
            "breast",
            "bust",
            "chest",
            "upperarm",
            "forearm",
            "elbow",
        ],
    ) || token(name, "arm")
    {
        return BodyPart::Other;
    }
    if contains(
        name,
        &[
            "裙",
            "スカート",
            "skirt",
            "petticoat",
            "下装",
            "下衣",
            "裾",
            "衣摆",
            "衣擺",
            "后摆",
            "後擺",
            "下摆",
            "下擺",
            "下襬",
            "风衣",
            "外套",
            "コート",
            "燕尾",
        ],
    ) || ["coat", "cloak", "cape", "flap"]
        .iter()
        .any(|word| token(name, word))
    {
        return BodyPart::Garment;
    }
    BodyPart::Unknown
}

#[cfg(test)]
mod tests {
    use super::*;
    use mmd::pmx::rigid_body::{RigidBodyMode, RigidBodyShape};
    fn body(local: &str, english: &str) -> PmxRigidBody {
        PmxRigidBody {
            local_name: local.into(),
            universal_name: english.into(),
            bone_index: 0,
            group: 0,
            un_collision_group_flag: 0,
            shape: RigidBodyShape::Sphere,
            size: [0.1; 3],
            position: [0.0; 3],
            rotation: [0.0; 3],
            mass: 1.0,
            move_attenuation: 0.0,
            rotation_attenuation: 0.0,
            repulsion: 0.0,
            friction: 0.0,
            mode: RigidBodyMode::Dynamic,
        }
    }
    #[test]
    fn explicit_parts_override_exporter_skirt_templates() {
        for (name, part) in [
            ("后长发_0_1", BodyPart::Hair(HairKind::Back)),
            ("长发蝴蝶结L", BodyPart::Accessory),
            ("左髮飾3", BodyPart::Accessory),
            ("发带蝴蝶结", BodyPart::Accessory),
            ("长袖_0_1", BodyPart::Sleeve),
            ("胸前穗_0_1", BodyPart::Accessory),
            ("左衣帶_8_1", BodyPart::Accessory),
        ] {
            assert_eq!(classify(&body(name, "Skirt_0_1"), None), part, "{name}");
        }
    }
    #[test]
    fn direct_bone_semantics_disambiguate_generic_names_and_conflicts_are_conservative() {
        assert_eq!(
            classify(&body("Skirt_0_1", ""), Some("後髪_1")),
            BodyPart::Hair(HairKind::Back)
        );
        assert_eq!(
            classify(&body("rigid_01", "Skirt_0_1"), Some("长袖_1")),
            BodyPart::Sleeve
        );
        assert_eq!(
            classify(&body("左裙片", ""), Some("後髪_1")),
            BodyPart::Unknown
        );
        assert_eq!(
            classify(&body("后长发", "Skirt_0_1"), Some("頭")),
            BodyPart::Hair(HairKind::Back)
        );
    }
    #[test]
    fn genuine_garments_and_unqualified_hair_keep_their_distinct_roles() {
        for name in [
            "裙_1_2",
            "下擺_1_2",
            "衣擺_1_2",
            "後擺_1_2",
            "スカート_1_2",
            "Skirt_0_1",
            "Sp_Hi_MSkirt0_F_00_skirt_physics",
            "披风cape_01",
        ] {
            assert_eq!(classify(&body(name, ""), None), BodyPart::Garment);
        }
        assert_eq!(
            classify(&body("hair_01", ""), None),
            BodyPart::Hair(HairKind::Unspecified)
        );
        assert_eq!(
            classify(&body("前髪_1", ""), None),
            BodyPart::Hair(HairKind::Front)
        );
        for name in ["发射器", "chair", "rainbow", "landscape"] {
            assert_eq!(classify(&body(name, ""), None), BodyPart::Unknown);
        }
    }
}
