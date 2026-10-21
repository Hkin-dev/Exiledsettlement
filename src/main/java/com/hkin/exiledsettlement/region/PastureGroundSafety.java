package com.hkin.exiledsettlement.region;

import com.hkin.exiledsettlement.blueprint.*;
import com.hkin.exiledsettlement.building.*;
import com.hkin.exiledsettlement.citizen.*;
import com.hkin.exiledsettlement.debug.SaveAcceptanceService;
import com.hkin.exiledsettlement.settlement.*;
import com.hkin.exiledsettlement.terrain.*;
import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import java.util.*;

/** Saved, physical holding pen and resident-led transfer. Never moves or respawns an animal. */
public final class PastureGroundSafety {
    private PastureGroundSafety() {}
    private static final Map<Animal,Long> LEASH_RESTORE=new WeakHashMap<>();
    private static final Map<Animal,TransferGoal> CONTROLLED=new IdentityHashMap<>();
    public static boolean controlsAi(Animal animal){var goal=CONTROLLED.get(animal);return goal!=null && goal.valid();}
    private static void takeAi(ServerLevel l,SettlementRecord s,ProductionRegion r,CitizenRecord c,SettlementCitizenEntity holder,Animal animal){
        var old=CONTROLLED.get(animal);if(old!=null && old.holder==holder && old.region==r)return;
        releaseAi(animal);var goal=new TransferGoal(l,s,r,c,holder,animal);CONTROLLED.put(animal,goal);animal.goalSelector.addGoal(-1,goal);
        SaveAcceptanceService.action(l,c,"animal_ai_takeover",animal.blockPosition(),"minecraft:lead",0);
    }
    private static void releaseAi(Animal animal){
        var goal=CONTROLLED.remove(animal);if(goal==null)return;animal.goalSelector.removeGoal(goal);animal.getNavigation().stop();
        SaveAcceptanceService.action(goal.level,goal.citizen,"animal_ai_release",animal.blockPosition(),"minecraft:lead",0);
    }
    public static void releaseAllAi(){for(var animal:List.copyOf(CONTROLLED.keySet()))releaseAi(animal);LEASH_RESTORE.clear();}
    public static void releaseAnimalAi(Animal animal){releaseAi(animal);LEASH_RESTORE.remove(animal);}
    private static final class TransferGoal extends net.minecraft.world.entity.ai.goal.Goal {
        final ServerLevel level;final SettlementRecord settlement;final ProductionRegion region;final CitizenRecord citizen;
        final SettlementCitizenEntity holder;final Animal animal;BlockPos last;long retry;
        TransferGoal(ServerLevel l,SettlementRecord s,ProductionRegion r,CitizenRecord c,SettlementCitizenEntity h,Animal a){
            level=l;settlement=s;region=r;citizen=c;holder=h;animal=a;setFlags(EnumSet.of(Flag.MOVE,Flag.LOOK));
        }
        boolean valid(){var work=job(settlement,citizen.targetBuildingId());return !animal.isRemoved() && animal.isAlive() && !animal.isNoAi() && !animal.isPassenger()
            && holder.isAlive() && citizen.alive() && active(region) && !region.groundPaused
            && CitizenWorkService.schedulePhase(level.getDayTime(),citizen)==CitizenWorkService.SchedulePhase.WORK
            && citizen.workState()==CitizenWorkState.TO_CAPTURE && animal.getUUID().equals(citizen.targetItemEntityId())
            && work!=null && work.region()==region && animal.isLeashed() && animal.getLeashHolder()==holder;
        }
        @Override public boolean canUse(){return valid();}
        @Override public boolean canContinueToUse(){return valid();}
        @Override public boolean requiresUpdateEveryTick(){return true;}
        @Override public void start(){last=null;retry=0;animal.getNavigation().stop();}
        @Override public void stop(){animal.getNavigation().stop();}
        @Override public void tick(){
            boolean into=region.animalSafety.getInt("Phase")==2;
            if(transferArrived(level,region,animal,into)){animal.getNavigation().stop();return;}
            var state=level.getBlockState(gate(region));boolean open=state.getBlock() instanceof FenceGateBlock && state.getValue(FenceGateBlock.OPEN);
            var goal=gate(region).relative(into?outward(region).getOpposite():outward(region),2);
            if(!open)goal=gate(region).relative(into?outward(region):outward(region).getOpposite());
            if(!FarmConnectivity.standing(level,goal.below())){animal.getNavigation().stop();return;}
            animal.getLookControl().setLookAt(goal.getX()+.5,goal.getY()+.5,goal.getZ()+.5);
            if(!goal.equals(last) || level.getGameTime()>=retry){
                // A waypoint is already two blocks past the door; vanilla's one-node
                // reach allowance can otherwise finish before the animal clears it.
                animal.getNavigation().moveTo(goal.getX()+.5,goal.getY(),goal.getZ()+.5,0,1.0);last=goal;retry=level.getGameTime()+10;
            }
        }
    }
    public static boolean active(ProductionRegion r){return r!=null && r.type==BuildingType.PASTURE_AREA && r.animalSafety.getInt("Phase")>0 && r.animalSafety.getInt("Phase")<7;}
    public static boolean finished(ProductionRegion r){return r==null || r.type!=BuildingType.PASTURE_AREA || r.animalSafety.getInt("Phase")==7;}
    private static AABB area(ServerLevel l,ProductionRegion r){return new AABB(r.min().getX(),l.getMinBuildHeight(),r.min().getZ(),r.max().getX()+1,l.getMaxBuildHeight(),r.max().getZ()+1);}
    private static List<Animal> animals(ServerLevel l,ProductionRegion r){return l.getEntitiesOfClass(Animal.class,area(l,r),a->a.isAlive() && r.cell(a.blockPosition())!=null);}
    private static Set<UUID> tracked(ProductionRegion r){var ids=new LinkedHashSet<UUID>();var list=r.animalSafety.getList("Animals",Tag.TAG_COMPOUND);for(int i=0;i<list.size();i++)if(list.getCompound(i).hasUUID("Id"))ids.add(list.getCompound(i).getUUID("Id"));return ids;}
    public static void remember(ServerLevel l,ProductionRegion r){
        if(r==null || r.type!=BuildingType.PASTURE_AREA || active(r))return;
        var ids=tracked(r);boolean changed=false;for(var a:animals(l,r))changed|=ids.add(a.getUUID());
        if(changed){var list=new ListTag();for(var id:ids){var tag=new CompoundTag();tag.putUUID("Id",id);list.add(tag);}r.animalSafety.put("Animals",list);SettlementSavedData.get(l).touch();}
    }
    private static boolean penColumn(ProductionRegion r,BlockPos p){var t=r.animalSafety;return t.contains("MinX") && p.getX()>=t.getInt("MinX") && p.getX()<=t.getInt("MaxX") && p.getZ()>=t.getInt("MinZ") && p.getZ()<=t.getInt("MaxZ");}
    private static boolean penInterior(ProductionRegion r,BlockPos p){var t=r.animalSafety;return penColumn(r,p) && p.getX()>t.getInt("MinX") && p.getX()<t.getInt("MaxX") && p.getZ()>t.getInt("MinZ") && p.getZ()<t.getInt("MaxZ") && p.getY()==r.flatY+1;}
    private static BlockPos gate(ProductionRegion r){return BlockPos.of(r.animalSafety.getLong("Gate"));}
    private static Direction outward(ProductionRegion r){return r.animalSafety.contains("GateFacing")?Direction.from2DDataValue(r.animalSafety.getInt("GateFacing")):Direction.NORTH;}
    private static boolean chooseGate(ServerLevel l,ProductionRegion r){
        var t=r.animalSafety;int x=(t.getInt("MinX")+t.getInt("MaxX"))/2,z=(t.getInt("MinZ")+t.getInt("MaxZ"))/2;
        for(var d:List.of(Direction.NORTH,Direction.SOUTH,Direction.EAST,Direction.WEST)){
            var p=new BlockPos(d==Direction.EAST?t.getInt("MaxX"):d==Direction.WEST?t.getInt("MinX"):x,r.flatY+1,d==Direction.NORTH?t.getInt("MinZ"):d==Direction.SOUTH?t.getInt("MaxZ"):z);
            var outer=r.cell(p.relative(d));var inner=r.cell(p.relative(d.getOpposite()));
            if(outer==null || inner==null || !r.groundReady(l,outer) || !FarmConnectivity.standing(l,outer.ground())
                    || !r.groundReady(l,inner) || !FarmConnectivity.standing(l,inner.ground()))continue;
            t.putLong("Gate",p.asLong());t.putInt("GateFacing",d.get2DDataValue());return true;
        }
        return false;
    }
    private static Map<BlockPos,BlockState> fences(ProductionRegion r){
        var t=r.animalSafety;var result=new LinkedHashMap<BlockPos,BlockState>();if(!t.contains("MinX"))return result;
        var g=gate(r);for(int x=t.getInt("MinX");x<=t.getInt("MaxX");x++)for(int z=t.getInt("MinZ");z<=t.getInt("MaxZ");z++)
            if(x==t.getInt("MinX") || x==t.getInt("MaxX") || z==t.getInt("MinZ") || z==t.getInt("MaxZ")){
                var p=new BlockPos(x,r.flatY+1,z);result.put(p,p.equals(g)?Blocks.OAK_FENCE_GATE.defaultBlockState().setValue(FenceGateBlock.FACING,outward(r)):Blocks.OAK_FENCE.defaultBlockState());
            }
        return result;
    }
    private static boolean enclosed(ServerLevel l,ProductionRegion r,boolean closed){
        return !fences(r).isEmpty() && fences(r).entrySet().stream().allMatch(e->BlueprintService.matchesPlacedState(l.getBlockState(e.getKey()),e.getValue())
            && (!closed || !(l.getBlockState(e.getKey()).getBlock() instanceof FenceGateBlock) || !l.getBlockState(e.getKey()).getValue(FenceGateBlock.OPEN)));
    }
    public static boolean prepare(ServerLevel l,SettlementRecord s,BlueprintRecord b){
        var r=b.region();if(r==null || r.type!=BuildingType.PASTURE_AREA)return true;
        var t=r.animalSafety;int phase=t.getInt("Phase");
        if(r.groundPaused)return false;
        if(phase==0){
            var herd=new ArrayList<>(animals(l,r));for(var id:tracked(r))if(l.getEntity(id) instanceof Animal a && a.isAlive() && !herd.contains(a))herd.add(a);
            boolean changing=r.cells.values().stream().anyMatch(c->!r.groundReady(l,c) || !FarmConnectivity.standing(l,c.ground()));
            if(herd.isEmpty() || !changing){t.putInt("Phase",7);return true;}
            var ids=new ListTag();var counts=new HashMap<net.minecraft.resources.ResourceLocation,Integer>();for(var a:herd){var entry=new CompoundTag();entry.putUUID("Id",a.getUUID());ids.add(entry);counts.merge(net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(a.getType()),1,Integer::sum);}counts.forEach((id,n)->r.animalFloors.merge(id,n,Math::max));t.put("Animals",ids);
            int side=Math.max(7,(int)Math.ceil(Math.sqrt(herd.size()*2.0))+2);boolean found=false;
            // The temporary enclosure must have a real, already supported common floor.
            for(var seed:r.cells.values()){
                int x=seed.ground().getX(),z=seed.ground().getZ();boolean safe=true;
                for(int dx=0;dx<side && safe;dx++)for(int dz=0;dz<side;dz++){
                    var c=r.cell(new BlockPos(x+dx,r.flatY,z+dz));
                    if(c==null || c.use()!=ProductionRegion.Use.PASTURE || !r.groundReady(l,c)
                            || !FarmConnectivity.standing(l,c.ground()) || !l.getBlockState(c.ground().above()).isAir()){safe=false;break;}
                }
                if(!safe || r.usableArea()-(side-2)*(side-2)<Math.max(4,herd.size()*2))continue;
                t.putInt("MinX",x);t.putInt("MaxX",x+side-1);t.putInt("MinZ",z);t.putInt("MaxZ",z+side-1);found=chooseGate(l,r);if(found)break;
            }
            if(!found){r.status="暂缓有牲畜地面改造：没有足够且可达的真实平地保留区";return false;}
            t.putInt("Phase",1);phase=1;
        }
        if(phase==1){
            var outer=r.cell(gate(r).relative(outward(r)));
            if(outer==null || !FarmConnectivity.standing(l,outer.ground())){
                if(!chooseGate(l,r)){r.status="保留区入口没有实际内外落脚面";return false;}
            }
            var steps=new ArrayList<TerrainProject.Step>();for(var e:fences(r).entrySet())if(!BlueprintService.matchesPlacedState(l.getBlockState(e.getKey()),e.getValue())){
                var owner=s.terrain().cells.get(e.getKey());var previous=owner==null?null:s.terrain().projects.get(owner);
                if(previous!=null && previous.name.equals("牧场安全保留区围合") && previous.dependencies.contains(b.id())){
                    var oldGoal=previous.template().placementAt(e.getKey());
                    if(oldGoal!=null && !BlueprintService.matchesPlacedState(oldGoal.state(),e.getValue())){
                        for(int index:previous.indices(e.getKey()))previous.abandon(index);
                        s.terrain().cells.remove(e.getKey(),previous.id);
                    }
                }
                var current=l.getBlockState(e.getKey());if(!current.isAir()){
                    if(!(current.getBlock() instanceof FenceBlock || current.getBlock() instanceof FenceGateBlock)){r.status="临时围合受阻："+e.getKey().toShortString();return false;}
                    steps.add(new TerrainProject.Step(e.getKey(),Blocks.AIR.defaultBlockState(),current));
                }steps.add(new TerrainProject.Step(e.getKey(),e.getValue(),Blocks.AIR.defaultBlockState()));
            }
            var residents=l.getEntitiesOfClass(SettlementCitizenEntity.class,area(l,r),a->a.isAlive() && penInterior(r,a.blockPosition()));
            var gateState=l.getBlockState(gate(r));if(gateState.getBlock() instanceof FenceGateBlock && !gateState.getValue(FenceGateBlock.OPEN) && (!steps.isEmpty() || !residents.isEmpty()))com.hkin.exiledsettlement.citizen.FenceGateAccessService.change(l,s,gate(r),true,null);
            if(steps.isEmpty()){
                if(!residents.isEmpty()){
                    var landing=gate(r).relative(outward(r),2);
                    for(var resident:residents){var record=s.citizen(CitizenProjectionService.citizenId(resident));if(record!=null && record.workState()==CitizenWorkState.IDLE && resident.canStandAtPosition(landing))resident.navigateTo(landing,.65);}
                    r.status="等待工人沿实际入口离开保留区";return false;
                }
                if(gateState.getBlock() instanceof FenceGateBlock
                        && com.hkin.exiledsettlement.citizen.FenceGateAccessService.change(l,s,gate(r),false,null)) {
                    r.status="等待门口净空或红石解除后关闭保留区门";return false;
                }
                t.putInt("Phase",2);r.status="临时围合就绪，等待牧民实际引导牲畜";
            }
            else {ProductionRegionService.publish(l,s,b.id(),"牧场安全保留区围合",gate(r),steps);r.status="居民正取材建设实际保留区";}
            return false;
        }
        if(phase==2){
            if(!enclosed(l,r,false)){t.putInt("Phase",1);return false;}
            if(!all(l,r,true)){r.status="等待牧民沿真实地面引导牲畜进入保留区";return false;}
            if(!enclosed(l,r,true)){
                var state=l.getBlockState(gate(r));
                if(state.getBlock() instanceof FenceGateBlock)
                    com.hkin.exiledsettlement.citizen.FenceGateAccessService.change(l,s,gate(r),false,null);
                r.status="等待门口净空并关闭保留区门";return false;
            }
            t.putInt("Phase",3);r.status="牲畜已实际隔离，准备外围土层";return true;
        }
        if(phase==3){
            boolean ready=r.cells.values().stream().filter(c->!penColumn(r,c.ground())).allMatch(c->r.groundReady(l,c) && (c.use()==ProductionRegion.Use.BOUNDARY || FarmConnectivity.standing(l,c.ground())) && !RegionGroundService.pendingColumn(l,s,c.ground()));
            boolean closed=r.template().placements().stream().filter(p->p.state().getBlock() instanceof FenceBlock || p.state().getBlock() instanceof FenceGateBlock).allMatch(p->BlueprintService.placementSatisfied(l,r.template(),p));
            if(ready && closed){t.putInt("Phase",4);r.status="外围新地面与围合完成，等待实际转移牲畜";}
            return true;
        }
        if(phase==4){
            if(!all(l,r,false)){r.status="等待牧民将牲畜引导至已完成片区";return true;}
            t.putInt("Phase",5);phase=5;
        }
        if(phase==5){
            var steps=new ArrayList<TerrainProject.Step>();for(var e:fences(r).entrySet()){
                var state=l.getBlockState(e.getKey());var finalGoal=r.template().placementAt(e.getKey());
                if(finalGoal!=null && BlueprintService.matchesPlacedState(state,finalGoal.state()))continue;
                if(state.getBlock() instanceof FenceBlock || state.getBlock() instanceof FenceGateBlock){
                    var previous=s.terrain().projects.get(s.terrain().cells.get(e.getKey()));
                    if(previous!=null && previous.name.equals("牧场安全保留区围合") && previous.dependencies.contains(b.id())){
                        for(int index:previous.indices(e.getKey()))previous.abandon(index);
                        s.terrain().cells.remove(e.getKey(),previous.id);
                    }
                    steps.add(new TerrainProject.Step(e.getKey(),Blocks.AIR.defaultBlockState(),state));
                }
                else if(!state.isAir()){r.status="临时隔栏位置有新设施，保留并暂缓："+e.getKey().toShortString();return true;}
            }
            if(!steps.isEmpty()){ProductionRegionService.publish(l,s,b.id(),"牧场安全临时隔栏回收",gate(r),steps);r.status="居民真实回收临时隔栏及掉落";return true;}
            t.putInt("Phase",6);for(int i=0;i<r.ordered().size();i++)if(penColumn(r,r.ordered().get(i).ground()))r.preparationRetries.add(i);phase=6;
        }
        if(phase==6 && r.cells.values().stream().filter(c->penColumn(r,c.ground())).allMatch(c->r.groundReady(l,c) && !RegionGroundService.pendingColumn(l,s,c.ground()))){t.putInt("Phase",7);r.status="实际安全转移与保留区收尾完成";}
        SettlementSavedData.get(l).touch();return true;
    }
    private static boolean all(ServerLevel l,ProductionRegion r,boolean inside){
        for(var id:tracked(r)){
            var e=l.getEntity(id);if(!(e instanceof Animal a) || !a.isAlive() || a.isLeashed())return false;
            if(!transferArrived(l,r,a,inside))return false;
        }
        return true;
    }
    private static boolean transferArrived(ServerLevel l,ProductionRegion r,Animal a,boolean into){
        if(into)return penInterior(r,a.blockPosition()) && !a.getBoundingBox().intersects(new AABB(gate(r)).inflate(.3));
        var bounds=new AABB(r.animalSafety.getInt("MinX"),l.getMinBuildHeight(),r.animalSafety.getInt("MinZ"),r.animalSafety.getInt("MaxX")+1,l.getMaxBuildHeight(),r.animalSafety.getInt("MaxZ")+1);
        return !a.getBoundingBox().intersects(bounds) && r.cell(a.blockPosition())!=null;
    }
    public static String columnWait(ServerLevel l,ProductionRegion r,BlockPos p){
        if(r.type!=BuildingType.PASTURE_AREA)return "";int phase=r.animalSafety.getInt("Phase");
        if(phase==0 || phase==1 || phase==2)return "等待真实牲畜保留区与转移";
        if(phase>=3 && phase<=5 && penColumn(r,p))return "保留正在使用的牲畜隔离地面："+p.toShortString();
        var cell=r.cell(p);
        if(phase==7 && cell!=null && p.equals(cell.ground()) && r.groundReady(l,cell) && FarmConnectivity.standing(l,p))return "";
        for(var a:l.getEntitiesOfClass(Animal.class,new AABB(p).inflate(0,4,0),Entity::isAlive))if(a.getBoundingBox().intersects(new AABB(p)) || a.getBoundingBox().intersects(new AABB(p.above())) || a.blockPosition().below().equals(p))return "等待牲畜实际离开地面："+p.toShortString();
        return "";
    }
    public static String guard(ServerLevel l,SettlementRecord s,BlueprintRecord site,TerrainProject.Step step){
        var p=step.position();var project=site.terrainProject();
        var parents=project==null?java.util.List.<BlueprintRecord>of():project.dependencies.stream().map(s::blueprint)
                .filter(java.util.Objects::nonNull).filter(b->b.region()!=null && b.region().plainSoil()).toList();
        // Most orders have zero or one region owner. Preserve the original
        // settlement ordering when an older shared order names several regions.
        var parent=parents.size()==1?parents.getFirst():parents.isEmpty()?null:s.blueprints().stream().filter(parents::contains).findFirst().orElse(null);
        if(parent!=null){var r=parent.region();if(r.groundPaused)return "GROUND_MAINTENANCE_PAUSED";
            if(!site.terrainProject().name.startsWith("牧场安全") && !RegionGroundService.columnWait(l,s,parent,p).isEmpty())return r.type==BuildingType.TREE_FARM?"FORESTRY_CLEARANCE_PENDING":"ANIMAL_GROUND_OCCUPIED";
        }
        for(var a:l.getEntitiesOfClass(Animal.class,new AABB(p).inflate(1,2,1),Entity::isAlive))
            if(step.fill()?a.getBoundingBox().intersects(new AABB(p)):a.blockPosition().below().equals(p))return "ANIMAL_GROUND_OCCUPIED";
        return "";
    }
    private static BlueprintRecord job(SettlementRecord s,UUID building){return s.blueprints().stream().filter(b->b.region()!=null && b.region().type==BuildingType.PASTURE_AREA && building!=null && building.equals(b.region().workingBuilding) && !b.cancellationQueued()).findFirst().orElse(null);}
    private static BlockPos destination(ServerLevel l,ProductionRegion r,boolean into){
        return r.cells.values().stream().filter(c->c.use()==ProductionRegion.Use.PASTURE && r.groundReady(l,c) && FarmConnectivity.standing(l,c.ground())
            && (into?penInterior(r,c.ground().above()):!penColumn(r,c.ground())))
            .map(c->c.ground().above()).max(Comparator.comparingDouble(p->
                (into?-100:100)*((p.getX()-gate(r).getX())*outward(r).getStepX()+(p.getZ()-gate(r).getZ())*outward(r).getStepZ())-p.distSqr(gate(r)))).orElse(null);
    }
    public static boolean assign(ServerLevel l,SettlementRecord s,CitizenRecord c,Set<UUID> claimed){
        for(var b:s.blueprints())if(b.region()!=null && active(b.region()) && !b.region().groundPaused && b.region().workingBuilding!=null){
            var r=b.region();int phase=r.animalSafety.getInt("Phase");if(phase!=2 && phase!=4)continue;
            if(c.hasManualAssignment() && !r.workingBuilding.equals(c.manualWorkBuildingId()))continue;
            var dest=destination(l,r,phase==2);if(dest==null){r.status="没有可真实站立的牲畜转移目标";continue;}
            for(var id:tracked(r)){
                var e=l.getEntity(id);if(!(e instanceof Animal a) || transferArrived(l,r,a,phase==2) || a.isLeashed() || claimed.contains(id))continue;
                claimed.add(id);return c.startCapture(r.workingBuilding,dest,id);
            }
        }
        return false;
    }
    private static void physicalGate(ServerLevel l,ProductionRegion r,SettlementCitizenEntity entity,Animal a,boolean open){
        var p=gate(r);var state=l.getBlockState(p);if(!(state.getBlock() instanceof FenceGateBlock) || entity.distanceToSqr(p.getX()+.5,p.getY(),p.getZ()+.5)>9)return;
        var id=CitizenProjectionService.settlementId(entity);
        var settlement=id==null?null:SettlementSavedData.get(l).findById(id).orElse(null);
        if(settlement!=null)com.hkin.exiledsettlement.citizen.FenceGateAccessService.change(l,settlement,p,open,entity);
    }
    private static void navigateThroughPen(ServerLevel l,ProductionRegion r,SettlementCitizenEntity entity,BlockPos goal,double speed){
        boolean inside=penColumn(r,entity.blockPosition()), targetInside=penColumn(r,goal);
        if(inside==targetInside){if(!inside)physicalGate(l,r,entity,null,false);entity.navigateTo(goal,speed);return;}
        physicalGate(l,r,entity,null,true);
        var state=l.getBlockState(gate(r));
        var approach=gate(r).relative(inside?outward(r).getOpposite():outward(r));
        var exit=gate(r).relative(inside?outward(r):outward(r).getOpposite(),2);
        entity.navigateTo(state.getBlock() instanceof FenceGateBlock && state.getValue(FenceGateBlock.OPEN)?exit:approach,speed);
    }
    public static boolean capture(ServerLevel l,SettlementRecord s,CitizenRecord c,SettlementCitizenEntity entity){
        var b=job(s,c.targetBuildingId());if(b==null || !active(b.region()))return false;var r=b.region();var e=l.getEntity(c.targetItemEntityId());
        if(!(e instanceof Animal a) || !a.isAlive()){c.setActivity(CitizenActivity.CAPTURING,"ANIMAL_TRANSFER_TARGET_MISSING");return true;}
        if(r.groundPaused){entity.getNavigation().stop();return true;}
        var leads=r.animalSafety.getCompound("Leads");String key=a.getUUID().toString();
        if(a.isLeashed() && a.getLeashHolder()!=entity){c.setActivity(CitizenActivity.CAPTURING,"ANIMAL_ALREADY_LEASHED");return true;}
        if(leads.contains(key) && leads.getCompound(key).getBoolean("Attached") && !a.isLeashed()){
            long first=LEASH_RESTORE.computeIfAbsent(a,unused->l.getGameTime());
            entity.getNavigation().stop();if(l.getGameTime()-first<20)return true;
            // Vanilla has dropped the real broken lead. Do not refund its escrow or attach it remotely.
            leads.remove(key);r.animalSafety.put("Leads",leads);LEASH_RESTORE.remove(a);r.status="牵引绳已实际断开，等待重新领取并靠近牲畜";SettlementSavedData.get(l).touch();return true;
        }
        if(a.isLeashed())LEASH_RESTORE.remove(a);
        if(!leads.contains(key)){
            ItemStack real=c.inventory().stream().filter(stack->stack.is(Items.LEAD) && !stack.isEmpty()).findFirst().orElse(ItemStack.EMPTY);
            if(real.isEmpty()){
                var warehouse=s.buildings().stream().filter(w->w.active() && w.type().isStorage() && WarehouseInventoryService.countItem(l,w,Items.LEAD)>0).min(Comparator.comparingDouble(w->w.position().distSqr(entity.blockPosition()))).orElse(null);
                if(warehouse==null){c.setActivity(CitizenActivity.CAPTURING,"ANIMAL_TRANSFER_NEEDS_REAL_LEAD");r.status="真实转移缺少牵引绳";return true;}
                var station=WarehouseInventoryService.reserveExtractionPosition(l,warehouse,entity,Items.LEAD);
                if(station==null){c.setActivity(CitizenActivity.CAPTURING,"WAREHOUSE_INACCESSIBLE");return true;}
                if(!WarehouseInventoryService.canInteract(l,warehouse,entity)){navigateThroughPen(l,r,entity,station,.65);return true;}
                var taken=WarehouseInventoryService.extractItemForResident(l,warehouse,entity,Items.LEAD,1);if(taken.isEmpty())return true;
                var remaining=c.insertIntoInventory(taken);if(!remaining.isEmpty())WarehouseInventoryService.insertForResident(l,warehouse,entity,remaining);
                WarehouseInventoryService.releaseInteractionPosition(l,entity);SettlementSavedData.get(l).touch();return true;
            }
            if(entity.distanceToSqr(a)>9){navigateThroughPen(l,r,entity,a.blockPosition(),.65);return true;}
            var taken=c.extractInventoryItem(stack->stack.is(Items.LEAD),1);if(taken.isEmpty())return true;
            var held=new CompoundTag();held.putUUID("Holder",c.id());held.put("Stack",taken.save(l.registryAccess()));leads.put(key,held);r.animalSafety.put("Leads",leads);
        }
        if(!a.isLeashed() && entity.distanceToSqr(a)>9){navigateThroughPen(l,r,entity,a.blockPosition(),.65);return true;}
        if(!a.isLeashed())a.setLeashedTo(entity,true);
        leads.getCompound(key).putBoolean("Attached",true);r.animalSafety.put("Leads",leads);
        if(a.isNoAi() || a.isPassenger()){c.setActivity(CitizenActivity.CAPTURING,"牲畜原AI关闭或正在乘坐载具，暂缓转移");return true;}
        takeAi(l,s,r,c,entity,a);
        entity.setItemSlot(EquipmentSlot.MAINHAND,ItemStack.parseOptional(l.registryAccess(),leads.getCompound(key).getCompound("Stack")));
        boolean into=r.animalSafety.getInt("Phase")==2;
        boolean arrived=transferArrived(l,r,a,into);
        var dest=c.targetPosition();physicalGate(l,r,entity,a,true);
        if(dest!=null && !arrived && (!into || penInterior(r,entity.blockPosition()))) {
            // Vanilla followers stop behind their holder. Walk deep enough past the entrance.
            var deeper=destination(l,r,into);if(deeper!=null)dest=deeper;
        }
        if(!into && !arrived && dest!=null){
            var held=leads.getCompound(key);
            if(!held.contains("ExitWaypoint") && !penColumn(r,entity.blockPosition())
                    && entity.distanceToSqr(dest.getX()+.5,dest.getY(),dest.getZ()+.5)<2.25){
                // After walking through the door, continue along the outside lane so the
                // follower's complete body clears the enclosure before releasing its lead.
                var side=r.cells.values().stream().filter(cell->cell.use()==ProductionRegion.Use.PASTURE
                    && !penColumn(r,cell.ground()) && r.groundReady(l,cell) && FarmConnectivity.standing(l,cell.ground())
                    && (cell.ground().getX()-gate(r).getX())*outward(r).getStepX()
                        +(cell.ground().getZ()-gate(r).getZ())*outward(r).getStepZ()>=2)
                    .map(cell->cell.ground().above()).max(Comparator.comparingDouble(p->p.distSqr(a.blockPosition()))).orElse(null);
                if(side!=null){held.putLong("ExitWaypoint",side.asLong());leads.put(key,held);r.animalSafety.put("Leads",leads);}
            }
            if(held.contains("ExitWaypoint"))dest=BlockPos.of(held.getLong("ExitWaypoint"));
        }
        if(!arrived || (into?!penInterior(r,entity.blockPosition()):penColumn(r,entity.blockPosition()))){
            if(entity.distanceToSqr(a)>36){navigateThroughPen(l,r,entity,a.blockPosition(),.45);c.setActivity(CitizenActivity.CAPTURING,"沿真实通路返回并等待牲畜跟上");return true;}
            var gateState=l.getBlockState(gate(r));
            if(dest!=null)entity.navigateTo(gateState.getBlock() instanceof FenceGateBlock && !gateState.getValue(FenceGateBlock.OPEN)
                    ?gate(r).relative(into?outward(r):outward(r).getOpposite()):dest,.45);
            c.setActivity(CitizenActivity.CAPTURING,"沿真实地面引导牲畜");SettlementSavedData.get(l).touch();return true;
        }
        if(into){
            var state=l.getBlockState(gate(r));
            if(state.getBlock() instanceof FenceGateBlock && state.getValue(FenceGateBlock.OPEN)){
                if(entity.distanceToSqr(gate(r).getX()+.5,gate(r).getY(),gate(r).getZ()+.5)>9){entity.navigateTo(gate(r).relative(outward(r).getOpposite(),2),.45);return true;}
                physicalGate(l,r,entity,a,false);if(l.getBlockState(gate(r)).getValue(FenceGateBlock.OPEN))return true;
            }
        }
        releaseAi(a);a.dropLeash(true,false);returnLead(l,s,r,key,entity);
        SaveAcceptanceService.action(l,c,"pasture_ground_transfer",a.blockPosition(),"minecraft:lead",1);
        c.clearWork();entity.getNavigation().stop();SettlementSavedData.get(l).touch();return true;
    }
    private static void returnLead(ServerLevel l,SettlementRecord s,ProductionRegion r,String id,SettlementCitizenEntity entity){
        var leads=r.animalSafety.getCompound("Leads");if(!leads.contains(id))return;var held=leads.getCompound(id);var c=held.hasUUID("Holder")?s.citizen(held.getUUID("Holder")):null;
        var stack=ItemStack.parseOptional(l.registryAccess(),held.getCompound("Stack"));if(c!=null && c.alive())stack=c.insertIntoInventory(stack);
        if(!stack.isEmpty())Block.popResource(l,entity!=null?entity.blockPosition():gate(r),stack);leads.remove(id);r.animalSafety.put("Leads",leads);
    }
    public static void recoverLeads(ServerLevel l,SettlementRecord s){
        for(var entry:List.copyOf(CONTROLLED.entrySet()))if(entry.getKey().level()==l && !entry.getValue().valid())releaseAi(entry.getKey());
        var seen=Collections.newSetFromMap(new IdentityHashMap<ProductionRegion,Boolean>());var regions=new ArrayList<ProductionRegion>();s.buildings().forEach(b->{if(b.region()!=null)regions.add(b.region());});s.blueprints().forEach(b->{if(b.region()!=null)regions.add(b.region());});
        for(var r:regions)if(seen.add(r)){
            var leads=r.animalSafety.getCompound("Leads");for(String id:List.copyOf(leads.getAllKeys())){
                var held=leads.getCompound(id);var c=held.hasUUID("Holder")?s.citizen(held.getUUID("Holder")):null;var e=l.getEntity(UUID.fromString(id));
                if(c!=null && c.alive() && !r.groundPaused && CitizenWorkService.schedulePhase(l.getDayTime(),c)==CitizenWorkService.SchedulePhase.WORK && c.workState()==CitizenWorkState.TO_CAPTURE && UUID.fromString(id).equals(c.targetItemEntityId()) && job(s,c.targetBuildingId())!=null)continue;
                // An unloaded animal keeps its lead record until its physical state can be reconciled.
                if(e==null)continue;
                if(e instanceof Animal a && !a.isLeashed() && held.getBoolean("Attached")){
                    long first=LEASH_RESTORE.computeIfAbsent(a,unused->l.getGameTime());if(l.getGameTime()-first<20)continue;
                    leads.remove(id);r.animalSafety.put("Leads",leads);LEASH_RESTORE.remove(a);SettlementSavedData.get(l).touch();continue;
                }
                if(e instanceof Animal a){releaseAi(a);if(a.isLeashed())a.dropLeash(true,false);}returnLead(l,s,r,id,null);SettlementSavedData.get(l).touch();
            }
        }
    }
}
