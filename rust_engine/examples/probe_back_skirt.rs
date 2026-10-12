//! 后裙绑定姿态与静止物理对照；结果不能替代游戏画面验收。
use mmd_engine::{
    model::load_pmx,
    physics::{body_collider_scale_flags, set_config, CollisionStabilityMode, PhysicsConfig},
};
use serde_json::json;
use std::{collections::BTreeSet, env};

fn main() {
    let path = env::args().nth(1).expect("需要 PMX 路径");
    for (mode, scale) in [
        (CollisionStabilityMode::Strict, 0.7),
        (CollisionStabilityMode::Strict, 0.8),
        (CollisionStabilityMode::Strict, 1.0),
        (CollisionStabilityMode::Stable, 0.7),
        (CollisionStabilityMode::Stable, 0.8),
        (CollisionStabilityMode::Stable, 1.0),
    ] {
        let mut config = PhysicsConfig::default();
        config.collision_stability_mode = mode;
        config.static_collider_scale = scale;
        config.inertia_strength = 0.0;
        set_config(config);
        let mut model = load_pmx(&path).expect("读取 PMX");
        model.update_node_animation(false);
        // 使用生产分类，避免把挂裙根的骨盆误算为被缩小的碰撞壳。
        let flags = body_collider_scale_flags(&model.rigid_bodies, &model.joints);
        let scaled: Vec<_> = model
            .rigid_bodies
            .iter()
            .zip(&flags)
            .filter(|(b, _)| b.local_name.contains("下半身"))
            .map(|(b, scaled)| json!({"name":b.local_name,"scaled":scaled,"radius":b.size[0]}))
            .collect();
        let mesh = model
            .submeshes
            .iter()
            .find(|s| model.materials[s.material_id as usize].name == "裙")
            .expect("裙材质");
        let ids: BTreeSet<_> = model.indices
            [mesh.begin_index as usize..(mesh.begin_index + mesh.index_count) as usize]
            .iter()
            .copied()
            .collect();
        // 后部 PMX 的 Z 为正；生产加载器转成右手系后为负。
        let selected: Vec<_> = ids
            .into_iter()
            .filter(|&i| {
                let p = model.vertices[i as usize].position;
                p.z < -0.6 && p.y > 10.3 && p.y < 12.4
            })
            .collect();
        assert!(model.init_physics());
        for _ in 0..600 {
            model.tick_animation_no_skinning(1.0 / 60.0);
        }
        model.update();
        let vertices: Vec<_> = selected
            .iter()
            .map(|&i| {
                json!({"id":i,"bind":model.vertices[i as usize].position.to_array(),
                "simulated":model.update_positions[i as usize].to_array()})
            })
            .collect();
        println!(
            "{}",
            json!({"mode":format!("{mode:?}"),"scale":scale,
            "scaled_bodies":scaled,"seconds":10,"rear_vertices":vertices,
            "diagnostic":model.issue_debug_diagnostic(0x104)})
        );
    }
}
