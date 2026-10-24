# AutoAngler · 自动钓鱼

> 为 **捕星者（Starcatcher）** 打造的自动钓鱼模组：自动换竿、抛竿、识别咬钩、收杆，并把搏斗小游戏也一起自动打完。纯客户端，不进服务端。

![Minecraft](https://img.shields.io/badge/Minecraft-1.20.1-informational)
![Forge](https://img.shields.io/badge/Forge-47.3.33-informational)
![Side](https://img.shields.io/badge/side-client-blue)

---

## 简介

捕星者的钓鱼流程包含「抛竿 → 等咬钩 → 收杆 → 进入搏斗小游戏 → 命中钓点」多个环节，手钓很费神。
`AutoAngler` 把整条链路做成一个状态机，用原版输入包（右键、切手、点物品栏）驱动，服务端看到的行为和真人点击完全一致。

最麻烦的一点是：多人游戏里浮漂实体要经过一次网络往返才会出现在客户端。模组为此加了 **抛竿宽限期**（默认 3 秒），宽限期内查不到浮漂视为「还在路上」，
不会误判丢失而疯狂收杆/抛竿。

## 功能

- **全自动循环**：找竿 → 换到主手 → 抛竿 → 等咬钩 → 收杆 → 打完小游戏 → 下一轮
- **钓点筛选**：可勾选基础 / 冻结 / 水流 / 岩石 / 金钓点；未勾选的钓点永远不会被命中
- **危险钓点永久禁用**：TNT 钓点会掉进度且无收益，代码层面直接排除
- **宝藏模式**：出现宝藏进度条时，进度低于 50% 优先命中非金钓点维持进度，高于 50% 只命中金钓点填满宝藏条，填满后恢复正常策略
- **多层钓点**：检测到变暗钓点时，先切换指针层再命中
- **安全停止**：按 F8 / 点停止按钮 / 按鼠标左右键 / 移动超过 1 格 / 鱼竿丢失 / 离开世界，任一发生立即停止
- **防卡死**：每秒采样一次状态，连续 3 次处于「等待抛竿」就强制重新抛竿

## 环境要求

- Minecraft **1.20.1** + Forge **47.3.33**（47.x）+ Java **17**
- **Starcatcher（捕星者）**：提供鱼竿与小游戏界面；未安装时模组不会启动钓鱼流程

## 安装

1. 装好 Forge 与 Starcatcher。
2. 把 `autoangler-1.0.0.jar` 放进 `mods/`。
3. 背包里准备一把星捕手鱼竿，靠近水边。

## 使用

1. 按 `F8`（可在按键绑定里改）打开自动钓鱼界面。
2. 界面会提示是否检测到捕星者模组。
3. 点「开始钓鱼」；想调整时先点「钓点筛选 → 调整」，勾掉不想打的钓点。
4. 点「停止钓鱼」或直接移动/按鼠标键即可停止。

## 配置

配置文件：`config/autoangler.json`（首次运行自动生成）

```json
{
  "spotBasic": true,
  "spotFrozen": false,
  "spotAqua": true,
  "spotStone": false,
  "spotGold": true,
  "treasureMode": false,
  "debugMode": false
}
```

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `spotBasic` | true | 基础钓点（普通/细条/凋灵/苦力怕/叶子/螃蟹等正向外观） |
| `spotFrozen` | false | 冻结钓点（命中会冻结指针） |
| `spotAqua` | true | 水流钓点（消失后不再出现） |
| `spotStone` | false | 岩石钓点（进度极低） |
| `spotGold` | true | 金钓点（增加宝藏进度） |
| `treasureMode` | false | 宝藏模式（见上文策略） |
| `debugMode` | false | 关键日志实时输出到聊天框 |

## 实现说明

状态机：

```
PREPARE → CAST → WAIT_BITE → WAIT_MINIGAME → MINIGAME → (回到 CAST)
```

关键常量（`AutoAnglerController`）：

| 常量 | 值 | 用途 |
| --- | --- | --- |
| `ACTION_COOLDOWN` | 8 tick | 两次右键的最小间隔 |
| `MINIGAME_WAIT_TIMEOUT` | 60 tick | 收杆后等小游戏弹出的上限，超时视为跳过小游戏 |
| `RECALL_COOLDOWN` | 20 tick | 收回浮漂后等其从客户端消失 |
| `CAST_GRACE_TICKS` | 60 tick | 抛竿后浮漂同步的宽限期 |
| `MAX_MOVE_DISTANCE` | 1.0 格 | 超过即停止 |
| `SAMPLE_INTERVAL` / `IDLE_SAMPLES_TO_RECAST` | 20 tick / 3 次 | 防卡死重抛策略 |

## 构建

```bash
./gradlew build
# 产物：build/libs/autoangler-1.0.0.jar
```

## 目录结构

```
src/main/java/com/agen/autoangler/
├── AutoAnglerMod.java              # 入口（纯客户端逻辑的空壳）
└── client/
    ├── AutoAnglerClient.java       # 按键注册（F8）
    ├── AutoAnglerConfig.java       # JSON 配置读写
    ├── AutoAnglerController.java   # 钓鱼状态机（核心）
    ├── AutoAnglerEvents.java       # tick / 输入事件
    ├── AutoAnglerScreen.java       # 设置界面
    └── StarcatcherCompat.java      # 与捕星者的反射兼容层（含小游戏命中策略）
```

## 注意

自动钓鱼在多数服务器属于自动化行为，请自行确认服务器规则；作者不对封禁负责。

## 许可

All Rights Reserved（保留所有权利）。
