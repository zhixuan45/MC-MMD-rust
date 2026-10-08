package com.shiroha.mmdskin.forge.maid;

import com.shiroha.mmdskin.compat.maid.ui.MaidModelSelectorScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.ModList;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Field;
import java.util.UUID;

/** Forge 可选集成：只向官方确认的车万女仆原生容器 GUI 添加本地换装按钮。 */
public final class MaidContainerGuiHandler {
    private static final Logger LOGGER = LogManager.getLogger();
    private static final String MAID_GUI_BASE =
            "com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.AbstractMaidContainerGui";
    private static final ResourceLocation MAID_TYPE = new ResourceLocation("touhou_little_maid", "maid");
    private static final Field MAID_FIELD = resolveMaidField();

    private MaidContainerGuiHandler() {}

    public static void registerIfAvailable() {
        if (ModList.get().isLoaded("touhou_little_maid")) MinecraftForge.EVENT_BUS.register(MaidContainerGuiHandler.class);
    }

    @net.minecraftforge.eventbus.api.SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        if (MAID_FIELD == null || !(event.getScreen() instanceof AbstractContainerScreen<?> screen)
                || !isMaidGui(screen.getClass())) return;
        Entity maid = readMaid(screen);
        Minecraft minecraft = Minecraft.getInstance();
        if (maid == null || minecraft.player == null
                || !MAID_TYPE.equals(BuiltInRegistries.ENTITY_TYPE.getKey(maid.getType()))
                || !(maid instanceof TamableAnimal tameable)) return;
        LivingEntity owner = tameable.getOwner();
        if (owner == null || !minecraft.player.getUUID().equals(owner.getUUID())) return;

        // 官方 GUI 在左上角状态图标横排使用 x=8、42、52、62；x=73 的空位容纳按钮。
        int x = Math.max(2, Math.min(Minecraft.getInstance().getWindow().getGuiScaledWidth() - 14, screen.getGuiLeft() + 73));
        int y = Math.max(2, Math.min(Minecraft.getInstance().getWindow().getGuiScaledHeight() - 14, screen.getGuiTop() + 13));
        UUID maidUUID = maid.getUUID();
        int entityId = maid.getId();
        String name = maid.getName().getString();
        Button button = Button.builder(Component.literal("M"), ignored ->
                        minecraft.setScreen(new MaidModelSelectorScreen(maidUUID, entityId, name, screen)))
                .bounds(x, y, 12, 12)
                .tooltip(net.minecraft.client.gui.components.Tooltip.create(
                        Component.translatable("gui.mmdskin.maid_model_selector")))
                .build();
        event.addListener(button);
    }

    private static boolean isMaidGui(Class<?> type) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            if (MAID_GUI_BASE.equals(current.getName())) return true;
        }
        return false;
    }

    private static Entity readMaid(Object screen) {
        try {
            Object value = MAID_FIELD.get(screen);
            return value instanceof Entity entity ? entity : null;
        } catch (IllegalAccessException exception) {
            LOGGER.debug("无法读取官方女仆界面的实体字段，跳过本次按钮注入", exception);
            return null;
        }
    }

    private static Field resolveMaidField() {
        if (!ModList.get().isLoaded("touhou_little_maid")) return null;
        try {
            Class<?> base = Class.forName(MAID_GUI_BASE, false, MaidContainerGuiHandler.class.getClassLoader());
            Field field = base.getDeclaredField("maid");
            if (!field.trySetAccessible()) return null;
            return field;
        } catch (ReflectiveOperationException | LinkageError exception) {
            LOGGER.debug("当前车万女仆版本不具备已核实的原生界面接口，跳过换装按钮", exception);
            return null;
        }
    }
}
