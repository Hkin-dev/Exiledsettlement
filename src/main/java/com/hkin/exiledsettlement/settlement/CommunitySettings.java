package com.hkin.exiledsettlement.settlement;

import java.util.*;
import net.minecraft.core.BlockPos;

/** Shared option catalog: the UI and server validate the same explicit values. */
public final class CommunitySettings {
    public record Choice(String value, String label) { }
    public record Setting(String id, String group, String label, String help, List<Choice> choices) {
        public String display(String value) {
            return choices.stream().filter(c -> c.value().equals(value)).map(Choice::label)
                    .findFirst().orElse(value + "（现有值）");
        }
    }
    private static List<Choice> switches() { return List.of(new Choice("off", "关闭"), new Choice("on", "开启")); }
    private static List<Choice> counts(String unit, int... values) {
        return Arrays.stream(values).mapToObj(n -> new Choice("" + n, n + unit)).toList();
    }
    private static Setting toggle(String id, String group, String label, String help) {
        return new Setting(id, group, label, help, switches());
    }
    private static final List<Choice> STOCK = counts("件", 0, 12, 24, 48, 64, 96, 128, 192, 256, 512, 1024, 2048, 4096);
    public static final List<Setting> ALL = List.of(
            toggle("auto_terrain","works","居住区修整","默认开启。居民保留现有房屋高度与外壳，真实补地基、整理近旁平台和门前通路，分级衔接并修筑挡土墙。关闭暂停旧房修整，新建必要地基仍优先。"),
            toggle("auto_roads", "works", "公共工程", "默认开启。空闲成年居民可执行道路及其整地、通路、台阶和公共支撑。无规模、深度与每日额度上限；仍需真实工具、材料与可达位置。生活、本职工作优先，政策和储备线不整体停工。关闭暂停公共道路工程，已确认蓝图的必要地基继续。居住区修整也默认开启，可分别关闭。"),
            new Setting("policy", "life", "用工政策", "正常：按原岗位工作，允许空闲工人修路与美化。秋收优先：最多2名未锁定工人支援农田，暂停美化，空闲居民仍可执行公共工程。紧急保供：暂停普通新建、维修、拆除和美化，优先物流。手动选择持续生效，不会随季节自动恢复。",
                    List.of(new Choice("normal", "正常"), new Choice("harvest", "秋收优先"), new Choice("emergency", "紧急保供"))),
            new Setting("food_reserve", "life", "食物保留线", "单位为公共库存食物件数。约束烹饪和流民接纳；食物不高于此数时暂停美化，空闲居民仍可执行公共工程。居民仍可进食。0表示不额外保留，并不提供食物。", STOCK),
            new Setting("fuel_reserve", "life", "燃料保留线", "单位为公共库存煤炭与木炭的总件数。约束烹饪；燃料不高于此数时暂停美化，空闲居民仍可执行公共工程。居民仍可取暖。", STOCK),
            new Setting("meal_target", "life", "熟食库存目标", "餐厅熟食按库存目标补给。饥饿居民仍可取真实小麦自行合成面包。此项不限制仓库收储。", STOCK),
            toggle("steward", "life", "仓管库存整理", "仓管按统一库存管理中各仓库的物品规则，真实入仓取走并处理余量。关闭暂停处理，居民入库照常进行。默认雪球保留64件、其他物品全部保留。"),
            toggle("births", "life", "家庭生育", "开启后，满足家庭、住房和生活条件的居民才可能生育；不会立即生成儿童。成年居民不退休或自然老死。"),
            toggle("decorations", "decor", "自动装饰性模块", "居民从公共库存取料、组装并现场布置装饰性模块；普通建设和物流优先。道路装饰性模块只在已有道路旁装饰，创建道路请开启自动修路。"),
            new Setting("decor_density", "decor", "装饰性模块疏密", "稀疏：同类间隔20格、最多32处；适中：16格、64处；丰富：12格、96处。同类和灯具至少水平10格，异类至少5格。降低疏密不会自动拆除现有模块。",
                    List.of(new Choice("0", "稀疏"), new Choice("1", "适中"), new Choice("2", "丰富"))),
            new Setting("decor_style", "decor", "装饰性模块风格", "影响后续道路灯具的选择：拓荒木构使用小路灯，规整悬臂灯使用悬臂路灯。不会替换已完成的建筑或装饰性模块。",
                    List.of(new Choice("0", "拓荒木构"), new Choice("1", "规整悬臂灯"))),
            new Setting("decor_budget", "decor", "每日装饰性模块额度", "每个Minecraft世界日的装饰性模块施工额度，按组件所需方块数占用。仍须从真实库存取料；不是免费材料，也不是每日装饰性模块个数。", counts("格/日", 64, 128, 256, 512)),
            toggle("decor_road", "decor", "道路装饰性模块", "允许在已建道路旁布置路灯；本项不创建路面，受自动装饰性模块总开关控制。"),
            toggle("decor_homes", "decor", "住宅装饰性模块", "允许住宅附近装饰性模块，受自动装饰性模块总开关、库存和可用空间限制。"),
            toggle("decor_work", "decor", "生产装饰性模块", "允许生产区域装饰性模块，受自动装饰性模块总开关、库存和可用空间限制。"),
            toggle("decor_public", "decor", "公共装饰性模块", "允许公共空地和自然装饰性模块，受自动装饰性模块总开关、库存和可用空间限制。"),
            toggle("evacuate", "safety", "集合避险", "开启后居民优先前往安全集合点，修路与美化暂停。解除后按日常作息恢复。集合点默认在核心附近。"),
            toggle("local_season_scenery", "scenery", "季节景色", "仅此客户端：季节植物颜色与秋季落叶；真实降雨、降雪和积雪由服务器决定，关闭此项仍可见。"),
            toggle("local_season_hud", "scenery", "季节提示", "仅此客户端：显示服务器历法和下一季倒计时。关闭后季节仍正常推进。"));

    private CommunitySettings() { }
    public static Setting find(String id) { return ALL.stream().filter(s -> s.id().equals(id)).findFirst().orElse(null); }
    private static String on(boolean value) { return value ? "on" : "off"; }
    public static Map<String, String> values(SettlementRecord s, BlockPos player) {
        var c = s.community(); var d = s.decorations(); var r = s.autoRoads();
        var values = new LinkedHashMap<String, String>();
        values.put("auto_terrain",on(s.terrain().enabled));
        values.put("auto_roads", on(r.enabled));
        values.put("policy", c.policy); values.put("food_reserve", "" + c.foodReserve);
        values.put("fuel_reserve", "" + c.fuelReserve); values.put("meal_target", "" + c.mealTarget);
        values.put("steward",on(c.stewardEnabled));values.put("snow_limit",""+c.snowLimit);
        values.put("seed_rounds",""+c.seedRounds);values.put("wheat_target",""+c.wheatTarget);
        values.put("bone_target",""+c.boneTarget);values.put("life_slots",""+c.lifeSlots);
        values.put("births", on(c.birthsEnabled)); values.put("evacuate", on(c.evacuation));
        values.put("decorations", on(d.enabled)); values.put("decor_density", "" + d.density);
        values.put("decor_style", "" + d.style); values.put("decor_budget", "" + d.dailyBudget);
        values.put("decor_road", on(d.family(1))); values.put("decor_homes", on(d.family(2)));
        values.put("decor_work", on(d.family(4))); values.put("decor_public", on(d.family(8)));
        values.put("decor_forbid", d.forbidden(player) ? "已禁放" : "允许布置");
        values.put("danger", c.danger(player) ? "已标危险" : "未标危险");
        return Map.copyOf(values);
    }
    /** Explicit assignments are idempotent; unknown keys/values never mutate saved state. */
    public static boolean apply(SettlementRecord s, String id, String value) {
        var setting = find(id);
        if (setting == null || id.startsWith("local_") || setting.choices().stream().noneMatch(c -> c.value().equals(value))) return false;
        var c = s.community(); var d = s.decorations(); boolean enabled = value.equals("on");
        switch (id) {
            case "auto_terrain" -> {s.terrain().enabled=enabled;s.terrain().publicPaused=!enabled && !s.autoRoads().enabled;}
            case "auto_roads" -> {s.autoRoads().enabled = enabled;s.terrain().publicPaused=!enabled && !s.terrain().enabled;}
            case "policy" -> c.policy = value;
            case "food_reserve" -> c.foodReserve = Integer.parseInt(value);
            case "fuel_reserve" -> c.fuelReserve = Integer.parseInt(value);
            case "meal_target" -> c.mealTarget = Integer.parseInt(value);
            case "steward" -> c.stewardEnabled=enabled;
            case "snow_limit" -> c.snowLimit=Integer.parseInt(value);
            case "seed_rounds" -> c.seedRounds=Integer.parseInt(value);
            case "wheat_target" -> c.wheatTarget=Integer.parseInt(value);
            case "bone_target" -> c.boneTarget=Integer.parseInt(value);
            case "life_slots" -> c.lifeSlots=Integer.parseInt(value);
            case "births" -> c.birthsEnabled = enabled;
            case "evacuate" -> c.evacuation = enabled;
            case "decorations" -> d.enabled = enabled;
            case "decor_density" -> d.density = Integer.parseInt(value);
            case "decor_style" -> d.style = Integer.parseInt(value);
            case "decor_budget" -> d.dailyBudget = Integer.parseInt(value);
            case "decor_road", "decor_homes", "decor_work", "decor_public" -> {
                int bit = switch (id) { case "decor_road" -> 1; case "decor_homes" -> 2; case "decor_work" -> 4; default -> 8; };
                d.families = enabled ? d.families | bit : d.families & ~bit;
            }
            default -> { return false; }
        }
        return true;
    }
}
