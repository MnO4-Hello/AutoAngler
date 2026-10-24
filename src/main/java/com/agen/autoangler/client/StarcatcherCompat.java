package com.agen.autoangler.client;

import com.agen.autoangler.AutoAnglerMod;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.ForgeRegistries;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 捕星者(Starcatcher)兼容桥。
 *
 * 本模组不依赖捕星者mod的编译期产物，全部通过物品标签 + 反射在运行时对接，
 * 对接的目标以反编译版本 starcatcher-2.3.16-FORGE-1.20.1 为准：
 *
 * - 鱼竿：物品标签 starcatcher:rods（兜底：命名空间 starcatcher 且 id 以 "_rod" 结尾）
 * - 浮漂实体：starcatcher:fishing_bob，同步数据 STATE：1=飞行 2=漂浮 3=咬钩 4=搏斗
 * - 小游戏界面：com.wdiscute.starcatcher.minigame.FishingMinigameScreen
 *   - getActiveSweetSpots() 获取所有指示区
 *   - getPointerPosPrecise() 获取指针精确角度
 *   - doDegreesOverlapWithLeeway(float, float, int) 角度重叠判定
 *   - inputPressed() 触发一次"点击"判定
 *   - getModifiers() 获取本局生效的小游戏规则修正器
 *   - ActiveSweetSpot.pos/thickness/removed/texture/reward 指示区数据
 *   - treasureActive 宝藏激活（左侧出现宝藏进度条）
 *   - treasureProgress 宝藏进度条数值（金钓点命中+reward，>=100 时金钓点被移除；
 *     结算时 treasureProgress>100 才发放宝藏）
 *   - progress/hp 钓鱼进度与上限（progressSmooth<0 脱钩，progressSmooth>hp 钓上）
 *   - 多层钓点（Nikdo53Modifier，仅岩浆传说鱼使用）：钓点随机分散到多层，
 *     非当前指针层(pointerLayer)的钓点在更外侧渲染且变暗（灰度0.5），直接命中
 *     会被取消判定为空按（扣进度）；自动钓鱼会先把指针层切到目标钓点所在层再
 *     命中，等价于玩家按左/右方向键切换；钓点层号存于 extraData 的 key 53
 */
public final class StarcatcherCompat
{
    /** 捕星者mod是否已加载 */
    public static final boolean LOADED = ModList.get().isLoaded("starcatcher");

    /** 浮漂状态：飞行中 */
    public static final int STATE_FLYING = 1;
    /** 浮漂状态：漂浮等待 */
    public static final int STATE_BOBBING = 2;
    /** 浮漂状态：咬钩 */
    public static final int STATE_BITING = 3;
    /** 浮漂状态：与鱼搏斗中（已收杆） */
    public static final int STATE_FISHING = 4;

    /**
     * 钓点类型（对应小游戏里指示区的纹理外观）。
     *
     * 注意：捕星者把"岩石钓点"的行为类型也注册为 normal，仅纹理不同，
     * 因此类型判定必须以 ActiveSweetSpot#texture 的文件名为准，而不是注册行为名。
     * 捕星者中存在"只会生成螃蟹/凋灵/苦力怕/叶子钓点"的鱼，这些特殊外观
     * 本质是正向的普通钓点，统一归入基础钓点，避免这类鱼永远无法命中。
     */
    public enum SpotType
    {
        BASIC("gui.autoangler.spot.basic"),
        FROZEN("gui.autoangler.spot.frozen"),
        AQUA("gui.autoangler.spot.aqua"),
        STONE("gui.autoangler.spot.stone"),
        GOLD("gui.autoangler.spot.gold"),
        /** TNT 钓点：命中会扣除进度，属负面钓点，永远不会被自动命中 */
        TNT("gui.autoangler.spot.tnt");

        public final String translationKey;

        SpotType(String translationKey)
        {
            this.translationKey = translationKey;
        }

        /** 按纹理文件名判定钓点类型，无法识别的按 TNT 处理（保守不命中） */
        public static SpotType fromTextureName(String name)
        {
            if (name.startsWith("normal") || name.startsWith("thin")
                    || name.startsWith("wither") || name.startsWith("creeper")
                    || name.startsWith("leaf") || name.endsWith("_claw.png") || name.endsWith("_leg.png"))
            {
                return BASIC;
            }
            if (name.startsWith("frozen"))
            {
                return FROZEN;
            }
            if (name.startsWith("aqua"))
            {
                return AQUA;
            }
            if (name.startsWith("stone"))
            {
                return STONE;
            }
            if (name.startsWith("treasure"))
            {
                return GOLD;
            }
            return TNT;
        }

        /** 该类型在给定掩码下是否允许被命中（TNT 永远不允许） */
        public boolean isEnabled(int mask)
        {
            return this != TNT && (mask & (1 << ordinal())) != 0;
        }
    }

    /** 钓点筛选掩码位（与 SpotType 序号对应） */
    public static final int MASK_BASIC = 1 << SpotType.BASIC.ordinal();
    public static final int MASK_FROZEN = 1 << SpotType.FROZEN.ordinal();
    public static final int MASK_AQUA = 1 << SpotType.AQUA.ordinal();
    public static final int MASK_STONE = 1 << SpotType.STONE.ordinal();
    public static final int MASK_GOLD = 1 << SpotType.GOLD.ordinal();

    /** 宝藏进度条填满阈值（捕星者在 treasureProgress >= 100 时移除所有金钓点） */
    public static final int TREASURE_DONE = 100;

    /** 多层钓点机制把钓点层号存放在 ActiveSweetSpot#extraData 中的 key */
    private static final int SPOT_LAYER_DATA_KEY = 53;

    /** 钓点层号读取失败的哨兵值（此时保守跳过，不按下） */
    private static final int LAYER_UNKNOWN = Integer.MIN_VALUE;

    /** 宝藏模式下维持钓鱼进度的目标比例：低于该比例时优先命中非金钓点抬升进度 */
    private static final float TREASURE_RECOVER_RATIO = 0.5F;

    private static final ResourceLocation FISHING_BOB_ID = ResourceLocation.tryParse("starcatcher:fishing_bob");
    private static final TagKey<Item> ROD_TAG = ItemTags.create(ResourceLocation.tryParse("starcatcher:rods"));

    private static boolean initialized;
    private static EntityType<?> bobberType;
    private static Class<?> bobberClass;
    private static Field bobStateField;
    private static Class<?> minigameClass;
    private static Method methodGetSweetSpots;
    private static Method methodGetPointerPosPrecise;
    private static Method methodInputPressed;
    private static Method methodOverlapWithLeeway;
    private static Field spotPosField;
    private static Field spotThicknessField;
    private static Field spotRemovedField;
    private static Field spotTextureField;
    private static Field spotRewardField;
    private static Field screenTreasureActiveField;
    private static Field screenTreasureProgressField;
    private static Field screenProgressField;
    private static Field screenHpField;
    private static Method methodGetModifiers;
    private static Class<?> layeredSpotModifierClass;
    private static Field layeredPointerLayerField;
    private static Field layeredMaxPointerLayerField;
    private static Field spotExtraDataField;
    private static boolean reflectionFailed;

    private StarcatcherCompat()
    {
    }

    /** 懒初始化反射句柄，只执行一次 */
    private static void ensureInit()
    {
        if (initialized)
        {
            return;
        }
        initialized = true;
        if (!LOADED)
        {
            return;
        }

        try
        {
            bobberType = ForgeRegistries.ENTITY_TYPES.getValue(FISHING_BOB_ID);
            bobberClass = Class.forName("com.wdiscute.starcatcher.bobberentity.FishingBobEntity");
            bobStateField = bobberClass.getField("STATE");

            minigameClass = Class.forName("com.wdiscute.starcatcher.minigame.FishingMinigameScreen");
            methodGetSweetSpots = minigameClass.getMethod("getActiveSweetSpots");
            methodGetPointerPosPrecise = minigameClass.getMethod("getPointerPosPrecise");
            methodInputPressed = minigameClass.getMethod("inputPressed");
            methodOverlapWithLeeway = minigameClass.getMethod("doDegreesOverlapWithLeeway",
                    float.class, float.class, int.class);

            Class<?> sweetSpotClass = Class.forName("com.wdiscute.starcatcher.minigame.ActiveSweetSpot");
            spotPosField = sweetSpotClass.getField("pos");
            spotThicknessField = sweetSpotClass.getField("thickness");
            spotRemovedField = sweetSpotClass.getField("removed");
            spotTextureField = sweetSpotClass.getField("texture");

            AutoAnglerMod.LOGGER.info("[AutoAngler] 已成功对接捕星者mod (Starcatcher)");
        }
        catch (Throwable t)
        {
            reflectionFailed = true;
            AutoAnglerMod.LOGGER.error("[AutoAngler] 对接捕星者mod失败，自动钓鱼将不可用", t);
            return;
        }

        // 宝藏模式所需字段单独获取：旧版本捕星者缺失时只禁用宝藏模式，不影响基础自动钓鱼
        try
        {
            screenTreasureActiveField = minigameClass.getField("treasureActive");
            screenTreasureProgressField = minigameClass.getField("treasureProgress");
            screenProgressField = minigameClass.getField("progress");
            screenHpField = minigameClass.getField("hp");
            spotRewardField = Class.forName("com.wdiscute.starcatcher.minigame.ActiveSweetSpot").getField("reward");
        }
        catch (Throwable t)
        {
            screenTreasureActiveField = null;
            screenTreasureProgressField = null;
            screenProgressField = null;
            screenHpField = null;
            spotRewardField = null;
            AutoAnglerMod.LOGGER.warn("[AutoAngler] 当前捕星者版本缺少宝藏相关字段，宝藏模式不可用", t);
        }

        // 多层钓点(Nikdo53)机制所需句柄单独获取：旧版本捕星者缺失时静默退化，
        // 仅失去"变暗钓点自动切层命中"能力，不影响其余自动钓鱼功能
        try
        {
            methodGetModifiers = minigameClass.getMethod("getModifiers");
            layeredSpotModifierClass = Class.forName("com.wdiscute.starcatcher.registry.minigamemodifiers.Nikdo53Modifier");
            layeredPointerLayerField = layeredSpotModifierClass.getField("pointerLayer");
            layeredMaxPointerLayerField = layeredSpotModifierClass.getField("maxPointerLayer");
            spotExtraDataField = Class.forName("com.wdiscute.starcatcher.minigame.ActiveSweetSpot").getField("extraData");
        }
        catch (Throwable t)
        {
            methodGetModifiers = null;
            layeredSpotModifierClass = null;
            layeredPointerLayerField = null;
            layeredMaxPointerLayerField = null;
            spotExtraDataField = null;
            AutoAnglerMod.LOGGER.warn("[AutoAngler] 当前捕星者版本缺少多层钓点相关字段，多层钓点兼容不可用", t);
        }
    }

    /** 当前主手/任意物品栏中的物品是否为星捕手鱼竿 */
    public static boolean isRod(ItemStack stack)
    {
        if (stack == null || stack.isEmpty())
        {
            return false;
        }
        if (LOADED && stack.is(ROD_TAG))
        {
            return true;
        }
        // 兜底：标签未同步时按物品注册名判断
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        return id != null && "starcatcher".equals(id.getNamespace()) && id.getPath().endsWith("_rod");
    }

    /** 指定实体是否为捕星者的浮漂 */
    public static boolean isBobber(Entity entity)
    {
        ensureInit();
        if (bobberClass != null && bobberClass.isInstance(entity))
        {
            return true;
        }
        return bobberType != null && entity.getType() == bobberType;
    }

    /**
     * 查找离玩家最近的、属于该玩家的浮漂，超出64格视为无。
     *
     * 归属判断依据原版 Projectile 的 owner：服务端生成实体时把 owner 玩家 id 放入
     * ClientboundAddEntityPacket 的 data 字段，客户端重建实体时由
     * Projectile#recreateFromPacket 恢复 setOwner，因此 getOwner() 在客户端
     * 能精确区分自己与其他玩家的浮漂（捕星者没有重写这套机制）。
     */
    public static Entity findBobber(Level level, Player near)
    {
        ensureInit();
        if (!LOADED || level == null || near == null || bobberClass == null)
        {
            return null;
        }

        AABB searchArea = new AABB(-64.0D, -64.0D, -64.0D, 64.0D, 64.0D, 64.0D).move(near.position());
        List<Entity> entities = level.getEntities((Entity) null, searchArea, StarcatcherCompat::isBobber);

        Entity nearest = null;
        double nearestDist = Double.MAX_VALUE;
        for (Entity entity : entities)
        {
            if (!isOwnedBy(entity, near))
            {
                continue;
            }
            double dist = entity.distanceToSqr(near);
            if (dist < nearestDist)
            {
                nearest = entity;
                nearestDist = dist;
            }
        }
        return nearest;
    }

    /** 浮漂是否属于该玩家（客户端通过 Projectile#getOwner 判断，无法识别归属时不予采用） */
    private static boolean isOwnedBy(Entity bobber, Player owner)
    {
        if (bobber instanceof Projectile projectile)
        {
            return projectile.getOwner() == owner;
        }
        return false;
    }

    /** 读取浮漂的同步状态（1-4），失败返回 -1 */
    public static int getBobberState(Entity bobber)
    {
        ensureInit();
        if (bobber == null || bobStateField == null)
        {
            return -1;
        }
        try
        {
            Object accessor = bobStateField.get(null);
            if (!(accessor instanceof EntityDataAccessor<?> dataAccessor))
            {
                return -1;
            }
            @SuppressWarnings("unchecked")
            Integer value = bobber.getEntityData().get((EntityDataAccessor<Integer>) dataAccessor);
            return value == null ? -1 : value;
        }
        catch (Throwable t)
        {
            return -1;
        }
    }

    /** 指定界面是否为捕星者小游戏界面 */
    public static boolean isMinigameScreen(Screen screen)
    {
        ensureInit();
        return screen != null && minigameClass != null && minigameClass.isInstance(screen);
    }

    /** 本局小游戏是否已激活宝藏（左侧出现宝藏进度条） */
    public static boolean isTreasureActive(Screen screen)
    {
        ensureInit();
        if (screenTreasureActiveField == null || screen == null)
        {
            return false;
        }
        try
        {
            return screenTreasureActiveField.getBoolean(screen);
        }
        catch (Throwable t)
        {
            return false;
        }
    }

    /** 读取宝藏进度条数值（金钓点命中会增加），失败返回 -1 */
    public static int getTreasureProgress(Screen screen)
    {
        ensureInit();
        if (screenTreasureProgressField == null || screen == null)
        {
            return -1;
        }
        try
        {
            return screenTreasureProgressField.getInt(screen);
        }
        catch (Throwable t)
        {
            return -1;
        }
    }

    /**
     * 本局小游戏是否启用了多层钓点机制（Nikdo53Modifier，仅岩浆传说鱼使用）：
     * 钓点随机分散到多层，非当前指针层的钓点渲染更靠外且变暗，直接命中会被
     * 取消判定为空按（扣进度），自动钓鱼需要先把指针层切到目标钓点所在层。
     */
    public static boolean hasLayeredSpots(Screen screen)
    {
        ensureInit();
        if (reflectionFailed || screen == null || !isMinigameScreen(screen))
        {
            return false;
        }
        return findLayerController(screen) != null;
    }

    /**
     * 自动玩小游戏（普通规则）：当指针与"已勾选类型"的指示区（SweetSpot）重叠时触发一次判定。
     * 命中筛选细节见 pressWithFilter。
     *
     * @param enabledMask 已勾选的钓点类型掩码，见 MASK_BASIC 等常量
     * @return 本tick是否触发了一次命中判定
     */
    public static boolean autoHitMinigame(Screen screen, int enabledMask)
    {
        ensureInit();
        if (reflectionFailed || minigameClass == null || screen == null || !minigameClass.isInstance(screen))
        {
            return false;
        }
        try
        {
            return pressWithFilter(screen, enabledMask);
        }
        catch (Throwable t)
        {
            AutoAnglerMod.LOGGER.error("[AutoAngler] 小游戏自动游玩失败", t);
            return false;
        }
    }

    /**
     * 宝藏模式自动游玩：捕星者探测到宝藏后（小游戏左侧出现宝藏进度条，并加入金钓点），
     * 在宝藏条填满之前维持钓鱼进度在 50% 上下，保证鱼既不脱钩也不被钓上来：
     *
     * - 进度低于 hp 的 50%：优先命中"已勾选的非金钓点"抬升进度，且校验命中后进度不会
     *   达到上限 hp（避免这一竿把鱼直接钓起来）；没有可安全命中的非金钓点时退回金钓点；
     * - 进度不低于 50%：只命中金钓点（金钓点只加宝藏进度、不涨钓鱼进度），让进度自然衰减；
     * - 宝藏条填满（treasureProgress >= 100，金钓点已移除）或本局没有宝藏：
     *   按 enabledMask 恢复正常命中规则，正常把鱼钓上来并结算宝藏。
     *
     * 金钓点只增加宝藏进度不会提前把鱼钓上来，因此宝藏模式下无视钓点筛选强制命中金钓点。
     *
     * @param enabledMask 已勾选的钓点类型掩码，见 MASK_BASIC 等常量
     * @return 本tick是否触发了一次命中判定
     */
    public static boolean autoHitMinigameTreasure(Screen screen, int enabledMask)
    {
        ensureInit();
        if (reflectionFailed || minigameClass == null || screen == null || !minigameClass.isInstance(screen))
        {
            return false;
        }
        if (screenTreasureActiveField == null || screenTreasureProgressField == null
                || screenProgressField == null || screenHpField == null)
        {
            // 捕星者版本缺少宝藏相关字段：退化为普通规则
            return autoHitMinigame(screen, enabledMask);
        }

        try
        {
            boolean treasureActive = screenTreasureActiveField.getBoolean(screen);
            int treasureProgress = screenTreasureProgressField.getInt(screen);
            if (!treasureActive || treasureProgress >= TREASURE_DONE)
            {
                // 本局没有宝藏，或宝藏条已填满（金钓点已全部移除）：恢复正常钓鱼模式
                return autoHitMinigame(screen, enabledMask);
            }

            float progress = screenProgressField.getFloat(screen);
            int hp = screenHpField.getInt(screen);
            if (hp > 0 && progress < hp * TREASURE_RECOVER_RATIO)
            {
                int recoverMask = enabledMask & ~MASK_GOLD;
                if (recoverMask != 0 && canHitWithoutFinishing(screen, recoverMask, progress, hp)
                        && pressWithFilter(screen, recoverMask))
                {
                    return true;
                }
            }
            return pressWithFilter(screen, MASK_GOLD);
        }
        catch (Throwable t)
        {
            AutoAnglerMod.LOGGER.error("[AutoAngler] 宝藏模式小游戏自动游玩失败", t);
            return false;
        }
    }

    /**
     * 按 filterMask 触发一次命中判定：
     * 仅当存在"被允许且与指针重叠"的钓点时才按下；按下前临时把不允许的钓点从
     * activeSweetSpots 中移除，让原版 inputPressed() 命中列表里第一个重叠的允许钓点
     * （否则可能命中冻结/TNT 等不允许的钓点），调用结束后按原顺序恢复。
     *
     * 多层钓点（Nikdo53）机制下还会把"不在目标层"的钓点一并移出判定列表，并在需要时
     * 先把指针层切到目标钓点所在层（等价玩家按方向键），避免命中变暗钓点被取消判定为
     * 空按而扣钓鱼进度。
     */
    private static boolean pressWithFilter(Screen screen, int filterMask) throws Throwable
    {
        @SuppressWarnings("unchecked")
        List<Object> sweetSpots = (List<Object>) methodGetSweetSpots.invoke(screen);
        if (sweetSpots == null || sweetSpots.isEmpty())
        {
            return false;
        }

        // 选择命中目标：多层机制下优先命中当前指针层的亮点；若只与变暗钓点重叠，
        // 则先把指针层切到该钓点所在层再按下
        float pointerPos = (Float) methodGetPointerPosPrecise.invoke(screen);
        HitTarget target = selectTarget(screen, sweetSpots, pointerPos, filterMask);
        if (target == null)
        {
            return false;
        }
        Object layerController = findLayerController(screen);
        if (layerController != null && target.layer() != LAYER_UNKNOWN)
        {
            setPointerLayer(layerController, target.layer());
        }

        List<Object> snapshot = new ArrayList<>(sweetSpots);
        sweetSpots.removeIf(spot -> !typeOf(spot).isEnabled(filterMask)
                || (target.layer() != LAYER_UNKNOWN && readSpotLayer(spot) != target.layer()));
        try
        {
            methodInputPressed.invoke(screen);
        }
        finally
        {
            sweetSpots.clear();
            sweetSpots.addAll(snapshot);
        }
        return true;
    }

    /**
     * 选择本次要命中的目标钓点：
     *
     * - 本局没有多层钓点机制：取第一个"未移除、被 mask 允许且与指针重叠"的钓点；
     * - 有多层钓点机制：优先命中当前指针层（不暗）的允许钓点；若当前层没有，
     *   则选择其他层（变暗）的钓点作为目标，由调用方先把指针层切过去再命中，
     *   避免命中与指针层不匹配的钓点被取消判定为空按。
     *
     * 层号读取失败的钓点一律跳过（保守起见不按下，防止误扣进度）。
     */
    private static HitTarget selectTarget(Screen screen, List<Object> sweetSpots, float pointerPos, int mask) throws Throwable
    {
        Object controller = findLayerController(screen);
        int pointerLayer = controller == null ? LAYER_UNKNOWN : layeredPointerLayerField.getInt(controller);
        HitTarget dimmedFallback = null;
        for (Object spot : sweetSpots)
        {
            if (spotRemovedField.getBoolean(spot) || !typeOf(spot).isEnabled(mask) || !overlapsPointer(spot, pointerPos))
            {
                continue;
            }
            if (controller == null)
            {
                return new HitTarget(spot, LAYER_UNKNOWN);
            }
            int layer = readSpotLayer(spot);
            if (layer == pointerLayer)
            {
                // 当前指针层的亮点：直接命中
                return new HitTarget(spot, layer);
            }
            if (layer != LAYER_UNKNOWN && dimmedFallback == null)
            {
                // 其他层的变暗钓点：作为备选，稍后切换指针层再命中
                dimmedFallback = new HitTarget(spot, layer);
            }
        }
        return dimmedFallback;
    }

    /** 从本局生效的规则修正器列表中查找多层钓点控制器（Nikdo53），没有或反射句柄缺失时返回 null */
    private static Object findLayerController(Screen screen)
    {
        if (methodGetModifiers == null || layeredSpotModifierClass == null
                || layeredPointerLayerField == null || layeredMaxPointerLayerField == null
                || spotExtraDataField == null)
        {
            return null;
        }
        try
        {
            Object modifiers = methodGetModifiers.invoke(screen);
            if (modifiers instanceof List<?> list)
            {
                for (Object modifier : list)
                {
                    if (layeredSpotModifierClass.isInstance(modifier))
                    {
                        return modifier;
                    }
                }
            }
        }
        catch (Throwable ignored)
        {
        }
        return null;
    }

    /** 读取钓点所属层号；不存在多层机制或读取失败时返回 LAYER_UNKNOWN */
    private static int readSpotLayer(Object spot)
    {
        if (spotExtraDataField == null)
        {
            return LAYER_UNKNOWN;
        }
        try
        {
            Object extraData = spotExtraDataField.get(spot);
            if (extraData instanceof Map<?, ?> map && map.get(SPOT_LAYER_DATA_KEY) instanceof Integer layer)
            {
                return layer;
            }
        }
        catch (Throwable ignored)
        {
        }
        return LAYER_UNKNOWN;
    }

    /** 把指针切换到指定层（等价玩家按左/右方向键），层号按 maxPointerLayer 夹取 */
    private static void setPointerLayer(Object controller, int layer)
    {
        try
        {
            int maxLayer = Math.max(0, layeredMaxPointerLayerField.getInt(controller));
            layeredPointerLayerField.setInt(controller, Math.max(0, Math.min(layer, maxLayer)));
        }
        catch (Throwable ignored)
        {
        }
    }

    /** 钓点角度范围是否与指针重叠（判定方式与原版 inputPressed 一致） */
    private static boolean overlapsPointer(Object spot, float pointerPos) throws Throwable
    {
        float spotPos = spotPosField.getFloat(spot);
        int thickness = spotThicknessField.getInt(spot);
        Boolean overlap = (Boolean) methodOverlapWithLeeway.invoke(null, pointerPos, spotPos, thickness / 2);
        return Boolean.TRUE.equals(overlap);
    }

    /** 命中目标：spot 为要命中的钓点；layer 为其所在层（LAYER_UNKNOWN 表示不存在多层机制） */
    private record HitTarget(Object spot, int layer)
    {
    }

    /**
     * 复查命中 recoverMask 中将要命中的目标钓点后，钓鱼进度不会达到上限 hp
     * （目标选择与 pressWithFilter 完全一致；若奖励字段缺失则不做校验，直接允许）。
     */
    private static boolean canHitWithoutFinishing(Screen screen, int recoverMask, float progress, int hp) throws Throwable
    {
        @SuppressWarnings("unchecked")
        List<Object> sweetSpots = (List<Object>) methodGetSweetSpots.invoke(screen);
        if (sweetSpots == null || sweetSpots.isEmpty())
        {
            return false;
        }
        float pointerPos = (Float) methodGetPointerPosPrecise.invoke(screen);
        HitTarget target = selectTarget(screen, sweetSpots, pointerPos, recoverMask);
        if (target == null || spotRewardField == null)
        {
            return target != null;
        }
        int reward = spotRewardField.getInt(target.spot());
        return progress + reward < hp;
    }

    /**
     * 统计当前小游戏中各类型钓点的数量（索引 = SpotType.ordinal），失败返回 null。
     * 用于小游戏开始时输出一次日志，避免逐一提示造成刷屏。
     */
    public static int[] countSpots(Screen screen)
    {
        ensureInit();
        if (reflectionFailed || minigameClass == null || screen == null || !minigameClass.isInstance(screen))
        {
            return null;
        }
        try
        {
            @SuppressWarnings("unchecked")
            List<Object> sweetSpots = (List<Object>) methodGetSweetSpots.invoke(screen);
            if (sweetSpots == null)
            {
                return null;
            }
            int[] counts = new int[SpotType.values().length];
            for (Object spot : sweetSpots)
            {
                if (spotRemovedField.getBoolean(spot))
                {
                    continue;
                }
                counts[typeOf(spot).ordinal()]++;
            }
            return counts;
        }
        catch (Throwable t)
        {
            return null;
        }
    }

    /** 读取钓点的纹理，按纹理文件名归类 */
    private static SpotType typeOf(Object spot)
    {
        try
        {
            Object texture = spotTextureField.get(spot);
            if (texture instanceof ResourceLocation rl)
            {
                String path = rl.getPath();
                String name = path.substring(path.lastIndexOf('/') + 1);
                return SpotType.fromTextureName(name);
            }
        }
        catch (Throwable ignored)
        {
        }
        return SpotType.TNT;
    }
}
