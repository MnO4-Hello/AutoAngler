package com.agen.autoangler.client;

import com.agen.autoangler.AutoAnglerMod;
import net.minecraft.client.KeyMapping;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

/**
 * 客户端按键注册：F8（可在原版按键设置中修改）打开自动钓鱼配置界面。
 */
@Mod.EventBusSubscriber(modid = AutoAnglerMod.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class AutoAnglerClient
{
    public static final KeyMapping OPEN_CONFIG_KEY = new KeyMapping(
            "key.autoangler.open_config", GLFW.GLFW_KEY_F8, "key.categories.autoangler");

    private AutoAnglerClient()
    {
    }

    @SubscribeEvent
    public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event)
    {
        event.register(OPEN_CONFIG_KEY);
    }
}
