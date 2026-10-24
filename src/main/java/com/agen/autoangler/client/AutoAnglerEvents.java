package com.agen.autoangler.client;

import com.agen.autoangler.AutoAnglerMod;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

/**
 * 客户端事件：
 * - F8(可改键)：钓鱼中按F8会先停止钓鱼，再打开配置界面；界面已打开时再按F8关闭界面；
 * - 鼠标左/右键按下（包括打开界面时）会停止自动钓鱼；
 * - 每个客户端tick驱动自动钓鱼状态机。
 *
 * 注意：这里使用 Forge 的 InputEvent 而不是 KeyMapping.consumeClick()，
 * 因为原版在小游戏等界面打开时不会更新 KeyMapping 状态，而 InputEvent 在任何界面下都会触发。
 */
@Mod.EventBusSubscriber(modid = AutoAnglerMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class AutoAnglerEvents
{
    private AutoAnglerEvents()
    {
    }

    @SubscribeEvent
    public static void onKeyInput(InputEvent.Key event)
    {
        if (event.getAction() != GLFW.GLFW_PRESS)
        {
            return;
        }

        InputConstants.Key key = AutoAnglerClient.OPEN_CONFIG_KEY.getKey();
        if (key.getType() != InputConstants.Type.KEYSYM || key.getValue() != event.getKey())
        {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null)
        {
            return;
        }
        // 聊天输入时不响应，避免打断打字
        if (mc.screen instanceof ChatScreen)
        {
            return;
        }

        toggleConfigScreen(mc);
    }

    @SubscribeEvent
    public static void onMouseButton(InputEvent.MouseButton.Pre event)
    {
        if (event.getAction() != GLFW.GLFW_PRESS)
        {
            return;
        }
        if (event.getButton() != GLFW.GLFW_MOUSE_BUTTON_LEFT
                && event.getButton() != GLFW.GLFW_MOUSE_BUTTON_RIGHT)
        {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null)
        {
            return;
        }
        // 若正在自动钓鱼则停止（requestStop 内部会自动忽略未钓鱼的情况）
        AutoAnglerController.INSTANCE.requestStop(mc, AutoAnglerController.StopReason.MOUSE);
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event)
    {
        if (event.phase != TickEvent.Phase.END)
        {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null)
        {
            return;
        }
        AutoAnglerController.INSTANCE.tick(mc);
    }

    private static void toggleConfigScreen(Minecraft mc)
    {
        // 界面已打开：再按一次关闭
        if (mc.screen instanceof AutoAnglerScreen)
        {
            mc.setScreen(null);
            return;
        }

        // 钓鱼中按F8：先停止钓鱼，再打开配置界面
        AutoAnglerController controller = AutoAnglerController.INSTANCE;
        if (controller.isFishing())
        {
            controller.requestStop(mc, AutoAnglerController.StopReason.KEY);
        }
        mc.setScreen(new AutoAnglerScreen());
    }
}
