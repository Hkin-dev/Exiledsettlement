package com.hkin.exiledsettlement.debug;

import com.hkin.exiledsettlement.citizen.*;
import com.hkin.exiledsettlement.settlement.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.*;
import net.minecraft.network.chat.Component;
import java.util.*;

/** Test-harness-only checks on detached records; does not touch a world block or registered inventory. */
public final class WarehouseStewardChecks {
    private WarehouseStewardChecks() { }
    private static void check(boolean ok,String name,List<String> checks) {
        if(!ok)throw new IllegalStateException(name);checks.add(name);
    }
    private static CitizenRecord citizen(SettlementRecord s,Item input,int n,Item out,int made) {
        var c=s.addMigrant();c.insertIntoInventory(new ItemStack(input,n));
        var l=c.life();l.task="steward:bread";l.cookingStage=1;l.cookingInput=input.toString();l.cookingInputCount=n;
        l.cookingOutput=out.toString();l.cookingOutputCount=made;return c;
    }
    private static int count(CitizenRecord c,Item i){return c.inventory().stream().filter(v -> v.is(i)).mapToInt(ItemStack::getCount).sum();}
    public static String run(ServerLevel level) {
        if(!SaveAcceptanceService.enabled())throw new IllegalStateException("TEST_HARNESS_DISABLED");
        var s=new SettlementRecord(UUID.randomUUID(),"Detached warehouse checks",new BlockPos(150000,10,150000),UUID.randomUUID());
        var results=new ArrayList<String>();
        var c=citizen(s,Items.WHEAT,3,Items.BREAD,1);
        check(WarehouseStewardService.completeCraft(level,s,c) && count(c,Items.WHEAT)==0 && count(c,Items.BREAD)==1,"native_3_wheat_1_bread",results);
        check(!WarehouseStewardService.completeCraft(level,s,c) && count(c,Items.BREAD)==1,"completed_batch_not_repeated",results);
        var split=citizen(s,Items.WHEAT,1,Items.BREAD,1);split.life().cookingInputCount=3;
        var named=new ItemStack(Items.WHEAT,2);named.set(DataComponents.CUSTOM_NAME,Component.literal("Split test wheat"));split.insertIntoInventory(named);
        check(WarehouseStewardService.completeCraft(level,s,split) && count(split,Items.WHEAT)==0 && count(split,Items.BREAD)==1,"split_input_consumed_exactly",results);
        var missing=citizen(s,Items.WHEAT,2,Items.BREAD,1);missing.life().cookingInputCount=3;
        check(!WarehouseStewardService.completeCraft(level,s,missing) && count(missing,Items.WHEAT)==2,"insufficient_input_unchanged",results);
        var full=citizen(s,Items.WHEAT,4,Items.BREAD,1);full.life().cookingInputCount=3;
        full.insertIntoInventory(new ItemStack(Items.DIRT,64*(CitizenRecord.INVENTORY_SIZE-1)));
        check(!WarehouseStewardService.completeCraft(level,s,full) && count(full,Items.WHEAT)==4,"full_backpack_does_not_consume",results);
        full.life().task="steward:personal";
        var meal=WarehouseStewardService.takeCraftedMeal(level,s,full);
        check(meal.is(Items.BREAD) && meal.getCount()==1 && count(full,Items.WHEAT)==1 && count(full,Items.BREAD)==0,"full_backpack_station_meal_conserves_inputs",results);
        check(WarehouseStewardService.takeCraftedMeal(level,s,full).isEmpty(),"station_meal_cannot_repeat",results);
        full.extractInventoryItem(i -> i.is(Items.WHEAT),1);full.insertIntoInventory(new ItemStack(Items.WHEAT,4));full.life().cookingStage=1;
        full.extractInventoryItem(i -> i.is(Items.WHEAT),1);
        check(WarehouseStewardService.completeCraft(level,s,full) && count(full,Items.WHEAT)==0 && count(full,Items.BREAD)==1,"consumed_input_frees_output_slot",results);
        var hay=citizen(s,Items.WHEAT,9,Items.HAY_BLOCK,1);
        check(WarehouseStewardService.completeCraft(level,s,hay) && count(hay,Items.HAY_BLOCK)==1,"native_hay_packing",results);
        hay.life().cookingStage=1;hay.life().cookingInput=Items.HAY_BLOCK.toString();hay.life().cookingInputCount=1;
        hay.life().cookingOutput=Items.WHEAT.toString();hay.life().cookingOutputCount=9;
        check(WarehouseStewardService.completeCraft(level,s,hay) && count(hay,Items.WHEAT)==9 && count(hay,Items.HAY_BLOCK)==0,"hay_unpack_conserves_grain",results);
        var saved=citizen(s,Items.WHEAT,3,Items.BREAD,1);saved.life().stewardTarget=new BlockPos(3,64,7);
        var loaded=CitizenRecord.load(saved.save(level.registryAccess()),level.registryAccess());
        check(loaded.life().stewardTarget.equals(saved.life().stewardTarget) && WarehouseStewardService.completeCraft(level,s,loaded),"pending_batch_resume",results);
        loaded=CitizenRecord.load(loaded.save(level.registryAccess()),level.registryAccess());
        check(!WarehouseStewardService.completeCraft(level,s,loaded) && count(loaded,Items.BREAD)==1,"completed_reload_no_duplicate",results);
        check(CommunityLifeService.food(new ItemStack(Items.BREAD)),"bread_is_resident_food",results);
        var state=new CommunityState();state.snowLimit=128;state.seedRounds=3;state.wheatTarget=1024;state.boneTarget=96;state.lifeSlots=6;state.wheatPaused=true;state.stewardEnabled=false;
        var restored=new CommunityState();restored.load(state.save());
        check(restored.snowLimit==128 && restored.seedRounds==3 && restored.wheatTarget==1024 && restored.boneTarget==96
                && restored.lifeSlots==6 && restored.wheatPaused && !restored.stewardEnabled,"settings_and_hysteresis_persist",results);
        var old=state.save();for(var key:List.of("StewardEnabled","SnowLimit","SeedRounds","WheatTarget","BoneTarget","LifeSlots"))old.remove(key);
        restored.load(old);check(restored.stewardEnabled && restored.snowLimit==64 && restored.seedRounds==2 && restored.lifeSlots==4,"legacy_safe_defaults",results);
        check(!CommunitySettings.apply(s,"seed_rounds","0") && s.community().seedRounds==2,"invalid_options_rejected",results);
        return new com.google.gson.Gson().toJson(Map.of("passed",results.size(),"checks",results,"worldMutation",false));
    }
}
