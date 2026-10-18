package com.hkin.exiledsettlement.gametest;

import com.hkin.exiledsettlement.blueprint.*;
import com.hkin.exiledsettlement.building.*;
import com.hkin.exiledsettlement.citizen.*;
import com.hkin.exiledsettlement.settlement.*;
import com.hkin.exiledsettlement.menu.SettlementCoreMenu;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.gametest.*;
import java.util.*;

@GameTestHolder("exiledsettlement_apartment")
@PrefixGameTestTemplate(false)
public final class ApartmentGameTests {
    private static int serial;
    private record F(SettlementRecord s, SettlementSavedData data, BuildingRecord home, BuildingBlueprintTemplate t) {}
    private static F fixture(GameTestHelper h, Direction facing) {
        var level=h.getLevel();var at=h.absolutePos(new BlockPos(150000+serial++*96,12,150000));
        var t=BlueprintTemplates.create(BuildingType.APARTMENT,at,facing);
        var min=new ChunkPos(t.minCorner().offset(-8,0,-8));var max=new ChunkPos(t.maxCorner().offset(8,0,8));
        for(int x=min.x;x<=max.x;x++)for(int z=min.z;z<=max.z;z++)level.setChunkForced(x,z,true);
        for(var p:BlockPos.betweenClosed(t.minCorner().offset(-7,0,-7),new BlockPos(t.maxCorner().getX()+7,t.minCorner().getY(),t.maxCorner().getZ()+7)))level.setBlock(p,Blocks.STONE.defaultBlockState(),2);
        for(var p:t.placements())level.setBlock(p.position(),p.state(),2);
        var s=new SettlementRecord(UUID.randomUUID(),"Apartment validation",at.north(5),UUID.randomUUID());
        var b=s.addBuilding(BuildingType.APARTMENT,at,facing,t.minCorner(),t.maxCorner(),0,BlueprintTemplates.currentTemplateVersion(BuildingType.APARTMENT));
        var data=new SettlementSavedData();BuildingFunctionService.validateSingle(level,data,s,b);
        return new F(s,data,b,t);
    }
    @GameTest(templateNamespace="exiledsettlement_apartment",template="empty",timeoutTicks=200)
    public static void catalogAndFourRotationsKeepTwentyCompleteBeds(GameTestHelper h) {
        h.assertTrue(BuildingType.APARTMENT.ordinal()==38 && BuildingType.BRICK_KILN.ordinal()==37 && BuildingType.CHURCH.ordinal()==36,"Historical enum ordinals moved");
        h.assertTrue(SettlementCoreMenu.BUILDING_CATALOG.length==24 && Arrays.stream(SettlementCoreMenu.BUILDING_CATALOG).filter(e->e.type()==BuildingType.APARTMENT).count()==1,"Apartment catalog missing or duplicate");
        h.assertTrue(Arrays.stream(SettlementCoreMenu.BUILDING_CHOICES).noneMatch(t->t==BuildingType.STARTER_CAMP),"Hidden historic housing reintroduced");
        for(var facing:Direction.Plane.HORIZONTAL) {
            var f=fixture(h,facing);
            h.assertTrue(f.home.operational() && f.home.capacity()==20 && f.home.servicePositions().size()==20,"Floor beds missing: "+facing);
            h.assertTrue(f.t.placements().stream().filter(p->p.mode().isFinalStructure()).count()==5031,"Approved geometry/adaptation changed");
            var floors=new TreeMap<Integer,Integer>();
            for(var bed:f.home.servicePositions()) {
                var head=h.getLevel().getBlockState(bed);var foot=h.getLevel().getBlockState(bed.relative(head.getValue(BedBlock.FACING).getOpposite()));
                h.assertTrue(foot.getBlock()==head.getBlock() && foot.getValue(BedBlock.PART)==BedPart.FOOT && foot.getValue(BedBlock.FACING)==head.getValue(BedBlock.FACING),"Incomplete bed "+bed);
                floors.merge(bed.getY()-f.t.anchor().getY(),1,Integer::sum);
            }
            h.assertTrue(floors.equals(Map.of(0,4,5,8,10,8)),"Floor distribution changed: "+floors);
            h.assertTrue(f.t.entrances().size()==2 && f.t.minCorner().getY()==f.t.anchor().getY()-1,"Threshold height changed");
            for(var entrance:f.t.entrances())for(int step=-1;step<=1;step++) {
                var p=entrance.position().relative(entrance.outward(),step);
                h.assertTrue(h.getLevel().getBlockState(p.below()).isCollisionShapeFullBlock(h.getLevel(),p.below()),"Unsupported threshold "+p);
            }
            var plan=BlueprintRecord.create(BuildingType.APARTMENT,f.t.anchor(),f.t.anchor(),facing,f.s.ownerId(),false,BlueprintTemplates.currentTemplateVersion(BuildingType.APARTMENT),Map.of());
            h.assertTrue(BlueprintTemplates.create(BlueprintRecord.load(plan.save())).placements().equals(f.t.placements()),"Pending plan changed on save");
            h.assertTrue(BlueprintTemplates.createForBuilding(BuildingRecord.load(f.home.save(h.getLevel().registryAccess()))).placements().equals(f.t.placements()),"Completed plan changed on save");
            var legacy=BlueprintTemplates.create(BuildingType.APARTMENT,f.t.anchor(),facing,1);
            h.assertTrue(legacy.placements().stream().filter(p->p.mode().isFinalStructure()).count()==4953,"Historical apartment replaced");
            var oldPlan=BlueprintRecord.create(BuildingType.APARTMENT,f.t.anchor(),f.t.anchor(),facing,f.s.ownerId(),false,1,Map.of());
            var oldHome=BuildingRecord.create(BuildingType.APARTMENT,f.t.anchor(),facing,legacy.minCorner(),legacy.maxCorner(),0,1);
            h.assertTrue(BlueprintTemplates.create(BlueprintRecord.load(oldPlan.save())).placements().equals(legacy.placements()),"Historical apartment plan changed");
            h.assertTrue(BlueprintTemplates.createForBuilding(BuildingRecord.load(oldHome.save(h.getLevel().registryAccess()))).placements().equals(legacy.placements()),"Historical apartment building changed");
            h.assertTrue(f.t.requirements().values().stream().mapToInt(Integer::intValue).sum()>4000,"Construction has no real material bill");
        }
        h.succeed();
    }
    @GameTest(templateNamespace="exiledsettlement_apartment",template="empty",timeoutTicks=200)
    public static void twentyUniqueAssignmentsSurviveReloadAndCapacityLoss(GameTestHelper h) {
        var f=fixture(h,Direction.SOUTH);
        while(f.s.citizens().size()<21)f.s.addMigrant();
        f.s.refreshHousingAssignments();
        h.assertTrue(f.s.occupantsOf(f.home.id())==20,"Capacity is not exactly twenty");
        var beds=new HashSet<BlockPos>();var bindings=new HashMap<UUID,BlockPos>();
        for(var c:f.s.citizens())if(c.bedPosition()!=null){h.assertTrue(beds.add(c.bedPosition()),"Duplicate personal bed");bindings.put(c.id(),c.bedPosition());}
        h.assertTrue(beds.size()==20,"Not all beds allocated");
        var loaded=SettlementRecord.load(f.s.save(h.getLevel().registryAccess()),h.getLevel().registryAccess());loaded.refreshHousingAssignments();
        for(var c:loaded.citizens())h.assertTrue(Objects.equals(c.bedPosition(),bindings.get(c.id())),"Personal bed moved on reload");
        var missing=beds.iterator().next();h.getLevel().setBlock(missing,Blocks.AIR.defaultBlockState(),2);
        BuildingFunctionService.validateSingle(h.getLevel(),f.data,f.s,f.home);
        h.assertTrue(f.home.capacity()==19 && f.s.occupantsOf(f.home.id())==19,"Lost bed still counted");
        h.getLevel().setBlock(missing,f.t.placementAt(missing).state(),2);
        BuildingFunctionService.validateSingle(h.getLevel(),f.data,f.s,f.home);
        h.assertTrue(f.home.capacity()==20 && f.s.occupantsOf(f.home.id())==20,"Repaired bed not reassigned");h.succeed();
    }
    @GameTest(templateNamespace="exiledsettlement_apartment",template="empty",timeoutTicks=200)
    public static void repairDemolitionAndBedRouteScopeRemainBounded(GameTestHelper h) {
        var f=fixture(h,Direction.SOUTH);var level=h.getLevel();
        var wall=f.t.placements().stream().filter(p->p.state().is(Blocks.WHITE_TERRACOTTA) && p.position().getY()>f.t.anchor().getY()+10).findFirst().orElseThrow();
        level.setBlock(wall.position(),Blocks.AIR.defaultBlockState(),2);f.home.markDamaged(wall.position());
        h.assertTrue(BuildingRepairService.expectedState(level,f.s,f.home,wall.position()).equals(wall.state()) && !BuildingRepairService.satisfied(level,f.home,wall.position()),"Upper-storey repair lost its template");
        level.setBlock(wall.position(),wall.state(),2);
        h.assertTrue(BuildingRepairService.satisfied(level,f.home,wall.position()) && BuildingRepairService.clearRepair(f.s,f.home,wall.position()),"Repair cannot clear after restoration");
        var e=com.hkin.exiledsettlement.registry.ModEntities.SETTLEMENT_CITIZEN.get().create(level);var bed=f.home.servicePositions().getFirst();
        e.moveTo(f.t.anchor().getX()+.5,f.t.anchor().getY(),f.t.anchor().getZ()+3.5);
        ApartmentHomeNavigation.returning(e,f.home,bed);
        h.assertTrue(ApartmentHomeNavigation.allowance(e,Set.of(bed))==64,"Own apartment bed has no stair budget");
        h.assertTrue(ApartmentHomeNavigation.allowance(e,Set.of(f.t.maxCorner().east(10)))==0,"Outside target received apartment budget");
        e.moveTo(f.t.minCorner().getX()-10,f.t.anchor().getY(),f.t.minCorner().getZ()-10);
        h.assertTrue(ApartmentHomeNavigation.allowance(e,Set.of(bed))==0,"Outdoor trip received apartment budget");
        e.discard();f.home.queueDemolition();
        h.assertTrue(BuildingDemolitionService.isRemovable(level,f.home,wall.position()) && BuildingDemolitionService.nextBlock(level,f.home)!=null,"Apartment cannot use normal demolition");h.succeed();
    }
    @GameTest(templateNamespace="exiledsettlement_apartment",template="empty",timeoutTicks=250)
    public static void indoorNightLightAndBlockedStaircaseUseRealWorld(GameTestHelper h) {
        var f=fixture(h,Direction.SOUTH);var level=h.getLevel();
        var e=com.hkin.exiledsettlement.registry.ModEntities.SETTLEMENT_CITIZEN.get().create(level);
        e.moveTo(f.t.anchor().getX()+.5,f.t.anchor().getY(),f.t.anchor().getZ()+3.5);
        h.runAfterDelay(40,()->{
            int minimum=15,count=0;BlockPos darkest=null;
            for(int y:new int[]{1,6,11})for(int x=2;x<=24;x++)for(int z=4;z<=19;z++) {
                var p=f.t.minCorner().offset(x,y,z);
                if(!e.canUseBedStation(p))continue;
                int light=level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK,p);
                count++;if(light<minimum){minimum=light;darkest=p;}
            }
            System.out.println("APARTMENT_LIGHT samples="+count+" minimum="+minimum+" darkest="+darkest);
            h.assertTrue(count>300 && minimum>0,"Unlit usable interior: "+darkest);
            var bed=f.home.servicePositions().stream().filter(p->p.getY()==f.t.anchor().getY()+10).findFirst().orElseThrow();
            ApartmentHomeNavigation.returning(e,f.home,bed);
            var table=f.t.minCorner().offset(11,1,5);
            h.assertTrue(level.getBlockState(table.above()).getBlock() instanceof TrapDoorBlock
                    && ApartmentHomeNavigation.lowTableBlocks(e,table),"Low table head space must be blocked");
            h.assertTrue(ApartmentHomeNavigation.lowTableBlocks(e,table.above()),"Free-standing table must not become a jump-up route");
            h.assertTrue(!ApartmentHomeNavigation.lowTableBlocks(e,f.t.minCorner().offset(8,1,8)),"Open hallway was blocked");
            var oldHome=BuildingRecord.create(BuildingType.APARTMENT,f.t.anchor(),Direction.SOUTH,f.t.minCorner(),f.t.maxCorner(),0,1);
            oldHome.updateFunction(true,BuildingStatus.OPERATIONAL,20,f.home.servicePositions());
            ApartmentHomeNavigation.returning(e,oldHome,bed);
            h.assertTrue(!ApartmentHomeNavigation.lowTableBlocks(e,table),"Historical apartment navigation changed");
            ApartmentHomeNavigation.returning(e,f.home,bed);
            for(int x=2;x<=3;x++)for(int z=12;z<=16;z++)level.setBlock(f.t.minCorner().offset(x,5,z),Blocks.STONE.defaultBlockState(),2);
            var path=e.getNavigation().createPath(bed,0);
            h.assertTrue(path==null || !path.canReach(),"Search passed through a sealed stairwell");
            e.discard();h.succeed();
        });
    }
    @GameTest(templateNamespace="exiledsettlement_apartment",template="empty",timeoutTicks=200)
    public static void normalBlueprintPlacementQueuesPaidConstruction(GameTestHelper h) {
        var level=h.getLevel();var anchor=h.absolutePos(new BlockPos(250000,12,250000));
        var t=BlueprintTemplates.create(BuildingType.APARTMENT,anchor,Direction.SOUTH);
        for(var p:BlockPos.betweenClosed(t.minCorner().offset(-4,0,-4),new BlockPos(t.maxCorner().getX()+4,t.minCorner().getY(),t.maxCorner().getZ()+4))) {
            level.getChunkAt(p);level.setBlock(p,Blocks.GRASS_BLOCK.defaultBlockState(),2);
        }
        var data=SettlementSavedData.get(level);var owner=UUID.randomUUID();
        var created=data.createOrRecover(anchor.west(8),owner,"Apartment placement");
        h.assertTrue(created.success(),"Cannot create isolated placement fixture: "+created.reason());
        var s=created.value();var check=BlueprintService.previewSite(level,s,t);
        h.assertTrue(check.valid(),"Apartment rejected on flat site: "+check.reason()+" at "+check.position());
        var result=BlueprintService.placeBlueprint(level,owner,BuildingType.APARTMENT,anchor,Direction.SOUTH,false,null);
        h.assertTrue(result.success(),"Normal blueprint refused: "+result.reason());
        h.assertTrue(s.blueprint(result.value().id())!=null && s.buildings().isEmpty(),"Blueprint instantly became housing");
        var bed=t.placements().stream().filter(p->p.state().getBlock() instanceof BedBlock).findFirst().orElseThrow();
        h.assertTrue(level.getBlockState(bed.position()).isAir(),"Placement bypassed construction");
        h.assertTrue(!BlueprintService.confirm(level,s,result.value(),null,false).success(),"Unfunded direct completion succeeded");
        System.out.println("APARTMENT_PLACEMENT_OK version="+result.value().templateVersion()+" materials="+t.requirements().size()+" blocks="+t.requirements().values().stream().mapToInt(Integer::intValue).sum());
        s.deactivate();h.succeed();
    }
    @GameTest(templateNamespace="exiledsettlement_apartment",template="empty",timeoutTicks=9000)
    public static void twentyResidentsPhysicallyEnterClimbAndSleep(GameTestHelper h) {
        var f=fixture(h,Direction.SOUTH);var level=h.getLevel();
        while(f.s.citizens().size()<20)f.s.addMigrant();f.s.refreshHousingAssignments();
        var citizens=new ArrayList<>(f.s.citizens());var actors=new ArrayList<SettlementCitizenEntity>();
        var departed=new HashSet<Integer>();var traced=new HashSet<Integer>();long start=level.getGameTime(),wall=System.nanoTime();
        h.startSequence().thenWaitUntil(()->{
            CitizenProjectionService.ensureSettlementCitizens(level,f.s);
            h.assertTrue(citizens.stream().allMatch(c->CitizenProjectionService.findProjection(level,c)!=null),"Waiting for real residents");
        }).thenExecute(()->{
            for(int i=0;i<citizens.size();i++) {
                var e=CitizenProjectionService.findProjection(level,citizens.get(i));actors.add(e);
                var at=f.t.anchor().offset((i%5)*2-3,0,-3-(i/5)*2);
                e.moveTo(at.getX()+.5,at.getY(),at.getZ()+.5,0,0);e.setOnGround(true);
            }
        }).thenWaitUntil(()->{
            for(int i=0;i<actors.size();i++) {
                var c=citizens.get(i);var e=actors.get(i);c.updateProjection(e.getUUID(),e.blockPosition());
                h.assertTrue(e.isAlive(),"Resident died on route");
                if(Math.floorMod(level.getGameTime(),20)==i)CitizenWorkService.routeResidentToPersonalBed(level,f.data,f.s,c,e);
            }
            long sleeping=actors.stream().filter(SettlementCitizenEntity::isSleeping).count();
            if((level.getGameTime()-start)%200==0){System.out.println("APARTMENT_ROUTE ticks="+(level.getGameTime()-start)+" sleeping="+sleeping+"/20");for(int i=0;i<actors.size();i++)if(!actors.get(i).isSleeping())System.out.println("APARTMENT_WAIT index="+i+" pos="+actors.get(i).position()+" bed="+citizens.get(i).bedPosition()+" reason="+citizens.get(i).workFailure());}
            h.assertTrue(sleeping==20,"Residents still entering/climbing: "+sleeping+"/20");
        }).thenExecute(()->{
            for(int i=0;i<actors.size();i++)h.assertTrue(actors.get(i).isPhysicallySleepingAt(citizens.get(i).bedPosition()),"Wrong bed used");
            int light=f.home.servicePositions().stream().mapToInt(p->level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK,p.above())).min().orElse(0);
            System.out.println("APARTMENT_PHYSICAL_OK residents=20 floors=3 ticks="+(level.getGameTime()-start)+" seconds="+((System.nanoTime()-wall)/1e9)+" minimumBedBlockLight="+light);
            actors.forEach(SettlementCitizenEntity::stopSleeping);
        }).thenWaitUntil(()->{
            int exited=0;
            for(int i=0;i<actors.size();i++) {
                if(departed.contains(i)){exited++;continue;}
                var e=actors.get(i);var target=f.t.anchor().offset((i%5)*2-3,0,-3-(i/5)*2);
                if(e.distanceToSqr(target.getX()+.5,target.getY(),target.getZ()+.5)<2.25){exited++;departed.add(i);e.discard();}
                else if(Math.floorMod(level.getGameTime(),20)==i)e.navigateTo(target,.7);
                if(level.getGameTime()-start>=1400 && !departed.contains(i) && e.getNavigation().getPath()!=null && traced.add(i)) {
                    var trace=e.getNavigation().getPath();System.out.println("APARTMENT_TRACE resident="+i+" local="+e.position().subtract(f.t.minCorner().getX(),f.t.minCorner().getY(),f.t.minCorner().getZ()));
                    if(trace!=null)for(int n=0;n<trace.getNodeCount();n++)System.out.println("APARTMENT_NODE index="+n+" next="+trace.getNextNodeIndex()+" local="+trace.getNode(n).asBlockPos().subtract(f.t.minCorner())+" type="+trace.getNode(n).type);
                }
                if((level.getGameTime()-start)%200==0 && e.distanceToSqr(target.getX()+.5,target.getY(),target.getZ()+.5)>=2.25){System.out.println("APARTMENT_EXIT_WAIT index="+i+" local="+e.blockPosition().subtract(f.t.minCorner())+" target="+target.subtract(f.t.minCorner()));var path=e.getNavigation().getPath();if(path!=null)System.out.println("APARTMENT_EXIT_PATH next="+path.getNextNodeIndex()+"/"+path.getNodeCount()+" end="+path.getEndNode()+" velocity="+e.getDeltaMovement());}
            }
            if((level.getGameTime()-start)%200==0)System.out.println("APARTMENT_EXIT ticks="+(level.getGameTime()-start)+" exited="+exited+"/20");
            h.assertTrue(exited==20,"Residents still descending: "+exited+"/20");
        }).thenExecute(()->{
            System.out.println("APARTMENT_RETURN_OK residents=20 ticks="+(level.getGameTime()-start)+" seconds="+((System.nanoTime()-wall)/1e9));
            actors.forEach(SettlementCitizenEntity::discard);
        }).thenSucceed();
    }
}
