package com.agen.autoangler;

import com.mojang.logging.LogUtils;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

/**
 * AutoAngler —— 捕星者(Starcatcher)专用自动钓鱼模组。
 *
 * 本项目为纯客户端模组：
 * - F8(可在按键设置中更改) 打开配置界面；
 * - 配置界面右下角按钮开始/停止自动钓鱼；
 * - 自动检测背包中的星捕手鱼竿并换到主手 -> 抛竿 -> 等待咬钩 -> 收杆 -> 自动完成小游戏 -> 循环。
 */
@Mod(AutoAnglerMod.MODID)
public class AutoAnglerMod
{
    /** 模组ID，必须与 META-INF/mods.toml 中的 modId 一致 */
    public static final String MODID = "autoangler";

    /** 全局日志 */
    public static final Logger LOGGER = LogUtils.getLogger();

    public AutoAnglerMod()
    {
        // 所有逻辑均为客户端逻辑，见 client 包下的 AutoAnglerClient / AutoAnglerEvents
    }
}
