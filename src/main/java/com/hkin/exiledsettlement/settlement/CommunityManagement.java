package com.hkin.exiledsettlement.settlement;

import com.hkin.exiledsettlement.building.*;
import com.hkin.exiledsettlement.citizen.*;
import com.hkin.exiledsettlement.network.CommunitySnapshotPayload;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.*;
import net.minecraft.world.Container;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;
import java.util.*;

public final class CommunityManagement {
    private CommunityManagement() { }
    private static final Map<ServerPlayer, Long> LAST_REQUEST = new WeakHashMap<>();
    public static void open(ServerPlayer player, SettlementRecord settlement) {
        if (!settlement.active() || !settlement.isMember(player.getUUID())) return;
        CommunityLifeService.migrateCalendar(player.serverLevel(), SettlementSavedData.get(player.serverLevel()), settlement);
        PacketDistributor.sendToPlayer(player, new CommunitySnapshotPayload(settlement.corePosition(),
                settlement.name(), report(player.serverLevel(), settlement), CommunitySettings.values(settlement, player.blockPosition())));
    }
    public static void action(ServerPlayer player, BlockPos core, String action) {
        var level = player.serverLevel(); var data = SettlementSavedData.get(level);
        var settlement = data.findByCore(core).orElse(null);
        if (settlement == null || !settlement.active() || !settlement.isMember(player.getUUID())
                || !settlement.contains(player.blockPosition())) return;
        long now = level.getGameTime();
        if (now - LAST_REQUEST.getOrDefault(player, -100L) < 4) return;
        LAST_REQUEST.put(player, now);
        if(action.startsWith("works_")){
            com.hkin.exiledsettlement.terrain.PlayerWorksService.begin(player,settlement,action.substring(6));return;
        }
        var state = settlement.community(); String message = "";
        int separator = action.indexOf('=');
        if (separator >= 0) {
            String key = action.substring(0, separator), value = action.substring(separator + 1);
            if (!CommunitySettings.apply(settlement, key, value)) { open(player, settlement); return; }
            var setting = CommunitySettings.find(key);
            state.event(CommunityLifeService.day(level), "管理调整：" + setting.label() + " → " + setting.display(value));
            data.touch(); ProfessionAssignmentService.refresh(data, settlement);
            open(player, settlement); return;
        }
        switch (action) {
            case "bridge_cancel" -> message=com.hkin.exiledsettlement.road.PlayerBridgeWork.cancelNear(level,settlement,player.blockPosition());
            case "bridge_pause" -> message=com.hkin.exiledsettlement.road.PlayerBridgeWork.pauseNear(level,settlement,player.blockPosition(),true);
            case "bridge_resume" -> message=com.hkin.exiledsettlement.road.PlayerBridgeWork.pauseNear(level,settlement,player.blockPosition(),false);
            case "terrain_cancel" -> message=com.hkin.exiledsettlement.terrain.TerrainService.cancelNear(settlement,player.blockPosition());
            case "terrain_check" -> message=com.hkin.exiledsettlement.terrain.TerrainService.resurvey(level,settlement);
            case "refresh" -> { }
            case "auto_roads" -> CommunitySettings.apply(settlement,"auto_roads",settlement.autoRoads().enabled?"off":"on");
            case "road_cancel_here" -> message = com.hkin.exiledsettlement.road.AutoRoadService.cancelNear(settlement, player.blockPosition());
            case "decorations" -> settlement.decorations().enabled = !settlement.decorations().enabled;
            case "decor_density" -> settlement.decorations().density = (settlement.decorations().density + 1) % 3;
            case "decor_style" -> settlement.decorations().style = 1 - settlement.decorations().style;
            case "decor_budget" -> settlement.decorations().dailyBudget = settlement.decorations().dailyBudget >= 512 ? 64 : settlement.decorations().dailyBudget * 2;
            case "decor_road" -> settlement.decorations().families ^= 1;
            case "decor_homes" -> settlement.decorations().families ^= 2;
            case "decor_work" -> settlement.decorations().families ^= 4;
            case "decor_public" -> settlement.decorations().families ^= 8;
            case "decor_forbid" -> {
                long chunk = new net.minecraft.world.level.ChunkPos(player.blockPosition()).toLong();
                if (!settlement.decorations().forbiddenChunks.remove(chunk) && settlement.decorations().forbiddenChunks.size() < 64)
                    settlement.decorations().forbiddenChunks.add(chunk);
            }
            case "policy" -> state.policy = state.policy.equals("normal") ? "harvest" : state.policy.equals("harvest") ? "emergency" : "normal";
            case "food_reserve" -> state.foodReserve = next(state.foodReserve);
            case "fuel_reserve" -> state.fuelReserve = next(state.fuelReserve);
            case "meal_target" -> state.mealTarget = next(state.mealTarget);
            case "births" -> state.birthsEnabled = !state.birthsEnabled;
            case "evacuate" -> state.evacuation = !state.evacuation;
            case "rally" -> {
                BlockPos p = player.blockPosition();
                if (state.danger(p) || !level.getBlockState(p).getCollisionShape(level, p).isEmpty()
                        || !level.getBlockState(p.above()).getCollisionShape(level, p.above()).isEmpty()
                        || level.getBlockState(p.below()).getCollisionShape(level, p.below()).isEmpty()) message = "集合点需要安全、可站立的地面。";
                else state.rally = p.immutable();
            }
            case "danger" -> {
                long chunk = new net.minecraft.world.level.ChunkPos(player.blockPosition()).toLong();
                if (!state.dangerChunks.remove(chunk) && state.dangerChunks.size() < 64) state.dangerChunks.add(chunk);
            }
            case "clear_danger" -> state.dangerChunks.clear();
            case "accept" -> message = accept(level, settlement);
            case "reject" -> {
                if (state.migrantAdults + state.migrantChildren > 0) {
                    state.migrantAdults = state.migrantChildren = 0; state.offerExpires = -1;
                    state.event(CommunityLifeService.day(level), "拒绝本次流民家庭申请。");
                }
            }
            case "barter_seeds" -> message = barter(level, settlement, Items.OAK_LOG, 16, Items.MELON_SEEDS, 8);
            case "barter_iron" -> message = barter(level, settlement, Items.OAK_LOG, 64, Items.IRON_INGOT, 8);
            case "recover_kit" -> message = FoundingService.recover(player) ? "已恢复尚未使用的开拓蓝图或旧凭证。" : "没有可恢复的开拓资格，或物资已经兑换。";
            default -> { return; }
        }
        if (!action.equals("refresh")) {
            state.event(CommunityLifeService.day(level), "管理调整：" + actionLabel(action) + (message.isEmpty() ? "" : "；" + message));
            data.touch();
            ProfessionAssignmentService.refresh(data, settlement);
        }
        if (!message.isEmpty()) player.displayClientMessage(Component.literal(message), true);
        open(player, settlement);
    }
    private static String actionLabel(String action) {
        return switch (action) {
            case "decorations" -> "自动装饰性模块开关"; case "decor_density" -> "装饰性模块疏密";
            case "auto_roads" -> "居民自动修路";
            case "road_cancel_here" -> "撤销附近自动路线";
            case "decor_style" -> "装饰性模块风格"; case "decor_budget" -> "装饰性模块施工预算";
            case "decor_road" -> "道路装饰性模块"; case "decor_homes" -> "住宅装饰性模块";
            case "decor_work" -> "生产装饰性模块"; case "decor_public" -> "公共装饰性模块"; case "decor_forbid" -> "当前区块禁放";
            case "policy" -> "用工政策"; case "food_reserve" -> "食物保留线"; case "fuel_reserve" -> "燃料保留线";
            case "meal_target" -> "熟食库存目标"; case "births" -> "生育开关"; case "evacuate" -> "集合避险";
            case "rally" -> "集合点"; case "danger" -> "危险区域"; case "clear_danger" -> "清除危险区域";
            case "accept" -> "接纳流民"; case "reject" -> "拒绝流民";
            case "barter_seeds" -> "种子易货"; case "barter_iron" -> "铁料易货"; default -> "开拓道具恢复";
        };
    }
    private static int next(int current) { return current >= 192 ? 0 : current == 0 ? 12 : current * 2; }
    public static List<String> report(ServerLevel level, SettlementRecord settlement) {
        var state = settlement.community(); long day = CommunityLifeService.day(level);
        int adults = (int) settlement.citizens().stream().filter(CitizenRecord::canWork).count();
        int children = (int) settlement.aliveCitizenCount() - adults;
        int food = SettlementInventoryService.countCategory(level, settlement, WarehouseCategory.FOOD);
        int fuel = SettlementInventoryService.countItem(level, settlement, Items.COAL)
                + SettlementInventoryService.countItem(level, settlement, Items.CHARCOAL);
        int families = (int) settlement.citizens().stream().filter(CitizenRecord::alive)
                .filter(c -> settlement.citizen(c.life().partner) != null && settlement.citizen(c.life().partner).alive()).count() / 2;
        String policy = state.policy.equals("harvest") ? "秋收优先（最多2名未锁定工人临时支援农田）"
                : state.policy.equals("emergency") ? "紧急保供（暂停新建、维修与拆除，优先物流）" : "正常";
        List<String> lines = new ArrayList<>();
        lines.add("仓管整理："+(state.stewardEnabled?"开启":"关闭")+" / "+state.stewardStatus);
        lines.add("小麦当量 "+WarehouseStewardService.grain(level,settlement)+"；抽象仓库取消旧粮食停种和入库预留限制。余量规则请打开统一库存管理。");
        long keepers=settlement.citizens().stream().filter(CitizenRecord::canWork).filter(c -> c.profession()==CitizenProfession.WAREHOUSE_KEEPER).count();
        lines.add("仓库管理员 "+keepers+"人；各自负责绑定仓库的余量。雪球默认保留64件，其余真实取出处理；入库不因此拒收。");
        lines.add("第" + (state.calendarDay(day) / (state.seasonLength * 4L) + 1) + "年 / 世界第" + (day + 1) + "日 / " + List.of("春", "夏", "秋", "冬").get(CommunityLifeService.season(settlement, day)));
        long calendarTime = state.calendarTime(level.getDayTime());
        lines.add(SeasonCalendar.date(calendarTime, state.seasonLength) + "；" + SeasonCalendar.countdown(calendarTime, state.seasonLength));
        if (!level.getGameRules().getBoolean(net.minecraft.world.level.GameRules.RULE_DAYLIGHT))
            lines.add("日夜循环已关闭，世界日历不会自然推进；季节因此暂停。");
        lines.add("所有聚落使用共享世界历法；右侧可切换草木景色与提示。冬季实际降水形成真实薄雪，开春逐渐融化；不新增季节水体结冰。");
        lines.add("成年 " + adults + " / 儿童 " + children + " / 家庭 " + families + " / 住房 " + settlement.housingCapacity());
        long cold = settlement.citizens().stream().filter(CitizenRecord::alive).filter(c -> c.thermal().body < ThermalSettings.warning).count();
        long clothed = settlement.citizens().stream().filter(CitizenRecord::alive).filter(c -> CitizenClothingService.isGarment(c.thermal().garment)).count();
        int wool = CommunityStock.count(level, settlement, com.hkin.exiledsettlement.registry.ModItems.WOOL_COAT.get());
        int leather = CommunityStock.count(level, settlement, com.hkin.exiledsettlement.registry.ModItems.LEATHER_COAT.get());
        lines.add("受寒 " + cold + "人 / 已穿保温衣物 " + clothed + "人 / 未穿 " + (settlement.aliveCitizenCount() - clothed)
                + (settlement.creativeCoreInstalled()?"人；创造核心提供无限衣物，居民须到场领取":"人；公共衣物：羊毛 " + wool + " / 皮革 " + leather));
        lines.add("衣物缺口 " + Math.max(0, settlement.aliveCitizenCount() - clothed - wool - leather)
                + "件；空闲普通劳工在仓储现场用4份羊毛或皮革、2份线制作并交货。体温低于35℃优先避寒，恢复到36.5℃后复工。");
        lines.add("成年居民持续工作；无年龄降效、退休或自然老死。生育：" + (state.birthsEnabled ? "开启" : "关闭"));
        lines.add("政策：" + policy + "。手动选择持续生效，不会随季节自动解除。");
        lines.add(settlement.creativeCoreInstalled()?"创造核心：食物与物资无限供给，仍须居民到场领取":"公共食物 " + food + "（保留 " + state.foodReserve + "）/ 煤炭及木炭 " + fuel + "（保留 " + state.fuelReserve + "）");
        lines.add("熟食目标 " + state.mealTarget + "；本年烹饪 " + state.mealsProduced + " / 进食 " + state.mealsEaten + " / 保暖用燃料 " + state.coldFuelBurned);
        lines.add("保留线限制烹饪、流民和易货，不禁止居民进食或取暖。冬季农业停止，已采收产物仍可运输。");
        lines.add("餐厅由空闲且未锁定的工人取料、消耗燃料加工，再把熟食送入真实公共库存。");
        lines.add("随身口粮目标 " + CommunityContent.carriedFoodTarget + "份；每类保留一件斧、镐、铲、锄、剪刀、鱼竿、拴绳，额外工具在休息或到仓库时领取。");
        lines.add("个人产物在休息时交货；工作中仅背包容量不足时紧急送货，公共物流照常执行。");
        lines.add(CommunityContent.recoveryIntervalTicks == 0 ? "饱食恢复生命：关闭。"
                : "饱食恢复生命：饱食至少" + CommunityContent.recoveryNutritionMinimum + "%时，每"
                + (CommunityContent.recoveryIntervalTicks / 20.0D) + "秒最多恢复半颗心、消耗"
                + CommunityContent.recoveryNutritionCost + "%饱食；受击后10秒及自卫中暂停。");
        lines.add("集合避险：" + (state.evacuation ? "已开启" : "关闭") + " / 危险区块 " + state.dangerChunks.size()
                + " / 集合点 " + (state.rally == null ? "核心附近" : state.rally.toShortString()));
        if (state.migrantAdults + state.migrantChildren > 0) lines.add("流民申请：" + state.migrantAdults + "成年 + " + state.migrantChildren
                + "儿童；需要3个空床位和30份食物，额外保留线必须满足；" + (state.offerExpires - day) + "日后离开。可出现短期疾病，休养恢复。");
        else lines.add("暂无流民家庭申请；夏季会出现一次家庭来访机会。");
        lines.add("商队易货：16橡木原木→8西瓜种子；64橡木原木→8铁锭。仅商人在场时可成交，每次访问每种报价限一次。");
        lines.add("居民名称组合库：" + CommunityContent.nameCapacity() + "种；旧姓名和命名牌姓名保持原样。");
        lines.add("阶段目标：" + state.milestones.size() + "项已达成；开拓→熟食生产→安全过冬→新生居民→成年→康复。");
        lines.addAll(com.hkin.exiledsettlement.terrain.TerrainService.report(settlement));
        lines.addAll(com.hkin.exiledsettlement.terrain.PublicWorkService.report(level,settlement));
        var decor = settlement.decorations();
        lines.add("—— 自动装饰性模块 ——");
        lines.add("自动布置：" + (decor.enabled ? "开启" : "关闭") + "；风格：" + (decor.style == 0 ? "拓荒木构" : "规整悬臂灯")
                + "；疏密：" + List.of("稀疏", "适中", "丰富").get(decor.density));
        lines.add("道路 " + (decor.family(1) ? "开" : "关") + " / 住宅 " + (decor.family(2) ? "开" : "关")
                + " / 生产 " + (decor.family(4) ? "开" : "关") + " / 公共与自然 " + (decor.family(8) ? "开" : "关"));
        long complete = decor.features.values().stream().filter(f -> f.building != null && !f.suppressed).count();
        long pending = decor.features.values().stream().filter(f -> settlement.blueprint(f.blueprint) != null).count();
        lines.add("完工 " + complete + " / 待施工 " + pending + " / 已安排 " + decor.features.size() + "（上限 " + decor.limit() + "）");
        lines.add("今日施工额度 " + decor.spent + "/" + decor.dailyBudget + "；禁放区块 " + decor.forbiddenChunks.size() + "；" + decor.status);
        lines.add("工人从公共库存取料并组装；普通建设和物流优先。缺粮、缺燃料、秋收或避险时暂停；玩家移除的装饰性模块不补回。");
        lines.add("装饰性模块原料：原木、石料、铁料和煤炭；花草、土壤、羊毛仍需真实库存供应。木料每种保留32，燃料服从生活保留线。");
        for (var f : decor.features.values().stream().filter(f -> settlement.blueprint(f.blueprint) != null).limit(8).toList()) {
            var failure = settlement.citizens().stream().filter(c -> f.blueprint.equals(c.targetBlueprintId()))
                    .map(CitizenRecord::workFailure).filter(v -> !v.isEmpty()).findFirst().orElse("等待空闲工人");
            failure = switch (failure) {
                case "MATERIALS_MISSING", "FOOTING_MATERIAL_MISSING", "SCAFFOLD_FRAME_MATERIAL_MISSING" -> "库存原料不足";
                case "NO_PATH", "STUCK", "CONSTRUCTION_POSITION_UNREACHABLE" -> "暂时无法到达工地";
                case "WAREHOUSE_STATION_BUSY", "WORK_STATION_BUSY", "TRAFFIC_WAIT" -> "等待其他居民让出站位";
                case "WAREHOUSE_INACCESSIBLE" -> "无法进入库存领取位置";
                case "INVENTORY_FULL" -> "工人背包已满";
                case "PROTECTED_AREA" -> "领地规则拒绝施工";
                case "等待空闲工人" -> failure;
                default -> "工人正在处理施工阻碍，可在居民详情查看原因";
            };
            var entry = com.hkin.exiledsettlement.decoration.DecorationCatalog.ENTRIES.get(f.kind);
            lines.add((entry == null ? "装饰性模块" : entry.displayName()) + " @ " + f.anchor.toShortString() + "：" + failure);
        }
        lines.addAll(com.hkin.exiledsettlement.road.AutoRoadService.report(level, settlement));
        lines.add("—— 居民与家庭 ——");
        for (var c : settlement.citizens().stream().filter(CitizenRecord::alive).limit(200).toList()) {
            var partner = settlement.citizen(c.life().partner);
            lines.add(c.name() + "｜" + (c.canWork() ? "成年" : "儿童") + " " + c.life().ageDays + "日｜营养 " + c.nutrition()
                    + String.format(java.util.Locale.ROOT," / 体温 %.1f℃", c.thermal().body) + " / 饮食 " + c.life().dietCoverage(day, CommunityContent.dietWindowDays) + "/4"
                    + "｜随身口粮 " + Math.min(CommunityContent.carriedFoodTarget, CommunityLifeService.carriedFood(c)) + "/" + CommunityContent.carriedFoodTarget
                    + (partner == null ? "" : "｜伴侣 " + partner.name())
                    + (c.life().sickUntil > day ? "｜休养至第" + (c.life().sickUntil + 1) + "日" : ""));
        }
        lines.add("—— 年度报告（启用后记录；库存净变化含外部存取，并非纯生产统计）——");
        if (state.reports.isEmpty()) lines.add("尚未跨年；当前库存账本持续记录。");
        lines.addAll(state.reports);
        lines.add("—— 最近事件 ——");
        lines.addAll(state.events);
        return List.copyOf(lines);
    }
    public static String accept(ServerLevel level, SettlementRecord settlement) {
        var state = settlement.community(); long day = CommunityLifeService.day(level);
        int count = state.migrantAdults + state.migrantChildren;
        if (count == 0 || day >= state.offerExpires) return "当前没有有效申请。";
        if (settlement.housingCapacity() - settlement.aliveCitizenCount() < count
                || settlement.aliveCitizenCount() + count > settlement.level().populationCap()) return "住房或人口额度不足，申请仍保留。";
        int food = SettlementInventoryService.countCategory(level, settlement, WarehouseCategory.FOOD);
        int cost = state.migrantAdults * 12 + state.migrantChildren * 6;
        if (food < cost + state.foodReserve) return "食物不足，或接纳后低于保留线。";
        var before = inventorySnapshot(level, settlement);
        if (consumeFood(level, settlement, cost) != cost) {
            restoreInventory(before);
            return "库存发生变化，未扣除食物，申请仍保留。";
        }
        CommunityStock.invalidate(level, settlement);
        List<CitizenRecord> adults = new ArrayList<>();
        for (int i = 0; i < state.migrantAdults; i++) adults.add(settlement.addMigrant());
        if (adults.size() >= 2) {
            adults.get(0).life().partner = adults.get(1).id(); adults.get(1).life().partner = adults.get(0).id();
            adults.get(0).life().lastBirthDay = adults.get(1).life().lastBirthDay = day;
        }
        for (int i = 0; i < state.migrantChildren; i++) settlement.addChild(adults.isEmpty() ? null : adults.get(0), adults.size() < 2 ? null : adults.get(1));
        if (CommunityContent.crisesEnabled && !adults.isEmpty() && Math.floorMod(settlement.id().hashCode() + (int) day, 3) == 0) {
            adults.get(0).life().sickUntil = day + 3;
            state.event(day, adults.get(0).name() + "出现疾病症状；需供养休息，三日内恢复，暂不加入传播机制。");
        }
        state.migrantAdults = state.migrantChildren = 0; state.offerExpires = -1;
        settlement.refreshHousingAssignments(); state.event(day, "接纳流民家庭，新增消费人口和成年劳力。");
        CommunityLifeService.milestone(state, day, "migrants", "接纳一个外来家庭");
        return "流民家庭已接纳，物资已真实扣除。";
    }
    private static int consumeFood(ServerLevel level, SettlementRecord settlement, int amount) {
        int remaining = amount;
        for (var b : SettlementInventoryService.warehouses(settlement)) for (var at : WarehouseInventoryService.loadedContainers(level, b)) {
            Container container = at.container();
            for (int slot = 0; slot < container.getContainerSize() && remaining > 0; slot++) {
                var stack = container.getItem(slot);
                if (WarehouseCategory.classify(stack) != WarehouseCategory.FOOD) continue;
                int used = Math.min(stack.getCount(), remaining); stack.shrink(used); remaining -= used; container.setChanged();
            }
        }
        return amount - remaining;
    }
    /** Server-side transaction; player authorization is checked by action() before dispatch. */
    public static String barter(ServerLevel level, SettlementRecord settlement, Item payment, int count, Item output, int amount) {
        if (!((payment == Items.OAK_LOG && count == 16 && output == Items.MELON_SEEDS && amount == 8)
                || (payment == Items.OAK_LOG && count == 64 && output == Items.IRON_INGOT && amount == 8))) return "没有该商队报价。";
        var merchant = settlement.merchantState(); var state = settlement.community();
        if (merchant.traderId() == null || level.getEntity(merchant.traderId()) == null
                || CommunityLifeService.day(level) >= merchant.visitEndsDay()) return "商队尚未到达或已经离开。";
        String claim = "barter:" + merchant.traderId() + ":" + net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(output);
        if (state.barterClaims.contains(claim)) return "本次访问的该报价已经成交。";
        if (SettlementInventoryService.countItem(level, settlement, payment) < count) return "易货物资不足。";
        var stack = new ItemStack(output, amount);
        var warehouse = SettlementInventoryService.warehouses(settlement).stream()
                .filter(b -> WarehouseInventoryService.canAccept(level, b, stack))
                .filter(b -> remainingCapacity(level, b, stack) >= amount).findFirst().orElse(null);
        if (warehouse == null) return "公共库存没有足够空间，未扣除物资。";
        var before = inventorySnapshot(level, settlement);
        int remaining = count;
        for (var b : SettlementInventoryService.warehouses(settlement)) {
            remaining -= WarehouseInventoryService.extractItem(level, b, payment, remaining).getCount();
            if (remaining == 0) break;
        }
        if (remaining != 0 || !WarehouseInventoryService.insert(level, warehouse, stack).isEmpty()) {
            restoreInventory(before);
            CommunityStock.invalidate(level, settlement);
            return "交易未能完整支付或收货，库存已恢复，报价仍可使用。";
        }
        CommunityStock.invalidate(level, settlement);
        // Keep at most the two offer claims for the current visit, separate from permanent goals.
        state.barterClaims.removeIf(v -> !v.startsWith("barter:" + merchant.traderId() + ":"));
        state.barterClaims.add(claim);
        state.event(CommunityLifeService.day(level), "完成商队实物交换；公共库存支付与收货已经结算。");
        return "实物交换完成。";
    }
    private static Map<Container, List<ItemStack>> inventorySnapshot(ServerLevel level, SettlementRecord settlement) {
        Map<Container, List<ItemStack>> before = new IdentityHashMap<>();
        for (var b : SettlementInventoryService.warehouses(settlement)) for (var at : WarehouseInventoryService.loadedContainers(level, b)) {
            var container = at.container();
            if (before.containsKey(container)) continue;
            List<ItemStack> slots = new ArrayList<>();
            for (int i = 0; i < container.getContainerSize(); i++) slots.add(container.getItem(i).copy());
            before.put(container, slots);
        }
        return before;
    }
    private static void restoreInventory(Map<Container, List<ItemStack>> before) {
        before.forEach((container, slots) -> {
            for (int i = 0; i < slots.size(); i++) container.setItem(i, slots.get(i).copy());
            container.setChanged();
        });
    }
    private static int remainingCapacity(ServerLevel level, BuildingRecord building, ItemStack stack) {
        int capacity = 0;
        for (var at : WarehouseInventoryService.loadedContainers(level, building)) for (int slot = 0; slot < at.container().getContainerSize(); slot++) {
            var existing = at.container().getItem(slot);
            if (!at.container().canPlaceItem(slot, stack)) continue;
            if (existing.isEmpty()) capacity += Math.min(at.container().getMaxStackSize(stack), stack.getMaxStackSize());
            else if (ItemStack.isSameItemSameComponents(existing, stack)) capacity += Math.max(0,
                    Math.min(existing.getMaxStackSize(), at.container().getMaxStackSize(stack)) - existing.getCount());
        }
        return capacity;
    }
}
