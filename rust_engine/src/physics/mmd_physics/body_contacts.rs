use super::*;

impl MMDPhysics {
    /// 初始深嵌入触发自身祖先躯干壳过滤；Strict 保留原始碰撞。
    pub fn configure_embedded_body_contacts(
        &mut self,
        bone_names: &[String],
        bone_parents: &[i32],
    ) -> usize {
        let config = get_config();
        self.embedded_body_filtered_pairs = 0;
        if !config.collision_enabled
            || !config.joints_enabled
            || self.collision_stability_mode == CollisionStabilityMode::Strict
        {
            return 0;
        }

        self.world.detect_collisions();
        let contacts: Vec<_> = self
            .world
            .contact_manifolds()
            .into_iter()
            .filter_map(|contact| {
                let a = *self.debug_body_pointer_indices.get(&contact.body_a)?;
                let b = *self.debug_body_pointer_indices.get(&contact.body_b)?;
                Some((a, b, contact))
            })
            .collect();
        let pairs = super::super::embedded_body_contacts::embedded_body_pairs(
            &self.rigid_bodies,
            bone_names,
            bone_parents,
            &contacts,
        );
        let mut applied = 0;
        let mut refreshed = HashSet::new();
        for (a, b) in pairs {
            let (Some(body_a), Some(body_b)) = (
                self.rigid_bodies[a].bullet_body.as_ref(),
                self.rigid_bodies[b].bullet_body.as_ref(),
            ) else {
                continue;
            };
            body_a.set_ignore_collision_check(body_b, true);
            if !body_a.check_collide_with(body_b) && !body_b.check_collide_with(body_a) {
                applied += 1;
                refreshed.insert(a);
                log::info!(
                    "[Bullet3][身体初始嵌入过滤] A[{}]='{}' B[{}]='{}'",
                    a,
                    self.rigid_bodies[a].name,
                    b,
                    self.rigid_bodies[b].name
                );
            }
        }
        for index in refreshed {
            if let Some(body) = &self.rigid_bodies[index].bullet_body {
                self.world.refresh_body_collision_filter(body);
            }
        }
        self.world.detect_collisions();
        self.embedded_body_filtered_pairs = applied;
        applied
    }
}

#[cfg(test)]
#[path = "body_contacts_tests.rs"]
mod tests;
