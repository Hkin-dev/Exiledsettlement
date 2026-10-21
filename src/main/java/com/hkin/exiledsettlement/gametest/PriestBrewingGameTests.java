package com.hkin.exiledsettlement.gametest;

import com.hkin.exiledsettlement.blueprint.*;
import com.hkin.exiledsettlement.building.*;
import com.hkin.exiledsettlement.citizen.*;
import com.hkin.exiledsettlement.registry.ModEntities;
import com.hkin.exiledsettlement.settlement.*;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.*;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BrewingStandBlockEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.*;
import java.util.*;

/** Isolated finite-stock acceptance tests. No player instance or save is used. */
@GameTestHolder("exiledsettlement_priest")
@PrefixGameTestTemplate(false)
public final class PriestBrewingGameTests {
    private static int serial;
    private static final String RESTART_NAME = "Priest181Save";
    private record F(GameTestHelper h, BlockPos at, SettlementSavedData data, SettlementRecord s,
                     BuildingRecord church, BuildingRecord stock, List<CitizenRecord> workers,
                     List<SettlementCitizenEntity> actors, List<BlockPos> stands) {
        void clean() {
            actors.forEach(SettlementCitizenEntity::discard);
            s.deactivate();
            data.touch();
            for (int x=-2;x<=3;x++) for (int z=-2;z<=3;z++)
                h.getLevel().setChunkForced((at.getX()>>4)+x,(at.getZ()>>4)+z,false);
        }
    }
    private static void passed() {
        com.hkin.exiledsettlement.ExiledSettlementMod.LOGGER.info("PRIEST181_PASS");
    }
    private static F fixture(GameTestHelper h, int workerCount) {
        var level=h.getLevel();
        var at=new BlockPos(720000+serial++*512,100,720000);
        for(int x=-2;x<=3;x++)for(int z=-2;z<=3;z++)
            level.setChunkForced((at.getX()>>4)+x,(at.getZ()>>4)+z,true);
        for(var p:BlockPos.betweenClosed(at.offset(-12,-1,-12),at.offset(45,-1,45)))
            level.setBlock(p,Blocks.STONE.defaultBlockState(),2);
        level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false,level.getServer());
        level.setDayTime(6000);
        var data=SettlementSavedData.get(level);
        var s=data.createOrRecover(at,UUID.randomUUID(),"Priest finite stock").value();
        s.terrain().enabled=false;s.autoRoads().enabled=false;s.decorations().enabled=false;s.deactivate();
        var template=BlueprintTemplates.create(BuildingType.CHURCH,at.offset(8,0,8),Direction.SOUTH);
        for(var p:template.placements())level.setBlock(p.position(),p.state(),2);
        var church=s.addBuilding(BuildingType.CHURCH,template.anchor(),Direction.SOUTH,
                template.minCorner(),template.maxCorner(),0,BlueprintTemplates.currentTemplateVersion(BuildingType.CHURCH));
        BuildingFunctionService.validateSingle(level,data,s,church);
        h.assertTrue(church.operational() && church.workerCapacity()==2,"Formal church does not offer two jobs");
        church.setWorkerTarget(workerCount,church.configurationRevision());
        ProfessionAssignmentService.refresh(data,s);
        var workers=s.citizens().stream().filter(c->church.id().equals(c.workBuildingId())).toList();
        h.assertTrue(workers.size()==workerCount,"Selected priest count was not assigned");
        var actors=new ArrayList<SettlementCitizenEntity>();
        for(int i=0;i<workers.size();i++)actors.add(actor(level,s,workers.get(i),at.offset(-4,0,i*2)));
        var p=at.west(7);
        level.setBlock(p,Blocks.BARREL.defaultBlockState(),2);
        var stock=s.addBuilding(BuildingType.WAGON,p,Direction.NORTH,p,p,0,4);
        stock.updateFunction(true,BuildingStatus.OPERATIONAL,WarehouseStock.CAPACITY,List.of(p));
        var stands=template.placements().stream().filter(cell->cell.state().is(Blocks.BREWING_STAND))
                .map(BlueprintPlacement::position).toList();
        return new F(h,at,data,s,church,stock,workers,actors,stands);
    }
    private static SettlementCitizenEntity actor(ServerLevel level,SettlementRecord s,CitizenRecord c,BlockPos at) {
        var e=ModEntities.SETTLEMENT_CITIZEN.get().create(level);
        e.moveTo(at.getX()+.5,at.getY(),at.getZ()+.5,0,0);e.setOnGround(true);
        e.getPersistentData().putString(CitizenProjectionService.SETTLEMENT_ID_TAG,s.id().toString());
        e.getPersistentData().putString(CitizenProjectionService.CITIZEN_ID_TAG,c.id().toString());
        c.updateProjection(e.getUUID(),at);level.addFreshEntity(e);return e;
    }
    private static BrewingStandBlockEntity stand(F f,int index) {
        return (BrewingStandBlockEntity)f.h.getLevel().getBlockEntity(f.stands.get(index));
    }
    private static CompoundTag machine(F f,int index) {
        return stand(f,index).saveWithoutMetadata(f.h.getLevel().registryAccess());
    }
    private static ItemStack water() {
        return PotionContents.createItemStack(Items.POTION,Potions.WATER);
    }
    private static int count(Container c,ItemStack match) {
        int total=0;
        for(int i=0;i<c.getContainerSize();i++)
            if(ItemStack.isSameItemSameComponents(c.getItem(i),match))total+=c.getItem(i).getCount();
        return total;
    }
    private static int count(CitizenRecord c,ItemStack match) {
        return c.inventory().stream().filter(s->ItemStack.isSameItemSameComponents(s,match)).mapToInt(ItemStack::getCount).sum();
    }
    private static int stored(F f,ItemStack match) {
        return WarehouseInventoryService.loadedContainers(f.h.getLevel(),f.stock).stream()
                .mapToInt(c->count(c.container(),match)).sum();
    }
    private static void put(F f,ItemStack stack,int count) {
        for(int i=0;i<count;i++)f.h.assertTrue(WarehouseInventoryService.insert(f.h.getLevel(),f.stock,stack.copyWithCount(1)).isEmpty(),"Could not fund stock");
    }
    private static void fund(F f,int water,int wart,int melon,int fuel) {
        put(f,water(),water);
        put(f,new ItemStack(Items.NETHER_WART),wart);
        put(f,new ItemStack(Items.GLISTERING_MELON_SLICE),melon);
        put(f,new ItemStack(Items.BLAZE_POWDER),fuel);
    }
    private static void tick(F f,long start) {
        for(int i=0;i<f.workers.size();i++) {
            var c=f.workers.get(i);var e=f.actors.get(i);
            c.updateProjection(e.getUUID(),e.blockPosition());
            if((f.h.getLevel().getGameTime()-start)%CitizenWorkService.WORK_INTERVAL_TICKS==0)
                PriestProductionService.work(f.h.getLevel(),f.data,f.s,c,e);
        }
    }
    private static void begin(F f) {
        for(var c:f.workers)
            f.h.assertTrue(PriestProductionService.assign(f.h.getLevel(),f.s,c),"Priest could not claim a funded job: "+c.workFailure());
    }
    private static void roundTrip(F f,CitizenRecord c,String stage) {
        var restored=CitizenRecord.load(c.save(f.h.getLevel().registryAccess()),f.h.getLevel().registryAccess());
        f.h.assertTrue(restored.life().save().equals(c.life().save()),"Task metadata changed during "+stage+" NBT round trip");
        f.h.assertTrue(restored.inventory().size()==c.inventory().size(),"Inventory size changed during "+stage);
        for(int i=0;i<c.inventory().size();i++)
            f.h.assertTrue(ItemStack.matches(restored.inventory().get(i),c.inventory().get(i)),"Exact cargo changed during "+stage);
    }
    /** A finite paid cargo fixture for interference tests, without pretending these transfers prove walking. */
    private static void carryReserved(F f,Item... ingredients) {
        put(f,water(),3);begin(f);var c=f.workers.getFirst();var task=c.life().brewing;
        for(int i=0;i<3;i++) {
            var stack=WarehouseInventoryService.extractItem(f.h.getLevel(),f.stock,Items.POTION,1);
            f.h.assertTrue(ItemStack.isSameItemSameComponents(stack,water()),"Reserved water fixture changed identity");
            c.insertIntoInventory(stack);
        }
        task.bottlesInBag=3;task.bagProduct=BrewingRecipes.WATER;task.phase=PriestBrewingTask.LOAD;
        for(var ingredient:ingredients) {
            put(f,new ItemStack(ingredient),1);
            c.insertIntoInventory(WarehouseInventoryService.extractItem(f.h.getLevel(),f.stock,ingredient,1));
            task.cargo.merge(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(ingredient),1,Integer::sum);
        }
    }

    @GameTest(templateNamespace="exiledsettlement_priest",template="empty",timeoutTicks=100)
    public static void paidTurtleHelmetAndGoldenCarrotSurviveEquipmentAndHunger(GameTestHelper h) {
        var f=fixture(h,1);var c=f.workers.getFirst();var e=f.actors.getFirst();
        carryReserved(f,Items.TURTLE_HELMET,Items.GOLDEN_CARROT,Items.PUFFERFISH,Items.NETHER_WART,Items.BLAZE_POWDER);
        c.insertIntoInventory(new ItemStack(Items.LEATHER_HELMET));
        CitizenEquipmentService.syncVisibleEquipment(c,e);
        h.assertTrue(count(c,new ItemStack(Items.TURTLE_HELMET))==1 &&
                c.equipmentItem(net.minecraft.world.entity.EquipmentSlot.HEAD).is(Items.LEATHER_HELMET),
                "Paid turtle reagent auto-equipped, or ordinary spare helmet no longer equips");
        h.assertTrue(!CommunityLifeService.food(new ItemStack(Items.PUFFERFISH)) &&
                !CommunityLifeService.food(new ItemStack(Items.SPIDER_EYE)),"Poisonous raw reagents became food");
        h.assertTrue(CommunityLifeService.carriedFood(c)==0,"Paid golden carrot incorrectly counted as personal ration");
        c.insertIntoInventory(new ItemStack(Items.BREAD));c.adjustNeeds(60-c.nutrition(),0,0);
        CommunityLifeService.personal(h.getLevel(),f.data,f.s,c,e);
        h.assertTrue(count(c,new ItemStack(Items.GOLDEN_CARROT))==1 && count(c,new ItemStack(Items.PUFFERFISH))==1 &&
                count(c,new ItemStack(Items.BREAD))==0 && c.nutrition()>60,
                "Hunger ate paid ingredient instead of ordinary bread");
        roundTrip(f,c,"protected reagent");
        f.clean();passed();h.succeed();
    }

    @GameTest(templateNamespace="exiledsettlement_priest",template="empty",timeoutTicks=100)
    public static void ordinaryRestLogisticsCannotDeliverOwnedBrewingInputs(GameTestHelper h) {
        var f=fixture(h,1);var c=f.workers.getFirst();var level=h.getLevel();
        carryReserved(f,Items.NETHER_WART,Items.GLISTERING_MELON_SLICE,Items.BLAZE_POWDER);
        long daytime=level.getDayTime();
        try {
            level.setDayTime(13000+CitizenWorkService.scheduleOffset(c));
            h.assertTrue(CitizenWorkService.schedulePhase(level.getDayTime(),c)==CitizenWorkService.SchedulePhase.REST,
                    "Rest fixture is not resting");
            h.assertTrue(!CitizenWorkService.farmerDeliveryReady(level,f.s,c) &&
                    CitizenWorkService.farmerDeliverySlot(f.s,c)<0,"Rest hauler adopted paid brewing cargo");
            for(int slot=0;slot<CitizenRecord.INVENTORY_SIZE;slot++)
                h.assertTrue(CitizenEquipmentService.deliverableCount(c,slot)==0,"Owned brewing input is deliverable");
            c.insertIntoInventory(new ItemStack(Items.COBBLESTONE,5));
            int slot=CitizenWorkService.farmerDeliverySlot(f.s,c);
            h.assertTrue(CitizenWorkService.farmerDeliveryReady(level,f.s,c) && slot>=0 &&
                    c.inventoryItem(slot).is(Items.COBBLESTONE) && CitizenEquipmentService.deliverableCount(c,slot)==5,
                    "Reservation suppressed unrelated ordinary cargo");
            var saved=c.life().brewing.save();
            PriestProductionService.work(level,f.data,f.s,c,f.actors.getFirst());
            h.assertTrue(c.life().brewing.save().equals(saved) && count(c,water())==3 &&
                    count(c,new ItemStack(Items.NETHER_WART))==1,"Rest altered paid task or bottles");
        } finally {level.setDayTime(daytime);}
        f.clean();passed();h.succeed();
    }

    @GameTest(templateNamespace="exiledsettlement_priest",template="empty",timeoutTicks=300)
    public static void churchFourRotationsCapacityAndOldDefaults(GameTestHelper h) {
        h.assertTrue(CitizenProfession.TEACHER.ordinal()==14 && CitizenProfession.PRIEST.ordinal()==15,
                "Historical profession ordinals changed");
        int offset=0;
        for(var facing:Direction.Plane.HORIZONTAL) {
            var at=new BlockPos(710000+offset++*64,100,710000);
            var t=BlueprintTemplates.create(BuildingType.CHURCH,at,facing);
            for(var p:t.placements())h.getLevel().setBlock(p.position(),p.state(),2);
            var s=new SettlementRecord(UUID.randomUUID(),"Old church",at,UUID.randomUUID());
            var b=s.addBuilding(BuildingType.CHURCH,at,facing,t.minCorner(),t.maxCorner(),0,1);
            b.updateFunction(false,BuildingStatus.MISSING_CONTAINER,0,List.of());
            BuildingFunctionService.validateSingle(h.getLevel(),new SettlementSavedData(),s,b);
            h.assertTrue(b.operational() && b.workerCapacity()==2 && b.workerTarget()==0,
                    "Upgrade did not preserve unassigned old church "+facing);
            h.assertTrue(b.productionId().equals(BrewingRecipes.DEFAULT_PRODUCT) && b.brewStockTarget()==12,
                    "Default healing/12 missing");
            h.assertTrue(b.setWorkerTarget(2,b.configurationRevision()) && !b.setWorkerTarget(3,b.configurationRevision()),"Capacity limit missing");
            var stations=t.placements().stream().filter(p->p.state().is(Blocks.BREWING_STAND)).toList();
            h.getLevel().setBlock(stations.getFirst().position(),Blocks.AIR.defaultBlockState(),2);
            BuildingFunctionService.validateSingle(h.getLevel(),new SettlementSavedData(),s,b);
            h.assertTrue(b.operational() && b.workerCapacity()==1 && b.workerTarget()==2,"One remaining stand not reflected");
            h.getLevel().setBlock(stations.getLast().position(),Blocks.AIR.defaultBlockState(),2);
            BuildingFunctionService.validateSingle(h.getLevel(),new SettlementSavedData(),s,b);
            h.assertTrue(!b.operational() && b.workerCapacity()==0,"No stand still operational");
        }
        passed();h.succeed();
    }

    @GameTest(templateNamespace="exiledsettlement_priest",template="empty",timeoutTicks=300)
    public static void everyProductUsesNativeRecipesAndRealContainerReturns(GameTestHelper h) {
        var f=fixture(h,0);var l=h.getLevel();var be=stand(f,0);int products=0,steps=0,returns=0;
        var forms=new HashSet<Item>();
        for(var product:BrewingRecipes.products()) {
            var result=BrewingRecipes.stack(product);var plan=BrewingRecipes.plan(l,product);
            h.assertTrue(!result.isEmpty() && !plan.isEmpty(),"Listed product has no real recipe "+product);
            ItemStack previous=water();forms.add(result.getItem());products++;
            be.clearContent();be.setItem(4,new ItemStack(Items.BLAZE_POWDER));
            for(var step:plan) {
                h.assertTrue(ItemStack.isSameItemSameComponents(previous,step.input()),"Broken recipe chain "+product);
                h.assertTrue(l.potionBrewing().hasMix(step.input(),new ItemStack(step.ingredient())),"Step not accepted by server recipes");
                be.setItem(0,previous.copy());be.setItem(1,previous.copy());be.setItem(2,previous.copy());
                be.setItem(3,new ItemStack(step.ingredient()));
                BrewingStandBlockEntity.serverTick(l,be.getBlockPos(),be.getBlockState(),be);
                h.assertTrue(machine(f,0).getShort("BrewTime")==400,"Machine timer is not native 400 ticks");
                for(int t=0;t<399;t++)BrewingStandBlockEntity.serverTick(l,be.getBlockPos(),be.getBlockState(),be);
                h.assertTrue(ItemStack.isSameItemSameComponents(be.getItem(0),previous),"Potion finished early");
                BrewingStandBlockEntity.serverTick(l,be.getBlockPos(),be.getBlockState(),be);
                for(int i=0;i<3;i++)h.assertTrue(ItemStack.isSameItemSameComponents(be.getItem(i),step.output()),"Machine output mismatch "+product);
                var ingredient=new ItemStack(step.ingredient());
                if(ingredient.hasCraftingRemainingItem()) {
                    h.assertTrue(ItemStack.matches(be.getItem(3),ingredient.getCraftingRemainingItem()),"Lost native returned container");
                    returns++;
                } else h.assertTrue(be.getItem(3).isEmpty(),"Ingredient was not consumed once");
                previous=step.output();steps++;
            }
            h.assertTrue(ItemStack.isSameItemSameComponents(previous,result),"Final potion identity mismatch");
        }
        h.assertTrue(products>60 && forms.containsAll(List.of(Items.POTION,Items.SPLASH_POTION,Items.LINGERING_POTION))
                && returns>0,"Recipe coverage misses bottle forms/dragon breath");
        h.assertTrue(BrewingRecipes.plan(l,ResourceLocation.withDefaultNamespace("diamond")).isEmpty(),"Invalid output got recipe");
        System.out.println("PRIEST181_RECIPES products="+products+" steps="+steps+" returnedContainers="+returns);
        f.clean();passed();h.succeed();
    }

    @GameTest(templateNamespace="exiledsettlement_priest",template="empty",timeoutTicks=100)
    public static void bottleDisplayFlagsDoNotTriggerRepairOrChangeMachineContents(GameTestHelper h) {
        var f=fixture(h,0);var be=stand(f,0);be.setItem(0,water());be.setItem(4,new ItemStack(Items.BLAZE_POWDER));
        BrewingStandBlockEntity.serverTick(h.getLevel(),be.getBlockPos(),be.getBlockState(),be);
        var before=machine(f,0);
        h.assertTrue(BlueprintService.matchesPlacedState(be.getBlockState(),Blocks.BREWING_STAND.defaultBlockState()),
                "Native bottle display flagged as structure damage");
        BuildingFunctionService.validateSingle(h.getLevel(),f.data,f.s,f.church);
        h.assertTrue(f.church.operational() && f.church.workerCapacity()==2 && before.equals(machine(f,0)),
                "Facility refresh altered real bottles/fuel");
        h.assertTrue(before.getByte("Fuel")==20 && count(be,water())==1,"Native fuel/container fixture invalid");
        f.clean();passed();h.succeed();
    }

    @GameTest(templateNamespace="exiledsettlement_priest",template="empty",timeoutTicks=100)
    public static void namedWaterMissingInputsAndOccupiedStandsCannotBeConsumed(GameTestHelper h) {
        var f=fixture(h,1);var c=f.workers.getFirst();
        var named=water();named.set(DataComponents.CUSTOM_NAME,Component.literal("Player's water"));
        put(f,named,3);fund(f,0,1,1,1);
        h.assertTrue(!PriestProductionService.assign(h.getLevel(),f.s,c),"Named custom water accepted as ordinary reagent");
        h.assertTrue(stored(f,named)==3 && stored(f,new ItemStack(Items.NETHER_WART))==1,"Rejected recipe changed stock");
        f.clean();
        var occupied=fixture(h,1);fund(occupied,3,1,1,1);
        for(int i=0;i<2;i++)stand(occupied,i).setItem(0,PotionContents.createItemStack(Items.POTION,Potions.POISON));
        h.assertTrue(!PriestProductionService.assign(h.getLevel(),occupied.s,occupied.workers.getFirst()),"Player-owned occupied stands were adopted");
        for(int i=0;i<2;i++)h.assertTrue(count(stand(occupied,i),PotionContents.createItemStack(Items.POTION,Potions.POISON))==1,"Player potion removed");
        h.assertTrue(stored(occupied,water())==3,"Occupied stand consumed water");
        occupied.clean();
        var missing=fixture(h,1);fund(missing,3,1,0,1);
        var worker=missing.workers.getFirst();begin(missing);
        var e=missing.actors.getFirst();var feet=missing.stock.position().east();
        e.moveTo(feet.getX()+.5,feet.getY(),feet.getZ()+.5,0,0);e.setOnGround(true);
        for(int i=0;i<10;i++)PriestProductionService.work(h.getLevel(),missing.data,missing.s,worker,e);
        h.assertTrue(worker.life().brewing!=null && worker.life().brewing.phase==PriestBrewingTask.FETCH &&
                stored(missing,water())+count(worker,water())==3,"Missing material lost water or advanced machine");
        h.assertTrue(stored(missing,BrewingRecipes.stack(BrewingRecipes.DEFAULT_PRODUCT))==0,"Missing melon produced healing");
        missing.clean();passed();h.succeed();
    }

    @GameTest(templateNamespace="exiledsettlement_priest",template="empty",timeoutTicks=100)
    public static void twoPriestsReserveOnlyExactTargetAndKeepBatchAcrossProductChange(GameTestHelper h) {
        var f=fixture(h,2);fund(f,9,3,3,2);f.church.setBrewStockTarget(3,f.church.configurationRevision());
        var first=f.workers.getFirst();var second=f.workers.getLast();
        h.assertTrue(PriestProductionService.assign(h.getLevel(),f.s,first),"First target reservation failed");
        var template=BlueprintTemplates.create(BuildingType.CHURCH,f.at.offset(32,0,8),Direction.SOUTH);
        for(var cell:template.placements())h.getLevel().setBlock(cell.position(),cell.state(),2);
        var otherChurch=f.s.addBuilding(BuildingType.CHURCH,template.anchor(),Direction.SOUTH,
                template.minCorner(),template.maxCorner(),0,BlueprintTemplates.currentTemplateVersion(BuildingType.CHURCH));
        BuildingFunctionService.validateSingle(h.getLevel(),f.data,f.s,otherChurch);
        otherChurch.setWorkerTarget(1,otherChurch.configurationRevision());
        otherChurch.setBrewStockTarget(3,otherChurch.configurationRevision());
        second.assignProfession(CitizenProfession.PRIEST,otherChurch.id());
        h.assertTrue(!PriestProductionService.assign(h.getLevel(),f.s,second),"Second church duplicated settlement-wide reserved target");
        var before=first.life().save().copy();
        var other=BrewingRecipes.products().stream().filter(id->!id.equals(BrewingRecipes.DEFAULT_PRODUCT)).findFirst().orElseThrow();
        h.assertTrue(f.church.setProductionId(other),"Valid product change failed");
        h.assertTrue(first.life().save().equals(before),"Product change rewrote already claimed batch");
        roundTrip(f,first,"fetch");
        var noBagProduct=first.life().brewing.save();
        noBagProduct.remove("BagProduct");
        var restoredTask=PriestBrewingTask.load(noBagProduct);
        h.assertTrue(restoredTask!=null && restoredTask.bagProduct==null && restoredTask.save().equals(noBagProduct),
                "Missing optional bag product gained an empty resource ID on reload");
        var loaded=SettlementRecord.load(f.s.save(h.getLevel().registryAccess()),h.getLevel().registryAccess());
        h.assertTrue(loaded.building(f.church.id()).productionId().equals(other) &&
                loaded.building(f.church.id()).brewStockTarget()==3,"Save lost selection/target");
        f.clean();passed();h.succeed();
    }

    @GameTest(templateNamespace="exiledsettlement_priest",template="empty",timeoutTicks=100)
    public static void stockTargetRevisionBoundsAndLegacyDefaultsPersist(GameTestHelper h) {
        var f=fixture(h,0);int revision=f.church.configurationRevision();
        h.assertTrue(!f.church.setBrewStockTarget(-1,revision) && !f.church.setBrewStockTarget(193,revision),
                "Out-of-range stock target accepted");
        h.assertTrue(f.church.setBrewStockTarget(0,revision),"Zero target should disable new batches");
        h.assertTrue(!f.church.setBrewStockTarget(192,revision),"Stale target revision accepted");
        h.assertTrue(f.church.setBrewStockTarget(192,f.church.configurationRevision()),"Maximum target rejected");
        h.assertTrue(!f.stock.setBrewStockTarget(12,f.stock.configurationRevision()),"Non-church acquired brewing target");
        var restored=SettlementRecord.load(f.s.save(h.getLevel().registryAccess()),h.getLevel().registryAccess());
        h.assertTrue(restored.building(f.church.id()).brewStockTarget()==192,"Stock target did not persist");
        var old=f.s.save(h.getLevel().registryAccess());
        for(var tag:old.getList("Buildings",10))((CompoundTag)tag).remove("BrewStockTarget");
        var legacy=SettlementRecord.load(old,h.getLevel().registryAccess());
        h.assertTrue(legacy.building(f.church.id()).brewStockTarget()==12,"Legacy target not 12");
        f.clean();passed();h.succeed();
    }

    @GameTest(templateNamespace="exiledsettlement_priest",template="empty",timeoutTicks=6000)
    public static void twoRealPriestsFetchBrewDeliverAndReuseNativeFuel(GameTestHelper h) {
        var f=fixture(h,2);var level=h.getLevel();fund(f,12,4,4,2);
        for(int n=0;n<185;n++)f.workers.getLast().professionExperience().addPoint(CitizenProfession.PRIEST);
        begin(f);
        h.assertTrue(!f.workers.getFirst().life().brewing.stand.equals(f.workers.getLast().life().brewing.stand),
                "Two automatic jobs claimed the same native stand");
        var healing=BrewingRecipes.stack(BrewingRecipes.DEFAULT_PRODUCT);
        double[] moved={0,0};Vec3[] previous=f.actors.stream().map(e->e.position()).toArray(Vec3[]::new);
        boolean[] entered={false,false};boolean[] fetchSaved={false,false},machineSaved={false,false},deliverySaved={false,false};
        boolean[] restarted={false};long[] secondAfter={-1};long start=level.getGameTime();
        h.startSequence().thenWaitUntil(()->{
            for(int i=0;i<2;i++) {
                var c=f.workers.get(i);var e=f.actors.get(i);moved[i]+=previous[i].distanceTo(e.position());previous[i]=e.position();
                if(c.life().brewing!=null) {
                    int phase=c.life().brewing.phase;
                    if(!fetchSaved[i] && phase==0) {roundTrip(f,c,"fetch");fetchSaved[i]=true;}
                    if(!machineSaved[i] && phase==2) {roundTrip(f,c,"machine");machineSaved[i]=true;}
                    if(!deliverySaved[i] && phase==4) {roundTrip(f,c,"delivery");deliverySaved[i]=true;}
                    if(phase==2)entered[i]|=f.church.contains(e.blockPosition()) && level.noCollision(e);
                }
            }
            tick(f,start);
            int bottles=stored(f,healing);
            if(!restarted[0] && bottles==6 && f.workers.stream().allMatch(c->c.life().brewing==null)) {
                if(secondAfter[0]<0)secondAfter[0]=level.getGameTime()+21;
                h.assertTrue(stored(f,new ItemStack(Items.BLAZE_POWDER))==0,"First round did not use two real fuel items");
                h.assertTrue(machine(f,0).getByte("Fuel")+machine(f,1).getByte("Fuel")==36,"First round should spend two uses per stand");
                if(level.getGameTime()>=secondAfter[0]) {begin(f);restarted[0]=true;}
            }
            if((level.getGameTime()-start)%200==0)
                System.out.println("PRIEST181_REAL_TRACE tick="+(level.getGameTime()-start)+" delivered="+bottles+" jobs="+
                        f.workers.stream().map(c->(c.life().brewing==null?"idle":c.life().brewing.phase+":"+c.life().brewing.step)+":"+c.workFailure()).toList());
            h.assertTrue(restarted[0] && stored(f,healing)==12 && f.workers.stream().allMatch(c->c.life().brewing==null),
                    "Waiting for two real rounds and warehouse delivery");
            for(int i=0;i<2;i++)h.assertTrue(entered[i] && moved[i]>10 && fetchSaved[i] && machineSaved[i] && deliverySaved[i],
                    "Missing physical movement/three-stage save evidence for worker "+i);
            h.assertTrue(stored(f,water())==0 && stored(f,new ItemStack(Items.NETHER_WART))==0 &&
                    stored(f,new ItemStack(Items.GLISTERING_MELON_SLICE))==0 &&
                    stored(f,new ItemStack(Items.BLAZE_POWDER))==0,"Finite recipe supplies not conserved");
            h.assertTrue(machine(f,0).getByte("Fuel")+machine(f,1).getByte("Fuel")==32,"Second round failed to reuse residual native fuel");
            for(var c:f.workers)h.assertTrue(count(c,healing)==0 && !PriestProductionService.assign(level,f.s,c),"Target overproduced or cargo remained");
            System.out.println("PRIEST181_REAL_COMPLETE ticks="+(level.getGameTime()-start)+" moved="+Arrays.toString(moved)+
                    " water=12 wart=4 melon=4 blazePowder=2 healing=12 fuelRemaining=32");
        }).thenExecute(()->{f.clean();passed();}).thenSucceed();
    }

    @GameTest(templateNamespace="exiledsettlement_priest",template="empty",timeoutTicks=4000)
    public static void realDeliveryKeepsPotionsThroughBlockedAndFullWarehouse(GameTestHelper h) {
        var f=fixture(h,1);var level=h.getLevel();fund(f,3,1,1,1);
        f.church.setBrewStockTarget(3,f.church.configurationRevision());begin(f);
        var c=f.workers.getFirst();var healing=BrewingRecipes.stack(BrewingRecipes.DEFAULT_PRODUCT);
        long start=level.getGameTime();long[] until={-1};int[] obstacle={0};double[] moved={0};
        Vec3[] previous={f.actors.getFirst().position()};
        h.startSequence().thenWaitUntil(()->{
            moved[0]+=previous[0].distanceTo(f.actors.getFirst().position());previous[0]=f.actors.getFirst().position();
            if(obstacle[0]==0 && c.life().brewing!=null && c.life().brewing.phase==4 && count(c,healing)==3) {
                roundTrip(f,c,"blocked delivery");
                for(var p:BlockPos.betweenClosed(f.stock.position().offset(-1,0,-1),f.stock.position().offset(1,3,1)))
                    if(!p.equals(f.stock.position()))level.setBlock(p,Blocks.BEDROCK.defaultBlockState(),2);
                obstacle[0]=1;until[0]=level.getGameTime()+120;
            }
            if(obstacle[0]==1 && level.getGameTime()>=until[0]) {
                h.assertTrue(count(c,healing)==3 && c.life().brewing!=null && stored(f,healing)==0,"Blocked warehouse remotely stored or lost cargo");
                for(var p:BlockPos.betweenClosed(f.stock.position().offset(-1,0,-1),f.stock.position().offset(1,3,1)))
                    if(!p.equals(f.stock.position()))level.setBlock(p,Blocks.AIR.defaultBlockState(),2);
                h.assertTrue(WarehouseInventoryService.insert(level,f.stock,new ItemStack(Items.COBBLESTONE,WarehouseStock.CAPACITY)).isEmpty(),"Full stock fixture failed");
                obstacle[0]=2;until[0]=level.getGameTime()+120;
            }
            if(obstacle[0]==2 && level.getGameTime()>=until[0]) {
                h.assertTrue(count(c,healing)==3 && c.life().brewing!=null && stored(f,healing)==0,"Full warehouse lost/replayed cargo");
                WarehouseInventoryService.extractItem(level,f.stock,Items.COBBLESTONE,3);
                obstacle[0]=3;
            }
            tick(f,start);
            h.assertTrue(obstacle[0]==3 && c.life().brewing==null,"Waiting for restored legal delivery");
            h.assertTrue(stored(f,healing)==3 && count(c,healing)==0 && moved[0]>10,"Actual recovery delivery did not conserve potions");
            System.out.println("PRIEST181_BLOCKED_COMPLETE ticks="+(level.getGameTime()-start)+" moved="+moved[0]+" potions=3 blockedAndFullWait=240");
        }).thenExecute(()->{f.clean();passed();}).thenSucceed();
    }

    @GameTest(templateNamespace="exiledsettlement_priest",template="empty",timeoutTicks=3000)
    public static void restRepairAndPlayerInterferencePreserveRealMachineAndCargo(GameTestHelper h) {
        var f=fixture(h,1);var level=h.getLevel();fund(f,3,1,1,1);begin(f);
        var c=f.workers.getFirst();var e=f.actors.getFirst();long start=level.getGameTime();
        long[] idleUntil={-1};BlockPos[] chosen={null};
        h.startSequence().thenWaitUntil(()->{
            tick(f,start);
            h.assertTrue(c.life().brewing!=null && c.life().brewing.phase==PriestBrewingTask.BREW,
                    "Waiting for a genuinely started native step");
            chosen[0]=c.life().brewing.stand;
            var be=(BrewingStandBlockEntity)level.getBlockEntity(chosen[0]);
            h.assertTrue(be.saveWithoutMetadata(level.registryAccess()).getShort("BrewTime")>0,
                    "Waiting for native machine countdown");
        }).thenExecute(()->{
            roundTrip(f,c,"resting machine");
            idleUntil[0]=level.getGameTime()+420;
        }).thenWaitUntil(()->{
            h.assertTrue(level.getGameTime()>=idleUntil[0],"Native brewing continues while worker takes no actions");
        }).thenExecute(()->{
            var be=(BrewingStandBlockEntity)level.getBlockEntity(chosen[0]);
            var awkward=PotionContents.createItemStack(Items.POTION,Potions.AWKWARD);
            h.assertTrue(count(be,awkward)==3 && be.getItem(3).isEmpty() &&
                    be.saveWithoutMetadata(level.registryAccess()).getShort("BrewTime")==0,
                    "Unattended started step did not finish with actual ingredient");
            var before=be.saveWithoutMetadata(level.registryAccess());
            var task=c.life().brewing.save();
            long day=level.getDayTime();
            try {
                level.setDayTime(23000+CitizenWorkService.scheduleOffset(c));
                PriestProductionService.work(level,f.data,f.s,c,e);
                h.assertTrue(before.equals(be.saveWithoutMetadata(level.registryAccess())) && task.equals(c.life().brewing.save()),
                        "Resting worker advanced or reset a batch");
            } finally {level.setDayTime(day);}
            f.church.updateFunction(false,BuildingStatus.MISSING_CONTAINER,0,List.of());
            ProfessionAssignmentService.refresh(f.data,f.s);
            h.assertTrue(c.profession()==CitizenProfession.PRIEST && c.life().brewing!=null,
                    "Repair reassigned an unfinished brewer");
            PriestProductionService.work(level,f.data,f.s,c,e);
            h.assertTrue(before.equals(be.saveWithoutMetadata(level.registryAccess())),"Repair changed real machine");
            BuildingFunctionService.validateSingle(level,f.data,f.s,f.church);
            var playerPotion=PotionContents.createItemStack(Items.POTION,Potions.POISON);
            playerPotion.set(DataComponents.CUSTOM_NAME,Component.literal("Player replacement"));
            be.setItem(0,playerPotion);
            PriestProductionService.work(level,f.data,f.s,c,e);
            h.assertTrue(ItemStack.matches(be.getItem(0),playerPotion) && count(be,awkward)==2,
                    "Interference consumed player potion or manufactured replacement");
            h.assertTrue(count(c,BrewingRecipes.stack(BrewingRecipes.DEFAULT_PRODUCT))==0 &&
                    stored(f,BrewingRecipes.stack(BrewingRecipes.DEFAULT_PRODUCT))==0,
                    "Stale task manufactured final healing");
            h.assertTrue(count(c,new ItemStack(Items.GLISTERING_MELON_SLICE))+
                    stored(f,new ItemStack(Items.GLISTERING_MELON_SLICE))==1,
                    "Interference deleted unconsumed melon");
            System.out.println("PRIEST181_INTERRUPTION nativeUnattendedTicks=420 awkward=2 playerReplacement=1 finalHealing=0");
            f.clean();passed();
        }).thenSucceed();
    }

    @GameTest(templateNamespace="exiledsettlement_priest",template="empty",timeoutTicks=4000)
    public static void priestBatchesSurviveWorldRestart(GameTestHelper h) {
        var level=h.getLevel();var data=SettlementSavedData.get(level);
        var existing=data.settlements().stream().flatMap(s->s.citizens().stream())
                .filter(c->c.name().startsWith(RESTART_NAME)).toList();
        if(!existing.isEmpty()) {
            h.assertTrue(existing.size()==3,"Missing one of the three saved stages");
            for(var c:existing) {
                var task=c.life().brewing;
                h.assertTrue(task!=null && task.product.equals(BrewingRecipes.DEFAULT_PRODUCT) && task.amount==3,
                        "Reload lost batch metadata "+c.name());
                h.assertTrue(c.professionExperience().experience(CitizenProfession.PRIEST)==35,
                        "Reload lost priest experience");
                var owner=data.settlements().stream().filter(s->s.citizens().contains(c)).findFirst().orElseThrow();
                h.assertTrue(owner.building(task.church).brewStockTarget()==3,"Reload lost church target");
                if(c.name().endsWith("Fetch")) {
                    h.assertTrue(task.phase==PriestBrewingTask.FETCH && task.bottlesInBag==0,"Fetch phase changed offline");
                    h.assertTrue(PriestProductionService.stockCount(level,owner,water())==3,"Unfetched bottles changed offline");
                } else if(c.name().endsWith("Machine")) {
                    level.getChunkAt(task.stand);
                    var be=(BrewingStandBlockEntity)level.getBlockEntity(task.stand);
                    h.assertTrue(be!=null && task.phase==PriestBrewingTask.BREW,"Native stand/task did not survive");
                    int bottles=0;for(int i=0;i<3;i++)if(be.getItem(i).is(Items.POTION))bottles+=be.getItem(i).getCount();
                    h.assertTrue(bottles==3 && count(c,water())==0 &&
                            count(c,BrewingRecipes.stack(BrewingRecipes.DEFAULT_PRODUCT))==0,
                            "Machine save duplicated or lost physical bottles");
                    h.assertTrue(be.saveWithoutMetadata(level.registryAccess()).getByte("Fuel")==19,
                            "Native remaining fuel was not preserved");
                } else {
                    h.assertTrue(task.phase==PriestBrewingTask.DELIVER &&
                            count(c,BrewingRecipes.stack(BrewingRecipes.DEFAULT_PRODUCT))==3,
                            "Delivery save lost precise potion components");
                }
            }
            System.out.println("PRIEST181_RESTART loaded stages=fetch,machine,delivery priestExperience=35 physicalBottles=9");
            passed();h.succeed();return;
        }
        var fetch=fixture(h,1);var machine=fixture(h,1);var delivery=fixture(h,1);
        var fs=List.of(fetch,machine,delivery);String[] names={"Fetch","Machine","Delivery"};
        for(int i=0;i<3;i++) {
            var f=fs.get(i);fund(f,3,1,1,1);
            f.church.setBrewStockTarget(3,f.church.configurationRevision());
            var c=f.workers.getFirst();c.rename(RESTART_NAME+names[i]);
            for(int n=0;n<35;n++)c.professionExperience().addPoint(CitizenProfession.PRIEST);
            begin(f);
        }
        boolean[] reached={true,false,false};long start=level.getGameTime();
        h.startSequence().thenWaitUntil(()->{
            for(int i=1;i<3;i++)if(!reached[i]) {
                var f=fs.get(i);var c=f.workers.getFirst();tick(f,start);
                if(c.life().brewing==null)continue;
                if(i==1 && c.life().brewing.phase==PriestBrewingTask.BREW) {
                    var be=(BrewingStandBlockEntity)level.getBlockEntity(c.life().brewing.stand);
                    reached[i]=be.saveWithoutMetadata(level.registryAccess()).getShort("BrewTime")>0;
                }
                if(i==2 && c.life().brewing.phase==PriestBrewingTask.DELIVER)
                    reached[i]=count(c,BrewingRecipes.stack(BrewingRecipes.DEFAULT_PRODUCT))==3;
                if(reached[i])roundTrip(f,c,names[i]);
            }
            h.assertTrue(reached[1] && reached[2],"Waiting for genuine machine and delivery save points");
        }).thenExecute(()->{
            for(var f:fs){roundTrip(f,f.workers.getFirst(),"world exit");f.clean();}
            data.touch();
            System.out.println("PRIEST181_RESTART prepared stages=fetch,machine,delivery priestExperience=35 physicalBottles=9");
            passed();
        }).thenSucceed();
    }
}
