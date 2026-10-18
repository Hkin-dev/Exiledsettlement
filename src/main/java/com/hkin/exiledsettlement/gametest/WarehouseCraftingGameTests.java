package com.hkin.exiledsettlement.gametest;

import com.hkin.exiledsettlement.blueprint.*;
import com.hkin.exiledsettlement.building.*;
import com.hkin.exiledsettlement.citizen.*;
import com.hkin.exiledsettlement.registry.ModEntities;
import com.hkin.exiledsettlement.settlement.*;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.*;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.neoforged.neoforge.gametest.*;
import java.util.*;

@net.neoforged.fml.common.EventBusSubscriber(modid="exiledsettlement",bus=net.neoforged.fml.common.EventBusSubscriber.Bus.MOD)
@GameTestHolder("exiledsettlement_autocraft")
@PrefixGameTestTemplate(false)
public final class WarehouseCraftingGameTests {
    @net.neoforged.bus.api.SubscribeEvent
    public static void register(net.neoforged.neoforge.event.RegisterGameTestsEvent e) {
        if("warehouse_crafting".equals(System.getProperty("exiledsettlement.testSuite")))e.register(WarehouseCraftingGameTests.class);
    }
    private static int count(List<ItemStack> stacks,Item item){return stacks.stream().filter(s->s.is(item)).mapToInt(ItemStack::getCount).sum();}
    private static WarehouseCrafting.Plan plan(GameTestHelper h,Item target,int n,ItemStack... stacks) {
        return WarehouseCrafting.plan(h.getLevel(),List.of(stacks),target,n,Map.of());
    }
    @GameTest(templateNamespace="exiledsettlement_autocraft",template="empty")
    public static void speciesAndColorsPreserveShapeAndExactCost(GameTestHelper h) {
        var p=plan(h,Items.SPRUCE_LOG,6,new ItemStack(Items.BIRCH_LOG,6));
        h.assertTrue(p!=null && p.remaining().isEmpty() && p.output().getCount()==6,"Log conversion failed");
        p=plan(h,Items.SPRUCE_DOOR,3,new ItemStack(Items.BIRCH_LOG,2));
        h.assertTrue(p!=null && p.output().getCount()==3,"Log-to-door chain failed");
        h.assertTrue(p.remaining().stream().mapToInt(ItemStack::getCount).sum()==2,"Door recipe ratio wrong");
        p=plan(h,Items.BLUE_STAINED_GLASS,4,new ItemStack(Items.RED_STAINED_GLASS,4));
        h.assertTrue(p!=null && p.remaining().isEmpty(),"Color conversion failed");
        h.assertTrue(plan(h,Items.QUARTZ_BLOCK,1,new ItemStack(Items.COBBLESTONE,64))==null,"Precious stone crossed families");
        h.succeed();
    }
    @GameTest(templateNamespace="exiledsettlement_autocraft",template="empty")
    public static void chainedLanternAndIngredientAlternatives(GameTestHelper h) {
        var p=plan(h,Items.LANTERN,1,new ItemStack(Items.IRON_INGOT),new ItemStack(Items.CHARCOAL),new ItemStack(Items.BIRCH_LOG));
        h.assertTrue(p!=null && count(p.remaining(),Items.IRON_NUGGET)==1 && count(p.remaining(),Items.TORCH)==3,"Lantern chain or surplus wrong");
        h.assertTrue(plan(h,Items.LANTERN,1,new ItemStack(Items.IRON_INGOT),new ItemStack(Items.BIRCH_LOG))==null,"Missing fuel fabricated");
        h.assertTrue(WarehouseCrafting.indexedOutputs(h.getLevel())>500,"Recipe index unexpectedly narrow");h.succeed();
    }
    @GameTest(templateNamespace="exiledsettlement_autocraft",template="empty")
    public static void recipeRemaindersAndFailureAreAtomic(GameTestHelper h) {
        var source=List.of(new ItemStack(Items.MILK_BUCKET),new ItemStack(Items.MILK_BUCKET),new ItemStack(Items.MILK_BUCKET),
                new ItemStack(Items.SUGAR,2),new ItemStack(Items.EGG),new ItemStack(Items.WHEAT,3));
        var p=WarehouseCrafting.plan(h.getLevel(),source,Items.CAKE,1,Map.of());
        h.assertTrue(p!=null && count(p.remaining(),Items.BUCKET)==3 && p.remaining().stream().mapToInt(ItemStack::getCount).sum()==3,"Milk containers lost");
        h.assertTrue(source.getLast().getCount()==3,"Read-only plan consumed inputs");
        h.assertTrue(WarehouseCrafting.plan(h.getLevel(),source,Items.CAKE,2,Map.of())==null && source.getLast().getCount()==3,"Partial failure changed inventory");h.succeed();
    }
    @GameTest(templateNamespace="exiledsettlement_autocraft",template="empty")
    public static void componentsReserveAndCyclesStaySafe(GameTestHelper h) {
        var named=new ItemStack(Items.OAK_LOG,8);named.set(DataComponents.CUSTOM_NAME,Component.literal("Keep this"));
        h.assertTrue(plan(h,Items.SPRUCE_LOG,1,named)==null,"Named goods consumed");
        h.assertTrue(plan(h,Items.IRON_INGOT,1)==null,"Empty compression cycle manufactured iron");
        var p=plan(h,Items.IRON_NUGGET,10,new ItemStack(Items.IRON_BLOCK));
        h.assertTrue(p!=null && count(p.remaining(),Items.IRON_INGOT)*9+count(p.remaining(),Items.IRON_NUGGET)+10==81,"Compression conservation failed");
        h.assertTrue(WarehouseCrafting.plan(h.getLevel(),List.of(new ItemStack(Items.OAK_LOG,4)),Items.SPRUCE_LOG,1,Map.of(Items.OAK_LOG,4))==null,"Reserved goods consumed");
        h.assertTrue(plan(h,Items.GLASS,1,new ItemStack(Items.SAND),new ItemStack(Items.COAL))==null,"Smelting silently treated as crafting");h.succeed();
    }
    @GameTest(templateNamespace="exiledsettlement_autocraft",template="empty")
    public static void capacityRevisionAndSaveDoNotDuplicate(GameTestHelper h) {
        var source=new ArrayList<ItemStack>();for(int i=0;i<3124;i++)source.add(new ItemStack(Items.DIRT,64));
        source.add(new ItemStack(Items.DIRT,63));source.add(new ItemStack(Items.IRON_BLOCK));
        h.assertTrue(plan(h,Items.IRON_NUGGET,1,source.toArray(ItemStack[]::new))==null,"Full warehouse accepted expansion overflow");
        var stock=new WarehouseStock().bind(h.getLevel());stock.insert(new ItemStack(Items.OAK_LOG,2));long revision=stock.revision();
        var p=plan(h,Items.SPRUCE_LOG,1,new ItemStack(Items.OAK_LOG,2));
        h.assertTrue(stock.commitCraft(revision,p.remaining()),"First commit failed");
        h.assertTrue(!stock.commitCraft(revision,p.remaining()),"Stale plan committed twice");
        var restored=new WarehouseStock();restored.load(stock.save(h.getLevel().registryAccess()));restored.bind(h.getLevel());
        h.assertTrue(restored.quantity()==1 && restored.summary().getOrDefault(Items.OAK_LOG,0)==1,"Save duplicated or lost remainder");h.succeed();
    }
    @GameTest(templateNamespace="exiledsettlement_autocraft",template="empty")
    public static void loadedDataPackRecipeAndReloadAreUsed(GameTestHelper h) {
        var p=plan(h,Items.BELL,1,new ItemStack(Items.COPPER_INGOT,4),new ItemStack(Items.IRON_NUGGET));
        h.assertTrue(p!=null && p.remaining().isEmpty(),"Loaded test data-pack recipe not honored");
        var manager=h.getLevel().getRecipeManager();var old=new ArrayList<>(manager.getRecipes());
        try {
            manager.replaceRecipes(old.stream().filter(r->!r.id().toString().equals("exiledsettlement_autocraft:test_bell")).toList());
            h.assertTrue(plan(h,Items.BELL,1,new ItemStack(Items.COPPER_INGOT,4),new ItemStack(Items.IRON_NUGGET))==null,"Reload kept removed recipe");
        } finally {manager.replaceRecipes(old);WarehouseCrafting.invalidate();}
        h.succeed();
    }
    @GameTest(templateNamespace="exiledsettlement_autocraft",template="empty",timeoutTicks=2400)
    public static void realWorkerCollectsConvertedAndCraftedGoodsThenBuilds(GameTestHelper h) {
        var l=h.getLevel();var at=h.absolutePos(new BlockPos(122000,8,122000));
        for(var p:BlockPos.betweenClosed(at.offset(-10,-1,-6),at.offset(14,-1,6))){l.getChunkAt(p);l.setBlock(p,Blocks.STONE.defaultBlockState(),2);for(int y=1;y<=5;y++)l.setBlock(p.above(y),Blocks.AIR.defaultBlockState(),2);}
        for(int x=(at.getX()-16)>>4;x<=(at.getX()+32)>>4;x++)for(int z=(at.getZ()-16)>>4;z<=(at.getZ()+16)>>4;z++)l.setChunkForced(x,z,true);
        var data=SettlementSavedData.get(l);var s=data.createOrRecover(at,UUID.randomUUID(),"Auto craft physical").value();h.assertTrue(s!=null,"Fixture overlaps");
        s.terrain().enabled=false;s.autoRoads().enabled=false;s.decorations().enabled=false;s.community().fuelReserve=0;s.community().foodReserve=0;l.setDayTime(6000);
        var c=s.citizens().iterator().next();for(var other:s.citizens())if(other!=c)other.markDead(at);
        c.assignProfession(CitizenProfession.WORKER,null);c.insertIntoInventory(new ItemStack(Items.BREAD,16));c.receiveEquipment(new ItemStack(Items.IRON_AXE));c.receiveEquipment(new ItemStack(Items.IRON_PICKAXE));
        var b=s.addBuilding(BuildingType.WAGON,at.west(7),Direction.SOUTH,at.west(7),at.west(7),0,2);
        l.setBlock(b.position(),Blocks.BARREL.defaultBlockState(),3);b.updateFunction(true,BuildingStatus.OPERATIONAL,27,List.of(b.position()));
        var stock=WarehouseInventoryService.stock(l,b);stock.insert(new ItemStack(Items.BIRCH_LOG,4));stock.insert(new ItemStack(Items.ANDESITE,2));
        var goal=at.east(7);var site=s.addBlueprint(BuildingType.HOUSE,goal,goal.north(),Direction.NORTH,s.ownerId(),false);
        site.withCustomTemplate(CustomTemplateSnapshot.create("Auto craft fixture",BuildingType.HOUSE,goal,Direction.NORTH,
                List.of(new BlueprintPlacement(goal,Blocks.SPRUCE_PLANKS.defaultBlockState(),Items.SPRUCE_PLANKS),
                        new BlueprintPlacement(goal.east(),Blocks.CRAFTING_TABLE.defaultBlockState(),Items.CRAFTING_TABLE)),List.of(new BlueprintEntrance(goal,Direction.NORTH))));
        c.setPreferredWorksite(site.id());h.assertTrue(WarehouseConstructionSupply.warehouse(l,s,at,Items.SPRUCE_PLANKS)==b,"Craftable supply not discovered");
        h.assertTrue(stock.summary().getOrDefault(Items.BIRCH_LOG,0)==4,"Supply query mutated stock");
        long start=l.getGameTime(),wall=System.nanoTime();SettlementCitizenEntity[] actor={null};boolean[] visited={false},carried={false};
        h.startSequence().thenWaitUntil(()->{CitizenProjectionService.ensureSettlementCitizens(l,s);actor[0]=CitizenProjectionService.findProjection(l,c);h.assertTrue(actor[0]!=null,"Waiting for real resident");})
        .thenExecute(()->{actor[0].moveTo(at.getX()+.5,at.getY(),at.getZ()+.5,0,0);actor[0].setOnGround(true);})
        .thenWaitUntil(()->{
            visited[0]|=WarehouseInventoryService.canInteract(l,b,actor[0]);
            carried[0]|=c.inventory().stream().anyMatch(i->i.is(Items.SPRUCE_PLANKS)||i.is(Items.CRAFTING_TABLE));
            h.assertTrue(l.getBlockState(goal).is(Blocks.SPRUCE_PLANKS) && l.getBlockState(goal.east()).is(Blocks.CRAFTING_TABLE),"Waiting for real supply/build: "+c.workState()+" "+c.workFailure()+" at="+actor[0].blockPosition());
            h.assertTrue(visited[0] && carried[0],"Did not observe physical collection");
        }).thenExecute(()->{
            System.out.println("WAREHOUSE_AUTOCRAFT_PHYSICAL_OK ticks="+(l.getGameTime()-start)+" seconds="+(System.nanoTime()-wall)/1e9+" visited="+visited[0]+" carried="+carried[0]);
            var repair=at.east(4).south(3);l.setBlock(repair,Blocks.STONE_BRICK_STAIRS.defaultBlockState(),3);
            var mob=net.minecraft.world.entity.EntityType.CREEPER.create(l);
            MobRecoveryService.capture(l,mob,List.of(repair),"autocraft_repair_fixture");l.removeBlock(repair,false);
        }).thenIdle(8).thenWaitUntil(()->{
            MobRecoveryService.tick(l);
            h.assertTrue(l.getBlockState(at.east(4).south(3)).is(Blocks.STONE_BRICK_STAIRS),"Waiting for paid recovery: "+c.workState()+" "+c.workFailure());
        }).thenExecute(()->{
            h.assertTrue(stock.summary().getOrDefault(Items.ANDESITE,0)<2,"Repair did not consume stone inputs");
            System.out.println("WAREHOUSE_AUTOCRAFT_RECOVERY_OK ticks="+(l.getGameTime()-start)+" seconds="+(System.nanoTime()-wall)/1e9);
            s.deactivate();actor[0].discard();for(int x=(at.getX()-16)>>4;x<=(at.getX()+32)>>4;x++)for(int z=(at.getZ()-16)>>4;z<=(at.getZ()+16)>>4;z++)l.setChunkForced(x,z,false);
        }).thenSucceed();
    }
}
