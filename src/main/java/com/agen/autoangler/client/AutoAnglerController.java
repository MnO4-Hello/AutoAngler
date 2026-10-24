package com.agen.autoangler.client;

import com.agen.autoangler.AutoAnglerMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * 自动钓鱼状态机（纯客户端，所有操作都走原版输入包，与真实玩家点击等价）。
 *
 * 工作流程：
 *   PREPARE        在背包里找星捕手鱼竿并换到主手
 *   CAST           无浮漂则抛竿；已有浮漂则右键打断并重新抛竿
 *   WAIT_BITE      等待浮漂进入咬钩状态(3)，一旦咬钩立即右键收杆
 *   WAIT_MINIGAME  等待小游戏界面弹出（捕星者可能配置为跳过小游戏）
 *   MINIGAME       小游戏进行中，自动命中指针；开启宝藏模式时按宝藏策略命中
 *   钓完一条后回到 CAST 反复循环。
 *
 * 宝藏模式：小游戏出现宝藏进度条时，钓鱼进度低于50%优先命中非金钓点维持进度，
 * 高于50%只命中金钓点填满宝藏条，保证鱼不脱钩也不被提前钓上；宝藏条填满后
 * 恢复正常命中（详见 StarcatcherCompat#autoHitMinigameTreasure）。
 *
 * 停止策略：F8 / 鼠标左右键 / 移动超过一格 / 鱼竿丢失。
 * 防打断：每秒采样一次当前状态，连续3次都处于"等待抛竿"（无浮漂且无小游戏）时强制重新抛竿。
 * 抛竿宽限期：抛竿后浮漂实体要经过一个网络往返才出现在客户端（多人游戏可能延迟1~3秒），
 * 宽限期内查不到浮漂视为"浮漂还在路上"，不判丢失，避免把正在飞行的浮漂
 * 误判为丢失而疯狂抛竿/收竿。
 */
public final class AutoAnglerController
{
    public static final AutoAnglerController INSTANCE = new AutoAnglerController();

    public enum Phase
    {
        IDLE, PREPARE, CAST, WAIT_BITE, WAIT_MINIGAME, MINIGAME
    }

    public enum StopReason
    {
        NONE, BUTTON, KEY, MOUSE, MOVE, NO_ROD, ROD_LOST, WORLD
    }

    public enum StartResult
    {
        OK, NO_PLAYER, NO_STARCATCHER, NO_ROD
    }

    /** 两次右键动作之间的间隔(tick) */
    private static final int ACTION_COOLDOWN = 8;
    /** 收杆后等待小游戏界面弹出的最长时间(tick)，3秒；超时视为跳过了小游戏 */
    private static final int MINIGAME_WAIT_TIMEOUT = 60;
    /** 收回浮漂后等待其从客户端消失的时间(tick)，给网络往返留余量 */
    private static final int RECALL_COOLDOWN = 20;
    /** 抛竿后等待浮漂实体同步出现的宽限期(tick)，60tick = 3秒 */
    private static final int CAST_GRACE_TICKS = 60;
    /** 移动超过该距离(格)即停止 */
    private static final double MAX_MOVE_DISTANCE = 1.0D;
    /** 防打断采样间隔(tick)，20tick = 1秒 */
    private static final int SAMPLE_INTERVAL = 20;
    /** 连续检测到"等待抛竿"状态的次数，达到后强制抛竿 */
    private static final int IDLE_SAMPLES_TO_RECAST = 3;
    /** 物品栏搬运(换到快捷栏)后等待同步的最长时间(tick) */
    private static final int INVENTORY_MOVE_PENDING = 20;

    private boolean fishing;
    private Phase phase = Phase.IDLE;
    private StopReason lastStopReason = StopReason.NONE;
    private int cooldown;
    private int inventoryMovePending;
    private int sampleTimer;
    private int idleSamples;
    private Vec3 anchor = Vec3.ZERO;
    /** 本局小游戏累计命中次数（仅用于结束日志） */
    private int minigameHits;
    /** 宝藏模式：本局是否已记录"进入宝藏模式"日志 */
    private boolean treasureModeLogged;
    /** 宝藏模式：本局是否已记录"宝藏条填满"日志 */
    private boolean treasureDoneLogged;
    /** 本次抛竿流程是否已记录过"收回旧浮漂"日志，避免重复刷屏 */
    private boolean interruptLogged;
    /** 抛竿宽限期倒计时(tick)：>0 表示浮漂可能还在网络同步路上 */
    private int castGraceTimer;

    private AutoAnglerController()
    {
    }

    public boolean isFishing()
    {
        return fishing;
    }

    public Phase getPhase()
    {
        return phase;
    }

    public StopReason getLastStopReason()
    {
        return lastStopReason;
    }

    /** 开始自动钓鱼前先检测：玩家、捕星者mod、背包中的星捕手鱼竿 */
    public StartResult start(Minecraft mc)
    {
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null || mc.gameMode == null)
        {
            return StartResult.NO_PLAYER;
        }
        if (!StarcatcherCompat.LOADED)
        {
            return StartResult.NO_STARCATCHER;
        }
        if (findRodSlot(player) < 0)
        {
            return StartResult.NO_ROD;
        }

        fishing = true;
        phase = Phase.PREPARE;
        lastStopReason = StopReason.NONE;
        cooldown = 0;
        inventoryMovePending = 0;
        sampleTimer = SAMPLE_INTERVAL;
        idleSamples = 0;
        interruptLogged = false;
        castGraceTimer = 0;
        anchor = player.position();
        log(mc, "log.autoangler.started", "开始自动钓鱼");
        return StartResult.OK;
    }

    /** 停止自动钓鱼 */
    public void requestStop(Minecraft mc, StopReason reason)
    {
        if (!fishing)
        {
            return;
        }
        fishing = false;
        phase = Phase.IDLE;
        lastStopReason = reason;

        if (shouldNotify(reason) && mc.player != null)
        {
            mc.player.displayClientMessage(
                    Component.translatable("gui.autoangler.actionbar.stopped", reasonText(reason)), true);
        }
        log(mc, "log.autoangler.stopped", "自动钓鱼已停止：%s", reasonText(reason).getString());
    }

    public static Component reasonText(StopReason reason)
    {
        return switch (reason)
        {
            case BUTTON -> Component.translatable("gui.autoangler.reason.button");
            case KEY -> Component.translatable("gui.autoangler.reason.key");
            case MOUSE -> Component.translatable("gui.autoangler.reason.mouse");
            case MOVE -> Component.translatable("gui.autoangler.reason.move");
            case NO_ROD -> Component.translatable("gui.autoangler.reason.no_rod");
            case ROD_LOST -> Component.translatable("gui.autoangler.reason.rod_lost");
            case WORLD -> Component.translatable("gui.autoangler.reason.world");
            default -> Component.empty();
        };
    }

    private static boolean shouldNotify(StopReason reason)
    {
        return reason == StopReason.MOUSE || reason == StopReason.MOVE || reason == StopReason.ROD_LOST;
    }

    /** 每客户端tick调用一次 */
    public void tick(Minecraft mc)
    {
        if (!fishing)
        {
            return;
        }

        LocalPlayer player = mc.player;
        if (player == null || mc.level == null || mc.gameMode == null)
        {
            fishing = false;
            phase = Phase.IDLE;
            lastStopReason = StopReason.WORLD;
            log(mc, "log.autoangler.stopped", "自动钓鱼已停止：%s", reasonText(StopReason.WORLD).getString());
            return;
        }

        // ---------- 停止策略 ----------
        // （鼠标点击停止由 AutoAnglerEvents.onMouseButton 事件处理）
        if (player.position().distanceToSqr(anchor) > MAX_MOVE_DISTANCE * MAX_MOVE_DISTANCE)
        {
            requestStop(mc, StopReason.MOVE);
            return;
        }

        // ---------- 防打断：每秒采样，连续3次"等待抛竿"则强制抛竿 ----------
        if (--sampleTimer <= 0)
        {
            sampleTimer = SAMPLE_INTERVAL;
            if (isWaitingToCast(mc))
            {
                if (++idleSamples >= IDLE_SAMPLES_TO_RECAST)
                {
                    idleSamples = 0;
                    forceRecast(mc, player);
                }
            }
            else
            {
                idleSamples = 0;
            }
        }

        // ---------- 小游戏界面优先处理 ----------
        Screen screen = mc.screen;
        if (StarcatcherCompat.isMinigameScreen(screen))
        {
            if (phase != Phase.MINIGAME)
            {
                phase = Phase.MINIGAME;
                minigameHits = 0;
                treasureModeLogged = false;
                treasureDoneLogged = false;
                log(mc, "log.autoangler.phase.minigame", "进入搏斗小游戏");
                logMinigameSpots(mc, screen);
            }
            boolean hit;
            if (AutoAnglerConfig.isTreasureMode())
            {
                logTreasureState(mc, screen);
                hit = StarcatcherCompat.autoHitMinigameTreasure(screen, AutoAnglerConfig.spotMask());
            }
            else
            {
                hit = StarcatcherCompat.autoHitMinigame(screen, AutoAnglerConfig.spotMask());
            }
            if (hit)
            {
                minigameHits++;
            }
            return;
        }
        if (phase == Phase.MINIGAME)
        {
            // 小游戏结束（成功/失败都会关闭界面），稍等浮漂清理后重新抛竿
            log(mc, "log.autoangler.minigame.end", "搏斗小游戏结束，本局命中%d次", minigameHits);
            phase = Phase.CAST;
            cooldown = 10;
        }

        if (cooldown > 0)
        {
            cooldown--;
        }

        Entity bobber = StarcatcherCompat.findBobber(mc.level, player);
        switch (phase)
        {
            case PREPARE -> tickPrepare(mc, player);
            case CAST -> tickCast(mc, player, bobber);
            case WAIT_BITE -> tickWaitBite(mc, player, bobber);
            case WAIT_MINIGAME -> tickWaitMinigame(mc, player, bobber);
            default ->
            {
            }
        }
    }

    /** 检测当前是否处于"等待抛竿"状态：没有浮漂、也没有小游戏，且不在抛竿宽限期内 */
    private boolean isWaitingToCast(Minecraft mc)
    {
        if (castGraceTimer > 0)
        {
            // 刚抛竿，浮漂还在同步路上，不算卡死
            return false;
        }
        if (StarcatcherCompat.isMinigameScreen(mc.screen))
        {
            return false;
        }
        return StarcatcherCompat.findBobber(mc.level, mc.player) == null;
    }

    /** 连续3次检测到等待抛竿后强制回到抛竿流程 */
    private void forceRecast(Minecraft mc, LocalPlayer player)
    {
        cooldown = 0;
        inventoryMovePending = 0;
        if (phase == Phase.PREPARE || phase == Phase.CAST)
        {
            return;
        }
        log(mc, "log.autoangler.recast_forced", "连续3次检测到等待抛竿，强制重新抛竿");
        phase = StarcatcherCompat.isRod(player.getMainHandItem()) ? Phase.CAST : Phase.PREPARE;
    }

    /** 装备阶段：把背包里的星捕手鱼竿换到主手 */
    private void tickPrepare(Minecraft mc, LocalPlayer player)
    {
        if (StarcatcherCompat.isRod(player.getMainHandItem()))
        {
            inventoryMovePending = 0;
            phase = Phase.CAST;
            log(mc, "log.autoangler.ready", "鱼竿已就位，开始抛竿");
            return;
        }

        // 玩家打开了其他容器(箱子等)时暂缓装备操作，避免把物品栏点击发送到错误的容器菜单
        if (player.containerMenu != player.inventoryMenu)
        {
            return;
        }

        // 等待上一次物品栏搬运同步到服务端
        if (inventoryMovePending > 0)
        {
            inventoryMovePending--;
            return;
        }
        if (cooldown > 0)
        {
            return;
        }

        int slot = findRodSlot(player);
        if (slot < 0)
        {
            requestStop(mc, StopReason.ROD_LOST);
            return;
        }

        if (slot <= 8)
        {
            // 在快捷栏：直接切换选中槽位
            selectHotbarSlot(mc, player, slot);
            cooldown = 3;
            log(mc, "log.autoangler.equip.hotbar", "已将鱼竿切换到快捷栏第%d格", slot + 1);
        }
        else if (slot < 36)
        {
            // 在主背包：与当前选中快捷栏槽位交换
            swapSlotWithSelected(mc, player, slot);
            inventoryMovePending = INVENTORY_MOVE_PENDING;
            log(mc, "log.autoangler.equip.swap", "正在把背包第%d格的鱼竿交换到主手", slot + 1);
        }
        else
        {
            // 在副手(40)：与当前选中快捷栏槽位交换
            swapOffhandWithSelected(mc, player);
            inventoryMovePending = INVENTORY_MOVE_PENDING;
            log(mc, "log.autoangler.equip.offhand", "正在把副手的鱼竿交换到主手");
        }
    }

    /** 抛竿阶段：无浮漂则抛竿；有浮漂（含咬钩）则右键打断/收杆后重新抛竿 */
    private void tickCast(Minecraft mc, LocalPlayer player, Entity bobber)
    {
        if (!StarcatcherCompat.isRod(player.getMainHandItem()))
        {
            phase = Phase.PREPARE;
            cooldown = 0;
            log(mc, "log.autoangler.rod_unequipped", "鱼竿不在主手，重新装备");
            return;
        }

        if (bobber != null)
        {
            int state = StarcatcherCompat.getBobberState(bobber);
            if (state == StarcatcherCompat.STATE_BITING)
            {
                // 开始钓鱼时恰好有鱼咬钩：直接收杆
                if (cooldown <= 0 && useRod(mc, player))
                {
                    phase = Phase.WAIT_MINIGAME;
                    cooldown = MINIGAME_WAIT_TIMEOUT;
                    log(mc, "log.autoangler.bite_handled", "检测到咬钩中的浮漂，直接收杆");
                }
            }
            else if (state == StarcatcherCompat.STATE_FISHING)
            {
                // 浮漂已处于搏斗状态（收杆已生效但小游戏界面尚未弹出）：
                // 不能再右键（会打断搏斗流程），转入等待小游戏
                phase = Phase.WAIT_MINIGAME;
                cooldown = MINIGAME_WAIT_TIMEOUT;
                log(mc, "log.autoangler.reel_pending", "浮漂处于搏斗状态，等待小游戏界面");
            }
            else if (cooldown <= 0 && useRod(mc, player))
            {
                // 右键收回已有浮漂（打断），随后下一轮真正抛竿；
                // 等待更久让服务端收回浮漂并同步到客户端，避免浮漂还在时重复右键
                cooldown = RECALL_COOLDOWN;
                if (!interruptLogged)
                {
                    interruptLogged = true;
                    log(mc, "log.autoangler.cast_interrupt", "检测到已有浮漂（状态%d），收回后重新抛竿", state);
                }
            }
            return;
        }

        // 潜行时右键鱼竿会打开鱼竿菜单，等待玩家松开潜行再抛竿
        if (player.isShiftKeyDown())
        {
            return;
        }

        if (cooldown <= 0 && useRod(mc, player))
        {
            phase = Phase.WAIT_BITE;
            cooldown = ACTION_COOLDOWN;
            castGraceTimer = CAST_GRACE_TICKS;
            interruptLogged = false;
            log(mc, "log.autoangler.cast_success", "已抛竿，等待浮漂落水与鱼咬钩");
        }
    }

    /** 等待咬钩：浮漂状态变为咬钩(3)时右键收杆 */
    private void tickWaitBite(Minecraft mc, LocalPlayer player, Entity bobber)
    {
        if (bobber == null)
        {
            // 刚抛竿后浮漂实体需要等一个网络往返才出现在客户端（多人游戏可能延迟1~3秒）。
            // 宽限期内视为"浮漂还在路上"，不能判为丢失，否则会把正在飞行的浮漂
            // 误判为"浮漂消失"而反复抛竿/收竿。
            if (castGraceTimer > 0)
            {
                castGraceTimer--;
                return;
            }
            // 宽限期结束仍没有浮漂：抛竿失败或被服务端收回（错过咬钩），重新走抛竿流程
            phase = Phase.CAST;
            if (cooldown < 5)
            {
                cooldown = 5;
            }
            log(mc, "log.autoangler.bobber_lost", "等待浮漂超时（抛竿失败或错过咬钩），重新抛竿");
            return;
        }
        castGraceTimer = 0;

        if (cooldown > 0)
        {
            return;
        }

        if (StarcatcherCompat.getBobberState(bobber) == StarcatcherCompat.STATE_BITING
                && useRod(mc, player))
        {
            phase = Phase.WAIT_MINIGAME;
            cooldown = MINIGAME_WAIT_TIMEOUT;
            log(mc, "log.autoangler.bite", "检测到鱼咬钩，右键收杆");
        }
    }

    /** 等待小游戏界面：超时（捕星者配置跳过小游戏）则回到抛竿流程 */
    private void tickWaitMinigame(Minecraft mc, LocalPlayer player, Entity bobber)
    {
        if (cooldown > 0)
        {
            return;
        }

        if (bobber == null)
        {
            phase = Phase.CAST;
            cooldown = 5;
            log(mc, "log.autoangler.minigame_skipped", "未进入小游戏（可能已跳过），重新抛竿");
            return;
        }

        if (StarcatcherCompat.getBobberState(bobber) == StarcatcherCompat.STATE_BITING)
        {
            // 收杆没有生效，浮漂仍在咬钩，重试
            phase = Phase.WAIT_BITE;
            cooldown = 0;
            log(mc, "log.autoangler.reel_retry", "收杆未生效，浮漂仍在咬钩，重试");
        }
        else
        {
            // 浮漂仍在水中但没有小游戏（已跳过/超时），收回并重新抛竿
            phase = Phase.CAST;
            cooldown = 0;
            log(mc, "log.autoangler.minigame_skipped", "未进入小游戏（可能已跳过），重新抛竿");
        }
    }

    /**
     * 右键使用鱼竿：优先主手，其次副手，确保右键的一定是鱼竿，
     * 通过 MultiPlayerGameMode 发送原版使用物品包，服务端行为与真人右键完全一致。
     */
    private boolean useRod(Minecraft mc, LocalPlayer player)
    {
        if (mc.gameMode == null)
        {
            return false;
        }
        if (StarcatcherCompat.isRod(player.getMainHandItem()))
        {
            mc.gameMode.useItem(player, InteractionHand.MAIN_HAND);
            return true;
        }
        if (StarcatcherCompat.isRod(player.getOffhandItem()))
        {
            mc.gameMode.useItem(player, InteractionHand.OFF_HAND);
            return true;
        }
        return false;
    }

    /** 在玩家物品栏中查找星捕手鱼竿，返回槽位(0-8快捷栏, 9-35背包, 40副手)，找不到返回-1 */
    private int findRodSlot(LocalPlayer player)
    {
        Inventory inventory = player.getInventory();
        if (StarcatcherCompat.isRod(player.getMainHandItem()))
        {
            return inventory.selected;
        }
        for (int i = 0; i <= 8; i++)
        {
            if (StarcatcherCompat.isRod(inventory.getItem(i)))
            {
                return i;
            }
        }
        for (int i = 9; i < 36; i++)
        {
            if (StarcatcherCompat.isRod(inventory.getItem(i)))
            {
                return i;
            }
        }
        if (StarcatcherCompat.isRod(inventory.getItem(40)))
        {
            return 40;
        }
        return -1;
    }

    /** 切换快捷栏选中槽位 */
    private void selectHotbarSlot(Minecraft mc, LocalPlayer player, int slot)
    {
        player.getInventory().selected = slot;
        if (mc.getConnection() != null)
        {
            mc.getConnection().send(new ServerboundSetCarriedItemPacket(slot));
        }
    }

    /**
     * 把主背包槽位(9-35)与当前选中的快捷栏槽位交换。
     * 原版 SWAP 点击中：slotId 是物品栏菜单槽位ID（主背包区 9-35 恰好与 Inventory 索引一致），
     * button 是 Inventory 索引（0-8 为快捷栏）。
     */
    private void swapSlotWithSelected(Minecraft mc, LocalPlayer player, int slot)
    {
        mc.gameMode.handleInventoryMouseClick(player.inventoryMenu.containerId,
                slot, player.getInventory().selected, ClickType.SWAP, player);
    }

    /**
     * 把副手物品与当前选中的快捷栏槽位交换。
     * 原版 SWAP 点击中：button=40 指向 Inventory 的副手槽，
     * slotId 必须使用物品栏菜单里的快捷栏区槽位ID(36-44)，不能直接用 Inventory 索引。
     */
    private void swapOffhandWithSelected(Minecraft mc, LocalPlayer player)
    {
        mc.gameMode.handleInventoryMouseClick(player.inventoryMenu.containerId,
                InventoryMenu.USE_ROW_SLOT_START + player.getInventory().selected, 40, ClickType.SWAP, player);
    }

    /**
     * 统一日志出口：无论调试模式是否开启都写入日志文件；
     * 调试模式开启时额外用聊天框实时反馈（只记录低频关键事件，
     * 每tick的状态检测、逐个钓点判定等高频日志不会经过这里，避免刷屏）。
     */
    private void log(Minecraft mc, String key, String cnTemplate, Object... args)
    {
        String text = args.length == 0 ? cnTemplate : String.format(cnTemplate, args);
        AutoAnglerMod.LOGGER.info("[AutoAngler] {}", text);
        if (AutoAnglerConfig.isDebugMode() && mc != null && mc.player != null)
        {
            mc.player.displayClientMessage(Component.translatable(key, args), false);
        }
    }

    /** 宝藏模式的进入/填满各只记录一次，避免刷屏 */
    private void logTreasureState(Minecraft mc, Screen screen)
    {
        if (!StarcatcherCompat.isTreasureActive(screen))
        {
            return;
        }
        if (!treasureModeLogged)
        {
            treasureModeLogged = true;
            log(mc, "log.autoangler.treasure.start", "检测到宝藏，进入宝藏模式：维持进度并用金钓点填满宝藏条");
        }
        if (!treasureDoneLogged && StarcatcherCompat.getTreasureProgress(screen) >= StarcatcherCompat.TREASURE_DONE)
        {
            treasureDoneLogged = true;
            log(mc, "log.autoangler.treasure.done", "宝藏进度已满，恢复正常钓鱼模式");
        }
    }

    /** 小游戏开始时输出一次钓点统计（含被忽略的类型），避免逐个提示造成刷屏 */
    private void logMinigameSpots(Minecraft mc, Screen screen)
    {
        int[] counts = StarcatcherCompat.countSpots(screen);
        if (counts == null)
        {
            return;
        }
        int mask = AutoAnglerConfig.spotMask();
        StarcatcherCompat.SpotType[] types = StarcatcherCompat.SpotType.values();
        StringBuilder found = new StringBuilder();
        StringBuilder ignored = new StringBuilder();
        for (int i = 0; i < counts.length && i < types.length; i++)
        {
            if (counts[i] <= 0)
            {
                continue;
            }
            StarcatcherCompat.SpotType type = types[i];
            String name = Component.translatable(type.translationKey).getString();
            StringBuilder target = type.isEnabled(mask) ? found : ignored;
            if (target.length() > 0)
            {
                target.append(", ");
            }
            target.append(name).append(" x").append(counts[i]);
        }
        if (found.length() > 0)
        {
            log(mc, "log.autoangler.minigame.spots", "本局钓点：%s", found.toString());
        }
        if (ignored.length() > 0)
        {
            log(mc, "log.autoangler.minigame.ignored", "已忽略（未勾选或危险）：%s", ignored.toString());
        }
        if (StarcatcherCompat.hasLayeredSpots(screen))
        {
            log(mc, "log.autoangler.minigame.layers", "检测到多层钓点：变暗钓点位于其他层，将先切换指针层再命中");
        }
    }
}
