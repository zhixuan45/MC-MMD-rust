package com.shiroha.mmdskin.neoforge.entity;

import com.shiroha.mmdskin.render.entity.MobReplacementRenderer;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.RenderLivingEvent;

/**
 * NeoForge 原版生物的 MMD 替换渲染事件处理器。
 */
@OnlyIn(Dist.CLIENT)
public class MobReplacementRenderEventHandler {

    @SubscribeEvent(priority = EventPriority.HIGH)
    public void onRenderLivingPre(RenderLivingEvent.Pre<?, ?> event) {
        LivingEntity entity = event.getEntity();
        if (entity instanceof AbstractClientPlayer) {
            return;
        }

        if (MobReplacementRenderer.render(entity, entity.getYRot(), event.getPartialTick(), event.getPoseStack(), event.getPackedLight())) {
            event.setCanceled(true);
        }
    }
}
