package com.hkin.exiledsettlement.gametest;

import com.hkin.exiledsettlement.ExiledSettlementMod;
import com.hkin.exiledsettlement.decoration.*;
import com.hkin.exiledsettlement.blueprint.*;
import com.hkin.exiledsettlement.building.*;
import com.hkin.exiledsettlement.citizen.*;
import com.hkin.exiledsettlement.road.RoadSpaceData;
import com.hkin.exiledsettlement.settlement.*;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.*;
import java.util.*;

@GameTestHolder(ExiledSettlementMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class DecorationGameTests {
    private static int fixtureIndex;
    private static SettlementRecord record() { return new SettlementRecord(UUID.randomUUID(), "Decoration", new BlockPos(0, 10, 0), UUID.randomUUID()); }
    private static int count(CitizenRecord c, Item item) { return c.inventory().stream().filter(s -> s.is(item)).mapToInt(ItemStack::getCount).sum(); }
    @GameTest(templateNamespace="minecraft", template="bastion/mobs/empty", batch="decorations")
    public static void pastureV4SeparatesOptionalPropsAndPreservesV3(GameTestHelper h) {
        var at = new BlockPos(0, 10, 0);
        var old = BlueprintTemplates.createArea(BuildingType.PASTURE_AREA, at, at.offset(16,0,16), Direction.NORTH, 3);
        var latest = BlueprintTemplates.createArea(BuildingType.PASTURE_AREA, at, at.offset(16,0,16), Direction.NORTH);
        h.assertTrue(old.requirements().get(Items.COMPOSTER)==4 && old.requirements().get(Items.HAY_BLOCK)==4,
                "Legacy pasture props changed");
        h.assertTrue(!latest.requirements().containsKey(Items.COMPOSTER) && !latest.requirements().containsKey(Items.HAY_BLOCK)
                && latest.entrances().equals(old.entrances()), "New pasture requires props or lost gates");
        var finalOld = old.placements().stream().filter(p -> !p.state().is(Blocks.COMPOSTER) && !p.state().is(Blocks.HAY_BLOCK)).toList();
        h.assertTrue(latest.placements().equals(finalOld), "Pasture fences, roads or clearance changed");
        var saved = BlueprintRecord.createArea(BuildingType.PASTURE_AREA, at, at, Direction.NORTH, UUID.randomUUID(), false,
                at, at.offset(16,0,16),3,Map.of());
        h.assertTrue(BlueprintTemplates.create(BlueprintRecord.load(saved.save())).placements().equals(old.placements()),
                "Saved v3 pasture was upgraded on load"); h.succeed();
    }
    @GameTest(templateNamespace="minecraft", template="bastion/mobs/empty", batch="decorations")
    public static void pasturePropsUsePaidAssemblyAndPlanOutsideWorkArea(GameTestHelper h) {
        var f=fixture(h); var s=f.s(); s.setCreativeCoreInstalled(true); s.decorations().families=4;
        var at=s.corePosition(); var pasture=s.addBuilding(BuildingType.PASTURE_AREA,at,Direction.NORTH,
                at.offset(-8,-1,-8),at.offset(8,0,8),1);
        for(int i=0;i<8;i++) DecorationService.tick(h.getLevel(),s);
        h.assertTrue(s.decorations().features.size()==2 && s.decorations().features.values().stream()
                .allMatch(v -> pasture.id().equals(v.parent) && (v.kind.equals("composter") || v.kind.equals("hay"))),
                "Pasture optional props were not automatically planned");
        for(var feature:s.decorations().features.values()) {
            var template=BlueprintTemplates.create(feature.type,feature.anchor,feature.facing,feature.version);
            h.assertTrue(template.placements().stream().noneMatch(p -> p.position().getX()>=pasture.minCorner().getX()
                    && p.position().getX()<=pasture.maxCorner().getX() && p.position().getZ()>=pasture.minCorner().getZ()
                    && p.position().getZ()<=pasture.maxCorner().getZ()), "Props intruded into pasture work area");
        }
        var c=s.citizens().iterator().next(); c.insertIntoInventory(new ItemStack(Items.OAK_LOG,2));
        c.insertIntoInventory(new ItemStack(Items.WHEAT,9));
        DecorationCrafting.craft(h.getLevel(),s,c,Map.of(Items.COMPOSTER,1,Items.HAY_BLOCK,1));
        h.assertTrue(count(c,Items.COMPOSTER)==1 && count(c,Items.HAY_BLOCK)==1 && count(c,Items.WHEAT)==0
                && count(c,Items.OAK_LOG)==0, "Pasture props did not pay wood/wheat");
        s.deactivate(); clean(h,f); h.succeed();
    }
    @GameTest(templateNamespace="minecraft", template="bastion/mobs/empty", batch="decorations")
    public static void oldSaveOptsInAndNewPolicyPersists(GameTestHelper h) {
        var s = record(); var d = s.decorations(); d.enabled = true; d.style = 1; d.density = 2; d.families = 5; d.dailyBudget = 512;
        d.forbiddenChunks.add(new net.minecraft.world.level.ChunkPos(1, 1).toLong()); d.newDay(42); d.reserve(123);
        var tag = s.save(h.getLevel().registryAccess()); var restored = SettlementRecord.load(tag, h.getLevel().registryAccess());
        h.assertTrue(restored.decorations().enabled && restored.decorations().dailyBudget == 512 && restored.decorations().spent == 123
                && restored.decorations().families == 5 && restored.decorations().forbidden(new BlockPos(16, 0, 16)), "Decoration settings lost");
        tag.remove("Decorations"); restored = SettlementRecord.load(tag, h.getLevel().registryAccess());
        h.assertTrue(!restored.decorations().enabled && restored.aliveCitizenCount() == 6, "Legacy world silently decorated or residents changed"); h.succeed();
    }
    @GameTest(templateNamespace="minecraft", template="bastion/mobs/empty", batch="decorations")
    public static void forbiddenFeatureAndCancellationSurviveRestart(GameTestHelper h) {
        var s = record(); var f = new DecorationState.Feature("woodpile", BuildingType.WOODSHED, 2, s.corePosition().east(10), Direction.WEST, UUID.randomUUID(), UUID.randomUUID(), 4);
        f.suppressed = true; f.building = UUID.randomUUID(); s.decorations().features.put("stable-slot", f);
        var loaded = SettlementRecord.load(s.save(h.getLevel().registryAccess()), h.getLevel().registryAccess());
        var l = loaded.decorations().features.get("stable-slot");
        h.assertTrue(l.suppressed && l.blueprint.equals(f.blueprint) && l.building.equals(f.building) && l.parent.equals(f.parent)
                && l.version == 2 && l.facing == Direction.WEST && !DecorationService.allowed(loaded, l), "Removal ownership/version was lost"); h.succeed();
    }
    @GameTest(templateNamespace="minecraft", template="bastion/mobs/empty", batch="decorations")
    public static void workerAssemblesPaidLanternAndDoesNotReplayInputs(GameTestHelper h) {
        var s = record(); var c = s.citizens().iterator().next();
        c.insertIntoInventory(new ItemStack(Items.OAK_LOG, 2)); c.insertIntoInventory(new ItemStack(Items.IRON_INGOT));
        c.insertIntoInventory(new ItemStack(Items.CHARCOAL));
        DecorationCrafting.craft(h.getLevel(), s, c, Map.of(Items.LANTERN, 1));
        h.assertTrue(count(c, Items.LANTERN) == 1 && count(c, Items.IRON_INGOT) == 0 && count(c, Items.IRON_NUGGET) == 1
                && count(c, Items.CHARCOAL) == 0 && count(c, Items.TORCH) == 3, "Assembly did not pay exact inputs/keep leftovers");
        var before = c.save(h.getLevel().registryAccess()); DecorationCrafting.craft(h.getLevel(), s, c, Map.of(Items.LANTERN, 1));
        h.assertTrue(before.equals(c.save(h.getLevel().registryAccess())), "Demand was replayed after target was met"); h.succeed();
    }
    @GameTest(templateNamespace="minecraft", template="bastion/mobs/empty", batch="decorations")
    public static void shortageAndFullBackpackNeverCreateFreeOutput(GameTestHelper h) {
        var s = record(); var c = s.citizens().iterator().next(); c.insertIntoInventory(new ItemStack(Items.OAK_LOG, 2));
        DecorationCrafting.craft(h.getLevel(), s, c, Map.of(Items.LANTERN, 1)); h.assertTrue(count(c, Items.LANTERN) == 0, "Missing iron/fuel created a lamp");
        var full = new CitizenRecord(UUID.randomUUID(), "Full", s.corePosition());
        for (int i = 0; i < CitizenRecord.INVENTORY_SIZE; i++) full.insertIntoInventory(new ItemStack(Items.COBBLESTONE, 64));
        DecorationCrafting.craft(h.getLevel(), s, full, Map.of(Items.COBBLESTONE_SLAB, 1));
        h.assertTrue(count(full, Items.COBBLESTONE) == 36 * 64 && count(full, Items.COBBLESTONE_SLAB) == 0, "Full bag lost raw material"); h.succeed();
    }
    @GameTest(templateNamespace="minecraft", template="bastion/mobs/empty", batch="decorations")
    public static void allComponentRotationsAndOldSnapshotsStayIndependent(GameTestHelper h) {
        for (var e : DecorationCatalog.ENTRIES.values()) for (var dir : List.of(Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST)) {
            var t = BlueprintTemplates.create(e.type(), new BlockPos(0, 10, 0), dir, e.version());
            h.assertTrue(!t.placements().isEmpty() && !t.entrances().isEmpty() && !t.requirements().isEmpty(), "Missing component " + e.id());
            h.assertTrue(t.placements().stream().map(BlueprintPlacement::position).distinct().count() == t.placements().size(), "Duplicate rotated cells " + e.id());
        }
        var old = BlueprintTemplates.create(BuildingType.WOODSHED, new BlockPos(0, 10, 0), Direction.SOUTH, 1);
        var current = BlueprintTemplates.create(BuildingType.WOODSHED, new BlockPos(0, 10, 0), Direction.SOUTH, 2);
        h.assertTrue(old.placements().size() > current.placements().size() && BlueprintTemplates.currentTemplateVersion(BuildingType.WOODSHED) == 1,
                "Automatic component overwrote the old/manual shed version"); h.succeed();
    }
    @GameTest(templateNamespace="minecraft", template="bastion/mobs/empty", batch="decorations")
    public static void budgetDayAndEmergencyControlsAreBounded(GameTestHelper h) {
        var s = record(); var d = s.decorations(); d.enabled = true; d.dailyBudget = 64; d.newDay(1);
        h.assertTrue(d.reserve(64) && !d.reserve(1), "Budget exceeded"); d.newDay(1); h.assertTrue(d.spent == 64, "Same-day refill");
        d.newDay(2); h.assertTrue(d.spent == 0 && d.reserve(12), "Day boundary did not refill");
        var f = new DecorationState.Feature("lamp_small", BuildingType.LAMP_SMALL, 1, s.corePosition(), Direction.SOUTH, UUID.randomUUID(), null, 1);
        s.community().policy = "emergency"; h.assertTrue(!DecorationService.allowed(s, f), "Emergency did not pause decorative work");
        s.community().policy = "harvest"; h.assertTrue(!DecorationService.allowed(s, f), "Harvest lost worker priority");
        s.community().policy = "normal"; d.families = 14; h.assertTrue(!DecorationService.allowed(s, f), "Disabled family kept working"); h.succeed();
    }
    private record Fixture(SettlementRecord s, List<net.minecraft.world.level.ChunkPos> chunks) { }
    private static Fixture fixture(GameTestHelper h) {
        var level = h.getLevel(); var core = h.absolutePos(new BlockPos(60000 + fixtureIndex++ * 256, 3, 0));
        var chunks = new ArrayList<net.minecraft.world.level.ChunkPos>();
        for (int x = (core.getX() - 32) >> 4; x <= (core.getX() + 32) >> 4; x++)
            for (int z = (core.getZ() - 32) >> 4; z <= (core.getZ() + 32) >> 4; z++) { level.setChunkForced(x,z,true); chunks.add(new net.minecraft.world.level.ChunkPos(x,z)); }
        for (var p : BlockPos.betweenClosed(core.offset(-30,-1,-30), core.offset(30,-1,30))) level.setBlock(p, Blocks.DIRT.defaultBlockState(), 2);
        var s = SettlementSavedData.get(level).createOrRecover(core, UUID.randomUUID(), "Decoration fixture").value();
        s.decorations().enabled = true; return new Fixture(s, chunks);
    }
    private static void clean(GameTestHelper h, Fixture f) { f.s().decorations().enabled = false; f.chunks().forEach(c -> h.getLevel().setChunkForced(c.x,c.z,false)); }
    @GameTest(templateNamespace="minecraft", template="bastion/mobs/empty", batch="decorations")
    public static void plannerReservesOnlyOnceAndRejectsUserBlocksRoadsAndForbid(GameTestHelper h) {
        var f = fixture(h); var s = f.s; var at = s.corePosition().east(12);
        h.assertTrue(DecorationService.plan(h.getLevel(), s, "slot", "lamp_small", at, Direction.SOUTH, null, 1), "Valid lamp plan rejected");
        h.assertTrue(!DecorationService.plan(h.getLevel(), s, "slot", "lamp_small", at, Direction.SOUTH, null, 1) && s.blueprints().size() == 1, "Duplicate plan");
        var other = s.corePosition().west(12); var t = BlueprintTemplates.create(BuildingType.LAMP_SMALL, other, Direction.SOUTH, 1);
        h.getLevel().setBlock(other, Blocks.CHEST.defaultBlockState(), 3);
        h.assertTrue(!DecorationService.safeSite(h.getLevel(), s, t), "Player chest was adopted/overwritten"); h.getLevel().setBlock(other, Blocks.AIR.defaultBlockState(), 3);
        RoadSpaceData.get(h.getLevel()).addAll(List.of(other.below()));
        h.assertTrue(!DecorationService.safeSite(h.getLevel(), s, t), "Road was obstructed");
        var forbidden = s.corePosition().north(12);
        s.decorations().forbiddenChunks.add(new net.minecraft.world.level.ChunkPos(forbidden).toLong());
        h.assertTrue(!DecorationService.plan(h.getLevel(), s, "forbid", "lamp_small", forbidden, Direction.SOUTH, null, 1), "Forbidden region was used"); clean(h,f); h.succeed();
    }
    @GameTest(templateNamespace="minecraft", template="bastion/mobs/empty", batch="decorations")
    public static void visitorCannotChangeDecorationPolicy(GameTestHelper h) {
        var f = fixture(h); var visitor = h.makeMockServerPlayerInLevel(); visitor.moveTo(f.s.corePosition().getCenter());
        var before = f.s.decorations().save(); CommunityManagement.action(visitor, f.s.corePosition(), "decorations");
        h.assertTrue(before.equals(f.s.decorations().save()), "Visitor changed decoration settings"); clean(h,f); h.succeed();
    }
    @GameTest(templateNamespace="minecraft", template="bastion/mobs/empty", batch="decorations")
    public static void plannerCoversRoadHousingProductionAndPublicSpace(GameTestHelper h) {
        for (int family : List.of(1,2,4,8)) {
            var f = fixture(h); var s = f.s(); s.setCreativeCoreInstalled(true); s.decorations().families = family;
            if (family == 1) {
                var road = s.corePosition().east(12).below();
                road = road.offset(Math.floorMod(-road.getX(), s.decorations().spacing()), 0, 0);
                RoadSpaceData.get(h.getLevel()).addAll(List.of(road));
                s.decorations().roadCursor = RoadSpaceData.get(h.getLevel()).decorationPositions().indexOf(road);
            } else if (family == 2 || family == 4) {
                var p = s.corePosition().east(8);
                s.addBuilding(family == 2 ? BuildingType.HOUSE : BuildingType.SAWMILL, p, Direction.SOUTH,
                        p.offset(-2,-1,-2),p.offset(2,3,2),1);
            }
            for (int n=0;n<8;n++) DecorationService.tick(h.getLevel(),s);
            var features = s.decorations().features.values();
            h.assertTrue(!features.isEmpty() && features.stream().allMatch(v -> v.family==family),"No automatic scene plans for family "+family);
            if (family == 1) h.assertTrue(features.stream().map(v->v.facing).distinct().count()>=2,"Road lamps were restricted to one side");
            if (family == 2 || family == 4) h.assertTrue(features.size()==3 && features.stream().allMatch(v->v.parent!=null),"Scene components incomplete for family "+family);
            h.assertTrue(s.blueprints().size()<=DecorationCatalog.PENDING_LIMIT,"Planner exceeded pending work budget");
            s.deactivate(); clean(h,f);
        } h.succeed();
    }
    @GameTest(templateNamespace="minecraft", template="bastion/mobs/empty", batch="decorations")
    public static void reserveShortagePausesExistingWorkAndPublicBreakNeedsPermission(GameTestHelper h) {
        var f=fixture(h); var s=f.s(); var at=s.corePosition().east(10);
        h.assertTrue(DecorationService.plan(h.getLevel(),s,"reserve-lamp","lamp_small",at,Direction.SOUTH,null,1),"Plan failed");
        var feature=s.decorations().features.get("reserve-lamp"); DecorationService.tick(h.getLevel(),s);
        h.assertTrue(!DecorationService.allowed(s,feature),"Empty survival stock did not pause existing decoration");
        h.getLevel().setBlock(at,Blocks.SPRUCE_FENCE.defaultBlockState(),3);
        var visitor=h.makeMockServerPlayerInLevel();
        var event=new net.neoforged.neoforge.event.level.BlockEvent.BreakEvent(h.getLevel(),at,h.getLevel().getBlockState(at),visitor);
        new com.hkin.exiledsettlement.event.SettlementEvents().onBlockBreak(event);
        h.assertTrue(event.isCanceled() && !feature.suppressed,"Visitor broke or suppressed a managed vanilla component");
        s.deactivate();clean(h,f);h.succeed();
    }
    @GameTest(templateNamespace="minecraft", template="bastion/mobs/empty", batch="decorations_physical", timeoutTicks=1800)
    public static void workerWalksToStockAssemblesAndBuildsAutomaticLamp(GameTestHelper h) {
        var f = fixture(h); var s = f.s; var level = h.getLevel(); var data = SettlementSavedData.get(level);
        s.decorations().families = 1; s.community().foodReserve = 0; s.community().fuelReserve = 0;
        var residents = new ArrayList<>(s.citizens()); var worker = residents.getFirst();
        for (int i=1; i<residents.size(); i++) residents.get(i).markDead(s.corePosition());
        worker.assignProfession(CitizenProfession.WORKER, null);
        worker.insertIntoInventory(new ItemStack(Items.STONE_AXE)); worker.insertIntoInventory(new ItemStack(Items.STONE_PICKAXE));
        var storage = s.addBuilding(BuildingType.WAGON, s.corePosition().west(8));
        level.setBlock(storage.position(), Blocks.BARREL.defaultBlockState(), 3);
        storage.updateFunction(true, BuildingStatus.OPERATIONAL, 27, List.of(storage.position()));
        var inventory = (net.minecraft.world.Container) level.getBlockEntity(storage.position());
        inventory.setItem(0,new ItemStack(Items.OAK_LOG,64)); inventory.setItem(1,new ItemStack(Items.OAK_LOG,32));
        inventory.setItem(2,new ItemStack(Items.IRON_INGOT,4)); inventory.setItem(3,new ItemStack(Items.CHARCOAL,32));
        inventory.setItem(4,new ItemStack(Items.STONE_BRICKS,8)); inventory.setItem(5,new ItemStack(Items.BREAD,64));
        var at = s.corePosition().east(10);
        h.assertTrue(DecorationService.plan(level,s,"physical-lamp","lamp_small",at,Direction.SOUTH,null,1),"Cannot plan physical lamp");
        var feature = s.decorations().features.get("physical-lamp");
        level.setDayTime(6000); CitizenProjectionService.ensureSettlementCitizens(level,s);
        boolean[] visited = {false}; long start = level.getGameTime();
        h.startSequence().thenWaitUntil(() -> h.assertTrue(CitizenProjectionService.findProjection(level,worker)!=null,"Waiting for worker projection"))
                .thenWaitUntil(() -> {
                    var entity = CitizenProjectionService.findProjection(level,worker);
                    level.setDayTime(6000);
                    if (entity != null) {
                        worker.updateProjection(entity.getUUID(),entity.blockPosition());
                        if (WarehouseInventoryService.canInteract(level,storage,entity)) visited[0] = true;
                        if ((level.getGameTime()-start)%8==0) CitizenWorkService.tickSettlement(level,data,s);
                    }
                    h.assertTrue(s.blueprint(feature.blueprint)==null && level.getBlockState(at.above(2)).is(Blocks.LANTERN),
                            "Automatic lamp incomplete: "+worker.workState()+" "+worker.workFailure()+" @ "+worker.lastPosition());
                }).thenExecute(() -> {
                    h.assertTrue(visited[0] && WarehouseInventoryService.countItem(level,storage,Items.IRON_INGOT)<4
                            && WarehouseInventoryService.countItem(level,storage,Items.OAK_LOG)<96,"Construction bypassed movement or charged no inputs");
                    DecorationService.tick(level,s); h.assertTrue(feature.building!=null,"Finished component ownership not reconciled");
                    BuildingRepairService.recordExplosionDamage(level,List.of(at.above(2)));
                    level.setBlock(at.above(2),Blocks.AIR.defaultBlockState(),3);
                }).thenWaitUntil(() -> {
                    level.setDayTime(6000);
                    var entity=CitizenProjectionService.findProjection(level,worker);
                    if(entity!=null && (level.getGameTime()-start)%8==0) {
                        worker.updateProjection(entity.getUUID(),entity.blockPosition()); CitizenWorkService.tickSettlement(level,data,s);
                    }
                    h.assertTrue(level.getBlockState(at.above(2)).is(Blocks.LANTERN),"Paid component repair incomplete: "+worker.workState()+" "+worker.workFailure());
                }).thenExecute(() -> {
                    DecorationService.notePlayerRemoval(level,at.above(2));
                }).thenIdle(2).thenExecute(() -> {
                    DecorationService.flushPlayerRemovals(level);
                    h.assertTrue(!feature.suppressed,"Canceled/protected break suppressed intact furniture");
                    level.setBlock(at.above(2),Blocks.AIR.defaultBlockState(),3);
                    DecorationService.playerRemoved(level,at.above(2)); DecorationService.tick(level,s);
                    h.assertTrue(feature.suppressed && !DecorationService.allowed(s,feature),"Player removal was automatically repaired");
                    var entity=CitizenProjectionService.findProjection(level,worker); if(entity!=null) entity.discard(); s.deactivate(); clean(h,f);
                }).thenSucceed();
    }
}
