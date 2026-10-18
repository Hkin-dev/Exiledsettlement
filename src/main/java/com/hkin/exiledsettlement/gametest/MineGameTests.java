package com.hkin.exiledsettlement.gametest;

import com.hkin.exiledsettlement.blueprint.*;
import com.hkin.exiledsettlement.building.*;
import com.hkin.exiledsettlement.citizen.*;
import com.hkin.exiledsettlement.registry.ModBlocks;
import com.hkin.exiledsettlement.settlement.*;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.neoforged.neoforge.gametest.*;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import java.util.*;

@net.neoforged.fml.common.EventBusSubscriber(modid="exiledsettlement",bus=net.neoforged.fml.common.EventBusSubscriber.Bus.MOD)
@GameTestHolder("exiledsettlement_mine")
@PrefixGameTestTemplate(false)
public final class MineGameTests {
    @net.neoforged.bus.api.SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        if("mine".equals(System.getProperty("exiledsettlement.testSuite")))event.register(MineGameTests.class);
    }
    private static int serial;
    private static final List<Item> ADDED_FINDS = List.of(Items.GRANITE, Items.DIORITE,
            Items.ANDESITE, Items.TUFF, Items.CALCITE, Items.DRIPSTONE_BLOCK, Items.BASALT,
            Items.BLACKSTONE, Items.SAND, Items.CLAY_BALL, Items.FLINT, Items.AMETHYST_SHARD,
            Items.EMERALD, Items.QUARTZ, Items.STONE, Items.DEEPSLATE, Items.OBSIDIAN,
            Items.POINTED_DRIPSTONE, Items.AMETHYST_BLOCK, Items.RED_SAND, Items.SANDSTONE,
            Items.RED_SANDSTONE);
    private record Fixture(GameTestHelper h,SettlementRecord s,BlockPos at) {
        void clean(){s.deactivate();for(var c:s.citizens()){var e=CitizenProjectionService.findProjection(h.getLevel(),c);if(e!=null)e.discard();}}
    }
    private static Fixture fixture(GameTestHelper h) {
        var l=h.getLevel();var at=new BlockPos(2000+(serial++)*192,100,2000);
        for(int x=-3;x<=3;x++)for(int z=-3;z<=3;z++)l.setChunkForced((at.getX()>>4)+x,(at.getZ()>>4)+z,true);
        for(var p:BlockPos.betweenClosed(at.offset(-28,-19,-24),at.offset(28,12,24)))
            l.setBlock(p,p.getY()<at.getY()?Blocks.STONE.defaultBlockState():Blocks.AIR.defaultBlockState(),2);
        var core=at.offset(-16,0,16);l.setBlock(core,ModBlocks.SETTLEMENT_CORE.get().defaultBlockState(),3);
        var s=SettlementSavedData.get(l).createOrRecover(core,UUID.randomUUID(),"New independent mine acceptance").value();
        s.upgradeLevel();s.upgradeLevel();s.terrain().enabled=false;s.autoRoads().enabled=false;s.decorations().enabled=false;
        l.setDayTime(2000);return new Fixture(h,s,at);
    }
    private static BuildingRecord built(Fixture f,BuildingBlueprintTemplate t) {
        var l=f.h().getLevel();for(var p:t.placements())l.setBlock(p.position(),p.state(),2|16);
        BlueprintBlockStateResolver.refreshCompleted(l,t);
        var b=f.s().addBuilding(t.type(),t.anchor(),t.facing(),t.minCorner(),t.maxCorner(),t.transitionMargin(),BlueprintTemplates.currentTemplateVersion(t.type()));
        BuildingFunctionService.validateSingle(l,SettlementSavedData.get(l),f.s(),b);return b;
    }
    @GameTest(templateNamespace="exiledsettlement_mine",template="empty",timeoutTicks=100)
    public static void registeredBlueprintReservesUndergroundAndRejectsFluid(GameTestHelper h) {
        var f=fixture(h);var l=h.getLevel();var t=BlueprintTemplates.create(BuildingType.MINE,f.at(),Direction.NORTH);
        var bottom=MineBlueprint.workPositions(f.at(),Direction.NORTH).getFirst();
        h.assertTrue(t.placementLayout().air().contains(bottom),"Underground hall is not protected air");
        l.setBlock(bottom,Blocks.WATER.defaultBlockState(),2);
        int before=f.s().blueprints().size();
        var denied=BlueprintService.placeBlueprint(l,f.s().ownerId(),BuildingType.MINE,f.at(),Direction.NORTH,false,null);
        h.assertTrue(!denied.success() && f.s().blueprints().size()==before,"Flooded mine accepted or denial mutated work orders");
        l.setBlock(bottom,Blocks.STONE.defaultBlockState(),2);
        var result=BlueprintService.placeBlueprint(l,f.s().ownerId(),BuildingType.MINE,f.at(),Direction.NORTH,false,null);
        h.assertTrue(result.success(),"Mine placement failed: "+result.reason());
        h.assertTrue(f.s().buildings().stream().noneMatch(b->b.type()==BuildingType.MINE),"Unfinished mine became productive");
        h.assertTrue(!com.hkin.exiledsettlement.terrain.TerrainService.needsFoundation(f.s(),result.value()),"Surface foundation would fill shaft");
        var loaded=BlueprintRecord.load(result.value().save());
        h.assertTrue(BlueprintTemplates.create(loaded).minCorner().getY()==f.at().getY()-17,"Underground geometry lost on reload");
        f.clean();h.succeed();
    }
    @GameTest(templateNamespace="exiledsettlement_mine",template="empty",timeoutTicks=100)
    public static void mixedFindsBulkStorageAndUndergroundReload(GameTestHelper h) {
        var f=fixture(h);var l=h.getLevel();
        for(var direction:Direction.Plane.HORIZONTAL) {
            var t=BlueprintTemplates.create(BuildingType.MINE,f.at(),direction);
            h.assertTrue(new HashSet<>(MineBlueprint.workPositions(f.at(),direction)).size()==10,"Mine lacks ten distinct work positions");
            for(var at:MineBlueprint.workPositions(f.at(),direction)) {
                h.assertTrue(t.placementAt(at).state().isAir() && t.placementAt(at.above()).state().isAir(),"Workplace blocked");
                h.assertTrue(!t.placementAt(at.below()).state().isAir(),"Workplace has no floor");
            }
            for(var entrance:t.entrances())h.assertTrue(t.placementAt(entrance.position()).state().isAir(),"Entrance blocked");
        }
        var b=built(f,BlueprintTemplates.create(BuildingType.MINE,f.at(),Direction.NORTH));
        h.assertTrue(b.operational() && b.workerCapacity()==10,"Mine lacks ten real jobs");
        h.assertTrue(!BuildingManagementActions.production(f.s(),b,net.minecraft.resources.ResourceLocation.withDefaultNamespace("raw_iron")),"Mine incorrectly allows targeted iron production");
        var random=net.minecraft.util.RandomSource.create(19281L);var kinds=new HashSet<Item>();var totals=new HashSet<Integer>();
        var frequencies=new HashMap<Item,Integer>();
        for(int i=0;i<20000;i++) {
            var batch=MineProductionService.roll(random);int total=batch.stream().mapToInt(ItemStack::getCount).sum();
            h.assertTrue(total>=3 && total<=12 && batch.size()<=3,"Batch exceeds existing cargo capacity allowance");
            h.assertTrue(batch.stream().map(ItemStack::getItem).distinct().count()==batch.size(),"Duplicate finds were not merged");
            totals.add(total);
            for(var stack:batch) {
                h.assertTrue(MineProductionService.isOutput(stack),"Produced cargo is not recognized for mine delivery");
                kinds.add(stack.getItem());frequencies.merge(stack.getItem(),stack.getCount(),Integer::sum);
            }
        }
        var expected=new HashSet<>(ADDED_FINDS);
        expected.addAll(List.of(Items.COBBLESTONE,Items.COBBLED_DEEPSLATE,Items.GRAVEL,Items.COAL,
                Items.RAW_COPPER,Items.RAW_IRON,Items.RAW_GOLD,Items.REDSTONE,Items.LAPIS_LAZULI,Items.DIAMOND));
        h.assertTrue(kinds.equals(expected) && totals.size()>3,"Mining does not cover all 32 variable finds");
        for(var excluded:List.of(Items.NETHERRACK,Items.SOUL_SAND,Items.SOUL_SOIL,Items.GLOWSTONE_DUST,Items.ANCIENT_DEBRIS))
            h.assertTrue(!MineProductionService.isOutput(new ItemStack(excluded)),"Excluded Nether find was enabled: "+excluded);
        // Broad statistical bounds guard accidental dilution of existing fuel/metal supply.
        h.assertTrue(frequencies.get(Items.COAL)>17500 && frequencies.get(Items.COAL)<21000,"Coal supply rate changed");
        h.assertTrue(frequencies.get(Items.RAW_IRON)>8000 && frequencies.get(Items.RAW_IRON)<10000,"Iron supply rate changed");
        h.assertTrue(frequencies.get(Items.DIAMOND)>330 && frequencies.get(Items.DIAMOND)<570,"Diamond reduction is outside expected range");
        h.assertTrue(frequencies.get(Items.EMERALD)>330 && frequencies.get(Items.EMERALD)<570,"Emerald reduction is outside expected range");
        h.assertTrue(frequencies.get(Items.RAW_GOLD)>1100 && frequencies.get(Items.RAW_GOLD)<1600,"Gold reduction is outside expected range");
        h.assertTrue(frequencies.get(Items.AMETHYST_SHARD)>1100 && frequencies.get(Items.AMETHYST_SHARD)<1600,"Amethyst reduction is outside expected range");
        for(var rare:List.of(Items.OBSIDIAN,Items.AMETHYST_BLOCK))
            h.assertTrue(frequencies.get(rare)>160 && frequencies.get(rare)<290,"Rare block reduction is outside expected range: "+rare);
        h.assertTrue(!MineProductionService.isOutput(new ItemStack(Items.BREAD))
                && !MineProductionService.isOutput(new ItemStack(Items.IRON_PICKAXE)),"Mine cargo includes supplies");
        for(var item:ADDED_FINDS)WarehouseInventoryService.insert(l,b,new ItemStack(item,2));
        var expandedCopy=SettlementRecord.load(f.s().save(l.registryAccess()),l.registryAccess());
        for(var item:ADDED_FINDS) {
            h.assertTrue(WarehouseInventoryService.summarize(l,expandedCopy.building(b.id())).getOrDefault(item,0)==2,"New find lost on save: "+item);
            h.assertTrue(WarehouseInventoryService.extractItem(l,b,item,2).getCount()==2,"New find cannot be withdrawn: "+item);
        }
        com.hkin.exiledsettlement.ExiledSettlementMod.LOGGER.info("[mine-diversity] kinds={} batches=20000 quantities={}",kinds.size(),frequencies);
        WarehouseInventoryService.insert(l,b,new ItemStack(Items.RAW_COPPER,10000));
        h.assertTrue(b.workerTarget()==10 || b.setWorkerTarget(10,b.configurationRevision()),"Cannot request ten miners");
        h.assertTrue(!b.setWorkerTarget(11,b.configurationRevision()),"Mine accepts eleven miners");
        var copy=SettlementRecord.load(f.s().save(l.registryAccess()),l.registryAccess());
        h.assertTrue(copy.building(b.id()).productionId().equals(b.productionId()),"Output mode lost on save");
        h.assertTrue(WarehouseInventoryService.stock(l,copy.building(b.id())).quantity()==10000,"Mine cargo lost on save");
        h.assertTrue(WarehouseInventoryService.totalSlots(l,b)==200000,"Mine lacks bulk storage");
        var c=f.s().citizens().iterator().next();
        for(var other:f.s().citizens())if(other!=c)other.markDead(f.at());
        var storage=MineBlueprint.storagePosition(f.at(),Direction.NORTH);
        c.updateProjection(null,storage);CitizenProjectionService.ensureSettlementCitizens(l,f.s());
        var overflow=WarehouseInventoryService.insert(l,b,new ItemStack(Items.COBBLESTONE,190010));
        h.assertTrue(overflow.getCount()==10 && WarehouseInventoryService.stock(l,b).quantity()==200000,"Bulk limit lost or duplicated items");
        export(f);
        h.startSequence().thenWaitUntil(()->{
            CitizenProjectionService.ensureSettlementCitizens(l,f.s());
            h.assertTrue(CitizenProjectionService.findProjection(l,c)!=null,"Waiting for fresh chunk entity loading");
        }).thenExecute(()->{
            var e=CitizenProjectionService.findProjection(l,c);
            h.assertTrue(Math.abs(e.getY()-storage.getY())<0.1,"Underground saved resident lifted to surface: "+e.blockPosition());
            h.assertTrue(WarehouseInventoryService.canInteract(l,b,e),"Ore cellar is not usable");f.clean();
        }).thenSucceed();
    }
    @GameTest(batch="mine_rest",templateNamespace="exiledsettlement_mine",template="empty",timeoutTicks=8000)
    public static void minerRestHaulExcludesWorkersAndWorkHours(GameTestHelper h) {
        var f=fixture(h);var l=h.getLevel();var data=SettlementSavedData.get(l);
        long started=System.nanoTime(),startTick=l.getGameTime();
        var mine=built(f,BlueprintTemplates.create(BuildingType.MINE,f.at(),Direction.NORTH));
        var warehouse=built(f,BlueprintTemplates.create(BuildingType.WAREHOUSE,f.at().offset(16,0,12),Direction.WEST));
        WarehouseInventoryService.insert(l,mine,new ItemStack(Items.RAW_IRON,48));
        for(var item:ADDED_FINDS)WarehouseInventoryService.insert(l,mine,new ItemStack(item,2));
        var citizens=new ArrayList<>(f.s().citizens());var c=citizens.get(0);var worker=citizens.get(1);
        for(var other:citizens)if(other!=c && other!=worker)other.markDead(f.at());
        c.setManualAssignment(CitizenProfession.MINER,mine.id());c.assignProfession(CitizenProfession.MINER,mine.id());
        worker.setManualAssignment(CitizenProfession.WORKER,null);worker.assignProfession(CitizenProfession.WORKER,null);
        long restTime=12500L+CitizenWorkService.scheduleOffset(c);
        h.assertTrue(CitizenWorkService.schedulePhase(restTime,c)==CitizenWorkService.SchedulePhase.REST,"Fixture did not select this miner's rest period");
        for(var resident:List.of(c,worker)) {
            resident.insertIntoInventory(new ItemStack(Items.BREAD,16));resident.insertIntoInventory(new ItemStack(Items.IRON_AXE));
            resident.insertIntoInventory(new ItemStack(Items.IRON_PICKAXE));resident.insertIntoInventory(new ItemStack(Items.IRON_SHOVEL));
            resident.updateProjection(null,f.at().offset(-8,0,resident==c?7:8));
        }
        // An old, uncollected worker order must also be revoked after the upgrade.
        worker.startContainerHaul(mine.id(),warehouse.id());
        CitizenProjectionService.ensureSettlementCitizens(l,f.s());data.touch();
        boolean[] down={false},cargo={false},rest={false};
        h.onEachTick(()->{
            l.setDayTime(rest[0]?restTime:2000);var e=CitizenProjectionService.findProjection(l,c);if(e==null)return;
            h.assertTrue(!worker.hasInventoryItem(v->v.is(Items.RAW_IRON)),"Worker stole mine haul job");
            if(!rest[0])h.assertTrue(WarehouseInventoryService.summarize(l,warehouse).getOrDefault(Items.RAW_IRON,0)==0,"Miner exported during working hours");
            down[0]|=e.getY()<f.at().getY()-13;
            if(rest[0] && mine.id().equals(c.sourceBuildingId()) && c.hasInventoryItem(v->v.is(Items.RAW_IRON))){h.assertTrue(down[0],"Miner took stored ore without descending");cargo[0]=true;}
            if(l.getGameTime()%400==0)com.hkin.exiledsettlement.ExiledSettlementMod.LOGGER.info("[mine-rest] rest={} down={} cargo={} state={} failure={} pos={}",rest[0],down[0],cargo[0],c.workState(),c.workFailure(),e.blockPosition());
        });
        h.startSequence().thenIdle(400).thenExecute(()->{
            h.assertTrue(!mine.id().equals(worker.sourceBuildingId()),"Worker still owns old mine collection order");
            rest[0]=true;l.setDayTime(restTime);
        }).thenWaitUntil(()->h.assertTrue(cargo[0] && WarehouseInventoryService.summarize(l,warehouse).getOrDefault(Items.RAW_IRON,0)>=48,"Waiting for miner rest-time collection and delivery"))
                .thenWaitUntil(()->{
                    for(var item:ADDED_FINDS)h.assertTrue(WarehouseInventoryService.summarize(l,warehouse).getOrDefault(item,0)>=2,"Waiting for new find delivery: "+item);
                }).thenExecute(()->{
                    h.assertTrue(WarehouseInventoryService.stock(l,mine).quantity()==0,"Stored finds were copied or left behind");
                    com.hkin.exiledsettlement.ExiledSettlementMod.LOGGER.info("MINE_VARIETY_HAUL_OK kinds={} ticks={} wallSeconds={}",ADDED_FINDS.size(),l.getGameTime()-startTick,(System.nanoTime()-started)/1e9);
                    f.clean();
                }).thenSucceed();
    }

    @GameTest(templateNamespace="exiledsettlement_mine",template="empty",timeoutTicks=24000)
    public static void realWorkersExcavateAndBuildMineBeforeJobsOpen(GameTestHelper h) {
        var f=fixture(h);var l=h.getLevel();var data=SettlementSavedData.get(l);
        var warehouse=built(f,BlueprintTemplates.create(BuildingType.WAREHOUSE,f.at().offset(16,0,12),Direction.WEST));
        var template=BlueprintTemplates.create(BuildingType.MINE,f.at(),Direction.SOUTH);
        var materials=new HashMap<Item,Integer>();
        for(var p:template.placements())if(p.material()!=null)materials.merge(p.material(),1,Integer::sum);
        materials.forEach((item,n)->WarehouseInventoryService.insert(l,warehouse,new ItemStack(item,n+64)));
        for(var item:List.of(Items.DIAMOND_PICKAXE,Items.DIAMOND_AXE,Items.DIAMOND_SHOVEL))WarehouseInventoryService.insert(l,warehouse,new ItemStack(item,8));
        WarehouseInventoryService.insert(l,warehouse,new ItemStack(Items.BREAD,128));
        var placed=BlueprintService.placeBlueprint(l,f.s().ownerId(),BuildingType.MINE,f.at(),Direction.SOUTH,false,null);
        h.assertTrue(placed.success(),"Mine construction not accepted: "+placed.reason());
        int count=0;
        for(var c:f.s().citizens()) {
            if(count++>=2){c.markDead(f.at());continue;}
            c.setManualAssignment(CitizenProfession.WORKER,null);c.assignProfession(CitizenProfession.WORKER,null);
            c.insertIntoInventory(new ItemStack(Items.BREAD,16));c.updateProjection(null,f.at().offset(-10,0,5+count));
        }
        CitizenProjectionService.ensureSettlementCitizens(l,f.s());data.touch();
        h.onEachTick(()->{
            l.setDayTime(2000);
            var registered=f.s().buildings().stream().filter(b->b.type()==BuildingType.MINE).findFirst().orElse(null);
            if(f.s().blueprint(placed.value().id())!=null)h.assertTrue(registered==null,"Mine opened before construction completed");
            if(l.getGameTime()%1000==0)for(var c:f.s().citizens())if(c.alive())com.hkin.exiledsettlement.ExiledSettlementMod.LOGGER.info("[mine-construction] citizen={} state={} failure={} pos={} target={} jobs={}",c.id(),c.workState(),c.workFailure(),c.lastPosition(),c.targetPosition(),f.s().blueprints().size());
        });
        h.startSequence().thenWaitUntil(()->h.assertTrue(f.s().buildings().stream().anyMatch(b->b.type()==BuildingType.MINE && b.operational()),"Waiting for complete real mine construction"))
                .thenExecute(()->{
                    for(var p:template.placements())h.assertTrue(BlueprintService.placementSatisfied(l,template,p),"Completed mine differs at "+p.position());
                    h.assertTrue(WarehouseInventoryService.stock(l,warehouse).quantity()>0,"Material ledger unexpectedly empty");f.clean();
                }).thenSucceed();
    }
    private static void export(Fixture f) {
        var t=BlueprintTemplates.create(BuildingType.MINE,f.at(),Direction.SOUTH);
        var rows=new com.google.gson.JsonArray();
        for(var p:t.placements()) {
            var row=new com.google.gson.JsonObject();var pos=new com.google.gson.JsonArray();
            pos.add(p.position().getX()-f.at().getX());pos.add(p.position().getY()-f.at().getY());pos.add(p.position().getZ()-f.at().getZ());
            row.add("position",pos);row.addProperty("state",p.state().toString().replace("Block{","").replace("}",""));rows.add(row);
        }
        try {java.nio.file.Files.writeString(java.nio.file.Path.of("mine-blocks.json"),rows.toString());}
        catch(java.io.IOException e){throw new IllegalStateException(e);}
    }
    @GameTest(templateNamespace="exiledsettlement_mine",template="empty",timeoutTicks=1600)
    public static void fullStorageAndMaintenancePauseRealMining(GameTestHelper h) {
        var f=fixture(h);var l=h.getLevel();
        var mine=built(f,BlueprintTemplates.create(BuildingType.MINE,f.at(),Direction.EAST));
        WarehouseInventoryService.insert(l,mine,new ItemStack(Items.COBBLESTONE,200000));
        var c=f.s().citizens().iterator().next();for(var other:f.s().citizens())if(other!=c)other.markDead(f.at());
        c.setManualAssignment(CitizenProfession.MINER,mine.id());c.assignProfession(CitizenProfession.MINER,mine.id());
        c.insertIntoInventory(new ItemStack(Items.BREAD,16));c.insertIntoInventory(new ItemStack(Items.IRON_PICKAXE));
        c.updateProjection(null,MineBlueprint.workPositions(f.at(),Direction.EAST).getFirst());
        h.onEachTick(()->l.setDayTime(2000));
        h.startSequence().thenIdle(400).thenExecute(()->{
            var template=BlueprintTemplates.create(BuildingType.MINE,f.at(),Direction.EAST);
            for(var p:template.placements())if(p.state().isAir() && p.position().getY()<=f.at().getY()+2
                    && l.getBlockState(p.position().above()).isAir()
                    && l.getBlockState(p.position().below()).isFaceSturdy(l,p.position().below(),Direction.UP))
                h.assertTrue(l.getBrightness(net.minecraft.world.level.LightLayer.BLOCK,p.position())>0,"Unlit usable mine floor: "+p.position());
            h.assertTrue(!c.hasInventoryItem(MineProductionService::isOutput),"Full mine still produced");
            h.assertTrue(c.inventory().stream().filter(v->v.is(Items.IRON_PICKAXE)).allMatch(v->v.getDamageValue()==0),"Full mine consumed tool");
            WarehouseInventoryService.extractItem(l,mine,Items.COBBLESTONE,64);
        }).thenWaitUntil(()->h.assertTrue(c.hasInventoryItem(MineProductionService::isOutput),"Mining failed to resume after freeing space"))
                .thenExecute(()->{
                    var damaged=MineBlueprint.position(f.at(),Direction.EAST,3,0,1);
                    l.setBlock(damaged,Blocks.AIR.defaultBlockState(),3);mine.markDamaged(damaged);
                    c.clearWork();
                    int tool=c.inventory().stream().filter(v->v.is(Items.IRON_PICKAXE)).mapToInt(ItemStack::getDamageValue).sum();
                    h.runAfterDelay(240,()->{
                        h.assertTrue(c.inventory().stream().filter(v->v.is(Items.IRON_PICKAXE)).mapToInt(ItemStack::getDamageValue).sum()==tool,"Mine produced during maintenance");
                        f.clean();h.succeed();
                    });
                });
    }
    @GameTest(templateNamespace="exiledsettlement_mine",template="empty",timeoutTicks=10000)
    public static void nativeMinerFetchesPickaxeWalksDownProducesAndReturnsCargo(GameTestHelper h) {
        var f=fixture(h);var l=h.getLevel();var data=SettlementSavedData.get(l);
        long started=System.nanoTime(),startTick=l.getGameTime();
        var mine=built(f,BlueprintTemplates.create(BuildingType.MINE,f.at(),Direction.SOUTH));
        var warehouse=built(f,BlueprintTemplates.create(BuildingType.WAREHOUSE,f.at().offset(16,0,12),Direction.WEST));
        WarehouseInventoryService.insert(l,warehouse,new ItemStack(Items.IRON_PICKAXE,2));
        WarehouseInventoryService.insert(l,warehouse,new ItemStack(Items.BREAD,64));
        var c=f.s().citizens().iterator().next();
        for(var other:f.s().citizens())if(other!=c)other.markDead(f.at());
        c.setManualAssignment(CitizenProfession.MINER,mine.id());c.assignProfession(CitizenProfession.MINER,mine.id());
        c.insertIntoInventory(new ItemStack(Items.BREAD,16));c.updateProjection(null,f.at().offset(-8,0,7));
        CitizenProjectionService.ensureSettlementCitizens(l,f.s());data.touch();
        com.hkin.exiledsettlement.ExiledSettlementMod.LOGGER.info("[mine-fixture] anchor={} bounds={}/{} warehouse={}",f.at(),mine.minCorner(),mine.maxCorner(),warehouse.position());
        boolean[] down={false},produced={false},delivered={false};int[] stage={0};
        h.onEachTick(()->{
            var e=CitizenProjectionService.findProjection(l,c);if(e==null)return;
            int ore=c.inventory().stream().filter(MineProductionService::isOutput).mapToInt(ItemStack::getCount).sum();
            if(e.getY()<f.at().getY()-8)down[0]=true;
            if(ore>0) {
                h.assertTrue(down[0],"Miner produced without entering underground hall");produced[0]=true;
                h.assertTrue(c.inventory().stream().anyMatch(v->v.is(Items.IRON_PICKAXE) && v.getDamageValue()>0),"Mining did not consume tool durability");
            }
            if(WarehouseInventoryService.stock(l,mine).quantity()>0) {
                h.assertTrue(e.getY()<f.at().getY()-13,"Stored remotely without entering ore cellar");delivered[0]=true;
            }
            if(l.getGameTime()%200==0){var path=e.getNavigation().getPath();com.hkin.exiledsettlement.ExiledSettlementMod.LOGGER.info("[mine-native] down={} produced={} delivered={} pos={} state={} failure={} ore={} target={} path={}",down[0],produced[0],delivered[0],e.blockPosition(),c.workState(),c.workFailure(),ore,c.targetPosition(),path==null?"null":path.getTarget()+" reach="+path.canReach()+" node="+path.getNextNodeIndex()+"/"+path.getNodeCount());}
        });
        h.startSequence().thenWaitUntil(()->h.assertTrue(down[0] && produced[0] && delivered[0],"Waiting for real underground production and warehouse delivery"))
                .thenExecute(()->{
                    com.hkin.exiledsettlement.ExiledSettlementMod.LOGGER.info("MINE_PRODUCTION_OK ticks={} wallSeconds={} stored={}",l.getGameTime()-startTick,(System.nanoTime()-started)/1e9,WarehouseInventoryService.stock(l,mine).quantity());
                    f.clean();
                }).thenSucceed();
    }
}
