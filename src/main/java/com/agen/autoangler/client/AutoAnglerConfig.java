package com.agen.autoangler.client;

import com.agen.autoangler.AutoAnglerMod;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraftforge.fml.loading.FMLPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 自动钓鱼客户端配置，持久化到 config/autoangler.json。
 *
 * - 钓点筛选：与鱼搏斗时只命中勾选的钓点类型（默认勾选 基础/水流/金）；
 * - 宝藏模式：出现宝藏进度条时维持钓鱼进度，只命中金钓点填满宝藏条，详见 StarcatcherCompat#autoHitMinigameTreasure；
 * - 调试模式：开启后关键日志会实时反馈到聊天框（高频日志不反馈，详见 AutoAnglerController#log）。
 */
public final class AutoAnglerConfig
{
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FMLPaths.CONFIGDIR.get().resolve("autoangler.json");

    /** 基础钓点（普通/细条/螃蟹/凋灵/苦力怕/叶子等常规正向外观） */
    private static boolean spotBasic = true;
    /** 冻结钓点（命中会冻结指针，默认不勾选） */
    private static boolean spotFrozen = false;
    /** 水流钓点（消失后不再出现） */
    private static boolean spotAqua = true;
    /** 岩石钓点（进度极低，默认不勾选） */
    private static boolean spotStone = false;
    /** 金钓点（增加宝藏进度） */
    private static boolean spotGold = true;
    /** 宝藏模式：出现宝藏进度条时保持钓鱼进度在50%上下，用金钓点填满宝藏条 */
    private static boolean treasureMode = false;
    /** 调试模式：聊天框实时反馈关键日志 */
    private static boolean debugMode = false;

    private static boolean loadAttempted;

    private AutoAnglerConfig()
    {
    }

    public static boolean isSpotBasic()
    {
        ensureLoaded();
        return spotBasic;
    }

    public static boolean isSpotFrozen()
    {
        ensureLoaded();
        return spotFrozen;
    }

    public static boolean isSpotAqua()
    {
        ensureLoaded();
        return spotAqua;
    }

    public static boolean isSpotStone()
    {
        ensureLoaded();
        return spotStone;
    }

    public static boolean isSpotGold()
    {
        ensureLoaded();
        return spotGold;
    }

    public static boolean isTreasureMode()
    {
        ensureLoaded();
        return treasureMode;
    }

    public static boolean isDebugMode()
    {
        ensureLoaded();
        return debugMode;
    }

    /** 当前勾选的钓点类型位掩码（供小游戏命中筛选使用） */
    public static int spotMask()
    {
        ensureLoaded();
        int mask = 0;
        if (spotBasic)
        {
            mask |= StarcatcherCompat.MASK_BASIC;
        }
        if (spotFrozen)
        {
            mask |= StarcatcherCompat.MASK_FROZEN;
        }
        if (spotAqua)
        {
            mask |= StarcatcherCompat.MASK_AQUA;
        }
        if (spotStone)
        {
            mask |= StarcatcherCompat.MASK_STONE;
        }
        if (spotGold)
        {
            mask |= StarcatcherCompat.MASK_GOLD;
        }
        return mask;
    }

    public static void setSpotBasic(boolean value)
    {
        ensureLoaded();
        spotBasic = value;
    }

    public static void setSpotFrozen(boolean value)
    {
        ensureLoaded();
        spotFrozen = value;
    }

    public static void setSpotAqua(boolean value)
    {
        ensureLoaded();
        spotAqua = value;
    }

    public static void setSpotStone(boolean value)
    {
        ensureLoaded();
        spotStone = value;
    }

    public static void setSpotGold(boolean value)
    {
        ensureLoaded();
        spotGold = value;
    }

    public static void setTreasureMode(boolean value)
    {
        ensureLoaded();
        treasureMode = value;
    }

    public static void setDebugMode(boolean value)
    {
        ensureLoaded();
        debugMode = value;
    }

    /** 首次访问时懒加载配置文件 */
    private static void ensureLoaded()
    {
        if (loadAttempted)
        {
            return;
        }
        loadAttempted = true;
        load();
    }

    public static void load()
    {
        try
        {
            if (!Files.exists(FILE))
            {
                return;
            }
            String json = Files.readString(FILE, StandardCharsets.UTF_8);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            spotBasic = getBoolean(obj, "spotBasic", spotBasic);
            spotFrozen = getBoolean(obj, "spotFrozen", spotFrozen);
            spotAqua = getBoolean(obj, "spotAqua", spotAqua);
            spotStone = getBoolean(obj, "spotStone", spotStone);
            spotGold = getBoolean(obj, "spotGold", spotGold);
            treasureMode = getBoolean(obj, "treasureMode", treasureMode);
            debugMode = getBoolean(obj, "debugMode", debugMode);
        }
        catch (Throwable t)
        {
            AutoAnglerMod.LOGGER.warn("[AutoAngler] 读取配置失败，使用默认配置", t);
        }
    }

    public static void save()
    {
        try
        {
            JsonObject obj = new JsonObject();
            obj.addProperty("spotBasic", spotBasic);
            obj.addProperty("spotFrozen", spotFrozen);
            obj.addProperty("spotAqua", spotAqua);
            obj.addProperty("spotStone", spotStone);
            obj.addProperty("spotGold", spotGold);
            obj.addProperty("treasureMode", treasureMode);
            obj.addProperty("debugMode", debugMode);
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, GSON.toJson(obj), StandardCharsets.UTF_8);
        }
        catch (Throwable t)
        {
            AutoAnglerMod.LOGGER.warn("[AutoAngler] 保存配置失败", t);
        }
    }

    private static boolean getBoolean(JsonObject obj, String key, boolean fallback)
    {
        return obj.has(key) && obj.get(key).isJsonPrimitive() ? obj.get(key).getAsBoolean() : fallback;
    }
}
