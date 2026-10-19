package com.hkin.exiledsettlement.gametest;

import com.hkin.exiledsettlement.blueprint.*;
import com.hkin.exiledsettlement.building.*;
import com.hkin.exiledsettlement.citizen.*;
import com.hkin.exiledsettlement.settlement.*;
import com.hkin.exiledsettlement.registry.ModEntities;
import com.hkin.exiledsettlement.menu.SettlementCoreMenu;
import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.*;
import java.util.*;

@GameTestHolder("exiledsettlement_kiln")
@PrefixGameTestTemplate(false)
public final class BrickKilnGameTests {
    private record F(SettlementRecord s, BuildingRecord kiln, BuildingRecord stock, Container inv, CitizenRecord c) {}
    private static int serial;
    private static F fixture(GameTestHelper h) {
        var at=h.absolutePos(new BlockPos(90000+serial++*64,10,90000));
        var s=new SettlementRecord(UUID.randomUUID(),"Kiln rules",at,UUID.randomUUID());
        s.community().fuelReserve=0;
        var k=s.addBuilding(BuildingType.BRICK_KILN,at,Direction.SOUTH,at,at.offset(18,12,17),0,1);
        k.updateFunction(true,BuildingStatus.OPERATIONAL,1,List.of(at));
        var stock=s.addBuilding(BuildingType.WAGON,at.west(5));
        h.getLevel().setBlock(stock.position(),Blocks.BARREL.defaultBlockState(),3);
        stock.updateFunction(true,BuildingStatus.OPERATIONAL,27,List.of(stock.position()));
        var inv=(Container)h.getLevel().getBlockEntity(stock.position());
        inv.setItem(0,new ItemStack(Items.CLAY_BALL,8));inv.setItem(1,new ItemStack(Items.CHARCOAL));
        var c=s.citizens().iterator().next();c.setManualAssignment(CitizenProfession.KILN_WORKER,k.id());
        h.getLevel().setDayTime(6000);return new F(s,k,stock,inv,c);
    }
    private static int count(CitizenRecord c,Item item) {return c.inventory().stream().filter(s->KilnRecipes.matches(s,item)).mapToInt(ItemStack::getCount).sum();}
    private static void batch(CitizenRecord c,KilnRecipes.Recipe r) {
        var l=c.life();l.task="kiln:"+BuiltInRegistries.ITEM.getKey(r.input());
        l.cookingInput=BuiltInRegistries.ITEM.getKey(r.input()).toString();l.cookingInputCount=r.inputCount();
        l.cookingOutput=BuiltInRegistries.ITEM.getKey(r.output()).toString();l.cookingOutputCount=r.outputCount();
        l.cookingFuel="minecraft:charcoal";l.cookingStage=2;
    }
    @GameTest(templateNamespace="exiledsettlement_kiln",template="empty")
    public static void catalogRotationsFacilitiesAndRealBodyClearance(GameTestHelper h) {
        h.assertTrue(Arrays.stream(SettlementCoreMenu.BUILDING_CATALOG).filter(e->e.type()==BuildingType.BRICK_KILN).count()==1,"Kiln missing or duplicate");
        h.assertTrue(SettlementCoreMenu.BUILDING_CATALOG.length==24,"Unrelated catalog changed");
        h.assertTrue(CitizenProfession.CHEF.ordinal()==12 && BuildingType.CHURCH.ordinal()==36,"Historical ordinals changed");
        int index=0;
        for(var facing:Direction.Plane.HORIZONTAL) {
            var at=h.absolutePos(new BlockPos(88000+index++*40,10,88000));
            var t=BlueprintTemplates.create(BuildingType.BRICK_KILN,at,facing);
            for(var p:t.placements())h.getLevel().setBlock(p.position(),p.state(),2);
            var s=new SettlementRecord(UUID.randomUUID(),"Four rotations",at,UUID.randomUUID());
            var b=s.addBuilding(BuildingType.BRICK_KILN,at,facing,t.minCorner(),t.maxCorner(),0,1);
            BuildingFunctionService.validateSingle(h.getLevel(),new SettlementSavedData(),s,b);
            h.assertTrue(b.operational() && b.workerCapacity()==4,"Kiln failed validation "+facing);
            var furnaces=t.placements().stream().filter(p->p.state().is(Blocks.FURNACE)).toList();
            h.assertTrue(furnaces.size()==2 && t.placements().stream().anyMatch(p->p.mode()==BlueprintPlacementMode.CLEARANCE),"Missing furnaces or clearance");
            h.assertTrue(t.placements().stream().filter(p->p.state().is(Blocks.WATER_CAULDRON)).count()==1
                    && t.placements().stream().filter(p->p.state().is(Blocks.BARREL)).count()==6
                    && !KilnWaterService.stations(h.getLevel(),s,b).isEmpty(),"Rotated kiln lost its built-in water or storage points "+facing);
            for(var p:furnaces) {
                var front=p.position().relative(p.state().getValue(HorizontalDirectionalBlock.FACING));
                h.assertTrue(h.getLevel().noCollision(new AABB(front.getX()+.2,front.getY(),front.getZ()+.2,front.getX()+.8,front.getY()+1.95,front.getZ()+.8)),"Blocked furnace approach "+facing);
            }
            for(var p:t.placements()) if(p.state().getBlock() instanceof DoorBlock) {
                h.getLevel().setBlock(p.position(),p.state().setValue(DoorBlock.OPEN,true),2);
            }
            for(var entrance:t.entrances()) {
                var feet=entrance.position();
                for(int d=-1;d<=1;d++) {
                    var front=feet.relative(entrance.outward(),d);
                    h.assertTrue(h.getLevel().noCollision(new AABB(front.getX()+.2,front.getY(),front.getZ()+.2,front.getX()+.8,front.getY()+1.95,front.getZ()+.8)),"Blocked 1.95-height doorway "+facing);
                }
            }
            h.getLevel().setBlock(furnaces.getFirst().position(),Blocks.AIR.defaultBlockState(),2);
            BuildingFunctionService.validateSingle(h.getLevel(),new SettlementSavedData(),s,b);
            h.assertTrue(!b.operational(),"Missing kiln facility still operational");
        }
        h.succeed();
    }
    @GameTest(templateNamespace="exiledsettlement_kiln",template="empty")
    public static void oneSlotSelectionAndSaveSurvive(GameTestHelper h) {
        var f=fixture(h);var data=new SettlementSavedData();f.c.clearManualAssignment();f.c.assignProfession(CitizenProfession.WORKER,null);
        h.assertTrue(data.setBuildingWorkerTarget(f.s,f.s.ownerId(),f.kiln.id(),1,f.kiln.configurationRevision()),"Slot unavailable");
        h.assertTrue(!data.setBuildingWorkerTarget(f.s,f.s.ownerId(),f.kiln.id(),2,f.kiln.configurationRevision()),"Exceeded kiln capacity");
        ProfessionAssignmentService.refresh(data,f.s);
        h.assertTrue(f.s.professionActual(CitizenProfession.KILN_WORKER)==1,"No kiln worker assigned");
        h.assertTrue(f.kiln.setProductionId(ResourceLocation.withDefaultNamespace("glass")),"Glass unavailable");
        h.assertTrue(!f.kiln.setProductionId(ResourceLocation.withDefaultNamespace("diamond")),"Invalid output accepted");
        var restored=SettlementRecord.load(f.s.save(h.getLevel().registryAccess()),h.getLevel().registryAccess());
        h.assertTrue(restored.building(f.kiln.id()).productionId().getPath().equals("glass") && restored.building(f.kiln.id()).templateVersion()==1,"Save lost product/template");
        var glass=fixture(h);glass.kiln.setProductionId(ResourceLocation.withDefaultNamespace("glass"));glass.inv.setItem(0,new ItemStack(Items.SAND,8));CommunityStock.invalidate(h.getLevel(),glass.s);
        h.assertTrue(KilnProductionService.assign(h.getLevel(),glass.s,glass.c) && glass.c.life().cookingOutput.equals("minecraft:glass"),"Selected output ignored");
        h.succeed();
    }
    @GameTest(templateNamespace="exiledsettlement_kiln",template="empty")
    public static void allRecipesConsumeExactlyOnceAndRejectForgery(GameTestHelper h) {
        h.assertTrue(KilnProductionService.RECIPES.size()==59 && BuildingProductionService.KILN_PRODUCTS.size()==41,"Product coverage changed");
        for(var r:KilnProductionService.RECIPES) {
            h.assertTrue(BuildingProductionService.kilnUsesFuel(BuiltInRegistries.ITEM.getKey(r.output()))==r.usesFuel(),"UI fuel text disagrees with recipe");
            var f=fixture(h);batch(f.c,r);
            h.assertTrue(!KilnProductionService.completeBatch(f.s,f.c),"Empty inventory produced output");
            f.c.insertIntoInventory(KilnRecipes.stack(r.input(),r.inputCount()));
            if(r.usesFuel() || r.extraCount()>0)
                h.assertTrue(!KilnProductionService.completeBatch(f.s,f.c) && count(f.c,r.input())==r.inputCount(),"Unfunded batch consumed input");
            f.c.insertIntoInventory(new ItemStack(Items.CHARCOAL));
            for(int i=1;i<r.inputs().size();i++)f.c.insertIntoInventory(KilnRecipes.stack(r.inputs().get(i).item(),r.inputs().get(i).count()));
            h.assertTrue(KilnProductionService.completeBatch(f.s,f.c),"Funded recipe failed");
            h.assertTrue(count(f.c,r.input())==(r.returnedItem()==r.input()?r.returnedCount():0) && count(f.c,Items.CHARCOAL)==(r.usesFuel()?0:1)
                    && (r.extraCount()==0 || count(f.c,r.extraInput())==0) && count(f.c,r.output())==r.outputCount() && r.inputs().stream().allMatch(i -> count(f.c,i.item())==(r.returnedItem()==i.item()?r.returnedCount():0))
                    && (r.returnedCount()==0 || count(f.c,r.returnedItem())==r.returnedCount()),"Incorrect recipe debit/output: "+BuiltInRegistries.ITEM.getKey(r.output())+" bag="+f.c.inventory());
            h.assertTrue(!KilnProductionService.completeBatch(f.s,f.c) && f.s.community().mealsProduced==0,"Replay or food statistics polluted");
        }
        var f=fixture(h);batch(f.c,KilnProductionService.RECIPES.getFirst());f.c.life().cookingOutput="minecraft:diamond";
        f.c.insertIntoInventory(new ItemStack(Items.CLAY_BALL,8));f.c.insertIntoInventory(new ItemStack(Items.CHARCOAL));
        h.assertTrue(!KilnProductionService.completeBatch(f.s,f.c) && count(f.c,Items.CLAY_BALL)==8,"Forged saved output produced");h.succeed();
    }
    @GameTest(templateNamespace="exiledsettlement_kiln",template="empty")
    public static void unfiredInputsAndSavedSelectionRemainExact(GameTestHelper h) {
        var missing=fixture(h);missing.kiln.setProductionId(ResourceLocation.withDefaultNamespace("packed_mud"));
        missing.inv.setItem(0,new ItemStack(Items.MUD,8));missing.inv.setItem(1,ItemStack.EMPTY);CommunityStock.invalidate(h.getLevel(),missing.s);
        h.assertTrue(!KilnProductionService.assign(h.getLevel(),missing.s,missing.c),"Missing wheat accepted");
        var f=fixture(h);f.kiln.setProductionId(ResourceLocation.withDefaultNamespace("packed_mud"));f.s.community().fuelReserve=999;
        f.inv.setItem(0,new ItemStack(Items.MUD,8));f.inv.setItem(1,new ItemStack(Items.WHEAT,8));CommunityStock.invalidate(h.getLevel(),f.s);
        h.assertTrue(KilnProductionService.assign(h.getLevel(),f.s,f.c),"Unfired recipe requires fuel");
        var recipe=KilnProductionService.RECIPES.stream().filter(r->r.output()==Items.PACKED_MUD).findFirst().orElseThrow();
        batch(f.c,recipe);f.c.life().destination=f.kiln.id();f.c.life().workTicks=160;f.c.life().cookingFuel="";
        f.c.insertIntoInventory(new ItemStack(Items.MUD,8));f.c.insertIntoInventory(new ItemStack(Items.WHEAT,7));
        h.assertTrue(!KilnProductionService.completeBatch(f.s,f.c) && count(f.c,Items.MUD)==8 && count(f.c,Items.WHEAT)==7,"Short wheat partially consumed");
        f.c.insertIntoInventory(new ItemStack(Items.WHEAT));f.kiln.setProductionId(ResourceLocation.withDefaultNamespace("glass"));
        var saved=CitizenRecord.load(f.c.save(h.getLevel().registryAccess()),h.getLevel().registryAccess());
        h.assertTrue(saved.life().workTicks==160 && KilnProductionService.completeBatch(f.s,saved),"Saved two-input batch cannot resume without fuel");
        h.assertTrue(count(saved,Items.PACKED_MUD)==8 && count(saved,Items.MUD)==0 && count(saved,Items.WHEAT)==0
                && count(saved,Items.GLASS)==0,"Changed selection rewrote paid batch");h.succeed();
    }
    @GameTest(templateNamespace="exiledsettlement_kiln",template="empty")
    public static void reservesTargetsAndProfessionPreventUnfundedWork(GameTestHelper h) {
        var f=fixture(h);f.s.community().fuelReserve=1;
        h.assertTrue(!KilnProductionService.assign(h.getLevel(),f.s,f.c) && KilnProductionService.waitingReason(f.c).contains("燃料"),"Fuel reserve ignored");
        var missing=fixture(h);missing.inv.setItem(0,ItemStack.EMPTY);CommunityStock.invalidate(h.getLevel(),missing.s);
        h.assertTrue(!KilnProductionService.assign(h.getLevel(),missing.s,missing.c),"Missing input accepted");
        var target=fixture(h);for(int i=2;i<6;i++)target.inv.setItem(i,new ItemStack(Items.BRICKS,64));CommunityStock.invalidate(h.getLevel(),target.s);
        h.assertTrue(!KilnProductionService.assign(h.getLevel(),target.s,target.c),"Stock target ignored");
        var normal=fixture(h);var worker=normal.s.citizens().stream().filter(c->c!=normal.c).findFirst().orElseThrow();
        h.assertTrue(!KilnProductionService.assign(h.getLevel(),normal.s,worker),"Ordinary worker took kiln task");
        h.assertTrue(KilnProductionService.assign(h.getLevel(),normal.s,normal.c),"Valid batch rejected");
        worker.setManualAssignment(CitizenProfession.KILN_WORKER,normal.kiln.id());
        h.assertTrue(!KilnProductionService.assign(h.getLevel(),normal.s,worker),"Duplicate kiln batch");h.succeed();
    }
    @GameTest(templateNamespace="exiledsettlement_kiln",template="empty")
    public static void repairRestReloadAndReassignmentPreserveCargo(GameTestHelper h) {
        var f=fixture(h);h.assertTrue(KilnProductionService.assign(h.getLevel(),f.s,f.c),"No batch");
        batch(f.c,KilnProductionService.RECIPES.getFirst());f.c.life().destination=f.kiln.id();f.c.life().workTicks=160;
        f.c.insertIntoInventory(new ItemStack(Items.CLAY_BALL,8));f.c.insertIntoInventory(new ItemStack(Items.CHARCOAL));
        var actor=ModEntities.SETTLEMENT_CITIZEN.get().create(h.getLevel());actor.moveTo(f.kiln.position().getX(),f.kiln.position().getY(),f.kiln.position().getZ());
        h.getLevel().setDayTime(23000+CitizenWorkService.scheduleOffset(f.c));
        h.assertTrue(!KilnProductionService.work(h.getLevel(),new SettlementSavedData(),f.s,f.c,actor) && f.c.life().workTicks==160,"Rest reset or advanced firing");
        h.getLevel().setDayTime(6000);f.c.clearManualAssignment();f.kiln.setWorkerTarget(1,f.kiln.configurationRevision());
        f.kiln.updateFunction(false,BuildingStatus.MISSING_CONTAINER,0,List.of());ProfessionAssignmentService.refresh(new SettlementSavedData(),f.s);
        h.assertTrue(f.c.profession()==CitizenProfession.KILN_WORKER,"Repair reassigned funded worker");
        KilnProductionService.work(h.getLevel(),new SettlementSavedData(),f.s,f.c,actor);
        var saved=CitizenRecord.load(f.c.save(h.getLevel().registryAccess()),h.getLevel().registryAccess());
        h.assertTrue(saved.life().workTicks==160 && saved.life().cookingStage==2 && count(saved,Items.CLAY_BALL)==8,"Saved paused batch lost progress/cargo");
        f.c.setManualAssignment(CitizenProfession.KILN_WORKER,f.kiln.id());f.c.requestManualRelease();
        h.assertTrue(!f.c.completeManualReleaseIfSafe(),"Unlock abandoned batch");
        f.c.setManualAssignment(CitizenProfession.WORKER,null);KilnProductionService.work(h.getLevel(),new SettlementSavedData(),f.s,f.c,actor);
        h.assertTrue(f.c.life().task.isEmpty() && count(f.c,Items.CLAY_BALL)==8 && count(f.c,Items.CHARCOAL)==1,"Reassignment lost cargo");
        actor.discard();h.succeed();
    }
    @GameTest(templateNamespace="exiledsettlement_kiln",template="empty")
    public static void fullBagDoesNotConsumeBatch(GameTestHelper h) {
        var f=fixture(h);batch(f.c,KilnProductionService.RECIPES.getFirst());
        f.c.insertIntoInventory(new ItemStack(Items.CLAY_BALL,8));f.c.insertIntoInventory(new ItemStack(Items.CHARCOAL));
        for(int i=0;i<CitizenRecord.INVENTORY_SIZE;i++)f.c.insertIntoInventory(new ItemStack(Items.COBBLESTONE,64));
        h.assertTrue(!KilnProductionService.completeBatch(f.s,f.c) && count(f.c,Items.CLAY_BALL)==8 && count(f.c,Items.CHARCOAL)==1,"Full bag consumed batch");h.succeed();
    }
    @GameTest(templateNamespace="exiledsettlement_kiln",template="empty",timeoutTicks=2400)
    public static void physicalWorkerFetchesFiresAndReturnsAfterFullStock(GameTestHelper h) {
        var level=h.getLevel();var at=h.absolutePos(new BlockPos(86000,10,86000));
        var min=new ChunkPos(at.offset(-16,0,-16));var max=new ChunkPos(at.offset(32,0,32));
        for(int x=min.x;x<=max.x;x++)for(int z=min.z;z<=max.z;z++)level.setChunkForced(x,z,true);
        for(var p:BlockPos.betweenClosed(at.offset(-12,-1,-8),at.offset(28,-1,29)))level.setBlock(p,Blocks.STONE_BRICKS.defaultBlockState(),2);
        level.setDayTime(6000);var s=new SettlementRecord(UUID.randomUUID(),"Kiln physical",at,UUID.randomUUID());
        var c=s.citizens().iterator().next();for(var other:s.citizens())if(other!=c)other.markDead(at);
        s.community().fuelReserve=0;var data=new SettlementSavedData();
        var t=BlueprintTemplates.create(BuildingType.BRICK_KILN,at.south(5),Direction.SOUTH);
        for(var p:t.placements())level.setBlock(p.position(),p.state(),2);
        var kiln=s.addBuilding(BuildingType.BRICK_KILN,t.anchor(),Direction.SOUTH,t.minCorner(),t.maxCorner(),0,1);
        BuildingFunctionService.validateSingle(level,data,s,kiln);kiln.setWorkerTarget(1,kiln.configurationRevision());ProfessionAssignmentService.refresh(data,s);
        h.assertTrue(c.profession()==CitizenProfession.KILN_WORKER,"Worker not assigned");
        c.insertIntoInventory(new ItemStack(Items.BREAD,CommunityContent.carriedFoodTarget));
        var stock=s.addBuilding(BuildingType.WAGON,at.west(5));level.setBlock(stock.position(),Blocks.BARREL.defaultBlockState(),3);
        stock.updateFunction(true,BuildingStatus.OPERATIONAL,27,List.of(stock.position()));var inv=(Container)level.getBlockEntity(stock.position());
        inv.setItem(0,new ItemStack(Items.CLAY_BALL,8));inv.setItem(1,new ItemStack(Items.CHARCOAL));
        SettlementCitizenEntity[] actor={null};boolean[] entered={false},unblocked={false};long[] fullUntil={-1};long start=level.getGameTime(),wall=System.nanoTime();
        h.startSequence().thenWaitUntil(()->{CitizenProjectionService.ensureSettlementCitizens(level,s);actor[0]=CitizenProjectionService.findProjection(level,c);h.assertTrue(actor[0]!=null,"Waiting for entity");})
        .thenExecute(()->{actor[0].moveTo(at.getX()+.5,at.getY(),at.getZ()+.5,0,0);actor[0].setOnGround(true);})
        .thenWaitUntil(()->{
            var e=actor[0];c.updateProjection(e.getUUID(),e.blockPosition());
            if((level.getGameTime()-start)%200==0)System.out.println("KILN_TRACE tick="+(level.getGameTime()-start)+" pos="+e.position()+" stage="+c.life().cookingStage+" work="+c.life().workTicks+" reason="+c.workFailure());
            if((level.getGameTime()-start)%CitizenWorkService.WORK_INTERVAL_TICKS==0) {
                if(c.life().cookingStage==3 && !c.life().task.isEmpty() && fullUntil[0]<0) {
                    h.assertTrue(WarehouseInventoryService.countItem(level,stock,Items.CLAY_BALL)==0 && WarehouseInventoryService.countItem(level,stock,Items.CHARCOAL)==0,"Inputs were not withdrawn");
                    for(int i=0;i<inv.getContainerSize();i++)inv.setItem(i,new ItemStack(Items.COBBLESTONE,64));
                    fullUntil[0]=level.getGameTime()+80;CommunityStock.invalidate(level,s);
                    var restored=CitizenRecord.load(c.save(level.registryAccess()),level.registryAccess());
                    h.assertTrue(restored.life().cookingStage==3 && count(restored,Items.BRICKS)==2,"Delivery save lost output");
                }
                if(fullUntil[0]>0 && !unblocked[0] && level.getGameTime()>=fullUntil[0]) {
                    h.assertTrue(!c.life().task.isEmpty() && count(c,Items.BRICKS)==2,"Full stock lost or replayed output");
                    inv.setItem(0,ItemStack.EMPTY);unblocked[0]=true;CommunityStock.invalidate(level,s);
                }
                CommunityLifeService.personal(level,data,s,c,e);
                if(c.life().workTicks>0)entered[0]|=kiln.contains(e.blockPosition()) && level.noCollision(e);
            }
            h.assertTrue(unblocked[0] && c.life().task.isEmpty(),"Waiting for real delivery: stage="+c.life().cookingStage+" work="+c.life().workTicks+" "+c.workFailure());
            h.assertTrue(entered[0] && WarehouseInventoryService.countItem(level,stock,Items.BRICKS)==2 && count(c,Items.BRICKS)==0,"Physical chain/output mismatch");
            h.assertTrue(CommunityLifeService.carriedFood(c)==CommunityContent.carriedFoodTarget,"Rations lost");
        }).thenExecute(()->{System.out.println("KILN_PHYSICAL_CHAIN_OK ticks="+(level.getGameTime()-start)+" wallSeconds="+((System.nanoTime()-wall)/1e9)+" enteredKiln="+entered[0]+" fullStockWait="+unblocked[0]+" clayConsumed=8 charcoalConsumed=1 bricksDelivered=2");
            actor[0].discard();s.deactivate();for(int x=min.x;x<=max.x;x++)for(int z=min.z;z<=max.z;z++)level.setChunkForced(x,z,false);
        }).thenSucceed();
    }
    @GameTest(templateNamespace="exiledsettlement_kiln",template="empty",timeoutTicks=3000)
    public static void physicalMudChainConsumesWheatWithoutFuel(GameTestHelper h) {
        var level=h.getLevel();var at=h.absolutePos(new BlockPos(84000,10,84000));
        var min=new ChunkPos(at.offset(-16,0,-16));var max=new ChunkPos(at.offset(32,0,32));
        for(int x=min.x;x<=max.x;x++)for(int z=min.z;z<=max.z;z++)level.setChunkForced(x,z,true);
        for(var p:BlockPos.betweenClosed(at.offset(-12,-1,-8),at.offset(28,-1,29)))level.setBlock(p,Blocks.STONE_BRICKS.defaultBlockState(),2);
        level.setDayTime(6000);var s=new SettlementRecord(UUID.randomUUID(),"Mud material physical",at,UUID.randomUUID());
        var c=s.citizens().iterator().next();for(var other:s.citizens())if(other!=c)other.markDead(at);
        s.community().fuelReserve=999;var data=new SettlementSavedData();
        var t=BlueprintTemplates.create(BuildingType.BRICK_KILN,at.south(5),Direction.SOUTH);
        for(var p:t.placements())level.setBlock(p.position(),p.state(),2);
        var kiln=s.addBuilding(BuildingType.BRICK_KILN,t.anchor(),Direction.SOUTH,t.minCorner(),t.maxCorner(),0,1);
        BuildingFunctionService.validateSingle(level,data,s,kiln);kiln.setWorkerTarget(1,kiln.configurationRevision());ProfessionAssignmentService.refresh(data,s);
        kiln.setProductionId(ResourceLocation.withDefaultNamespace("mud_bricks"));
        c.insertIntoInventory(new ItemStack(Items.BREAD,CommunityContent.carriedFoodTarget));
        var stock=s.addBuilding(BuildingType.WAGON,at.west(5));level.setBlock(stock.position(),Blocks.BARREL.defaultBlockState(),3);
        stock.updateFunction(true,BuildingStatus.OPERATIONAL,27,List.of(stock.position()));var inv=(Container)level.getBlockEntity(stock.position());
        inv.setItem(0,new ItemStack(Items.MUD,8));inv.setItem(1,new ItemStack(Items.WHEAT,8));
        SettlementCitizenEntity[] actor={null};boolean[] intermediate={false},formed={false};long start=level.getGameTime(),wall=System.nanoTime();
        h.startSequence().thenWaitUntil(()->{CitizenProjectionService.ensureSettlementCitizens(level,s);actor[0]=CitizenProjectionService.findProjection(level,c);h.assertTrue(actor[0]!=null,"Waiting for entity");})
        .thenExecute(()->{actor[0].moveTo(at.getX()+.5,at.getY(),at.getZ()+.5,0,0);actor[0].setOnGround(true);})
        .thenWaitUntil(()->{
            var e=actor[0];c.updateProjection(e.getUUID(),e.blockPosition());
            if((level.getGameTime()-start)%200==0)System.out.println("MUD_KILN_TRACE tick="+(level.getGameTime()-start)+" pos="+e.position()+" stage="+c.life().cookingStage+" work="+c.life().workTicks+" secondBatch="+intermediate[0]+" reason="+c.workFailure());
            if((level.getGameTime()-start)%CitizenWorkService.WORK_INTERVAL_TICKS==0) {
                intermediate[0]|=count(c,Items.PACKED_MUD)>0;
                h.assertTrue(kiln.productionId().getPath().equals("mud_bricks"),"Final product was changed during chain");
                CommunityLifeService.personal(level,data,s,c,e);
                if(c.activity()==CitizenActivity.FORMING_BUILDING_MATERIALS && c.life().workTicks>0)
                    formed[0]|=WorkforceStatusService.working(level,s,c) && kiln.contains(e.blockPosition());
            }
            h.assertTrue(intermediate[0] && c.life().task.isEmpty() && WarehouseInventoryService.countItem(level,stock,Items.MUD_BRICKS)==8,
                    "Waiting for mud chain: stage="+c.life().cookingStage+" work="+c.life().workTicks+" "+c.workFailure());
            h.assertTrue(formed[0] && WarehouseInventoryService.countItem(level,stock,Items.PACKED_MUD)==0 && count(c,Items.MUD_BRICKS)==0,"Workstation or second real delivery missing");
            h.assertTrue(count(c,Items.WHEAT)==0 && count(c,Items.MUD)==0 && CommunityLifeService.carriedFood(c)==CommunityContent.carriedFoodTarget,"Cargo/rations mismatch");
        }).thenExecute(()->{System.out.println("MUD_KILN_PHYSICAL_OK ticks="+(level.getGameTime()-start)+" wallSeconds="+((System.nanoTime()-wall)/1e9)+" mudConsumed=8 wheatConsumed=8 packedMudTransferred=8 mudBricksDelivered=8 fuelConsumed=0");
            actor[0].discard();s.deactivate();for(int x=min.x;x<=max.x;x++)for(int z=min.z;z<=max.z;z++)level.setChunkForced(x,z,false);
        }).thenSucceed();
    }

    @GameTest(templateNamespace="exiledsettlement_kiln",template="empty")
    public static void dependencyPlanUsesOnlyShortfallAndPreservesFinalGoal(GameTestHelper h) {
        Map<Item,Integer> stock=new HashMap<>(Map.of(Items.PACKED_MUD,2,Items.MUD,3,Items.DIRT,3,Items.WHEAT,6,Items.GLASS_BOTTLE,3));
        var plan=KilnRecipes.plan(Items.MUD_BRICKS,8,i -> stock.getOrDefault(i,0));
        h.assertTrue(plan.ready() && plan.fuel()==0 && plan.steps().size()==4,"Missing automatic mud dependency chain");
        h.assertTrue(plan.steps().get(0).recipe().output()==Items.POTION && plan.steps().get(0).amount()==3
                && plan.steps().get(1).recipe().output()==Items.MUD && plan.steps().get(1).amount()==3
                && plan.steps().get(2).recipe().output()==Items.PACKED_MUD && plan.steps().get(2).amount()==6,"Existing intermediates not deducted from required amount");
        stock.put(Items.WHEAT,5);var missing=KilnRecipes.plan(Items.MUD_BRICKS,8,i -> stock.getOrDefault(i,0));
        h.assertTrue(!missing.ready() && missing.missing()==Items.WHEAT && missing.missingCount()==1,"Missing final-stage input not detected before starting chain");
        for(var color:DyeColor.values()) {
            Item dye=DyeItem.byColor(color),terracotta=KilnRecipes.colored(color,"terracotta"),concrete=KilnRecipes.colored(color,"concrete");
            var fired=KilnRecipes.plan(terracotta,8,i -> i==Items.CLAY_BALL?32:i==dye?1:0);
            h.assertTrue(fired.ready() && fired.fuel()==1 && fired.steps().size()==3,"Colored terracotta base cost mismatch: "+color);
            var wet=KilnRecipes.plan(concrete,8,i -> i==Items.SAND||i==Items.GRAVEL?4:i==dye?1:0);
            h.assertTrue(wet.ready() && wet.fuel()==0 && wet.needsWater() && wet.steps().size()==2,"Concrete cost mismatch: "+color);
        }
        var smooth=KilnRecipes.plan(Items.SMOOTH_STONE,8,i -> i==Items.COBBLESTONE?8:0);
        h.assertTrue(smooth.ready() && smooth.fuel()==2,"Double firing does not require two fuel");
        var mudCost=KilnRecipes.fullCost(Items.MUD_BRICKS,8);
        h.assertTrue(mudCost.inputs().get(Items.DIRT)==8 && mudCost.inputs().get(Items.WHEAT)==8
                && mudCost.inputs().get(Items.GLASS_BOTTLE)==8 && mudCost.returned().get(Items.GLASS_BOTTLE)==8
                && mudCost.fuel()==0 && mudCost.needsWater(),"Mud-brick source cost/empty bottle return changed");
        var f=fixture(h);f.inv.setItem(0,new ItemStack(Items.MUD,8));f.inv.setItem(1,new ItemStack(Items.WHEAT,8));
        f.kiln.setProductionId(ResourceLocation.withDefaultNamespace("mud_bricks"));
        h.assertTrue(KilnProductionService.assign(h.getLevel(),f.s,f.c),"Final selection cannot start intermediary");
        h.assertTrue(f.c.life().kilnFinalOutput.equals("minecraft:mud_bricks") && f.c.life().cookingOutput.equals("minecraft:packed_mud"),"Final/current output not separated");
        f.kiln.setProductionId(ResourceLocation.withDefaultNamespace("glass"));
        var saved=CitizenRecord.load(f.c.save(h.getLevel().registryAccess()),h.getLevel().registryAccess());
        h.assertTrue(saved.life().kilnFinalCount==8 && saved.life().kilnFinalOutput.equals("minecraft:mud_bricks"),"Reload or selector change lost final chain target");
        saved.life().clearTask();h.assertTrue(saved.life().kilnFinalOutput.isEmpty(),"Cancelled chain target leaked into a future task");
        h.succeed();
    }

    @GameTest(templateNamespace="exiledsettlement_kiln",template="empty")
    public static void waterMustBeRealAndPotionsCannotSubstituteForWater(GameTestHelper h) {
        var level=h.getLevel();var at=h.absolutePos(new BlockPos(82000+serial++*64,10,82000));
        var s=new SettlementRecord(UUID.randomUUID(),"Kiln built-in resources",at,UUID.randomUUID());s.community().fuelReserve=0;
        var t=BlueprintTemplates.create(BuildingType.BRICK_KILN,at,Direction.SOUTH);
        for(var p:t.placements())level.setBlock(p.position(),p.state(),2);
        var kiln=s.addBuilding(BuildingType.BRICK_KILN,t.anchor(),Direction.SOUTH,t.minCorner(),t.maxCorner(),0,1);
        level.setDayTime(6000);BuildingFunctionService.validateSingle(level,new SettlementSavedData(),s,kiln);
        var first=s.citizens().iterator();var c1=first.next();var c2=first.next();
        for(var citizen:List.of(c1,c2))citizen.setManualAssignment(CitizenProfession.KILN_WORKER,kiln.id());
        var stock=s.addBuilding(BuildingType.WAGON,at.west(5));level.setBlock(stock.position(),Blocks.BARREL.defaultBlockState(),3);
        stock.updateFunction(true,BuildingStatus.OPERATIONAL,27,List.of(stock.position()));var inv=(Container)level.getBlockEntity(stock.position());
        inv.setItem(0,new ItemStack(Items.DIRT,8));inv.setItem(1,new ItemStack(Items.WHEAT,8));inv.setItem(2,new ItemStack(Items.GLASS_BOTTLE,8));
        kiln.setProductionId(ResourceLocation.withDefaultNamespace("mud_bricks"));
        boolean centralOnly=KilnProductionService.assign(level,s,c1);
        h.assertTrue(!centralOnly && KilnProductionService.waitingReason(c1).contains("Glass Bottle")
                && WarehouseInventoryService.countItem(level,stock,Items.GLASS_BOTTLE)==8,"Central bottles substituted for kiln storage bottles; assigned="+centralOnly+" reason="+KilnProductionService.waitingReason(c1)+" local="+KilnProductionService.kilnBottleCount(level,kiln));
        var localBarrel=t.placements().stream().filter(p->p.state().is(Blocks.BARREL)).map(p->(Container)level.getBlockEntity(p.position())).filter(Objects::nonNull).findFirst().orElseThrow();
        localBarrel.setItem(0,new ItemStack(Items.GLASS_BOTTLE,8));
        boolean started=KilnProductionService.assign(level,s,c2);
        h.assertTrue(started && KilnProductionService.kilnBottleCount(level,kiln)==8,
                "Built-in cauldron and kiln-local bottles did not fund the automatic chain; started="+started+" reason="+KilnProductionService.waitingReason(c2)+" waterStations="+KilnWaterService.stations(level,s,kiln).size());
        var f=fixture(h);
        var mud=KilnRecipes.forOutput(Items.MUD);batch(f.c,mud);
        f.c.insertIntoInventory(new ItemStack(Items.DIRT,8));
        var healing=net.minecraft.world.item.alchemy.PotionContents.createItemStack(Items.POTION,net.minecraft.world.item.alchemy.Potions.HEALING);
        for(int i=0;i<8;i++)f.c.insertIntoInventory(healing.copy());
        h.assertTrue(!KilnProductionService.completeBatch(f.s,f.c) && count(f.c,Items.DIRT)==8,"Non-water potion was consumed as water");
        f.c.insertIntoInventory(KilnRecipes.stack(Items.POTION,8));
        h.assertTrue(KilnProductionService.completeBatch(f.s,f.c) && count(f.c,Items.MUD)==8 && count(f.c,Items.GLASS_BOTTLE)==8,"Real bottled-water mud recipe failed");
        h.assertTrue(f.c.inventory().stream().filter(stack -> ItemStack.isSameItemSameComponents(stack,healing)).mapToInt(ItemStack::getCount).sum()==8,"Unrelated potion components lost");
        h.assertTrue(!KilnProductionService.completeBatch(f.s,f.c) && count(f.c,Items.GLASS_BOTTLE)==8,"Bottle return duplicated on replay");
        var noDye=fixture(h);noDye.kiln.setProductionId(ResourceLocation.withDefaultNamespace("red_terracotta"));noDye.inv.setItem(0,new ItemStack(Items.CLAY_BALL,32));
        h.assertTrue(!KilnProductionService.assign(h.getLevel(),noDye.s,noDye.c) && KilnProductionService.waitingReason(noDye.c).contains("1"),"Missing dye allowed unfunded upstream work");
        h.succeed();
    }

    @GameTest(templateNamespace="exiledsettlement_kiln",template="empty",timeoutTicks=3600)
    public static void physicalDirtToMudBricksFillsAndReturnsBottles(GameTestHelper h) {
        physicalChain(h,82000,Items.MUD_BRICKS,List.of(new ItemStack(Items.DIRT,8),new ItemStack(Items.WHEAT,8),new ItemStack(Items.GLASS_BOTTLE,8)),true,Items.GLASS_BOTTLE,8,4,"DIRT_MUD_CHAIN_OK");
    }
    @GameTest(templateNamespace="exiledsettlement_kiln",template="empty",timeoutTicks=2600)
    public static void physicalColoredConcreteMixesAndHardensAtWater(GameTestHelper h) {
        physicalChain(h,80000,Items.CYAN_CONCRETE,List.of(new ItemStack(Items.SAND,4),new ItemStack(Items.GRAVEL,4),new ItemStack(Items.CYAN_DYE)),true,Items.AIR,0,2,"CONCRETE_CHAIN_OK");
    }
    @GameTest(templateNamespace="exiledsettlement_kiln",template="empty",timeoutTicks=3000)
    public static void physicalColoredTerracottaPacksFiresAndDyes(GameTestHelper h) {
        physicalChain(h,78000,Items.RED_TERRACOTTA,List.of(new ItemStack(Items.CLAY_BALL,32),new ItemStack(Items.RED_DYE),new ItemStack(Items.CHARCOAL)),false,Items.AIR,0,3,"TERRACOTTA_CHAIN_OK");
    }
    private static void physicalChain(GameTestHelper h,int offset,Item output,List<ItemStack> raw,boolean water,Item returned,int returnedCount,int stages,String marker) {
        var level=h.getLevel();var at=h.absolutePos(new BlockPos(offset,10,offset));
        var min=new ChunkPos(at.offset(-16,0,-16));var max=new ChunkPos(at.offset(32,0,32));
        for(int x=min.x;x<=max.x;x++)for(int z=min.z;z<=max.z;z++)level.setChunkForced(x,z,true);
        for(var p:BlockPos.betweenClosed(at.offset(-12,-1,-8),at.offset(28,-1,29)))level.setBlock(p,Blocks.STONE_BRICKS.defaultBlockState(),2);
        level.setDayTime(6000);var s=new SettlementRecord(UUID.randomUUID(),marker,at,UUID.randomUUID());
        var c=s.citizens().iterator().next();for(var other:s.citizens())if(other!=c)other.markDead(at);
        s.community().fuelReserve=0;var data=new SettlementSavedData();
        var t=BlueprintTemplates.create(BuildingType.BRICK_KILN,at.south(5),Direction.SOUTH);
        for(var p:t.placements())level.setBlock(p.position(),p.state(),2);
        var cauldron=t.placements().stream().filter(p->p.state().is(Blocks.WATER_CAULDRON)).map(p->p.position()).findFirst().orElseThrow();
        var kilnBarrel=t.placements().stream().filter(p->p.state().is(Blocks.BARREL)).map(p->(Container)level.getBlockEntity(p.position()))
                .filter(Objects::nonNull).findFirst().orElseThrow();
        var kiln=s.addBuilding(BuildingType.BRICK_KILN,t.anchor(),Direction.SOUTH,t.minCorner(),t.maxCorner(),0,1);
        BuildingFunctionService.validateSingle(level,data,s,kiln);kiln.setWorkerTarget(1,kiln.configurationRevision());ProfessionAssignmentService.refresh(data,s);
        var selected=BuiltInRegistries.ITEM.getKey(output);kiln.setProductionId(selected);
        c.insertIntoInventory(new ItemStack(Items.BREAD,CommunityContent.carriedFoodTarget));
        var stock=s.addBuilding(BuildingType.WAGON,at.west(5));level.setBlock(stock.position(),Blocks.BARREL.defaultBlockState(),3);
        stock.updateFunction(true,BuildingStatus.OPERATIONAL,27,List.of(stock.position()));var inv=(Container)level.getBlockEntity(stock.position());
        int warehouseSlot=0;
        for(var input:raw) {
            if(input.is(Items.GLASS_BOTTLE))kilnBarrel.setItem(0,input.copy());
            else inv.setItem(warehouseSlot++,input.copy());
        }
        SettlementCitizenEntity[] actor={null};Set<String> seen=new HashSet<>();boolean[] saved={false};long start=level.getGameTime(),wall=System.nanoTime();
        h.startSequence().thenWaitUntil(()->{CitizenProjectionService.ensureSettlementCitizens(level,s);actor[0]=CitizenProjectionService.findProjection(level,c);h.assertTrue(actor[0]!=null,"Waiting for entity");})
        .thenExecute(()->{actor[0].moveTo(at.getX()+.5,at.getY(),at.getZ()+.5,0,0);actor[0].setOnGround(true);})
        .thenWaitUntil(()->{
            var e=actor[0];c.updateProjection(e.getUUID(),e.blockPosition());
            if((level.getGameTime()-start)%CitizenWorkService.WORK_INTERVAL_TICKS==0) {
                CommunityLifeService.personal(level,data,s,c,e);
                if(c.life().workTicks>0 && !c.life().task.isEmpty())seen.add(c.life().cookingOutput);
                if(!saved[0] && c.life().workTicks>=160) {
                    var restored=CitizenRecord.load(c.save(level.registryAccess()),level.registryAccess());
                    h.assertTrue(restored.life().kilnFinalOutput.equals(selected.toString()) && restored.life().kilnFinalCount==8 && restored.life().workTicks==c.life().workTicks,"Save lost active chain");
                    c.life().load(restored.life().save());saved[0]=true;
                }
            }
            h.assertTrue(kiln.productionId().equals(selected),"Automatic worker changed the player's selected product");
            h.assertTrue(c.life().task.isEmpty() && WarehouseInventoryService.countItem(level,stock,output)==8,"Waiting for complete chain "+marker+" stage="+c.life().cookingStage+" work="+c.life().workTicks+" kilnBottles="+KilnProductionService.kilnBottleCount(level,kiln)+" publicBottles="+WarehouseInventoryService.countItem(level,stock,Items.GLASS_BOTTLE)+" cargo="+c.inventory()+" "+c.workFailure());
            h.assertTrue(seen.size()==stages && saved[0],"A production stage or save continuation was skipped");
            for(var input:raw)if(input.getItem()!=returned)h.assertTrue(WarehouseInventoryService.countItem(level,stock,input.getItem())==0 && count(c,input.getItem())==0,"Input not conserved: "+input);
            if(returnedCount>0)h.assertTrue(KilnProductionService.kilnBottleCount(level,kiln)==returnedCount
                    && WarehouseInventoryService.countItem(level,stock,returned)==0 && count(c,returned)==0,"Reusable bottles were not physically returned to kiln storage");
            if(water)h.assertTrue(level.getBlockState(cauldron).is(Blocks.WATER_CAULDRON)
                    && level.getBlockState(cauldron).getValue(LayeredCauldronBlock.LEVEL)==3
                    && !KilnWaterService.stations(level,s,kiln).isEmpty(),"Built-in water cauldron was missing or consumed");
            h.assertTrue(count(c,output)==0 && CommunityLifeService.carriedFood(c)==CommunityContent.carriedFoodTarget,"Final cargo/rations mismatch");
        }).thenExecute(()->{
            System.out.println(marker+" ticks="+(level.getGameTime()-start)+" wallSeconds="+((System.nanoTime()-wall)/1e9)+" output=8 realStages="+seen.size()+" returned="+returnedCount+" saved="+saved[0]);
            actor[0].discard();s.deactivate();for(int x=min.x;x<=max.x;x++)for(int z=min.z;z<=max.z;z++)level.setChunkForced(x,z,false);
        }).thenSucceed();
    }
}
