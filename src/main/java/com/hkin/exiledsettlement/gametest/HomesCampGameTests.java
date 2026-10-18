package com.hkin.exiledsettlement.gametest;

import com.hkin.exiledsettlement.blueprint.*;
import com.hkin.exiledsettlement.building.*;
import com.hkin.exiledsettlement.citizen.*;
import com.hkin.exiledsettlement.settlement.*;
import com.hkin.exiledsettlement.menu.SettlementCoreMenu;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.neoforged.neoforge.gametest.*;
import java.util.*;

@GameTestHolder("exiledsettlement_homes")
@PrefixGameTestTemplate(false)
public final class HomesCampGameTests {
    private static int serial;
    private static final List<BuildingType> TYPES=List.of(BuildingType.STARTER_CAMP,BuildingType.HOUSE_3,
            BuildingType.ADVANCED_HOUSE_1,BuildingType.ADVANCED_HOUSE_2,BuildingType.ADVANCED_HOUSE_3);
    private static final int[] VERSIONS={4,5,5,6,5}, COUNTS={1044,1014,926,1684,2680};
    private record F(SettlementRecord s, SettlementSavedData data, BuildingRecord home, BuildingBlueprintTemplate t) {}
    private static F fixture(GameTestHelper h, BuildingType type,Direction facing) {
        var level=h.getLevel();var at=h.absolutePos(new BlockPos(280000+serial++*96,12,280000));
        var t=BlueprintTemplates.create(type,at,facing);
        var min=new ChunkPos(t.minCorner().offset(-8,0,-8));var max=new ChunkPos(t.maxCorner().offset(8,0,8));
        for(int x=min.x;x<=max.x;x++)for(int z=min.z;z<=max.z;z++)level.setChunkForced(x,z,true);
        for(var p:BlockPos.betweenClosed(t.minCorner().offset(-7,-1,-7),new BlockPos(t.maxCorner().getX()+7,t.minCorner().getY(),t.maxCorner().getZ()+7)))level.setBlock(p,Blocks.STONE.defaultBlockState(),2);
        for(var p:t.placements())level.setBlock(p.position(),p.state(),2);
        var s=new SettlementRecord(UUID.randomUUID(),"Approved home fixture",at,UUID.randomUUID());
        var b=s.addBuilding(type,at,facing,t.minCorner(),t.maxCorner(),0,BlueprintTemplates.currentTemplateVersion(type));
        var data=new SettlementSavedData();BuildingFunctionService.validateSingle(level,data,s,b);
        return new F(s,data,b,t);
    }
    @GameTest(templateNamespace="exiledsettlement_homes",template="empty",timeoutTicks=400)
    public static void allFiveFourRotationsCapacityAndHistoricalVersions(GameTestHelper h) {
        var catalog=Arrays.stream(SettlementCoreMenu.BUILDING_CATALOG).map(e->e.type()).toList();
        h.assertTrue(catalog.size()==24 && new HashSet<>(catalog).size()==24,"Catalog size or duplicates");
        h.assertTrue(!catalog.contains(BuildingType.STARTER_CAMP) && catalog.contains(BuildingType.APARTMENT),"Camp/apartment boundary changed");
        for(int i=0;i<TYPES.size();i++) {
            var type=TYPES.get(i);int v=VERSIONS[i];
            h.assertTrue(BlueprintTemplates.currentTemplateVersion(type)==v,"Wrong current version "+type);
            if(type!=BuildingType.STARTER_CAMP)h.assertTrue(catalog.contains(type),"Missing home "+type);
            for(var facing:Direction.Plane.HORIZONTAL) {
                var f=fixture(h,type,facing);var t=f.t;
                h.assertTrue(t.placements().stream().filter(p->p.mode().isFinalStructure()).count()==COUNTS[i],"Approved geometry changed "+type);
                h.assertTrue(f.home.operational() && f.home.capacity()==type.housingLimit(),"Missing working beds "+type+" "+f.home.status());
                h.assertTrue(t.minCorner().getY()==t.anchor().getY()-1,"Raised ground floor "+type);
                for(var bed:f.home.servicePositions()) {
                    var head=h.getLevel().getBlockState(bed);var foot=h.getLevel().getBlockState(bed.relative(head.getValue(BedBlock.FACING).getOpposite()));
                    h.assertTrue(foot.getBlock()==head.getBlock() && foot.getValue(BedBlock.PART)==BedPart.FOOT && foot.getValue(BedBlock.FACING)==head.getValue(BedBlock.FACING),"Broken bed pair "+type);
                }
                var plan=BlueprintRecord.create(type,t.anchor(),t.anchor(),facing,f.s.ownerId(),false,v,Map.of());
                h.assertTrue(BlueprintTemplates.create(BlueprintRecord.load(plan.save())).placements().equals(t.placements()),"New pending template changed on reload");
                h.assertTrue(BlueprintTemplates.createForBuilding(BuildingRecord.load(f.home.save(h.getLevel().registryAccess()))).placements().equals(t.placements()),"New building changed on reload");
                for(int old=1;old<v;old++) {
                    var legacy=BlueprintTemplates.create(type,t.anchor(),facing,old);
                    var oldPlan=BlueprintRecord.create(type,t.anchor(),t.anchor(),facing,f.s.ownerId(),false,old,Map.of());
                    var oldHome=BuildingRecord.create(type,t.anchor(),facing,legacy.minCorner(),legacy.maxCorner(),0,old);
                    h.assertTrue(!legacy.placements().equals(t.placements()),"Old version silently upgraded "+type+" v"+old);
                    h.assertTrue(BlueprintTemplates.create(BlueprintRecord.load(oldPlan.save())).placements().equals(legacy.placements()),"Old pending version lost");
                    h.assertTrue(BlueprintTemplates.createForBuilding(BuildingRecord.load(oldHome.save(h.getLevel().registryAccess()))).placements().equals(legacy.placements()),"Old built version lost");
                }
                h.assertTrue(new HashSet<>(f.s.citizens().stream().map(CitizenRecord::bedPosition).filter(Objects::nonNull).toList()).size()==Math.min(6,type.housingLimit()),"Duplicate beds");
                if(type==BuildingType.STARTER_CAMP) {
                    var visitors=new ArrayList<net.minecraft.world.entity.animal.Cow>();
                    for(var e:t.entrances()){var cow=net.minecraft.world.entity.EntityType.COW.create(h.getLevel());cow.moveTo(e.position().getX()+.5,e.position().getY(),e.position().getZ()+.5);h.getLevel().addFreshEntity(cow);visitors.add(cow);}
                    BuildingFunctionService.validateSingle(h.getLevel(),f.data,f.s,f.home);
                    h.assertTrue(f.home.operational(),"Temporary foot traffic unassigned camp residents");
                    visitors.forEach(net.minecraft.world.entity.Entity::discard);
                    for(var e:t.entrances())h.getLevel().setBlock(e.position().above(),Blocks.STONE.defaultBlockState(),2);
                    BuildingFunctionService.validateSingle(h.getLevel(),f.data,f.s,f.home);
                    h.assertTrue(!f.home.operational() && f.home.status()==BuildingStatus.ENTRANCE_BLOCKED,"Blocked open entrance accepted");
                    for(var e:t.entrances())h.getLevel().setBlock(e.position().above(),Blocks.AIR.defaultBlockState(),2);
                    BuildingFunctionService.validateSingle(h.getLevel(),f.data,f.s,f.home);
                    h.assertTrue(f.home.operational(),"Cleared camp entrance not restored");
                }
                var bed=f.home.servicePositions().getFirst();var state=h.getLevel().getBlockState(bed);
                h.getLevel().setBlock(bed,Blocks.AIR.defaultBlockState(),2);f.home.markDamaged(bed);
                h.assertTrue(BuildingRepairService.expectedState(h.getLevel(),f.s,f.home,bed).equals(state),"Repair template mismatch");
                BuildingFunctionService.validateSingle(h.getLevel(),f.data,f.s,f.home);
                h.assertTrue(f.home.capacity()==type.housingLimit()-1,"Missing bed counted");
                h.getLevel().setBlock(bed,state,2);BuildingFunctionService.validateSingle(h.getLevel(),f.data,f.s,f.home);
                h.assertTrue(f.home.capacity()==type.housingLimit(),"Restored bed missing");
                f.home.queueDemolition();h.assertTrue(BuildingDemolitionService.nextBlock(h.getLevel(),f.home)!=null,"No normal demolition targets");
            }
        }
        h.succeed();
    }
    @GameTest(templateNamespace="exiledsettlement_homes",template="empty",timeoutTicks=400)
    public static void fourHomesQueueRealPaidConstruction(GameTestHelper h) {
        for(var type:TYPES)if(type!=BuildingType.STARTER_CAMP) {
            var l=h.getLevel();var anchor=h.absolutePos(new BlockPos(290000+serial++*320,12,290000));var t=BlueprintTemplates.create(type,anchor,Direction.SOUTH);
            for(var p:BlockPos.betweenClosed(t.minCorner().offset(-4,0,-4),new BlockPos(t.maxCorner().getX()+4,t.minCorner().getY(),t.maxCorner().getZ()+4))){l.getChunkAt(p);l.setBlock(p,Blocks.GRASS_BLOCK.defaultBlockState(),2);}
            var data=SettlementSavedData.get(l);var owner=UUID.randomUUID();var s=data.createOrRecover(anchor.west(8),owner,"Home placement").value();
            var preview=BlueprintService.previewSite(l,s,t);h.assertTrue(preview.valid(),"Preview failed "+type+" "+preview.reason());
            var result=BlueprintService.placeBlueprint(l,owner,type,anchor,Direction.SOUTH,false,null);
            h.assertTrue(result.success() && result.value().templateVersion()==BlueprintTemplates.currentTemplateVersion(type),"Placement/version failed "+type);
            h.assertTrue(s.buildings().isEmpty() && !BlueprintService.confirm(l,s,result.value(),null,false).success(),"Unfunded home completed");
            h.assertTrue(t.requirements().size()>10 && t.requirements().values().stream().mapToInt(Integer::intValue).sum()>500,"Home has no actual material requirements");s.deactivate();
        }
        h.succeed();
    }
    @GameTest(templateNamespace="exiledsettlement_homes",template="empty",timeoutTicks=2400)
    public static void campSevenResidentsWalkSleepAndLeave(GameTestHelper h){physical(h,BuildingType.STARTER_CAMP);}
    @GameTest(templateNamespace="exiledsettlement_homes",template="empty",timeoutTicks=2400)
    public static void houseThreeResidentsWalkSleepAndLeave(GameTestHelper h){physical(h,BuildingType.HOUSE_3);}
    @GameTest(templateNamespace="exiledsettlement_homes",template="empty",timeoutTicks=2400)
    public static void advancedOneResidentsWalkSleepAndLeave(GameTestHelper h){physical(h,BuildingType.ADVANCED_HOUSE_1);}
    @GameTest(templateNamespace="exiledsettlement_homes",template="empty",timeoutTicks=2400)
    public static void advancedTwoResidentsWalkSleepAndLeave(GameTestHelper h){physical(h,BuildingType.ADVANCED_HOUSE_2);}
    @GameTest(templateNamespace="exiledsettlement_homes",template="empty",timeoutTicks=2400)
    public static void advancedThreeResidentsWalkSleepAndLeave(GameTestHelper h){physical(h,BuildingType.ADVANCED_HOUSE_3);}
    private static void physical(GameTestHelper h,BuildingType type) {
        var f=fixture(h,type,Direction.SOUTH);var l=h.getLevel();while(f.s.citizens().size()<type.housingLimit())f.s.addMigrant();f.s.refreshHousingAssignments();
        var citizens=f.s.citizens().stream().filter(c->c.bedPosition()!=null).toList();
        h.assertTrue(citizens.size()==type.housingLimit(),"Wrong actual housed resident count "+type);
        var actors=new ArrayList<SettlementCitizenEntity>();var departed=new HashSet<Integer>();long start=l.getGameTime(),wall=System.nanoTime();
        h.startSequence().thenWaitUntil(()->{CitizenProjectionService.ensureSettlementCitizens(l,f.s);h.assertTrue(citizens.stream().allMatch(c->CitizenProjectionService.findProjection(l,c)!=null),"Waiting for residents");})
        .thenExecute(()->{for(int i=0;i<citizens.size();i++){var e=CitizenProjectionService.findProjection(l,citizens.get(i));actors.add(e);var at=f.t.anchor().offset((i%4)*2-2,0,-3-(i/4)*2);e.moveTo(at.getX()+.5,at.getY(),at.getZ()+.5,0,0);e.setOnGround(true);}})
        .thenWaitUntil(()->{
            for(int i=0;i<actors.size();i++){var c=citizens.get(i);var e=actors.get(i);c.updateProjection(e.getUUID(),e.blockPosition());h.assertTrue(e.isAlive(),"Resident died");if(Math.floorMod(l.getGameTime(),20)==i)CitizenWorkService.routeResidentToPersonalBed(l,f.data,f.s,c,e);}
            long sleeping=actors.stream().filter(SettlementCitizenEntity::isSleeping).count();
            if((l.getGameTime()-start)%200==0){System.out.println("HOMES_ROUTE "+type+" ticks="+(l.getGameTime()-start)+" sleeping="+sleeping+"/"+actors.size());for(int i=0;i<actors.size();i++)if(!actors.get(i).isSleeping())System.out.println("HOMES_WAIT "+type+" local="+actors.get(i).blockPosition().subtract(f.t.minCorner())+" bed="+citizens.get(i).bedPosition().subtract(f.t.minCorner())+" reason="+citizens.get(i).workFailure());}
            h.assertTrue(sleeping==actors.size(),"Residents still entering "+type+" "+sleeping+"/"+actors.size());
        }).thenExecute(()->{
            for(int i=0;i<actors.size();i++)h.assertTrue(actors.get(i).isPhysicallySleepingAt(citizens.get(i).bedPosition()),"Wrong personal bed");
            int light=f.home.servicePositions().stream().mapToInt(p->l.getBrightness(net.minecraft.world.level.LightLayer.BLOCK,p.above())).min().orElse(0);
            h.assertTrue(light>0,"Unlit bed "+type);System.out.println("HOMES_SLEEP_OK "+type+" residents="+actors.size()+" ticks="+(l.getGameTime()-start)+" minimumBedBlockLight="+light);actors.forEach(SettlementCitizenEntity::stopSleeping);
        }).thenWaitUntil(()->{
            for(int i=0;i<actors.size();i++)if(!departed.contains(i)){var e=actors.get(i);var target=f.t.anchor().offset((i%4)*2-2,0,-3-(i/4)*2);if(e.distanceToSqr(target.getX()+.5,target.getY(),target.getZ()+.5)<2.25){departed.add(i);e.discard();}else if(Math.floorMod(l.getGameTime(),20)==i)e.navigateTo(target,.7);if((l.getGameTime()-start)%200==0)System.out.println("HOMES_EXIT_WAIT "+type+" local="+e.blockPosition().subtract(f.t.minCorner()));}
            h.assertTrue(departed.size()==actors.size(),"Residents still leaving "+type+" "+departed.size()+"/"+actors.size());
        }).thenExecute(()->{System.out.println("HOMES_RETURN_OK "+type+" residents="+actors.size()+" ticks="+(l.getGameTime()-start)+" seconds="+((System.nanoTime()-wall)/1e9));for(var c:f.s.citizens()){var e=CitizenProjectionService.findProjection(l,c);if(e!=null)e.discard();}}).thenSucceed();
    }
}
