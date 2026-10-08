package com.terraformersmc.modmenu.event;

import com.mojang.blaze3d.platform.InputConstants;
import com.terraformersmc.modmenu.ModMenu;
import com.terraformersmc.modmenu.gui.ModsScreen;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import org.lwjgl.glfw.GLFW;

@EventBusSubscriber(modid = ModMenu.MOD_ID, value = Dist.CLIENT)
public final class ModMenuEventHandler {
    private static KeyMapping menuKey;

    private ModMenuEventHandler() { }

    public static void registerKeys(RegisterKeyMappingsEvent event) {
        menuKey = new KeyMapping("key.modmenu.open_menu", InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_UNKNOWN, KeyMapping.Category.MISC);
        event.register(menuKey);
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        while (menuKey != null && menuKey.consumeClick()) {
            Minecraft minecraft = Minecraft.getInstance();
            minecraft.gui.setScreen(new ModsScreen(minecraft.gui.screen()));
        }
    }
}
