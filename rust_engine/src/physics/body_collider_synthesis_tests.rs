use super::*;

    fn test_pmx_body(
        name: &str,
        mode: RigidBodyMode,
        shape: RigidBodyShape,
        size: [f32; 3],
    ) -> PmxRigidBody {
        PmxRigidBody {
            local_name: name.to_owned(),
            universal_name: String::new(),
            bone_index: 0,
            group: 0,
            un_collision_group_flag: 0,
            shape,
            size,
            position: [0.0; 3],
            rotation: [0.0; 3],
            mass: 1.0,
            move_attenuation: 0.0,
            rotation_attenuation: 0.0,
            repulsion: 0.0,
            friction: 0.0,
            mode,
        }
    }

    #[test]
    fn detects_missing_upper_body_collider_when_absent() {
        let only_skirt_colliders = vec![
            test_pmx_body(
                "left_thigh_skirt_collider",
                RigidBodyMode::Static,
                RigidBodyShape::Capsule,
                [1.0, 4.0, 0.0],
            ),
            test_pmx_body(
                "Sp_Hi_Tail0_B_00_body_blocker",
                RigidBodyMode::Static,
                RigidBodyShape::Sphere,
                [1.0, 0.0, 0.0],
            ),
        ];
        assert!(!has_upper_body_collider(&only_skirt_colliders));
    }

    #[test]
    fn detects_upper_body_collider_when_present() {
        let with_chest = vec![test_pmx_body(
            "上半身",
            RigidBodyMode::Static,
            RigidBodyShape::Capsule,
            [0.8, 1.2, 0.0],
        )];
        assert!(has_upper_body_collider(&with_chest));
    }

    #[test]
    fn detects_lower_body_collider_when_present_or_absent() {
        let only_tail = vec![test_pmx_body(
            "Sp_Hi_Tail0_B_00_anchor",
            RigidBodyMode::Static,
            RigidBodyShape::Sphere,
            [0.1, 0.0, 0.0],
        )];
        assert!(!has_lower_body_collider(&only_tail), "尾巴微小锚点不应视为骨盆碰撞体");

        let with_blocker = vec![test_pmx_body(
            "Sp_Hi_Tail0_B_00_body_blocker",
            RigidBodyMode::Static,
            RigidBodyShape::Sphere,
            [1.0, 0.0, 0.0],
        )];
        assert!(has_lower_body_collider(&with_blocker), "身体阻挡体应视为下半身碰撞体");

        let with_pelvis = vec![test_pmx_body(
            "下半身",
            RigidBodyMode::Static,
            RigidBodyShape::Capsule,
            [0.8, 1.0, 0.0],
        )];
        assert!(has_lower_body_collider(&with_pelvis), "应识别下半身碰撞体");
    }

    #[test]
    fn synthesizes_colliders_for_missing_model() {
        let rigid_bodies = vec![test_pmx_body(
            "Sp_He_Hair2_L_00_anchor",
            RigidBodyMode::Static,
            RigidBodyShape::Sphere,
            [0.1, 0.0, 0.0],
        )];
        let bone_names = vec!["センター", "下半身", "上半身", "首", "頭"];
        let bone_positions = vec![
            [0.0, 0.0, 0.0],
            [0.0, 8.0, 0.0],
            [0.0, 12.0, 0.0],
            [0.0, 15.0, 0.0],
            [0.0, 16.5, 0.0],
        ];

        let synthesized =
            synthesize_missing_body_colliders(&rigid_bodies, &bone_names, &bone_positions);
        assert_eq!(synthesized.len(), 3, "应合成胸部、颈部和骨盆三个跟骨碰撞体");
        assert_eq!(synthesized[0].bone_index, 2);
        assert_eq!(synthesized[1].bone_index, 3);
        assert_eq!(synthesized[2].bone_index, 1);
        assert_eq!(synthesized[0].mode, RigidBodyMode::Static);
        assert_eq!(synthesized[2].mode, RigidBodyMode::Static);
    }

    #[test]
    fn push_out_moves_penetrated_point_to_surface() {
        let spheres = vec![BodyColliderSphere {
            center: Vec3::new(0.0, 10.0, 0.0),
            radius: 1.0,
        }];
        let capsules = vec![];

        let inside = Vec3::new(0.0, 10.0, 0.5);
        let pushed = push_out_dynamic_bone_position(inside, &spheres, &capsules);

        assert!((pushed - Vec3::new(0.0, 10.0, 1.0)).length() < 1e-5);
    }

    #[test]
    fn probe_all_player_models() {
        let models = [
            ("Silence Suzuka", "C:\\tmp\\mc-mmd-rust\\neoforge\\run\\client\\3d-skin\\EntityPlayer\\Silence Suzuka\\1002_无声铃鹿.pmx"),
            ("Oguri Cap", "C:\\tmp\\mc-mmd-rust\\neoforge\\run\\client\\3d-skin\\EntityPlayer\\oguri\\1006_小栗帽.pmx"),
            ("Tokai Teio", "C:\\tmp\\mc-mmd-rust\\neoforge\\run\\client\\3d-skin\\EntityPlayer\\teio\\1003_Tokai Teio.pmx"),
            ("Grass Wonder", "C:\\tmp\\mc-mmd-rust\\neoforge\\run\\client\\3d-skin\\EntityPlayer\\grasswonder\\1011_Grass Wonder.pmx"),
        ];

        for (name, path) in models {
            println!("\n========================================================");
            println!("Testing Model: {}", name);
            if let Ok(mut model) = crate::model::load_pmx(path) {
                if model.init_physics() {
                    for _ in 0..60 {
                        model.update_node_animation(false);
                        model.update_physics(1.0 / 60.0);
                    }
                    println!("--- Skirt Bone Positions After 60 Frames ---");
                    for i in 0..model.bone_manager.bone_count() {
                        if let Some(bone) = model.bone_manager.get_bone(i) {
                            if bone.name.contains("Skirt") || bone.name.contains("skirt") {
                                let rest_pos = bone.initial_position;
                                let current_trans = model.bone_manager.get_global_transform(i);
                                let current_pos = current_trans.w_axis.truncate();
                                let _delta = current_pos - rest_pos;
                                if bone.name.ends_with("_01") || bone.name.ends_with("_02") || bone.name.ends_with("_03") {
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    #[test]
    fn probe_tail_physics() {
        for (name, path) in [
            ("Silence Suzuka", "C:\\tmp\\mc-mmd-rust\\neoforge\\run\\client\\3d-skin\\EntityPlayer\\Silence Suzuka\\1002_Silence Suzuka.pmx"),
            ("Tokai Teio", "C:\\tmp\\mc-mmd-rust\\neoforge\\run\\client\\3d-skin\\EntityPlayer\\teio\\1003_Tokai Teio.pmx"),
        ] {
            println!("\n========================================================");
            println!("Inspecting Tail for: {}", name);
            if let Ok(model) = crate::model::load_pmx(path) {
                for (i, rb) in model.rigid_bodies.iter().enumerate() {
                    if rb.local_name.to_lowercase().contains("tail") || rb.local_name.contains("尾") || rb.local_name.contains("尻尾") {
                        println!("  RB #{i:02} '{}' bone={} mode={:?} group={} mask=0x{:04X} shape={:?} size={:?} pos={:?} mass={}",
                            rb.local_name, rb.bone_index, rb.mode, rb.group, rb.un_collision_group_flag, rb.shape, rb.size, rb.position, rb.mass
                        );
                    }
                }
                for (i, joint) in model.joints.iter().enumerate() {
                    if joint.local_name.to_lowercase().contains("tail") || joint.local_name.contains("尾") || joint.local_name.contains("尻尾") {
                        println!("  Joint #{i:02} '{}' a={} b={} pos={:?} rot_min={:?} rot_max={:?} spring_rot={:?}",
                            joint.local_name, joint.rigid_body_a_index, joint.rigid_body_b_index,
                            joint.position, joint.rotation_min, joint.rotation_max, joint.rotation_spring
                        );
                    }
                }
            }
        }
    }

    #[test]
    fn probe_skirt_symmetry() {
        let path = "C:\\tmp\\mc-mmd-rust\\neoforge\\run\\client\\3d-skin\\EntityPlayer\\Silence Suzuka\\1002_Silence Suzuka.pmx";
        if let Ok(mut model) = crate::model::load_pmx(path) {
            println!("\n=== Silence Suzuka Skirt Bone Symmetry After Simulation ===");
            if model.init_physics() {
                for _ in 0..60 {
                    model.update_node_animation(false);
                    model.update_physics(1.0 / 60.0);
                }
                for i in 0..model.bone_manager.bone_count() {
                    if let Some(bone) = model.bone_manager.get_bone(i) {
                        let name = &bone.name;
                        if name.contains("MSkirt") || name.contains("skirt") || name.contains("Skirt") {
                            let _curr = model.bone_manager.get_global_transform(i).w_axis.truncate();
                        }
                    }
                }
            }
        }
    }

    #[test]
    fn probe_all_suzuka_colliders() {
        let path = "C:\\tmp\\mc-mmd-rust\\neoforge\\run\\client\\3d-skin\\EntityPlayer\\Silence Suzuka\\1002_Silence Suzuka.pmx";
        if let Ok(model) = crate::model::load_pmx(path) {
            println!("\n=== Silence Suzuka All Rigid Bodies ===");
            for (i, rb) in model.rigid_bodies.iter().enumerate() {
                println!("  RB #{i:02} '{:<35}' bone={:<3} mode={:?} group={:<2} mask=0x{:04X} shape={:?} size={:?} pos=({:.2},{:.2},{:.2})",
                    rb.local_name, rb.bone_index, rb.mode, rb.group, rb.un_collision_group_flag, rb.shape, rb.size, rb.position[0], rb.position[1], rb.position[2]
                );
            }
        }
    }

    #[test]
    fn probe_all_suzuka_joints() {
        let path = "C:\\tmp\\mc-mmd-rust\\neoforge\\run\\client\\3d-skin\\EntityPlayer\\Silence Suzuka\\1002_Silence Suzuka.pmx";
        if let Ok(model) = crate::model::load_pmx(path) {
            println!("\n=== Silence Suzuka All Joints ===");
            for (i, j) in model.joints.iter().enumerate() {
                let rba = &model.rigid_bodies[j.rigid_body_a_index as usize];
                let rbb = &model.rigid_bodies[j.rigid_body_b_index as usize];
                println!("Joint #{i:02} '{:<35}' A[{:02}] '{:<25}' <-> B[{:02}] '{:<25}' pos={:?} rot_min={:?} rot_max={:?} spring={:?}",
                    j.local_name, j.rigid_body_a_index, rba.local_name, j.rigid_body_b_index, rbb.local_name,
                    j.position, j.rotation_min, j.rotation_max, j.rotation_spring
                );
            }
        }
    }

    #[test]
    fn probe_skirt_mass() {
        let path = "C:\\tmp\\mc-mmd-rust\\neoforge\\run\\client\\3d-skin\\EntityPlayer\\Silence Suzuka\\1002_Silence Suzuka.pmx";
        if let Ok(model) = crate::model::load_pmx(path) {
            println!("\n=== SKIRT RIGID BODIES MASS & PARAMETERS ===");
            for (i, rb) in model.rigid_bodies.iter().enumerate() {
                if rb.local_name.contains("skirt") || rb.local_name.contains("MSkirt") {
                    println!("RB #{i:02} '{:<35}' mass={} friction={}",
                        rb.local_name, rb.mass, rb.friction
                    );
                }
            }
        }
    }

    #[test]
    fn probe_grasswonder() {
        let path = "C:\\tmp\\mc-mmd-rust\\neoforge\\run\\client\\3d-skin\\EntityPlayer\\grasswonder\\1011_Grass Wonder.pmx";
        if let Ok(model) = crate::model::load_pmx(path) {
            println!("\n=== Grass Wonder Rigid Bodies ===");
            for (i, rb) in model.rigid_bodies.iter().enumerate() {
                if rb.local_name.contains("skirt") || rb.local_name.contains("Skirt") || rb.local_name.contains("裙")
                    || rb.local_name.contains("thigh") || rb.local_name.contains("shin") || rb.local_name.contains("leg")
                    || rb.local_name.contains("足") || rb.local_name.contains("膝") || rb.local_name.contains("腰") || rb.local_name.contains("下半身")
                {
                    println!("  RB #{i:02} '{:<35}' bone={:<3} mode={:?} group={:<2} mask=0x{:04X} shape={:?} size={:?} pos=({:.2},{:.2},{:.2})",
                        rb.local_name, rb.bone_index, rb.mode, rb.group, rb.un_collision_group_flag, rb.shape, rb.size, rb.position[0], rb.position[1], rb.position[2]
                    );
                }
            }

            println!("--- Skirt Joints ---");
            for (i, j) in model.joints.iter().enumerate() {
                if j.local_name.contains("skirt") || j.local_name.contains("Skirt") || j.local_name.contains("裙") {
                    let rba = &model.rigid_bodies[j.rigid_body_a_index as usize];
                    let rbb = &model.rigid_bodies[j.rigid_body_b_index as usize];
                    println!("  Joint #{i:02} '{:<35}' A[{:02}] '{:<25}' <-> B[{:02}] '{:<25}' pos={:?} rot_min={:?} rot_max={:?} spring={:?}",
                        j.local_name, j.rigid_body_a_index, rba.local_name, j.rigid_body_b_index, rbb.local_name,
                        j.position, j.rotation_min, j.rotation_max, j.rotation_spring
                    );
                }
            }
        }
    }

    #[test]
    fn probe_grasswonder_run() {
        let path = "C:\\tmp\\mc-mmd-rust\\neoforge\\run\\client\\3d-skin\\EntityPlayer\\grasswonder\\1011_Grass Wonder.pmx";
        if let Ok(mut model) = crate::model::load_pmx(path) {
            println!("\n=== Grass Wonder Running Simulation (Leg Swing) ===");
            if model.init_physics() {
                let left_leg_idx = model.bone_manager.find_bone_by_name("左足").unwrap_or(0);
                let right_leg_idx = model.bone_manager.find_bone_by_name("右足").unwrap_or(0);
                let left_knee_idx = model.bone_manager.find_bone_by_name("左ひざ").unwrap_or(0);

                for frame in 1..=60 {
                    let angle = (frame as f32 * 0.1).sin() * 0.8;
                    model.bone_manager.set_bone_rotation(left_leg_idx, glam::Quat::from_rotation_x(angle));
                    model.bone_manager.set_bone_rotation(left_knee_idx, glam::Quat::from_rotation_x((angle * 0.5).max(0.0)));
                    model.bone_manager.set_bone_rotation(right_leg_idx, glam::Quat::from_rotation_x(-angle));

                    model.update_node_animation(false);
                    model.update_physics(1.0 / 60.0);
                }

                println!("Simulation finished 60 frames under heavy leg swing.");
                for i in 0..model.bone_manager.bone_count() {
                    if let Some(bone) = model.bone_manager.get_bone(i) {
                        if bone.name.contains("MSkirt0_F_") || bone.name.contains("MSkirt0_B_") || bone.name.contains("MSkirt0_L_") {
                            let curr = model.bone_manager.get_global_transform(i).w_axis.truncate();
                            let rest = bone.initial_position;
                            println!("  Bone {:<22} rest=({:+.2},{:+.2},{:+.2}) curr=({:+.2},{:+.2},{:+.2})",
                                bone.name, rest.x, rest.y, rest.z, curr.x, curr.y, curr.z
                            );
                        }
                    }
                }
            }
        }
    }

    #[test]
    fn probe_compare_pushout() {
        let path = "C:\\tmp\\mc-mmd-rust\\neoforge\\run\\client\\3d-skin\\EntityPlayer\\Silence Suzuka\\1002_Silence Suzuka.pmx";
        if let Ok(mut model) = crate::model::load_pmx(path) {
            println!("\n=== Silence Suzuka Skirt Bone Positions ===");
            if model.init_physics() {
                for _ in 0..60 {
                    model.update_node_animation(false);
                    model.update_physics(1.0 / 60.0);
                }
                for i in 0..model.bone_manager.bone_count() {
                    if let Some(bone) = model.bone_manager.get_bone(i) {
                        let name = &bone.name;
                        if name.contains("MSkirt") || name.contains("skirt") || name.contains("Skirt") {
                            let curr = model.bone_manager.get_global_transform(i).w_axis.truncate();
                            let rest = bone.initial_position;
                            let diff = curr - rest;
                            println!("  Bone #{:<3} {:<24} rest=({:+.2}, {:+.2}, {:+.2}) curr=({:+.2}, {:+.2}, {:+.2}) diff=({:+.2}, {:+.2}, {:+.2})",
                                i, name, rest.x, rest.y, rest.z, curr.x, curr.y, curr.z, diff.x, diff.y, diff.z
                            );
                        }
                    }
                }
            }
        }
    }

    #[test]
    fn probe_hair_skirt_overlap_test() {
        let path = "C:\\tmp\\mc-mmd-rust\\neoforge\\run\\client\\3d-skin\\EntityPlayer\\Silence Suzuka\\1002_Silence Suzuka.pmx";
        if let Ok(model) = crate::model::load_pmx(path) {
            println!("\n=== HAIR vs SKIRT OVERLAP PROBE ===");
            for (i, rba) in model.rigid_bodies.iter().enumerate() {
                if !rba.local_name.contains("Hair") && !rba.local_name.contains("hair") {
                    continue;
                }
                for (j, rbb) in model.rigid_bodies.iter().enumerate() {
                    if !rbb.local_name.contains("Skirt") && !rbb.local_name.contains("skirt") {
                        continue;
                    }
                    let pa = glam::Vec3::from_slice(&rba.position);
                    let pb = glam::Vec3::from_slice(&rbb.position);
                    let dist = pa.distance(pb);
                    let max_r_a = rba.size[0].max(rba.size[1]);
                    let max_r_b = rbb.size[0].max(rbb.size[1]).max(rbb.size[2]);
                    if dist < (max_r_a + max_r_b) {
                        println!("  OVERLAP: Hair RB #{:02} '{:<30}' (pos={:?}, size={:?}) <---> Skirt RB #{:02} '{:<30}' (pos={:?}, size={:?}) dist={:.3}",
                            i, rba.local_name, rba.position, rba.size,
                            j, rbb.local_name, rbb.position, rbb.size, dist
                        );
                    }
                }
            }
        }
    }

    #[test]
    fn probe_inspect_bl_br_details() {
        let path = "C:\\tmp\\mc-mmd-rust\\neoforge\\run\\client\\3d-skin\\EntityPlayer\\Silence Suzuka\\1002_Silence Suzuka.pmx";
        if let Ok(model) = crate::model::load_pmx(path) {
            println!("\n=== BL / BR BONES ===");
            for i in 0..model.bone_manager.bone_count() {
                if let Some(bone) = model.bone_manager.get_bone(i) {
                    let name = &bone.name;
                    if name.contains("BL") || name.contains("BR") || name.contains("左足") || name.contains("右足") || name.contains("腰") {
                        println!("  Bone #{:<3} '{:<25}' parent={:<3} pos=({:.3},{:.3},{:.3})",
                            i, name, bone.parent_index, bone.initial_position.x, bone.initial_position.y, bone.initial_position.z
                        );
                    }
                }
            }

            println!("\n=== BL / BR RIGID BODIES ===");
            for (i, rb) in model.rigid_bodies.iter().enumerate() {
                let name = &rb.local_name;
                if name.contains("BL") || name.contains("BR") || name.contains("thigh") || name.contains("pelvis") {
                    println!("  RB #{:<2} '{:<35}' bone={:<3} mode={:?} group={:<2} mask=0x{:04X} shape={:?} size={:?} pos=({:.3},{:.3},{:.3}) rot=({:.3},{:.3},{:.3})",
                        i, name, rb.bone_index, rb.mode, rb.group, rb.un_collision_group_flag, rb.shape, rb.size,
                        rb.position[0], rb.position[1], rb.position[2],
                        rb.rotation[0], rb.rotation[1], rb.rotation[2]
                    );
                }
            }

            println!("\n=== BL / BR JOINTS ===");
            for (i, j) in model.joints.iter().enumerate() {
                let name = &j.local_name;
                if name.contains("BL") || name.contains("BR") {
                    let rba = &model.rigid_bodies[j.rigid_body_a_index as usize];
                    let rbb = &model.rigid_bodies[j.rigid_body_b_index as usize];
                    println!("  Joint #{:<2} '{:<35}' A[{:02}] '{:<25}' <-> B[{:02}] '{:<25}' pos=({:.3},{:.3},{:.3}) rot_min={:?} rot_max={:?} spring_rot={:?}",
                        i, name, j.rigid_body_a_index, rba.local_name, j.rigid_body_b_index, rbb.local_name,
                        j.position[0], j.position[1], j.position[2],
                        j.rotation_min, j.rotation_max, j.rotation_spring
                    );
                }
            }
        }
    }

    #[test]
    fn probe_inspect_suzuka_all_skirt() {
        let path = "C:\\tmp\\mc-mmd-rust\\neoforge\\run\\client\\3d-skin\\EntityPlayer\\Silence Suzuka\\1002_Silence Suzuka.pmx";
        if let Ok(model) = crate::model::load_pmx(path) {
            println!("\n=== ALL SKIRT RIGID BODIES ===");
            for (i, rb) in model.rigid_bodies.iter().enumerate() {
                if rb.local_name.contains("MSkirt") || rb.local_name.contains("skirt") || rb.local_name.contains("Skirt") {
                    println!("RB #{:<2} '{:<35}' bone={:<3} mode={:?} group={:<2} mask=0x{:04X} shape={:?} size={:?} pos=({:+.3},{:+.3},{:+.3}) rot=({:+.3},{:+.3},{:+.3})",
                        i, rb.local_name, rb.bone_index, rb.mode, rb.group, rb.un_collision_group_flag, rb.shape, rb.size,
                        rb.position[0], rb.position[1], rb.position[2],
                        rb.rotation[0], rb.rotation[1], rb.rotation[2]
                    );
                }
            }

            println!("\n=== ALL SKIRT JOINTS ===");
            for (i, j) in model.joints.iter().enumerate() {
                if j.local_name.contains("MSkirt") || j.local_name.contains("skirt") || j.local_name.contains("Skirt") {
                    let rba = &model.rigid_bodies[j.rigid_body_a_index as usize];
                    let rbb = &model.rigid_bodies[j.rigid_body_b_index as usize];
                    println!("Joint #{:<2} '{:<42}' A[{:02}] '{:<26}' <-> B[{:02}] '{:<26}' pos=({:+.2},{:+.2},{:+.2}) rot_min={:?} rot_max={:?} spr_rot={:?}",
                        i, j.local_name, j.rigid_body_a_index, rba.local_name, j.rigid_body_b_index, rbb.local_name,
                        j.position[0], j.position[1], j.position[2],
                        j.rotation_min, j.rotation_max, j.rotation_spring
                    );
                }
            }
        }
    }

    #[test]
    fn probe_skirt_xz_cross_section_test() {
        let path = "C:\\tmp\\mc-mmd-rust\\neoforge\\run\\client\\3d-skin\\EntityPlayer\\Silence Suzuka\\1002_Silence Suzuka.pmx";
        if let Ok(mut model) = crate::model::load_pmx(path) {
            println!("\n=== SKIRT REST BONE POSITIONS (TOP-DOWN X, Z) ===");
            let chains = ["F", "FL", "FLL", "L", "BL", "B", "BR", "R", "FRR", "FR"];
            for level in ["00", "01", "02", "03"] {
                println!("\n--- Level {} ---", level);
                for chain in chains {
                    let bone_name = format!("Sp_Hi_MSkirt0_{}_{}", chain, level);
                    for i in 0..model.bone_manager.bone_count() {
                        if let Some(bone) = model.bone_manager.get_bone(i) {
                            if bone.name == bone_name {
                                let p = bone.initial_position;
                                let r = (p.x * p.x + p.z * p.z).sqrt();
                                let angle_deg = p.z.atan2(p.x).to_degrees();
                                println!("  {:<22} X={:+6.3} Z={:+6.3} (Y={:+6.3}) => R={:5.3}, angle={:+6.1}°",
                                    bone.name, p.x, p.z, p.y, r, angle_deg
                                );
                            }
                        }
                    }
                }
            }

            if model.init_physics() {
                for _ in 0..60 {
                    model.update_node_animation(false);
                    model.update_physics(1.0 / 60.0);
                }
                println!("\n=== SKIRT SIMULATED BONE POSITIONS (AFTER 60 FRAMES) ===");
                for level in ["00", "01", "02", "03"] {
                    println!("\n--- Level {} (Simulated) ---", level);
                    for chain in chains {
                        let bone_name = format!("Sp_Hi_MSkirt0_{}_{}", chain, level);
                        for i in 0..model.bone_manager.bone_count() {
                            if let Some(bone) = model.bone_manager.get_bone(i) {
                                if bone.name == bone_name {
                                    let curr = model.bone_manager.get_global_transform(i).w_axis.truncate();
                                    let rest = bone.initial_position;
                                    let diff = curr - rest;
                                    let r_curr = (curr.x * curr.x + curr.z * curr.z).sqrt();
                                    let r_rest = (rest.x * rest.x + rest.z * rest.z).sqrt();
                                    println!("  {:<22} REST=(X={:+6.3},Z={:+6.3}) CURR=(X={:+6.3},Z={:+6.3}) diff=(X={:+6.3},Z={:+6.3}) => dR={:+6.3}",
                                        bone.name, rest.x, rest.z, curr.x, curr.z, diff.x, diff.z, r_curr - r_rest
                                    );
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    #[test]
    fn probe_all_skirt_contacts_test() {
        let path = "C:\\tmp\\mc-mmd-rust\\neoforge\\run\\client\\3d-skin\\EntityPlayer\\Silence Suzuka\\1002_Silence Suzuka.pmx";
        if let Ok(mut model) = crate::model::load_pmx(path) {
            if model.init_physics() {
                for _ in 0..60 {
                    model.update_node_animation(false);
                    model.update_physics(1.0 / 60.0);
                }
                let debug_info = model.get_physics_debug_info();
                println!("\n=== ALL CONTACTS IN SUZUKA AFTER 60 FRAMES ===");
                println!("{}", debug_info);
            }
        }
    }

    #[test]
    fn probe_joint_rotations_test() {
        let path = "C:\\tmp\\mc-mmd-rust\\neoforge\\run\\client\\3d-skin\\EntityPlayer\\Silence Suzuka\\1002_Silence Suzuka.pmx";
        if let Ok(model) = crate::model::load_pmx(path) {
            println!("\n=== SUZUKA SKIRT JOINT ROTATIONS AND FRAMES ===");
            for (i, j) in model.joints.iter().enumerate() {
                if j.local_name.contains("MSkirt") {
                    let rba = &model.rigid_bodies[j.rigid_body_a_index as usize];
                    let rbb = &model.rigid_bodies[j.rigid_body_b_index as usize];
                    println!("Joint #{:<2} '{:<40}'\n  A: '{:<30}' rot={:?}\n  B: '{:<30}' rot={:?}\n  J: rot={:?} rot_min={:?} rot_max={:?} spr={:?}",
                        i, j.local_name,
                        rba.local_name, rba.rotation,
                        rbb.local_name, rbb.rotation,
                        j.rotation, j.rotation_min, j.rotation_max, j.rotation_spring
                    );
                }
            }
        }
    }

    #[test]
    fn probe_skirt_vertices_profile_test() {
        let path = "C:\\tmp\\mc-mmd-rust\\neoforge\\run\\client\\3d-skin\\EntityPlayer\\Silence Suzuka\\1002_Silence Suzuka.pmx";
        if let Ok(mut model) = crate::model::load_pmx(path) {
            println!("\n=== SKIRT MESH VERTEX XZ PROFILES ===");
            let mut skirt_bone_indices = Vec::new();
            for i in 0..model.bone_manager.bone_count() {
                if let Some(bone) = model.bone_manager.get_bone(i) {
                    if bone.name.contains("MSkirt") || bone.name.contains("skirt") || bone.name.contains("Skirt") {
                        skirt_bone_indices.push(i as u32);
                    }
                }
            }

            let mut skirt_v_indices = Vec::new();
            for (v_idx, w) in model.weights.iter().enumerate() {
                let is_skirt = match w {
                    crate::model::VertexWeight::Bdef1 { bone } => skirt_bone_indices.contains(&(*bone as u32)),
                    crate::model::VertexWeight::Bdef2 { bones, .. } => skirt_bone_indices.contains(&(bones[0] as u32)) || skirt_bone_indices.contains(&(bones[1] as u32)),
                    crate::model::VertexWeight::Bdef4 { bones, .. } => bones.iter().any(|idx| skirt_bone_indices.contains(&(*idx as u32))),
                    crate::model::VertexWeight::Sdef { bones, .. } => skirt_bone_indices.contains(&(bones[0] as u32)) || skirt_bone_indices.contains(&(bones[1] as u32)),
                    crate::model::VertexWeight::Qdef { bones, .. } => bones.iter().any(|idx| skirt_bone_indices.contains(&(*idx as u32))),
                };
                if is_skirt {
                    skirt_v_indices.push(v_idx);
                }
            }
            println!("Found {} skirt vertices.", skirt_v_indices.len());

            let check_bands = [(11.0, 11.5, "Upper Skirt"), (10.0, 10.5, "Mid Skirt"), (9.0, 9.5, "Lower Skirt")];

            for (y_min, y_max, label) in check_bands {
                println!("\n--- Band {} (Y in [{:.1}, {:.1}]) ---", label, y_min, y_max);
                let mut pts_rest = Vec::new();
                for &idx in &skirt_v_indices {
                    let p = model.vertices[idx].position;
                    if p.y >= y_min && p.y <= y_max {
                        pts_rest.push(p);
                    }
                }
                println!("  Rest vertices in band: {}", pts_rest.len());
                let mut min_x = f32::INFINITY; let mut max_x = f32::NEG_INFINITY;
                let mut min_z = f32::INFINITY; let mut max_z = f32::NEG_INFINITY;
                for p in &pts_rest {
                    min_x = min_x.min(p.x); max_x = max_x.max(p.x);
                    min_z = min_z.min(p.z); max_z = max_z.max(p.z);
                }
                println!("  Rest Bounds: X=[{:.3}, {:.3}] (width={:.3}), Z=[{:.3}, {:.3}] (depth={:.3})",
                    min_x, max_x, max_x - min_x, min_z, max_z, max_z - min_z
                );
            }

            if model.init_physics() {
                for _ in 0..60 {
                    model.update_node_animation(false);
                    model.update_physics(1.0 / 60.0);
                    model.end_animation();
                    model.update();
                }

                println!("\n=== AFTER 60 FRAMES SIMULATION ===");
                for (y_min, y_max, label) in check_bands {
                    println!("\n--- Band {} (Simulated) ---", label);
                    let mut min_x = f32::INFINITY; let mut max_x = f32::NEG_INFINITY;
                    let mut min_z = f32::INFINITY; let mut max_z = f32::NEG_INFINITY;
                    let mut count = 0;
                    for &idx in &skirt_v_indices {
                        let p = model.update_positions[idx];
                        if p.y >= y_min && p.y <= y_max {
                            min_x = min_x.min(p.x); max_x = max_x.max(p.x);
                            min_z = min_z.min(p.z); max_z = max_z.max(p.z);
                            count += 1;
                        }
                    }
                    println!("  Simulated vertices in band: {}", count);
                    println!("  Simulated Bounds: X=[{:.3}, {:.3}] (width={:.3}), Z=[{:.3}, {:.3}] (depth={:.3})",
                        min_x, max_x, max_x - min_x, min_z, max_z, max_z - min_z
                    );
                }
            }
        }
    }

    #[test]
    fn probe_warped_vertices_test() {
        let path = "C:\\tmp\\mc-mmd-rust\\neoforge\\run\\client\\3d-skin\\EntityPlayer\\Silence Suzuka\\1002_Silence Suzuka.pmx";
        if let Ok(mut model) = crate::model::load_pmx(path) {
            println!("\n=== TOP 20 MOST DISPLACED SKIRT VERTICES ===");
            if model.init_physics() {
                for _ in 0..60 {
                    model.update_node_animation(false);
                    model.update_physics(1.0 / 60.0);
                    model.end_animation();
                    model.update();
                }

                let mut displaced = Vec::new();
                for (i, v) in model.vertices.iter().enumerate() {
                    let rest = v.position;
                    let sim = model.update_positions[i];
                    let diff = sim - rest;
                    let dist = diff.length();
                    if dist > 0.5 {
                        displaced.push((dist, i, rest, sim, diff, &model.weights[i]));
                    }
                }

                displaced.sort_by(|a, b| b.0.partial_cmp(&a.0).unwrap());

                for (dist, idx, rest, sim, diff, weight) in displaced.iter().take(20) {
                    println!("Vertex #{:<5} dist={:5.3} rest=({:+6.3},{:+6.3},{:+6.3}) sim=({:+6.3},{:+6.3},{:+6.3}) diff=({:+6.3},{:+6.3},{:+6.3})",
                        idx, dist, rest.x, rest.y, rest.z, sim.x, sim.y, sim.z, diff.x, diff.y, diff.z
                    );
                    match weight {
                        crate::model::VertexWeight::Bdef1 { bone } => {
                            let name = model.bone_manager.get_bone(*bone as usize).map(|b| b.name.as_str()).unwrap_or("unknown");
                            println!("    Bone #{} '{}' (100%)", bone, name);
                        }
                        crate::model::VertexWeight::Bdef2 { bones, weight } => {
                            let n0 = model.bone_manager.get_bone(bones[0] as usize).map(|b| b.name.as_str()).unwrap_or("unknown");
                            let n1 = model.bone_manager.get_bone(bones[1] as usize).map(|b| b.name.as_str()).unwrap_or("unknown");
                            println!("    Bone #{} '{}' ({:.1}%), Bone #{} '{}' ({:.1}%)", bones[0], n0, weight * 100.0, bones[1], n1, (1.0 - weight) * 100.0);
                        }
                        crate::model::VertexWeight::Bdef4 { bones, weights } => {
                            for j in 0..4 {
                                if weights[j] > 0.001 {
                                    let n = model.bone_manager.get_bone(bones[j] as usize).map(|b| b.name.as_str()).unwrap_or("unknown");
                                    println!("    Bone #{} '{}' ({:.1}%)", bones[j], n, weights[j] * 100.0);
                                }
                            }
                        }
                        crate::model::VertexWeight::Sdef { bones, weight, .. } => {
                            let n0 = model.bone_manager.get_bone(bones[0] as usize).map(|b| b.name.as_str()).unwrap_or("unknown");
                            let n1 = model.bone_manager.get_bone(bones[1] as usize).map(|b| b.name.as_str()).unwrap_or("unknown");
                            println!("    SDEF Bone #{} '{}' ({:.1}%), Bone #{} '{}' ({:.1}%)", bones[0], n0, weight * 100.0, bones[1], n1, (1.0 - weight) * 100.0);
                        }
                        _ => {}
                    }
                }
            }
        }
    }

    #[test]
    fn probe_tail_trajectory_test() {
        let path = "C:\\tmp\\mc-mmd-rust\\neoforge\\run\\client\\3d-skin\\EntityPlayer\\Silence Suzuka\\1002_Silence Suzuka.pmx";
        if let Ok(mut model) = crate::model::load_pmx(path) {
            println!("\n=== TAIL BONES TRAJECTORY ===");
            if model.init_physics() {
                for frame in 0..60 {
                    model.update_node_animation(false);
                    model.update_physics(1.0 / 60.0);
                    model.end_animation();
                    if frame == 0 || frame == 1 || frame == 5 || frame == 10 || frame == 30 || frame == 59 {
                        println!("\n--- Frame {} ---", frame);
                        for i in 0..model.bone_manager.bone_count() {
                            if let Some(bone) = model.bone_manager.get_bone(i) {
                                if bone.name.contains("Tail") {
                                    let curr = model.bone_manager.get_global_transform(i).w_axis.truncate();
                                    let rest = bone.initial_position;
                                    println!("  Bone #{:<3} '{:<22}' rest=({:+.2},{:+.2},{:+.2}) curr=({:+.2},{:+.2},{:+.2})",
                                        i, bone.name, rest.x, rest.y, rest.z, curr.x, curr.y, curr.z
                                    );
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    #[test]
    fn synthesize_glutes_collider_for_skirt_models_test() {
        let path = "C:\\tmp\\mc-mmd-rust\\neoforge\\run\\client\\3d-skin\\EntityPlayer\\Silence Suzuka\\1002_Silence Suzuka.pmx";
        if let Ok(mut model) = crate::model::load_pmx(path) {
            assert!(model.init_physics(), "物理初始化应成功");

            for _ in 0..60 {
                model.update_node_animation(false);
                model.update_physics(1.0 / 60.0);
                model.end_animation();
            }

            // 验证 BL/BR 在模拟后得到有效后部支撑与环向约束，X 跨度保持饱满（>= 1.35，未坍塌至 1.28），不形成尖锐内凹
            for i in 0..model.bone_manager.bone_count() {
                if let Some(bone) = model.bone_manager.get_bone(i) {
                    if bone.name == "Sp_Hi_MSkirt0_BL_02" {
                        let curr = model.bone_manager.get_global_transform(i).w_axis.truncate();
                        assert!(curr.x >= 1.35, "BL_02 侧后裙片应保持饱满 X >= 1.35，实际: {}", curr.x);
                    } else if bone.name == "Sp_Hi_MSkirt0_BR_02" {
                        let curr = model.bone_manager.get_global_transform(i).w_axis.truncate();
                        assert!(curr.x <= -1.35, "BR_02 侧后裙片应保持饱满 X <= -1.35，实际: {}", curr.x);
                    }
                }
            }
        }
    }

    #[test]
    fn probe_test_skirt_ring_synthesis() {
        let path = "C:\\tmp\\mc-mmd-rust\\neoforge\\run\\client\\3d-skin\\EntityPlayer\\Silence Suzuka\\1002_Silence Suzuka.pmx";
        if let Ok(model) = crate::model::load_pmx(path) {
            println!("\n=== SKIRT RING EXTRACTION FOR SUZUKA ===");
            let mut skirt_rbs: Vec<(usize, &mmd::pmx::rigid_body::RigidBody)> = Vec::new();
            for (i, rb) in model.rigid_bodies.iter().enumerate() {
                if rb.mode != mmd::pmx::rigid_body::RigidBodyMode::Static {
                    let lower = rb.local_name.to_lowercase();
                    if lower.contains("skirt") || lower.contains("スカート") || lower.contains("裙") || lower.contains("裾") {
                        skirt_rbs.push((i, rb));
                    }
                }
            }
            println!("Found {} dynamic skirt rigid bodies.", skirt_rbs.len());

            let mut mean_x = 0.0;
            let mut mean_z = 0.0;
            for &(_, rb) in &skirt_rbs {
                mean_x += rb.position[0];
                mean_z += rb.position[2];
            }
            mean_x /= skirt_rbs.len() as f32;
            mean_z /= skirt_rbs.len() as f32;
            println!("Skirt horizontal center: ({:.3}, {:.3})", mean_x, mean_z);

            let mut sorted_by_y = skirt_rbs.clone();
            sorted_by_y.sort_by(|a, b| b.1.position[1].partial_cmp(&a.1.position[1]).unwrap());

            let mut layers: Vec<Vec<(usize, &mmd::pmx::rigid_body::RigidBody, f32)>> = Vec::new();
            for (idx, rb) in sorted_by_y {
                let angle = (rb.position[0] - mean_x).atan2(rb.position[2] - mean_z);
                let mut placed = false;
                for layer in &mut layers {
                    let layer_avg_y = layer.iter().map(|item| item.1.position[1]).sum::<f32>() / layer.len() as f32;
                    if (rb.position[1] - layer_avg_y).abs() < 0.45 {
                        layer.push((idx, rb, angle));
                        placed = true;
                        break;
                    }
                }
                if !placed {
                    layers.push(vec![(idx, rb, angle)]);
                }
            }

            println!("Identified {} skirt horizontal layers:", layers.len());
            for (l_idx, layer) in layers.iter_mut().enumerate() {
                layer.sort_by(|a, b| a.2.partial_cmp(&b.2).unwrap());
                println!("  Layer #{}: count={}, avg_Y={:.3}",
                    l_idx, layer.len(), layer.iter().map(|item| item.1.position[1]).sum::<f32>() / layer.len() as f32
                );
                for item in layer.iter() {
                    println!("    RB #{:<2} '{:<32}' angle={:+6.2}° pos=({:+6.3},{:+6.3},{:+6.3})",
                        item.0, item.1.local_name, item.2.to_degrees(), item.1.position[0], item.1.position[1], item.1.position[2]
                    );
                }
            }
        }
    }

    #[test]
    fn probe_test_plan_b_simulation() {
        let path = "C:\\tmp\\mc-mmd-rust\\neoforge\\run\\client\\3d-skin\\EntityPlayer\\Silence Suzuka\\1002_Silence Suzuka.pmx";
        if let Ok(mut model) = crate::model::load_pmx(path) {
            println!("\n=== SIMULATING PLAN B (CROSS JOINTS) ON SUZUKA ===");
            let mut skirt_rbs: Vec<(usize, &mmd::pmx::rigid_body::RigidBody)> = Vec::new();
            for (i, rb) in model.rigid_bodies.iter().enumerate() {
                if rb.mode != mmd::pmx::rigid_body::RigidBodyMode::Static {
                    let lower = rb.local_name.to_lowercase();
                    if lower.contains("skirt") || lower.contains("スカート") || lower.contains("裙") || lower.contains("裾") {
                        skirt_rbs.push((i, rb));
                    }
                }
            }

            let mut mean_x = 0.0;
            let mut mean_z = 0.0;
            for &(_, rb) in &skirt_rbs {
                mean_x += rb.position[0];
                mean_z += rb.position[2];
            }
            mean_x /= skirt_rbs.len() as f32;
            mean_z /= skirt_rbs.len() as f32;

            let mut sorted_by_y = skirt_rbs.clone();
            sorted_by_y.sort_by(|a, b| b.1.position[1].partial_cmp(&a.1.position[1]).unwrap());

            let mut layers: Vec<Vec<(usize, &mmd::pmx::rigid_body::RigidBody, f32)>> = Vec::new();
            for (idx, rb) in sorted_by_y {
                let angle = (rb.position[0] - mean_x).atan2(rb.position[2] - mean_z);
                let mut placed = false;
                for layer in &mut layers {
                    let layer_avg_y = layer.iter().map(|item| item.1.position[1]).sum::<f32>() / layer.len() as f32;
                    if (rb.position[1] - layer_avg_y).abs() < 0.45 {
                        layer.push((idx, rb, angle));
                        placed = true;
                        break;
                    }
                }
                if !placed {
                    layers.push(vec![(idx, rb, angle)]);
                }
            }

            let mut synthesized = Vec::new();
            for (l_idx, layer) in layers.iter_mut().enumerate() {
                let n = layer.len();
                if n < 3 {
                    continue;
                }
                layer.sort_by(|a, b| a.2.partial_cmp(&b.2).unwrap());

                for i in 0..n {
                    let a = &layer[i];
                    let b = &layer[(i + 1) % n];

                    let has_joint = model.joints.iter().any(|j| {
                        (j.rigid_body_a_index == a.0 as i32 && j.rigid_body_b_index == b.0 as i32)
                            || (j.rigid_body_a_index == b.0 as i32 && j.rigid_body_b_index == a.0 as i32)
                    });

                    if !has_joint {
                        let pos_a = glam::Vec3::from_array(a.1.position);
                        let pos_b = glam::Vec3::from_array(b.1.position);
                        let mid_pos = (pos_a + pos_b) * 0.5;
                        let chord = pos_b - pos_a;

                        let dir_x = chord.normalize();
                        let dir_y = glam::Vec3::Y;
                        let dir_z = dir_x.cross(dir_y).normalize();
                        let rot_mat = glam::Mat4::from_cols(
                            dir_x.extend(0.0),
                            dir_y.extend(0.0),
                            dir_z.extend(0.0),
                            glam::Vec3::ZERO.extend(1.0),
                        );
                        let rot_quat = glam::Quat::from_mat4(&rot_mat);
                        let (euler_z, euler_y, euler_x) = rot_quat.to_euler(glam::EulerRot::ZYX);
                        let rot_euler = [euler_x, euler_y, euler_z];

                        let pmx_joint = mmd::pmx::joint::Joint {
                            local_name: format!("Synthesized_Skirt_Cross_L{}_{}_{}", l_idx, a.0, b.0),
                            universal_name: format!("Synthesized_Skirt_Cross_L{}_{}_{}", l_idx, a.0, b.0),
                            type_: mmd::pmx::joint::JointType::Spring6DOF,
                            rigid_body_a_index: a.0 as i32,
                            rigid_body_b_index: b.0 as i32,
                            position: mid_pos.to_array(),
                            rotation: rot_euler,
                            position_min: [-0.05, -0.05, -0.05],
                            position_max: [0.05, 0.05, 0.05],
                            rotation_min: [-0.15, -0.15, -0.15],
                            rotation_max: [0.15, 0.15, 0.15],
                            position_spring: [30.0, 30.0, 30.0],
                            rotation_spring: [15.0, 15.0, 15.0],
                        };
                        synthesized.push(pmx_joint);
                    }
                }
            }

            println!("Synthesized {} cross joints.", synthesized.len());
            model.joints.extend(synthesized);

            if model.init_physics() {
                for _ in 0..60 {
                    model.update_node_animation(false);
                    model.update_physics(1.0 / 60.0);
                    model.end_animation();
                }

                println!("\n=== SKIRT SIMULATED POSITIONS WITH PLAN B ===");
                let chains = ["F", "FL", "FLL", "L", "BL", "B", "BR", "R", "FRR", "FR"];
                for level in ["01", "02", "03"] {
                    println!("\n--- Level {} ---", level);
                    for chain in chains {
                        let bone_name = format!("Sp_Hi_MSkirt0_{}_{}", chain, level);
                        for i in 0..model.bone_manager.bone_count() {
                            if let Some(bone) = model.bone_manager.get_bone(i) {
                                if bone.name == bone_name {
                                    let curr = model.bone_manager.get_global_transform(i).w_axis.truncate();
                                    let rest = bone.initial_position;
                                    let diff = curr - rest;
                                    println!("  {:<22} REST=(X={:+6.3},Z={:+6.3}) CURR=(X={:+6.3},Z={:+6.3}) diff=(X={:+6.3},Z={:+6.3})",
                                        bone.name, rest.x, rest.z, curr.x, curr.z, diff.x, diff.z
                                    );
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    #[test]
    fn probe_tail_deflection_test() {
        let path = "C:\\tmp\\mc-mmd-rust\\neoforge\\run\\client\\3d-skin\\EntityPlayer\\grasswonder\\1011_Grass Wonder.pmx";
        if let Ok(mut model) = crate::model::load_pmx(path) {
            if model.init_physics() {
                println!("\n=== GRASS WONDER TAIL DEFLECTION DURING RUNNING ===");

                println!("\n=== INSPECT TAIL RIGID BODIES AND JOINTS ===");
                for (i, rb) in model.rigid_bodies.iter().enumerate() {
                    if rb.local_name.contains("Tail") || rb.local_name.contains("tail") {
                        println!("  RB #{}: name={} mode={:?} shape={:?} size={:?} pos={:?}",
                            i, rb.local_name, rb.mode, rb.shape, rb.size, rb.position
                        );
                    }
                }

                let tail_tip_bone = model.bone_manager.find_bone_by_name("Sp_Hi_Tail0_B_04").unwrap();
                let tail_root_bone = model.bone_manager.find_bone_by_name("Sp_Hi_Tail0_B_01").unwrap();
                let rest_tip = model.bone_manager.get_bone(tail_tip_bone).unwrap().initial_position;
                let rest_root = model.bone_manager.get_bone(tail_root_bone).unwrap().initial_position;
                println!("Tail Rest Root: pos=({:+.2},{:+.2},{:+.2})", rest_root.x, rest_root.y, rest_root.z);
                println!("Tail Rest Tip:  pos=({:+.2},{:+.2},{:+.2})", rest_tip.x, rest_tip.y, rest_tip.z);

                // 1. Test sprint forward (frames 0..60)
                let mut pos_z = 0.0_f32;
                let speed = 5.6_f32;
                let dt = 1.0 / 60.0;

                println!("\n--- SPRINTING FORWARD ---");
                for frame in 0..60 {
                    pos_z += speed * dt;
                    model.set_model_position_and_yaw(0.0, 0.0, pos_z, 0.0);
                    model.update_node_animation(false);
                    model.update_physics(dt);
                    model.end_animation();
                    model.update();

                    if frame % 20 == 0 || frame == 59 {
                        let curr_tip = model.bone_manager.get_global_transform(tail_tip_bone).w_axis.truncate();
                        let curr_root = model.bone_manager.get_global_transform(tail_root_bone).w_axis.truncate();
                        let tail_vec = curr_tip - curr_root;
                        let angle_deg = (tail_vec.z).atan2(-tail_vec.y).to_degrees();
                        println!("Sprint Frame {:<2}: Tip=({:+5.2},{:+5.2},{:+5.2}) tail_angle={:+5.1}°",
                            frame, curr_tip.x, curr_tip.y, curr_tip.z, angle_deg
                        );
                    }
                }

                // 2. Test stopping (frames 60..120)
                println!("\n--- STOPPING / IDLE SETTLING ---");
                for frame in 0..60 {
                    model.set_model_position_and_yaw(0.0, 0.0, pos_z, 0.0);
                    model.update_node_animation(false);
                    model.update_physics(dt);
                    model.end_animation();
                    model.update();

                    if frame % 20 == 0 || frame == 59 {
                        let curr_tip = model.bone_manager.get_global_transform(tail_tip_bone).w_axis.truncate();
                        let curr_root = model.bone_manager.get_global_transform(tail_root_bone).w_axis.truncate();
                        let tail_vec = curr_tip - curr_root;
                        let angle_deg = (tail_vec.z).atan2(-tail_vec.y).to_degrees();
                        println!("Stop Frame {:<2}: Tip=({:+5.2},{:+5.2},{:+5.2}) tail_angle={:+5.1}°",
                            frame, curr_tip.x, curr_tip.y, curr_tip.z, angle_deg
                        );
                    }
                }

                // 3. Test yaw turning left while moving (frames 0..60)
                println!("\n--- TURNING LEFT WHILE RUNNING ---");
                let mut yaw = 0.0_f32;
                for frame in 0..60 {
                    yaw += 0.05; // turning left
                    pos_z += speed * dt;
                    model.set_model_position_and_yaw(0.0, 0.0, pos_z, yaw);
                    model.update_node_animation(false);
                    model.update_physics(dt);
                    model.end_animation();
                    model.update();

                    if frame % 20 == 0 || frame == 59 {
                        let curr_tip = model.bone_manager.get_global_transform(tail_tip_bone).w_axis.truncate();
                        println!("Turn Frame {:<2}: Tip=({:+5.2},{:+5.2},{:+5.2}) yaw={:+.2} rad",
                            frame, curr_tip.x, curr_tip.y, curr_tip.z, yaw
                        );
                    }
                }
            }
        }
    }
