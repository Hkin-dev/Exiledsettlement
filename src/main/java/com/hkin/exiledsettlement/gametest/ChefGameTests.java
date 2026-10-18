package com.hkin.exiledsettlement.gametest;

import com.hkin.exiledsettlement.blueprint.*;
import com.hkin.exiledsettlement.building.*;
import com.hkin.exiledsettlement.citizen.*;
import com.hkin.exiledsettlement.settlement.*;
import com.hkin.exiledsettlement.registry.ModEntities;
import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.Container;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.*;
import java.util.*;

@GameTestHolder("exiledsettlement_chef")
@PrefixGameTestTemplate(false)
public final class ChefGameTests {
    private record Fixture(SettlementRecord s, BuildingRecord kitchen, BuildingRecord stock, Container inventory, CitizenRecord chef) {}
    private static int serial;
    private static Fixture fixture(GameTestHelper h) {
        var core=h.absolutePos(new BlockPos(72000+serial++*96,10,72000));
        var s=new SettlementRecord(UUID.randomUUID(),"Chef rules",core,UUID.randomUUID());
        s.community().mealTarget=1;s.community().foodReserve=0;s.community().fuelReserve=0;
        var k=s.addBuilding(BuildingType.TAVERN,core,Direction.SOUTH,core,core.offset(16,10,14),0,2);
        k.updateFunction(true,BuildingStatus.OPERATIONAL,1,List.of(core));
        var stock=s.addBuilding(BuildingType.WAGON,core.west(5));
        h.getLevel().setBlock(stock.position(),Blocks.BARREL.defaultBlockState(),3);
        stock.updateFunction(true,BuildingStatus.OPERATIONAL,27,List.of(stock.position()));
        var inv=(Container)h.getLevel().getBlockEntity(stock.position());
        inv.setItem(0,new ItemStack(Items.WHEAT,6));inv.setItem(1,new ItemStack(Items.CHARCOAL,2));
        var c=s.citizens().iterator().next();c.setManualAssignment(CitizenProfession.CHEF,k.id());
        h.getLevel().setDayTime(6000);
        return new Fixture(s,k,stock,inv,c);
    }
    @GameTest(templateNamespace="exiledsettlement_chef",template="empty")
    public static void chefStaffingUsesBuildingTargetsAndSurvivesReload(GameTestHelper h) {
        var f=fixture(h);var data=new SettlementSavedData();f.chef.clearManualAssignment();f.chef.assignProfession(CitizenProfession.WORKER,null);
        h.assertTrue(data.setBuildingWorkerTarget(f.s,f.s.ownerId(),f.kitchen.id(),1,f.kitchen.configurationRevision()),"Cannot enable kitchen slot");
        h.assertTrue(!data.setBuildingWorkerTarget(f.s,f.s.ownerId(),f.kitchen.id(),2,f.kitchen.configurationRevision()),"Kitchen exceeded one chef");
        ProfessionAssignmentService.refresh(data,f.s);
        h.assertTrue(f.s.professionActual(CitizenProfession.CHEF)==1 && f.s.professionCapacity(CitizenProfession.CHEF)==1,"Wrong chef target/capacity");
        var assigned=f.s.citizens().stream().filter(c->c.profession()==CitizenProfession.CHEF).findFirst().orElseThrow();
        h.assertTrue(data.setCitizenManualAssignment(f.s,f.s.ownerId(),assigned.id(),CitizenProfession.CHEF,f.kitchen.id()),"Manual chef unavailable");
        var loaded=SettlementRecord.load(f.s.save(h.getLevel().registryAccess()),h.getLevel().registryAccess());
        ProfessionAssignmentService.refresh(data,loaded);
        h.assertTrue(loaded.citizen(assigned.id()).profession()==CitizenProfession.CHEF
                && loaded.citizen(assigned.id()).hasManualAssignment() && loaded.professionTarget(CitizenProfession.CHEF)==1,"Reload lost kitchen assignment");
        var old=f.s.addBuilding(BuildingType.RESTAURANT,f.kitchen.position().east(40));old.updateFunction(true,BuildingStatus.OPERATIONAL,1,List.of(old.position()));
        var worker=f.s.citizens().stream().filter(c->c!=assigned).findFirst().orElseThrow();
        h.assertTrue(CitizenProfession.forWorkplace(BuildingType.RESTAURANT)==CitizenProfession.CHEF
                && data.setCitizenManualAssignment(f.s,f.s.ownerId(),worker.id(),CitizenProfession.CHEF,old.id()),"Legacy restaurant cannot employ chef");
        h.assertTrue(CitizenProfession.MINER.ordinal()==11 && CitizenProfession.CHEF.ordinal()==12,"Old profession wire ordinals changed");
        h.succeed();
    }
    @GameTest(templateNamespace="exiledsettlement_chef",template="empty")
    public static void onlyOwnChefStartsAndPendingMealsReserveTarget(GameTestHelper h) {
        var f=fixture(h);var worker=f.s.citizens().stream().filter(c->c!=f.chef).findFirst().orElseThrow();
        h.assertTrue(!CommunityCookingService.assign(h.getLevel(),f.s,worker),"Worker stole cooking job");
        h.assertTrue(CommunityCookingService.assign(h.getLevel(),f.s,f.chef),"Assigned chef did not start");
        h.assertTrue(f.kitchen.id().equals(f.chef.life().destination),"Chef selected another kitchen");
        var second=f.s.addBuilding(BuildingType.RESTAURANT,f.kitchen.position().east(50));second.updateFunction(true,BuildingStatus.OPERATIONAL,1,List.of(second.position()));
        worker.setManualAssignment(CitizenProfession.CHEF,second.id());
        h.assertTrue(!CommunityCookingService.assign(h.getLevel(),f.s,worker),"Two kitchens duplicated the same target batch");
        var same=f.s.citizens().stream().filter(c->c!=f.chef && c!=worker).findFirst().orElseThrow();same.setManualAssignment(CitizenProfession.CHEF,f.kitchen.id());
        h.assertTrue(!CommunityCookingService.assign(h.getLevel(),f.s,same),"Same kitchen ran two batches");
        h.assertTrue(!CommunityCookingService.assign(h.getLevel(),f.s,f.chef),"Existing batch overwritten");h.succeed();
    }
    @GameTest(templateNamespace="exiledsettlement_chef",template="empty")
    public static void reservesMissingInputsAndDisabledTargetsStartNothing(GameTestHelper h) {
        var fuel=fixture(h);fuel.s.community().fuelReserve=2;
        h.assertTrue(!CommunityCookingService.assign(h.getLevel(),fuel.s,fuel.chef) && CommunityCookingService.waitingReason(fuel.chef).contains("燃料"),"Fuel reserve ignored");
        var missing=fixture(h);missing.inventory.setItem(0,ItemStack.EMPTY);CommunityStock.invalidate(h.getLevel(),missing.s);
        h.assertTrue(!CommunityCookingService.assign(h.getLevel(),missing.s,missing.chef) && missing.chef.life().task.isEmpty(),"Missing ingredient created batch");
        var stopped=fixture(h);stopped.s.community().mealTarget=0;
        h.assertTrue(!CommunityCookingService.assign(h.getLevel(),stopped.s,stopped.chef),"Disabled production ran");
        var food=fixture(h);food.inventory.setItem(0,new ItemStack(Items.POTATO,4));food.s.community().foodReserve=4;CommunityStock.invalidate(h.getLevel(),food.s);
        h.assertTrue(!CommunityCookingService.assign(h.getLevel(),food.s,food.chef),"Public food reserve consumed");h.succeed();
    }
    @GameTest(templateNamespace="exiledsettlement_chef",template="empty")
    public static void fundedBatchPausesSavesAndReassignmentKeepsCargo(GameTestHelper h) {
        var f=fixture(h);var c=f.chef;var l=c.life();
        h.assertTrue(CommunityCookingService.assign(h.getLevel(),f.s,c),"No initial chef batch");
        c.insertIntoInventory(new ItemStack(Items.WHEAT,3));c.insertIntoInventory(new ItemStack(Items.CHARCOAL));
        l.cookingStage=2;l.cookingFuel="minecraft:charcoal";l.workTicks=160;l.taskStarted=h.getLevel().getGameTime()-10000;
        var actor=ModEntities.SETTLEMENT_CITIZEN.get().create(h.getLevel());actor.moveTo(f.kitchen.position().getX(),f.kitchen.position().getY(),f.kitchen.position().getZ());
        h.getLevel().setDayTime(23000+CitizenWorkService.scheduleOffset(c));
        h.assertTrue(!CommunityCookingService.work(h.getLevel(),new SettlementSavedData(),f.s,c,actor) && l.workTicks==160 && l.cookingStage==2,"Rest consumed or reset batch");
        h.getLevel().setDayTime(6000);
        c.clearManualAssignment();f.kitchen.setWorkerTarget(1,f.kitchen.configurationRevision());
        f.kitchen.updateFunction(false,BuildingStatus.MISSING_CONTAINER,0,List.of());
        ProfessionAssignmentService.refresh(new SettlementSavedData(),f.s);
        h.assertTrue(c.profession()==CitizenProfession.CHEF && f.kitchen.id().equals(c.workBuildingId()),"Repair reassigned automatic chef with funded batch");
        CommunityCookingService.work(h.getLevel(),new SettlementSavedData(),f.s,c,actor);
        h.assertTrue(l.workTicks==160 && !l.task.isEmpty() && !c.hasInventoryItem(s->s.is(Items.BREAD)),"Repair/time elapsed discarded or completed batch");
        var restored=CitizenRecord.load(c.save(h.getLevel().registryAccess()),h.getLevel().registryAccess());
        h.assertTrue(restored.profession()==CitizenProfession.CHEF && restored.life().workTicks==160 && restored.life().cookingStage==2
                && restored.hasInventoryItem(s->s.is(Items.WHEAT)) && restored.hasInventoryItem(s->s.is(Items.CHARCOAL)),"Saved batch lost materials/progress");
        c.setManualAssignment(CitizenProfession.CHEF,f.kitchen.id());
        c.requestManualRelease();h.assertTrue(!c.completeManualReleaseIfSafe(),"Manual unlock abandoned funded batch");
        c.setManualAssignment(CitizenProfession.WORKER,null);
        CommunityCookingService.work(h.getLevel(),new SettlementSavedData(),f.s,c,actor);
        h.assertTrue(l.task.isEmpty() && c.hasInventoryItem(s->s.is(Items.WHEAT)) && c.hasInventoryItem(s->s.is(Items.CHARCOAL)),"Reassignment cooked/lost cargo");
        actor.discard();h.succeed();
    }
    @GameTest(templateNamespace="exiledsettlement_chef",template="empty")
    public static void eightRecipesConsumeOnceAndMissingFuelIsAtomic(GameTestHelper h) throws Exception {
        try(var stream=ChefGameTests.class.getResourceAsStream("/data/exiledsettlement/community/meals.json")) {
            var recipes=com.google.gson.JsonParser.parseReader(new java.io.InputStreamReader(stream)).getAsJsonArray();
            h.assertTrue(recipes.size()==8,"Meal coverage changed");
            for(var value:recipes) {
                var r=value.getAsJsonObject();var f=fixture(h);var l=f.chef.life();
                l.cookingInput=r.get("input").getAsString();l.cookingOutput=r.get("output").getAsString();
                l.cookingInputCount=r.get("input_count").getAsInt();l.cookingOutputCount=r.get("output_count").getAsInt();
                l.cookingFuel="minecraft:charcoal";l.cookingStage=2;
                Item input=BuiltInRegistries.ITEM.get(ResourceLocation.parse(l.cookingInput)),output=BuiltInRegistries.ITEM.get(ResourceLocation.parse(l.cookingOutput));
                f.chef.insertIntoInventory(new ItemStack(input,l.cookingInputCount));
                h.assertTrue(!CommunityCookingService.completeBatch(f.s,f.chef),"Unfunded recipe completed");
                f.chef.insertIntoInventory(new ItemStack(Items.CHARCOAL));
                h.assertTrue(CommunityCookingService.completeBatch(f.s,f.chef),"Funded recipe failed "+l.cookingInput);
                h.assertTrue(!f.chef.hasInventoryItem(s->s.is(input) || s.is(Items.CHARCOAL))
                        && f.chef.inventory().stream().filter(s->s.is(output)).mapToInt(ItemStack::getCount).sum()==l.cookingOutputCount,"Incorrect debit or output");
                h.assertTrue(!CommunityCookingService.completeBatch(f.s,f.chef),"Recipe replayed");
            }
        }h.succeed();
    }
    @GameTest(templateNamespace="exiledsettlement_chef",template="empty",timeoutTicks=2400)
    public static void physicalChefReturnsMealsAfterFullWarehouseWait(GameTestHelper h) {
        DistinctBuildingGameTests.cookWalksIntoApprovedTavernAndReturnsRealMeals(h);
    }
}
