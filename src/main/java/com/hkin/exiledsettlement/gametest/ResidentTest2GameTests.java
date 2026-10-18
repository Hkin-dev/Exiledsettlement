package com.hkin.exiledsettlement.gametest;

import com.hkin.exiledsettlement.blueprint.*;
import com.hkin.exiledsettlement.building.*;
import com.hkin.exiledsettlement.citizen.*;
import com.hkin.exiledsettlement.registry.ModEntities;
import com.hkin.exiledsettlement.settlement.*;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.neoforged.neoforge.gametest.*;
import java.util.*;

@GameTestHolder("exiledsettlement_resident2")
@PrefixGameTestTemplate(false)
public final class ResidentTest2GameTests {
    private static int serial;
    private static void passed(){com.hkin.exiledsettlement.ExiledSettlementMod.LOGGER.info("RESIDENT2_PASS");}
    private record F(GameTestHelper h,BlockPos at,SettlementSavedData data,SettlementRecord s,CitizenRecord c,SettlementCitizenEntity e) {
        void clean(){e.discard();s.deactivate();}
    }
    private static F fixture(GameTestHelper h) {
        return fixture(h,UUID.randomUUID());
    }
    private static F fixture(GameTestHelper h,UUID owner) {
        var l=h.getLevel();var at=new BlockPos(396000+serial++*512,100,396000);
        for(int x=-2;x<=3;x++)for(int z=-2;z<=3;z++)l.setChunkForced((at.getX()>>4)+x,(at.getZ()>>4)+z,true);
        for(var p:BlockPos.betweenClosed(at.offset(-12,-1,-12),at.offset(45,-1,45)))l.setBlock(p,Blocks.STONE.defaultBlockState(),2);
        var data=SettlementSavedData.get(l);var s=data.createOrRecover(at,owner,"Resident test 2").value();
        s.terrain().enabled=false;s.autoRoads().enabled=false;s.decorations().enabled=false;s.deactivate();
        var c=s.citizens().iterator().next();c.assignProfession(CitizenProfession.WORKER,null);
        var e=actor(l,s,c,at.offset(-2,0,0));l.setDayTime(6000);
        return new F(h,at,data,s,c,e);
    }
    private static SettlementCitizenEntity actor(ServerLevel l,SettlementRecord s,CitizenRecord c,BlockPos at) {
        var e=ModEntities.SETTLEMENT_CITIZEN.get().create(l);e.moveTo(at.getX()+.5,at.getY(),at.getZ()+.5,0,0);e.setOnGround(true);
        e.getPersistentData().putString(CitizenProjectionService.SETTLEMENT_ID_TAG,s.id().toString());
        e.getPersistentData().putString(CitizenProjectionService.CITIZEN_ID_TAG,c.id().toString());c.updateProjection(e.getUUID(),at);
        l.addFreshEntity(e);return e;
    }
    private static BlueprintRecord site(F f,List<BlueprintPlacement> cells) {
        var at=cells.getFirst().position();
        return f.s.addBlueprint(BuildingType.HOUSE,at,at.north(2),Direction.NORTH,f.s.ownerId(),false)
                .withCustomTemplate(CustomTemplateSnapshot.create("Paid work test",BuildingType.HOUSE,at,Direction.NORTH,cells,
                        List.of(new BlueprintEntrance(at.north(),Direction.NORTH))));
    }
    private static void invoke(String method,F f) {
        try {
            var m=CitizenWorkService.class.getDeclaredMethod(method,ServerLevel.class,SettlementSavedData.class,
                    SettlementRecord.class,CitizenRecord.class,SettlementCitizenEntity.class);
            m.setAccessible(true);m.invoke(null,f.h.getLevel(),f.data,f.s,f.c,f.e);
        }catch(java.lang.reflect.InvocationTargetException e){throw new RuntimeException(e.getCause());}
        catch(ReflectiveOperationException e){throw new RuntimeException(e);}
    }
    private static int count(CitizenRecord c,Item item) {return c.inventory().stream().filter(s->s.is(item)).mapToInt(ItemStack::getCount).sum();}
    private static BuildingRecord store(F f,BlockPos p) {
        f.h.getLevel().setBlock(p,Blocks.BARREL.defaultBlockState(),2);
        var b=f.s.addBuilding(BuildingType.WAGON,p,Direction.NORTH,p,p,0,4);
        b.updateFunction(true,BuildingStatus.OPERATIONAL,WarehouseStock.CAPACITY,List.of(p));return b;
    }
    @GameTest(templateNamespace="exiledsettlement_resident2",template="empty",timeoutTicks=100)
    public static void woodenStarterAndOptionalEquipmentPreserveOwnedTools(GameTestHelper h) {
        var quantities=FoundingCampSupplies.quantities();
        for(var item:List.of(Items.WOODEN_AXE,Items.WOODEN_PICKAXE,Items.WOODEN_SHOVEL,Items.WOODEN_HOE,Items.WOODEN_SWORD))
            h.assertTrue(quantities.getOrDefault(item,0)>0,"Missing wooden starter tool "+item);
        for(var item:List.of(Items.IRON_AXE,Items.IRON_PICKAXE,Items.IRON_SHOVEL,Items.IRON_HOE,Items.IRON_SWORD))
            h.assertTrue(!quantities.containsKey(item),"Old iron starter tool retained");
        var f=fixture(h);for(var profession:CitizenProfession.values()) {
            f.c.assignProfession(profession,null);
            h.assertTrue(CitizenEquipmentService.missingEquipment(f.c)==null && CitizenEquipmentService.nextEquipment(h.getLevel(),f.s,f.c)==null,
                    "Empty stock still blocks "+profession);
        }
        f.c.assignProfession(CitizenProfession.MINER,null);var b=store(f,f.at.east(4));
        WarehouseInventoryService.insert(h.getLevel(),b,new ItemStack(Items.WOODEN_PICKAXE));
        WarehouseInventoryService.insert(h.getLevel(),b,new ItemStack(Items.IRON_PICKAXE));
        // Snapshot cache expires normally; a newly used settlement avoids stale stock in this fixture.
        h.startSequence().thenIdle(21).thenExecute(()->{
            h.assertTrue(CitizenEquipmentService.nextEquipment(h.getLevel(),f.s,f.c)==Items.WOODEN_PICKAXE,"Does not prefer wooden default");
            var iron=new ItemStack(Items.IRON_PICKAXE);iron.setDamageValue(17);f.c.insertIntoInventory(iron);
            var loaded=CitizenRecord.load(f.c.save(h.getLevel().registryAccess()),h.getLevel().registryAccess());
            h.assertTrue(loaded.inventory().stream().anyMatch(s->s.is(Items.IRON_PICKAXE)&&s.getDamageValue()==17),"Owned tool changed on reload");
            f.clean();
        }).thenExecute(ResidentTest2GameTests::passed).thenSucceed();
    }
    @GameTest(templateNamespace="exiledsettlement_resident2",template="empty",timeoutTicks=200)
    public static void missingMaterialsStayAtWorksiteAndAppearInManagement(GameTestHelper h) {
        var player=h.makeMockServerPlayerInLevel();var f=fixture(h,player.getUUID());var p=f.at.east(3);var site=site(f,List.of(new BlueprintPlacement(p,Blocks.OAK_PLANKS.defaultBlockState(),Items.OAK_PLANKS)));
        f.c.startTask(CitizenWorkState.TO_CONSTRUCTION_SUPPLY,p,null,site.id());
        player.moveTo(f.at.getX()+30.5,100,f.at.getZ()+.5);player.getInventory().add(new ItemStack(Items.OAK_PLANKS,64));
        h.assertTrue(h.getLevel().getServer().getPlayerList().getPlayer(site.ownerId())==player,"Owner must be online to cover removed fallback");
        var before=f.e.position();int stock=player.getInventory().countItem(Items.OAK_PLANKS);
        h.onEachTick(()->invoke("fetchConstructionMaterial",f));
        h.startSequence().thenIdle(60).thenExecute(()->{
            h.assertTrue(f.e.position().distanceTo(before)<1 && player.getInventory().countItem(Items.OAK_PLANKS)==stock,"Resident pursued or took player stock");
            h.assertTrue(f.c.workFailure().equals("MATERIALS_MISSING") && f.s.blueprint(site.id())!=null,"Missing material did not retain work");
            var lines=CommunityManagement.report(h.getLevel(),f.s);
            h.assertTrue(lines.stream().anyMatch(v->v.contains("block.minecraft.oak_planks")&&v.contains("缺 1")),"Management report omitted exact shortage "+lines);
            h.assertTrue(count(f.c,Items.OAK_PLANKS)==0 && h.getLevel().getBlockState(p).isAir(),"Unfunded work completed");
            f.clean();player.discard();
        }).thenExecute(ResidentTest2GameTests::passed).thenSucceed();
    }
    @GameTest(templateNamespace="exiledsettlement_resident2",template="empty",timeoutTicks=100)
    public static void materialReportSharesFiniteInventoryWithoutConsumingIt(GameTestHelper h) {
        var f=fixture(h);var b=store(f,f.at.east(4));
        WarehouseInventoryService.insert(h.getLevel(),b,new ItemStack(Items.OAK_PLANKS,4));
        var before=WarehouseInventoryService.stock(h.getLevel(),b).quantity();
        var missing=WarehouseConstructionSupply.shortages(h.getLevel(),f.s,Map.of(Items.OAK_PLANKS,6,Items.COBBLESTONE,3));
        h.assertTrue(missing.getOrDefault(Items.OAK_PLANKS,0)==2 && missing.getOrDefault(Items.COBBLESTONE,0)==3,"Wrong material deficit "+missing);
        h.assertTrue(WarehouseInventoryService.stock(h.getLevel(),b).quantity()==before,"Report consumed stock");
        WarehouseInventoryService.insert(h.getLevel(),b,new ItemStack(Items.BIRCH_PLANKS,2));
        h.assertTrue(WarehouseConstructionSupply.shortages(h.getLevel(),f.s,Map.of(Items.OAK_PLANKS,6)).isEmpty(),"Same-group substitution omitted");
        h.assertTrue(WarehouseInventoryService.stock(h.getLevel(),b).quantity()==before+2,"Substitution preview consumed stock");
        f.clean();passed();h.succeed();
    }
    @GameTest(templateNamespace="exiledsettlement_resident2",template="empty",timeoutTicks=100)
    public static void inaccessibleOptionalToolDoesNotBlockWorkOrMoveStock(GameTestHelper h) {
        var f=fixture(h);var l=h.getLevel();var p=f.at.east(8);var b=store(f,p);
        WarehouseInventoryService.insert(l,b,new ItemStack(Items.WOODEN_PICKAXE));
        for(var at:BlockPos.betweenClosed(p.offset(-1,0,-1),p.offset(1,3,1)))
            if(!at.equals(p))l.setBlock(at,Blocks.BEDROCK.defaultBlockState(),2);
        f.c.assignProfession(CitizenProfession.MINER,null);f.c.startEquipmentFetch(b.id(),p);
        h.assertTrue(!WarehouseInventoryService.canInteract(l,b,f.e),"Sealed stock is remotely accessible");
        h.assertTrue(WarehouseInventoryService.extractItemForResident(l,b,f.e,Items.WOODEN_PICKAXE,1).isEmpty(),"Remote extraction succeeded");
        invoke("fetchEquipment",f);
        h.assertTrue(f.c.workState()==CitizenWorkState.IDLE && CitizenEquipmentService.nextEquipment(l,f.s,f.c)==null,"Optional gear still holds a worker");
        h.assertTrue(WarehouseInventoryService.countItem(l,b,Items.WOODEN_PICKAXE)==1 && count(f.c,Items.WOODEN_PICKAXE)==0,"Stock changed on failed access");
        f.clean();passed();h.succeed();
    }
    @GameTest(templateNamespace="exiledsettlement_resident2",template="empty",timeoutTicks=800)
    public static void bareWoodStoneIronPerformPaidConstructionAtDifferentRates(GameTestHelper h) {
        var fixtures=new ArrayList<F>();var sites=new ArrayList<BlueprintRecord>();var goals=new ArrayList<List<BlueprintPlacement>>();
        Item[] tools={Items.AIR,Items.WOODEN_PICKAXE,Items.STONE_PICKAXE,Items.IRON_PICKAXE};int[] finished=new int[4];int[] ticks={0};
        for(var tool:tools) {
            var f=fixture(h);f.e.setNoAi(true);var cells=new ArrayList<BlueprintPlacement>();
            for(int x=0;x<5;x++)for(int z=0;z<6;z++)cells.add(new BlueprintPlacement(f.at.offset(x,0,z),Blocks.OAK_PLANKS.defaultBlockState(),Items.OAK_PLANKS));
            var site=site(f,cells);f.c.insertIntoInventory(new ItemStack(Items.OAK_PLANKS,30));
            if(tool!=Items.AIR)f.c.insertIntoInventory(new ItemStack(tool));
            f.c.startTask(CitizenWorkState.TO_BLUEPRINT,cells.getFirst().position(),null,site.id());fixtures.add(f);sites.add(site);goals.add(cells);
        }
        h.onEachTick(()->{
            ticks[0]++;
            for(int i=0;i<fixtures.size();i++)if(finished[i]==0) {
                var f=fixtures.get(i);
                if(com.hkin.exiledsettlement.util.TickCadence.due(h.getLevel().getGameTime(),f.c.id(),CitizenToolSpeed.schedulerInterval(f.c,5),2))
                    CitizenWorkService.advanceSiteConstruction(h.getLevel(),f.data,f.s,f.c,f.e);
                if(goals.get(i).stream().allMatch(p->h.getLevel().getBlockState(p.position()).is(Blocks.OAK_PLANKS)))finished[i]=ticks[0];
            }
        });
        h.startSequence().thenWaitUntil(()->h.assertTrue(Arrays.stream(finished).allMatch(n->n>0),"Construction stalled "+fixtures.stream().map(f->f.c.workFailure()+":"+f.c.workState()).toList()))
        .thenExecute(()->{
            h.assertTrue(finished[0]>finished[1] && finished[1]>finished[2] && finished[2]>finished[3],"Tool rates did not improve "+Arrays.toString(finished));
            h.assertTrue(Math.abs((finished[0]-1)/(double)(finished[1]-1)-1.15)<.08 && Math.abs((finished[0]-1)/(double)(finished[3]-1)-1.6)<.1,"Wrong wood/iron speed "+Arrays.toString(finished));
            for(var f:fixtures)h.assertTrue(count(f.c,Items.OAK_PLANKS)==0,"Construction did not consume exactly 30 planks");
            com.hkin.exiledsettlement.ExiledSettlementMod.LOGGER.info("RESIDENT2_BUILD ticks={} blocksEach=30",Arrays.toString(finished));fixtures.forEach(F::clean);
        }).thenExecute(ResidentTest2GameTests::passed).thenSucceed();
    }
    @GameTest(templateNamespace="exiledsettlement_resident2",template="empty",timeoutTicks=100)
    public static void mineAddsDirtRetainsSandAndRareProbabilities(GameTestHelper h) {
        var random=net.minecraft.util.RandomSource.create(19281L);var kinds=new HashSet<Item>();var counts=new HashMap<Item,Integer>();
        for(int i=0;i<20000;i++)for(var stack:MineProductionService.roll(random)) {
            h.assertTrue(MineProductionService.isOutput(stack),"Unrecognized mine output");kinds.add(stack.getItem());counts.merge(stack.getItem(),stack.getCount(),Integer::sum);
        }
        h.assertTrue(kinds.size()==33 && counts.getOrDefault(Items.DIRT,0)>4500 && counts.getOrDefault(Items.SAND,0)>900,"Missing dirt/sand "+counts);
        h.assertTrue(counts.get(Items.DIAMOND)>330 && counts.get(Items.DIAMOND)<570 && counts.get(Items.EMERALD)>330 && counts.get(Items.EMERALD)<570,"Rare weight changed");
        com.hkin.exiledsettlement.ExiledSettlementMod.LOGGER.info("RESIDENT2_MINE kinds={} dirt={} sand={} diamonds={}",kinds.size(),counts.get(Items.DIRT),counts.get(Items.SAND),counts.get(Items.DIAMOND));passed();h.succeed();
    }
    @GameTest(templateNamespace="exiledsettlement_resident2",template="empty",timeoutTicks=100)
    public static void bareRoadWorkKeepsArrivalAndProtectionChecks(GameTestHelper h) {
        var f=fixture(h);var l=h.getLevel();f.s.activate();f.s.autoRoads().enabled=true;f.s.autoRoads().workReady=true;var p=f.at.below().east(8);l.setBlock(p,Blocks.DIRT.defaultBlockState(),2);
        var route=new com.hkin.exiledsettlement.road.AutoRoadState.Route(UUID.randomUUID(),p,List.of(p));
        f.s.autoRoads().routes.put("bare road",route);f.c.startTask(CitizenWorkState.TO_ROAD,p,null,null);
        h.assertTrue(!com.hkin.exiledsettlement.road.AutoRoadService.layCell(l,f.s,f.c,f.e,p).success(),"Remote road work was accepted");
        f.e.moveTo(p.getX()+.5,p.getY()+1,p.getZ()+.5,0,0);f.e.setOnGround(true);
        java.util.function.Consumer<net.neoforged.neoforge.event.level.BlockEvent.EntityPlaceEvent> cancel=event->{if(event.getPos().equals(p))event.setCanceled(true);};
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(cancel);
        try {h.assertTrue(!com.hkin.exiledsettlement.road.AutoRoadService.layCell(l,f.s,f.c,f.e,p).success() && l.getBlockState(p).is(Blocks.DIRT),"Protection did not restore road ground");}
        finally {net.neoforged.neoforge.common.NeoForge.EVENT_BUS.unregister(cancel);}
        var result=com.hkin.exiledsettlement.road.AutoRoadService.layCell(l,f.s,f.c,f.e,p);
        h.assertTrue(result.success() && l.getBlockState(p).is(Blocks.DIRT_PATH),"Bare road failed "+result.reason());f.clean();passed();h.succeed();
    }
    @GameTest(templateNamespace="exiledsettlement_resident2",template="empty",timeoutTicks=800)
    public static void farmingUsesToolRatesBelowTheOldEightTickScheduler(GameTestHelper h) {
        var fs=new ArrayList<F>();var cells=new ArrayList<BlockPos>();int[] done=new int[4];int[] ticks={0};
        Item[] tools={Items.AIR,Items.WOODEN_HOE,Items.STONE_HOE,Items.IRON_HOE};
        for(var tool:tools) {
            var f=fixture(h);f.e.setNoAi(true);var ground=f.at.below().east(6);
            var goals=List.of(new BlueprintPlacement(ground,Blocks.FARMLAND.defaultBlockState(),Items.DIRT),
                    new BlueprintPlacement(ground.offset(-10,0,-4),Blocks.STONE.defaultBlockState(),Items.STONE),
                    new BlueprintPlacement(ground.offset(6,3,5),Blocks.STONE.defaultBlockState(),Items.STONE));
            var farm=f.s.addBuilding(BuildingType.FARM_FIELD,ground,Direction.NORTH,ground.offset(-10,0,-4),ground.offset(6,3,5),0,1)
                    .withCustomTemplate(CustomTemplateSnapshot.create("Farm cadence",BuildingType.FARM_FIELD,ground,Direction.NORTH,goals,List.of(new BlueprintEntrance(ground.west(9).above(),Direction.WEST))));
            farm.updateFunction(true,BuildingStatus.OPERATIONAL,1,List.of());f.c.assignProfession(CitizenProfession.FARMER,farm.id());
            f.c.insertIntoInventory(new ItemStack(Items.WHEAT_SEEDS,32));if(tool!=Items.AIR)f.c.insertIntoInventory(new ItemStack(tool));
            h.getLevel().setBlock(ground,Blocks.FARMLAND.defaultBlockState().setValue(FarmBlock.MOISTURE,7),2);
            h.getLevel().setBlock(ground.above(),Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE,7),2);fs.add(f);cells.add(ground);
        }
        h.onEachTick(()->{ticks[0]++;for(int i=0;i<4;i++)if(done[i]==0){
            var f=fs.get(i);var ground=cells.get(i);
            if(com.hkin.exiledsettlement.util.TickCadence.due(h.getLevel().getGameTime(),f.c.id(),CitizenToolSpeed.schedulerInterval(f.c,8),0)) {
                if(f.c.workState()!=CitizenWorkState.TO_FARM)f.c.startTask(CitizenWorkState.TO_FARM,ground.above(),null,null);
                invoke("farm",f);
            }
            if(count(f.c,Items.WHEAT)>=30)done[i]=ticks[0];
            // Controlled mature crop input isolates labour time from random crop-growth time.
            else if(h.getLevel().getBlockState(ground.above()).is(Blocks.WHEAT))h.getLevel().setBlock(ground.above(),Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE,7),2);
        }});
        h.startSequence().thenWaitUntil(()->h.assertTrue(Arrays.stream(done).allMatch(t->t>0),"Farm work stalled "+fs.stream().map(f->f.c.workFailure()).toList()))
        .thenExecute(()->{
            h.assertTrue(Math.abs(done[0]/(double)done[1]-1.15)<.1 && Math.abs(done[0]/(double)done[2]-1.3)<.1 && Math.abs(done[0]/(double)done[3]-1.6)<.12,"Farm rate capped by scheduler "+Arrays.toString(done));
            com.hkin.exiledsettlement.ExiledSettlementMod.LOGGER.info("RESIDENT2_FARM_RATES ticks={} harvestsEach=30",Arrays.toString(done));fs.forEach(F::clean);
        }).thenExecute(ResidentTest2GameTests::passed).thenSucceed();
    }
    @GameTest(templateNamespace="exiledsettlement_resident2",template="empty",timeoutTicks=600)
    public static void bareFarmerTillsPlantsAndHarvestsWithRealSeeds(GameTestHelper h) {
        var f=fixture(h);var l=h.getLevel();f.e.setNoAi(true);var ground=f.at.below().east(6);
        var cells=List.of(new BlueprintPlacement(ground,Blocks.FARMLAND.defaultBlockState(),Items.DIRT),
                new BlueprintPlacement(ground.offset(-10,0,-4),Blocks.STONE.defaultBlockState(),Items.STONE),
                new BlueprintPlacement(ground.offset(6,3,5),Blocks.STONE.defaultBlockState(),Items.STONE));
        var farm=f.s.addBuilding(BuildingType.FARM_FIELD,ground,Direction.NORTH,ground.offset(-10,0,-4),ground.offset(6,3,5),0,1);
        farm.withCustomTemplate(CustomTemplateSnapshot.create("Bare farmer",BuildingType.FARM_FIELD,ground,Direction.NORTH,cells,List.of(new BlueprintEntrance(ground.west(9).above(),Direction.WEST))));
        farm.updateFunction(true,BuildingStatus.OPERATIONAL,1,List.of());f.c.assignProfession(CitizenProfession.FARMER,farm.id());
        l.setBlock(ground,Blocks.DIRT.defaultBlockState(),2);f.c.insertIntoInventory(new ItemStack(Items.WHEAT_SEEDS));
        f.c.startTask(CitizenWorkState.TO_FARM,ground,null,null);boolean[] planted={false};
        h.onEachTick(()->{
            if(com.hkin.exiledsettlement.util.TickCadence.due(l.getGameTime(),f.c.id(),CitizenWorkService.WORK_INTERVAL_TICKS,0))invoke("farm",f);
            if(!planted[0] && l.getBlockState(ground.above()).is(Blocks.WHEAT)) {
                h.assertTrue(count(f.c,Items.WHEAT_SEEDS)==0,"Planting did not consume real seed");planted[0]=true;
                l.setBlock(ground.above(),Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE,7),2);
                f.c.clearWork();f.c.startTask(CitizenWorkState.TO_FARM,ground.above(),null,null);
            }
        });
        h.startSequence().thenWaitUntil(()->h.assertTrue(planted[0] && count(f.c,Items.WHEAT)>0,"Bare farmer stalled "+f.c.workState()+"/"+f.c.workFailure()))
        .thenExecute(()->{h.assertTrue(f.c.inventory().stream().noneMatch(s->s.getItem() instanceof TieredItem),"Free tool was supplied");f.clean();}).thenExecute(ResidentTest2GameTests::passed).thenSucceed();
    }
    @GameTest(templateNamespace="exiledsettlement_resident2",template="empty",timeoutTicks=2500)
    public static void bareAndIronMinersProduceAtRealWorkPositions(GameTestHelper h) {
        var fs=new ArrayList<F>();var mines=new ArrayList<BuildingRecord>();var events=new ArrayList<List<Long>>();
        for(int i=0;i<2;i++) {
            var f=fixture(h);var l=h.getLevel();var at=f.at.above(18);var t=BlueprintTemplates.create(BuildingType.MINE,at,Direction.SOUTH);
            for(var p:t.placements())l.setBlock(p.position(),p.state(),2);
            var b=f.s.addBuilding(BuildingType.MINE,at,Direction.SOUTH,t.minCorner(),t.maxCorner(),0,BlueprintTemplates.currentTemplateVersion(BuildingType.MINE));
            BuildingFunctionService.validateSingle(l,f.data,f.s,b);h.assertTrue(b.operational(),"Mine fixture not operational "+b.status());
            var station=MineExpansionService.workPositions(b).get(4);f.e.moveTo(station.getX()+.5,station.getY(),station.getZ()+.5,0,0);f.e.setOnGround(true);f.e.setNoAi(true);
            f.c.assignProfession(CitizenProfession.MINER,b.id());if(i==1)f.c.insertIntoInventory(new ItemStack(Items.IRON_PICKAXE));
            f.c.startTask(CitizenWorkState.TO_MINE,station,null,null);fs.add(f);mines.add(b);events.add(new ArrayList<>());
        }
        h.onEachTick(()->{
            for(int i=0;i<2;i++) {
                var f=fs.get(i);if(events.get(i).size()>=3)continue;
                if(!com.hkin.exiledsettlement.util.TickCadence.due(h.getLevel().getGameTime(),f.c.id(),CitizenWorkService.WORK_INTERVAL_TICKS,0))continue;
                int before=f.c.inventory().stream().filter(MineProductionService::isOutput).mapToInt(ItemStack::getCount).sum();invoke("mine",f);
                int after=f.c.inventory().stream().filter(MineProductionService::isOutput).mapToInt(ItemStack::getCount).sum();
                if(after>before)events.get(i).add(h.getLevel().getGameTime());
            }
        });
        h.startSequence().thenWaitUntil(()->h.assertTrue(events.stream().allMatch(e->e.size()>=3),"Mine production stalled "+events+" / "+fs.stream().map(f->f.c.workState()+"/"+f.c.workFailure()).toList()))
        .thenExecute(()->{
            for(int i=0;i<2;i++) {
                int expected=i==0?800:500;var times=events.get(i);
                // 500 ticks falls between the existing eight-tick dispatches: 496/504 alternate.
                for(int n=1;n<times.size();n++)h.assertTrue(Math.abs(times.get(n)-times.get(n-1)-expected)<CitizenWorkService.WORK_INTERVAL_TICKS,"Wrong mining cadence "+times);
                h.assertTrue(times.getLast()-times.getFirst()==2L*expected,"Mining cadence drift "+times);
                var f=fs.get(i);h.assertTrue(Math.abs(f.e.getY()-(mines.get(i).position().getY()-10))<.1,"Mining left its real underground work level");
                if(i==0)h.assertTrue(f.c.inventory().stream().noneMatch(s->s.getItem() instanceof TieredItem),"Bare miner received fake tool");
                else h.assertTrue(f.c.inventory().stream().anyMatch(s->s.is(Items.IRON_PICKAXE)&&s.getDamageValue()==3),"Tool wear lost");
                f.clean();
            }
            com.hkin.exiledsettlement.ExiledSettlementMod.LOGGER.info("RESIDENT2_MINING ticks={}",events);
        }).thenExecute(ResidentTest2GameTests::passed).thenSucceed();
    }
    @GameTest(templateNamespace="exiledsettlement_resident2",template="empty",timeoutTicks=1600)
    public static void initialCampTwoResidentsLoadReturnAndLeaveWithoutMutualLock(GameTestHelper h) {
        var f=fixture(h);var l=h.getLevel();var t=BlueprintTemplates.create(BuildingType.STARTER_CAMP,f.at,Direction.SOUTH);
        for(var p:t.placements())l.setBlock(p.position(),p.state(),2);
        for(var p:BlockPos.betweenClosed(t.minCorner().offset(-10,0,-10),new BlockPos(t.maxCorner().getX()+10,t.minCorner().getY(),t.maxCorner().getZ()+10)))
        { if(l.getBlockState(p).isAir())l.setBlock(p,Blocks.STONE.defaultBlockState(),2);
          l.setBlock(p.below(),Blocks.STONE.defaultBlockState(),2); }
        var barrels=t.placements().stream().filter(p->p.state().is(Blocks.BARREL)).map(BlueprintPlacement::position).toList();
        h.assertTrue(barrels.size()==15,"Wrong initial camp template");
        var min=new BlockPos(barrels.stream().mapToInt(BlockPos::getX).min().orElseThrow(),barrels.stream().mapToInt(BlockPos::getY).min().orElseThrow(),barrels.stream().mapToInt(BlockPos::getZ).min().orElseThrow());
        var max=new BlockPos(barrels.stream().mapToInt(BlockPos::getX).max().orElseThrow(),barrels.stream().mapToInt(BlockPos::getY).max().orElseThrow(),barrels.stream().mapToInt(BlockPos::getZ).max().orElseThrow());
        var b=f.s.addBuilding(BuildingType.WAGON,barrels.getFirst(),Direction.SOUTH,min,max,0,4);b.updateFunction(true,BuildingStatus.OPERATIONAL,WarehouseStock.CAPACITY,barrels);
        WarehouseInventoryService.insert(l,b,new ItemStack(Items.DIRT,32));
        f.e.moveTo(min.getX()-6.5,f.at.getY(),min.getZ()-3.5,0,0);
        var second=f.s.citizens().stream().filter(c->c!=f.c).findFirst().orElseThrow();var e2=actor(l,f.s,second,new BlockPos(min.getX()-6,f.at.getY(),min.getZ()-2));
        var entities=List.of(f.e,e2);var citizens=List.of(f.c,second);int[] phase={0,0};int[] ticks={0};double[] maxStep={0};var previous=new net.minecraft.world.phys.Vec3[]{f.e.position(),e2.position()};
        var exits=new BlockPos[]{new BlockPos(min.getX()-7,f.at.getY(),min.getZ()-5),new BlockPos(min.getX()-5,f.at.getY(),min.getZ()-5)};
        h.assertTrue(CampStorageAccess.stations(l,b,f.e).size()>6,"Camp apron did not expose enough standing room");
        for(var c:citizens)c.startEquipmentFetch(b.id(),b.position());
        h.assertTrue(WarehouseInventoryService.canStand(l,f.e.blockPosition()) && WarehouseInventoryService.canStand(l,e2.blockPosition()),"Initial camp test starts off supported ground: "+min+" / "+f.e.blockPosition());
        h.onEachTick(()->{
            ticks[0]++;
            if(ticks[0]==1 || ticks[0]==20 || ticks[0]==100)
                com.hkin.exiledsettlement.ExiledSettlementMod.LOGGER.info("RESIDENT2_CAMP_TRACE tick={} actors={} min={} candidates={}",ticks[0],
                        entities.stream().map(e->e.position()+" floor="+l.getBlockState(e.blockPosition().below())+" removed="+e.isRemoved()+" nav="+e.getNavigation().isDone()).toList(),min,
                        entities.stream().map(e->WarehouseInventoryService.reserveInteractionPosition(l,b,e)).toList());
            for(int i=0;i<2;i++) {
                var e=entities.get(i);var c=citizens.get(i);maxStep[0]=Math.max(maxStep[0],e.position().distanceTo(previous[i]));previous[i]=e.position();
                if(phase[i]>=4)continue;
                if(phase[i]==1 || phase[i]==3) {
                    if(e.distanceToSqr(net.minecraft.world.phys.Vec3.atBottomCenterOf(exits[i]))<1.4){e.stopSettlementNavigation();phase[i]++;}
                    else if(ticks[0]%8==0)e.navigateTo(exits[i],.8);
                    continue;
                }
                if(WarehouseInventoryService.canInteract(l,b,e)) {
                    if(phase[i]==0) {
                        var taken=WarehouseInventoryService.extractItemForResident(l,b,e,Items.DIRT,8);h.assertTrue(taken.getCount()==8,"Pickup missing real stock");c.insertIntoInventory(taken);
                    } else {
                        var cargo=c.extractInventoryItem(s->s.is(Items.DIRT),8);h.assertTrue(cargo.getCount()==8,"Lost carried goods");
                        h.assertTrue(WarehouseInventoryService.insertForResident(l,b,e,cargo).isEmpty(),"Return refused");
                    }
                    WarehouseInventoryService.releaseInteractionPosition(l,e);phase[i]++;
                } else if(ticks[0]%8==0) {
                    var target=WarehouseInventoryService.reserveInteractionPosition(l,b,e);
                    if(target==null)target=WarehouseInventoryService.waitingPosition(l,b,e);
                    if(target!=null)e.navigateTo(target,.8);
                }
            }
        });
        h.startSequence().thenWaitUntil(()->h.assertTrue(phase[0]==4 && phase[1]==4,"Camp mutual lock phase="+Arrays.toString(phase)+" pos="+entities.stream().map(e->e.blockPosition()+"/"+e.constructionDecisionTrace()).toList()))
        .thenExecute(()->{
            h.assertTrue(maxStep[0]<1.5 && WarehouseInventoryService.countItem(l,b,Items.DIRT)==32,"Teleport or duplicated/lost stock");
            h.assertTrue(citizens.stream().allMatch(c->count(c,Items.DIRT)==0),"Cargo not returned");
            com.hkin.exiledsettlement.ExiledSettlementMod.LOGGER.info("RESIDENT2_CAMP ticks={} barrels={} stations={} maxStep={} stock=32",ticks[0],barrels.size(),CampStorageAccess.stations(l,b,f.e).size(),maxStep[0]);e2.discard();f.clean();
        }).thenExecute(ResidentTest2GameTests::passed).thenSucceed();
    }
}
