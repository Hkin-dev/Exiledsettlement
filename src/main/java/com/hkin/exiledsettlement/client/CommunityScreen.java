package com.hkin.exiledsettlement.client;

import com.hkin.exiledsettlement.network.*;
import com.hkin.exiledsettlement.settlement.CommunitySettings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.neoforged.neoforge.network.PacketDistributor;
import java.util.*;

public final class CommunityScreen extends Screen {
    private record Tab(String id, String label) { }
    private record Action(String id, String group, String label, String help) { }
    private record Control(String label, String help, Runnable click, boolean enabled) { }
    private static final List<Tab> TABS = List.of(new Tab("works", "公共工程"), new Tab("life", "生活"), new Tab("decor", "装饰性模块"), new Tab("safety", "安全"), new Tab("visitors", "来访"),
            new Tab("scenery", "景色"), new Tab("report", "报告"));
    private static final List<Action> ACTIONS = List.of(
            new Action("bridge_pause","works","暂停附近桥梁","暂停32格内最近的未完工玩家桥梁；保留已建结构、材料和进度。"),
            new Action("bridge_cancel","works","撤销附近桥梁","撤销32格内最近的未完工桥梁；保留已有实物和材料。"),
            new Action("bridge_resume","works","继续附近桥梁","恢复32格内最近的未完工玩家桥梁，按原进度施工。"),
            new Action("works_clear_plants","works","框选清理植被","选择地面两角，清理地面以上16格内的树木花草。正式建筑、农业、道路、桥梁和保护区域保留；先确认清单。"),
            new Action("works_clear_modules","works","框选清理模块","仅移除完整落在选区内的装饰性模块，居民真实拆除；抑制自动补回。正式设施需在建筑管理中单独选择拆除。"),
            new Action("works_clear_blocks","works","框选清理普通方块","选择地面两角，清理较低角以上至较高角上方8格的普通方块；不自动挖平地面。容器保留真实掉落，正式设施默认排除，提交前显示清单。"),
            new Action("terrain_check","works","重新勘察住宅","主动更新本聚落住宅整地：停止旧住宅工程的未做步骤，按小平台、挡土墙和必要台阶重新规划。保留已建土方和玩家移除记录，不撤销道路或农业工程；开启居住区修整后由居民施工。"),
            new Action("terrain_cancel","works","撤销附近整地","撤销32格内最近待建整地；保留实际地基和挡土墙，相关待建房屋会显示地基未完成。"),
            new Action("road_cancel_here", "works", "撤销附近自动路线", "点击即撤销距你16格内最近的自动路线。保留已铺路面，并持续禁止这条路线重建。"),
            new Action("decor_forbid", "decor", "当前区块", "点击切换你所在16×16区块的禁放状态；同时限制自动装饰性模块和自动修路，不拆现有装饰性模块。"),
            new Action("rally", "safety", "此处设为集合点", "把你脚下的安全可站立位置设为集合点；不会自动开启避险。"),
            new Action("danger", "safety", "当前危险区", "点击切换你所在16×16区块的危险标记；居民避开危险区，修路和装饰性模块也会绕开。"),
            new Action("clear_danger", "safety", "清除所有危险区", "点击即清除本聚落全部危险区标记；不会改变世界方块。"),
            new Action("accept", "visitors", "接纳流民家庭", "需要有效来访、床位与人口额度；每名成年消耗12份食物、儿童6份，接纳后还须满足保留线。"),
            new Action("reject", "visitors", "拒绝流民家庭", "点击即结束本次流民申请；之后仍可有新的来访。"),
            new Action("barter_seeds", "visitors", "16橡木换8瓜种", "商人在场时，从真实公共库存支付16根橡木原木，换8颗西瓜种子。每次来访限一次。"),
            new Action("barter_iron", "visitors", "64橡木换8铁锭", "商人在场时，从真实公共库存支付64根橡木原木，换8个铁锭。每次来访限一次。"),
            new Action("recover_kit", "visitors", "恢复开拓凭证", "仅恢复尚未兑换的开拓资格；已兑换物资不会重复发放。"));
    private CommunitySnapshotPayload snapshot;
    private final Screen parent;
    private List<FormattedCharSequence> wrapped = List.of();
    private String tab = "works", selected;
    private int scroll, controlsPage, pageCount = 1;
    private String feedback = "点击设置查看全部选项；选择具体值后生效";

    private CommunityScreen(CommunitySnapshotPayload snapshot, Screen parent) {
        super(Component.literal("聚落生活 · " + snapshot.name())); this.snapshot = snapshot; this.parent = parent;
    }
    public static void receive(CommunitySnapshotPayload payload) {
        var game = Minecraft.getInstance();
        if (game.screen instanceof CommunityScreen existing && existing.snapshot.core().equals(payload.core())) {
            existing.snapshot = payload;
            existing.feedback = "已读取服务器当前状态";
            existing.rebuild();
        } else game.setScreen(new CommunityScreen(payload, game.screen));
    }
    private int controlLeft() { return width - 158; }
    private int contentWidth() { return Math.max(40, controlLeft() - 32); }
    private int visibleRows() { return Math.max(1, (height - 110) / 12); }
    private String value(String id) {
        return switch (id) {
            case "local_season_hud" -> SeasonClientConfig.HUD.get() ? "on" : "off";
            case "local_season_scenery" -> SeasonClientConfig.SCENERY.get() ? "on" : "off";
            default -> snapshot.settings().getOrDefault(id, "未知");
        };
    }
    private boolean inGroup(CommunitySettings.Setting setting) {
        return setting.group().equals(tab) || tab.equals("works") && setting.id().equals("policy");
    }
    @Override protected void init() { rebuild(); }
    private void setTab(String id) { tab = id; selected = null; scroll = controlsPage = 0; rebuild(); }
    private void inspect(String id) { selected = id; scroll = controlsPage = 0; rebuild(); }

    private List<String> reportLines() {
        if (tab.equals("report")) return snapshot.lines();
        var lines = new ArrayList<String>();
        if(tab.equals("works")) {
            boolean keep=false;for(String line:snapshot.lines()){if(line.startsWith("——"))keep=line.equals("—— 工程缺料 ——") || line.equals("—— 居民整地 ——") || line.equals("—— 居民自动修路 ——") || line.equals("—— 公共工程分工 ——");if(keep)lines.add(line);}
        }else if (tab.equals("decor")) {
            String marker = tab.equals("terrain")?"—— 居民整地 ——":tab.equals("roads") ? "—— 居民自动修路 ——" : "—— 自动装饰性模块 ——";
            boolean found = false;
            for (String line : snapshot.lines()) {
                if (line.equals(marker)) { found = true; lines.add(line); continue; }
                if (found && line.startsWith("——")) break;
                if (found) lines.add(line);
            }
        } else {
            for (String line : snapshot.lines()) {
                if (line.startsWith("——")) break;
                if (tab.equals("life")
                        || tab.equals("safety") && line.startsWith("集合避险")
                        || tab.equals("visitors") && (line.contains("流民") || line.startsWith("商队"))
                        || tab.equals("scenery") && (line.contains("季") || line.contains("日历"))) lines.add(line);
            }
        }
        lines.add("—— 设置说明 ——");
        for (var setting : CommunitySettings.ALL) if (inGroup(setting)) {
            lines.add(setting.label() + "：" + setting.display(value(setting.id())));
            lines.add(setting.help());
        }
        for (var action : ACTIONS) if (action.group().equals(tab)) lines.add(action.label() + "：" + action.help());
        return lines;
    }

    private static Component reportComponent(String line) {
        var result=Component.empty();
        var matcher=java.util.regex.Pattern.compile("\\{\\{([a-z0-9_.:/-]+)\\}\\}").matcher(line);
        int last=0;
        while(matcher.find()) {
            result.append(Component.literal(line.substring(last,matcher.start())));
            result.append(Component.translatable(matcher.group(1)));last=matcher.end();
        }
        return result.append(Component.literal(line.substring(last)));
    }

    private void rebuild() {
        if (font == null) return;
        clearWidgets();
        int tabWidth = (width - 36) / TABS.size();
        for (int i = 0; i < TABS.size(); i++) {
            var entry = TABS.get(i);
            var button = addRenderableWidget(Button.builder(Component.literal(entry.label()), b -> setTab(entry.id()))
                    .bounds(18 + i * tabWidth, 39, tabWidth - 3, 20).build());
            button.active = !entry.id().equals(tab) || selected != null;
        }
        var controls = new ArrayList<Control>();
        var lines = new ArrayList<String>();
        if (selected != null) {
            var setting = CommunitySettings.find(selected);
            String current = value(selected);
            lines.add(setting.label() + " · 当前：" + setting.display(current));
            lines.add(setting.help());
            lines.add("全部可选值：" + String.join("、", setting.choices().stream().map(CommunitySettings.Choice::label).toList()));
            lines.add("在右侧选择具体值，带“✓”的是服务器当前值。翻页和查看说明不会修改设置。");
            for (var choice : setting.choices()) controls.add(new Control(
                    (choice.value().equals(current) ? "✓ " : "选择：") + choice.label(),
                    "将“" + setting.label() + "”设为“" + choice.label() + "”。", () -> choose(setting.id(), choice.value()), !choice.value().equals(current)));
        } else {
            lines.addAll(reportLines());
            for (var setting : CommunitySettings.ALL) if (inGroup(setting)) controls.add(new Control(
                    setting.label() + "：" + setting.display(value(setting.id())), setting.help(), () -> inspect(setting.id()), true));
            for (var action : ACTIONS) if (action.group().equals(tab)) {
                String label = action.label();
                if (snapshot.settings().containsKey(action.id())) label += "：" + value(action.id());
                controls.add(new Control(label, action.help(), () -> send(action.id()), true));
            }
        }
        var text = new ArrayList<FormattedCharSequence>();
        for (String line : lines) { text.addAll(font.split(reportComponent(line), contentWidth())); text.add(FormattedCharSequence.EMPTY); }
        wrapped = List.copyOf(text); scroll = Math.min(scroll, Math.max(0, wrapped.size() - visibleRows()));
        int perPage = Math.max(1, (height - 134) / 23);
        pageCount = Math.max(1, (controls.size() + perPage - 1) / perPage);
        controlsPage = Math.min(controlsPage, pageCount - 1);
        for (int i = controlsPage * perPage; i < Math.min(controls.size(), (controlsPage + 1) * perPage); i++) {
            var control = controls.get(i);
            String label = font.width(control.label()) > 134 ? font.plainSubstrByWidth(control.label(), 124) + "…" : control.label();
            var button = addRenderableWidget(Button.builder(Component.literal(label), b -> control.click().run())
                    .tooltip(Tooltip.create(Component.literal(control.label() + "\n" + control.help())))
                    .bounds(controlLeft(), 78 + i % perPage * 23, 140, 20).build());
            button.active = control.enabled();
        }
        var previous = addRenderableWidget(Button.builder(Component.literal("上一页"), b -> { controlsPage--; rebuild(); })
                .bounds(controlLeft(), height - 57, 67, 19).build());
        previous.active = controlsPage > 0;
        var next = addRenderableWidget(Button.builder(Component.literal("下一页"), b -> { controlsPage++; rebuild(); })
                .bounds(controlLeft() + 73, height - 57, 67, 19).build());
        next.active = controlsPage + 1 < pageCount;
        addRenderableWidget(Button.builder(Component.literal("刷新"), b -> send("refresh"))
                .bounds(controlLeft(), height - 33, 67, 20).build());
        addRenderableWidget(Button.builder(Component.literal(selected == null ? "返回" : "返回设置"), b -> onClose())
                .bounds(controlLeft() + 73, height - 33, 67, 20).build());
    }
    private void choose(String id, String choice) {
        if (id.startsWith("local_")) {
            var preference = id.equals("local_season_hud") ? SeasonClientConfig.HUD : SeasonClientConfig.SCENERY;
            preference.set(choice.equals("on")); SeasonClientConfig.SPEC.save(); feedback = "本机设置已保存"; rebuild();
        } else { feedback = "等待服务器确认…"; send(id + "=" + choice); }
    }
    private void send(String action) {
        PacketDistributor.sendToServer(new CommunityActionPayload(snapshot.core(), action));
    }
    @Override public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(graphics, mouseX, mouseY, partialTick);
        graphics.fill(8, 8, width - 8, height - 8, 0xFFE7D8B8);
    }
    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Draw all report text after the parent's background blur.
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawString(font, font.plainSubstrByWidth(title.getString(), width - 40), 20, 19, 0xFF3E2B1B, false);
        graphics.drawString(font, "滚轮阅读说明", 20, 65, 0xFF665647, false);
        graphics.drawString(font, (selected == null ? "当前设置" : "选择具体值") + " " + (controlsPage + 1) + "/" + pageCount,
                controlLeft(), 65, 0xFF665647, false);
        graphics.enableScissor(18, 77, controlLeft() - 10, height - 26);
        for (int i = 0; i < visibleRows() && scroll + i < wrapped.size(); i++)
            graphics.drawString(font, wrapped.get(scroll + i), 20, 79 + i * 12, 0xFF3E2B1B, false);
        graphics.disableScissor();
        graphics.drawString(font, font.plainSubstrByWidth(feedback, contentWidth()), 20, height - 21, 0xFF665647, false);
    }
    @Override public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        if (mouseX >= controlLeft()) {
            controlsPage = Math.max(0, Math.min(pageCount - 1, controlsPage - (int) Math.signum(vertical))); rebuild();
        } else scroll = Math.max(0, Math.min(Math.max(0, wrapped.size() - visibleRows()), scroll - (int) (vertical * 3)));
        return true;
    }
    @Override public void onClose() {
        if (selected != null) { selected = null; scroll = controlsPage = 0; rebuild(); }
        else minecraft.setScreen(parent);
    }
    @Override public boolean isPauseScreen() { return false; }
}
