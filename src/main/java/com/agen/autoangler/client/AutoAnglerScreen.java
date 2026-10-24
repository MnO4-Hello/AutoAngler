package com.agen.autoangler.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.Locale;
import java.util.function.Consumer;

/**
 * 自动钓鱼配置界面。
 * - 右下角为"开始钓鱼/停止钓鱼"按钮；
 * - 左下角为"钓点筛选"、"宝藏模式"与"调试模式"设置；
 * - 点击"钓点筛选"右侧的调整按钮会弹出小黑框，逐个勾选要命中的钓点类型；
 * - 小黑框最后一行只读展示 TNT 钓点（命中会扣进度的危险钓点，不允许勾选），
 *   鼠标悬停在"基础钓点"或 TNT 行上会显示说明；
 * - 鼠标悬停在"宝藏模式"按钮上会显示玩法说明。
 */
public class AutoAnglerScreen extends Screen
{
    private static final int MARGIN = 10;
    private static final int BUTTON_WIDTH = 140;
    private static final int BUTTON_HEIGHT = 20;

    /** 钓点筛选小黑框尺寸（高度需容纳 5 个勾选行 + 1 个 TNT 只读行 + 两行提示） */
    private static final int PANEL_WIDTH = 180;
    private static final int PANEL_HEIGHT = 200;
    private static final int ROW_HEIGHT = 22;

    /** 小黑框中五种可勾选钓点的顺序与翻译键 */
    private static final String[] SPOT_KEYS = {
            "gui.autoangler.spot.basic",
            "gui.autoangler.spot.frozen",
            "gui.autoangler.spot.aqua",
            "gui.autoangler.spot.stone",
            "gui.autoangler.spot.gold"
    };

    /** TNT 钓点所在行号（紧随可勾选行之后，只读展示） */
    private static final int TNT_ROW = SPOT_KEYS.length;

    private Button toggleButton;
    private Button filterButton;
    private Button debugButton;
    private Button treasureButton;
    private final Checkbox[] spotBoxes = new Checkbox[SPOT_KEYS.length];
    private boolean panelOpen;
    private Component statusOverride;

    public AutoAnglerScreen()
    {
        super(Component.translatable("gui.autoangler.title"));
    }

    @Override
    protected void init()
    {
        this.toggleButton = Button.builder(toggleLabel(), b -> onTogglePressed())
                .bounds(this.width - BUTTON_WIDTH - MARGIN, this.height - BUTTON_HEIGHT - MARGIN,
                        BUTTON_WIDTH, BUTTON_HEIGHT)
                .build();
        this.addRenderableWidget(this.toggleButton);

        // 左下角：钓点筛选 / 调试模式
        int baseY = this.height - 80;
        int buttonX = 20 + 112;
        this.filterButton = Button.builder(
                        Component.translatable("gui.autoangler.spot_filter.adjust"), b -> togglePanel())
                .bounds(buttonX, baseY, 60, 20)
                .build();
        this.addRenderableWidget(this.filterButton);

        this.debugButton = Button.builder(debugLabel(), b -> toggleDebugMode())
                .bounds(buttonX, baseY + 26, 60, 20)
                .build();
        this.addRenderableWidget(this.debugButton);

        this.treasureButton = Button.builder(treasureLabel(), b -> toggleTreasureMode())
                .bounds(buttonX, baseY + 52, 60, 20)
                .build();
        this.addRenderableWidget(this.treasureButton);

        // 小黑框：五种钓点的勾选框（名字由界面在左侧绘制，勾选框位于行右端）
        int panelX = panelX();
        int panelY = panelY();
        for (int i = 0; i < SPOT_KEYS.length; i++)
        {
            final int index = i;
            Checkbox box = new SpotBox(panelX + PANEL_WIDTH - 34, panelY + 26 + i * ROW_HEIGHT,
                    spotValue(i), value -> setSpotValue(index, value));
            box.visible = panelOpen;
            this.spotBoxes[i] = box;
            this.addRenderableWidget(box);
        }
    }

    private Component toggleLabel()
    {
        return Component.translatable(AutoAnglerController.INSTANCE.isFishing()
                ? "gui.autoangler.stop" : "gui.autoangler.start");
    }

    private static Component debugLabel()
    {
        return Component.translatable(AutoAnglerConfig.isDebugMode()
                ? "gui.autoangler.on" : "gui.autoangler.off");
    }

    private static Component treasureLabel()
    {
        return Component.translatable(AutoAnglerConfig.isTreasureMode()
                ? "gui.autoangler.on" : "gui.autoangler.off");
    }

    private void onTogglePressed()
    {
        if (this.minecraft == null)
        {
            return;
        }

        AutoAnglerController controller = AutoAnglerController.INSTANCE;
        if (controller.isFishing())
        {
            controller.requestStop(this.minecraft, AutoAnglerController.StopReason.BUTTON);
            this.statusOverride = null;
        }
        else
        {
            AutoAnglerController.StartResult result = controller.start(this.minecraft);
            if (result == AutoAnglerController.StartResult.OK)
            {
                // 开始成功，关闭界面回到游戏视角
                this.minecraft.setScreen(null);
                return;
            }
            this.statusOverride = Component.translatable(
                    "gui.autoangler.result." + result.name().toLowerCase(Locale.ROOT));
        }
        this.toggleButton.setMessage(toggleLabel());
    }

    /** 展开/收起钓点筛选小黑框 */
    private void togglePanel()
    {
        panelOpen = !panelOpen;
        for (Checkbox box : spotBoxes)
        {
            if (box != null)
            {
                box.visible = panelOpen;
            }
        }
    }

    private void toggleDebugMode()
    {
        AutoAnglerConfig.setDebugMode(!AutoAnglerConfig.isDebugMode());
        AutoAnglerConfig.save();
        if (this.debugButton != null)
        {
            this.debugButton.setMessage(debugLabel());
        }
    }

    private void toggleTreasureMode()
    {
        AutoAnglerConfig.setTreasureMode(!AutoAnglerConfig.isTreasureMode());
        AutoAnglerConfig.save();
        if (this.treasureButton != null)
        {
            this.treasureButton.setMessage(treasureLabel());
        }
    }

    private int panelX()
    {
        return (this.width - PANEL_WIDTH) / 2;
    }

    private int panelY()
    {
        return this.height / 2 - 110;
    }

    private static boolean spotValue(int index)
    {
        return switch (index)
        {
            case 0 -> AutoAnglerConfig.isSpotBasic();
            case 1 -> AutoAnglerConfig.isSpotFrozen();
            case 2 -> AutoAnglerConfig.isSpotAqua();
            case 3 -> AutoAnglerConfig.isSpotStone();
            default -> AutoAnglerConfig.isSpotGold();
        };
    }

    private static void setSpotValue(int index, boolean value)
    {
        switch (index)
        {
            case 0 -> AutoAnglerConfig.setSpotBasic(value);
            case 1 -> AutoAnglerConfig.setSpotFrozen(value);
            case 2 -> AutoAnglerConfig.setSpotAqua(value);
            case 3 -> AutoAnglerConfig.setSpotStone(value);
            default -> AutoAnglerConfig.setSpotGold(value);
        }
    }

    @Override
    public void tick()
    {
        super.tick();
        if (this.toggleButton != null)
        {
            this.toggleButton.setMessage(toggleLabel());
        }
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick)
    {
        this.renderBackground(guiGraphics);
        if (panelOpen)
        {
            drawPanelBackground(guiGraphics);
        }
        super.render(guiGraphics, mouseX, mouseY, partialTick);

        int centerX = this.width / 2;

        guiGraphics.drawCenteredString(this.font, this.title, centerX, 28, 0xFFFFFF);

        Component status = this.statusOverride != null ? this.statusOverride : currentStatus();
        int statusColor = this.statusOverride != null ? 0xFF5555 : 0xA0A0A0;
        guiGraphics.drawCenteredString(this.font, status, centerX, 52, statusColor);

        Component starcatcherState = Component.translatable(StarcatcherCompat.LOADED
                ? "gui.autoangler.starcatcher.detected" : "gui.autoangler.starcatcher.missing");
        guiGraphics.drawCenteredString(this.font, starcatcherState, centerX, 70,
                StarcatcherCompat.LOADED ? 0x55FF55 : 0xFF5555);

        // 左下角设置行的标签
        int baseY = this.height - 80;
        guiGraphics.drawString(this.font,
                Component.translatable("gui.autoangler.spot_filter"), 20, baseY + 6, 0xFFFFFF);
        guiGraphics.drawString(this.font,
                Component.translatable("gui.autoangler.debug_mode"), 20, baseY + 32, 0xFFFFFF);
        guiGraphics.drawString(this.font,
                Component.translatable("gui.autoangler.treasure_mode"), 20, baseY + 58, 0xFFFFFF);

        if (panelOpen)
        {
            drawPanelText(guiGraphics);
            Component tooltip = tooltipForRow(hoveredRow(mouseX, mouseY));
            if (tooltip != null)
            {
                guiGraphics.renderTooltip(this.font, tooltip, mouseX, mouseY);
            }
        }

        if (this.treasureButton != null && this.treasureButton.isMouseOver(mouseX, mouseY))
        {
            guiGraphics.renderTooltip(this.font,
                    Component.translatable("gui.autoangler.treasure_mode.tooltip"), mouseX, mouseY);
        }
    }

    /** 小黑框背景：黑色半透明底 + 灰色边框 */
    private void drawPanelBackground(GuiGraphics guiGraphics)
    {
        int x = panelX();
        int y = panelY();
        guiGraphics.fill(x - 1, y - 1, x + PANEL_WIDTH + 1, y + PANEL_HEIGHT + 1, 0xFF707070);
        guiGraphics.fill(x, y, x + PANEL_WIDTH, y + PANEL_HEIGHT, 0xF0000000);
    }

    /** 小黑框内的文字：标题、五个可勾选钓点名字、TNT 只读行、底部提示 */
    private void drawPanelText(GuiGraphics guiGraphics)
    {
        int x = panelX();
        int y = panelY();
        guiGraphics.drawCenteredString(this.font,
                Component.translatable("gui.autoangler.spot_filter.title"),
                x + PANEL_WIDTH / 2, y + 8, 0xFFFFFF);
        for (int i = 0; i < SPOT_KEYS.length; i++)
        {
            guiGraphics.drawString(this.font, Component.translatable(SPOT_KEYS[i]),
                    x + 14, y + 26 + i * ROW_HEIGHT + 6, 0xE0E0E0);
        }
        drawTntRow(guiGraphics);
        guiGraphics.drawString(this.font,
                Component.translatable("gui.autoangler.spot_filter.hint1"),
                x + 12, y + PANEL_HEIGHT - 30, 0x909090);
        guiGraphics.drawString(this.font,
                Component.translatable("gui.autoangler.spot_filter.hint2"),
                x + 12, y + PANEL_HEIGHT - 18, 0x909090);
    }

    /**
     * TNT 行：灰色名字 + 红色"危险"标注 + 画成灰色的禁用勾选框。
     * TNT 是负面钓点（命中会扣进度且无收益），因此只做展示、不提供可点击的勾选框。
     */
    private void drawTntRow(GuiGraphics guiGraphics)
    {
        int x = panelX();
        int y = panelY();
        int rowY = y + 26 + TNT_ROW * ROW_HEIGHT;
        Component name = Component.translatable("gui.autoangler.spot.tnt");
        guiGraphics.drawString(this.font, name, x + 14, rowY + 6, 0xA0A0A0);
        guiGraphics.drawString(this.font,
                Component.translatable("gui.autoangler.spot.tnt.warning"),
                x + 14 + this.font.width(name), rowY + 6, 0xB06060);

        int boxX = x + PANEL_WIDTH - 34;
        guiGraphics.fill(boxX, rowY, boxX + 20, rowY + 20, 0xFF101010);
        guiGraphics.fill(boxX, rowY, boxX + 20, rowY + 1, 0xFF505050);
        guiGraphics.fill(boxX, rowY + 19, boxX + 20, rowY + 20, 0xFF505050);
        guiGraphics.fill(boxX, rowY, boxX + 1, rowY + 20, 0xFF505050);
        guiGraphics.fill(boxX + 19, rowY, boxX + 20, rowY + 20, 0xFF505050);
        guiGraphics.drawString(this.font, "x", boxX + 7, rowY + 7, 0xFF707070);
    }

    /** 鼠标悬停的钓点行号：0-4 为可勾选行，TNT_ROW 为 TNT 行，无则 -1 */
    private int hoveredRow(int mouseX, int mouseY)
    {
        int x = panelX();
        int y = panelY();
        if (mouseX < x + 10 || mouseX > x + PANEL_WIDTH - 10)
        {
            return -1;
        }
        int offset = mouseY - (y + 26);
        if (offset < 0 || offset >= TNT_ROW * ROW_HEIGHT + 20)
        {
            return -1;
        }
        return Math.min(offset / ROW_HEIGHT, TNT_ROW);
    }

    /** 该行对应的说明提示（目前仅"基础钓点"与 TNT 行有），无则返回 null */
    private static Component tooltipForRow(int row)
    {
        if (row == 0)
        {
            return Component.translatable("gui.autoangler.spot.basic.tooltip");
        }
        if (row == TNT_ROW)
        {
            return Component.translatable("gui.autoangler.spot.tnt.tooltip");
        }
        return null;
    }

    /** 当前状态文本：进行中显示阶段，停止后显示停止原因 */
    private Component currentStatus()
    {
        AutoAnglerController controller = AutoAnglerController.INSTANCE;
        if (controller.isFishing())
        {
            return Component.translatable("gui.autoangler.status.running", phaseText(controller.getPhase()));
        }
        if (controller.getLastStopReason() != AutoAnglerController.StopReason.NONE)
        {
            return Component.translatable("gui.autoangler.status.stopped",
                    AutoAnglerController.reasonText(controller.getLastStopReason()));
        }
        return Component.translatable("gui.autoangler.status.idle");
    }

    private static Component phaseText(AutoAnglerController.Phase phase)
    {
        return Component.translatable("gui.autoangler.phase." + phase.name().toLowerCase(Locale.ROOT));
    }

    /** 小黑框里的单个勾选框：点击切换对应钓点的勾选状态并立即保存 */
    private static final class SpotBox extends Checkbox
    {
        private final Consumer<Boolean> onChange;

        private SpotBox(int x, int y, boolean value, Consumer<Boolean> onChange)
        {
            super(x, y, 20, 20, Component.empty(), value, false);
            this.onChange = onChange;
        }

        @Override
        public void onPress()
        {
            super.onPress();
            this.onChange.accept(this.selected());
            AutoAnglerConfig.save();
        }
    }
}
