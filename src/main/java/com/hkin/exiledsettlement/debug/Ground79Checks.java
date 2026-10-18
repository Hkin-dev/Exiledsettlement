package com.hkin.exiledsettlement.debug;
import com.hkin.exiledsettlement.blueprint.*;
import com.hkin.exiledsettlement.building.*;
import com.hkin.exiledsettlement.citizen.*;
import com.hkin.exiledsettlement.region.*;
import com.hkin.exiledsettlement.registry.*;
import com.hkin.exiledsettlement.settlement.*;
import com.hkin.exiledsettlement.terrain.*;
import net.minecraft.core.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.phys.AABB;
import com.google.gson.*;
import java.util.*;

/** Explicit isolated harness only. Test setup is never counted as resident construction. */
public final class Ground79Checks {
    private static final BlockPos CORE=new BlockPos(34000,250,34000);
    private static void check(boolean ok,String name,List<String> checks){if(!ok)throw new IllegalStateException(name);checks.add(name);}
    public static String run(ServerLevel l){
        if(!SaveAcceptanceService.enabled())throw new IllegalStateException("TEST_ONLY");var checks=new ArrayList<String>();
        for(var type:List.of(BuildingType.TREE_FARM,BuildingType.PASTURE_AREA)){
            var r=new ProductionRegion(type,new BlockPos(0,80,0),new BlockPos(8,80,8),Direction.NORTH);
            for(int x=0;x<9;x++)for(int z=0;z<9;z++)r.cells.put(ProductionRegion.key(x,z),new ProductionRegion.Cell(new BlockPos(x,x==4 && z==4?60:80,z),type==BuildingType.TREE_FARM?ProductionRegion.Use.FOREST:ProductionRegion.Use.PASTURE,BuildingProductionService.defaultFor(type),type));
            r.surveyed=true;r.waterSurveyDone=true;r.rebuildUses();
            check(!r.agricultural() && r.levelled(),type+" identity",checks);
            check(r.flatY==80 && r.cells.values().stream().allMatch(c->c.ground().getY()==80),type+" one plane deep pit",checks);
            check(r.stairs.isEmpty() && r.waterBarriers.isEmpty() && r.farmPath.isEmpty(),type+" no farm facilities",checks);
            check(r.template().placements().stream().noneMatch(p->p.material()==Items.WATER_BUCKET || p.state().is(Blocks.FARMLAND) || p.state().is(Blocks.COBBLESTONE_STAIRS)),type+" plain template",checks);
            check(r.template().placements().stream().filter(p->p.state().is(Blocks.DIRT)).count()==81,type+" whole soil membership",checks);
            r.groundPaused=true;r.groundMaintenance=true;r.animalSafety.putInt("Phase",3);r.gridX=17;
            var copy=ProductionRegion.load(r.save());check(copy.save().equals(r.save()),type+" saved goals and safety",checks);
            copy.cells.replaceAll((k,c)->new ProductionRegion.Cell(c.ground().above(7),c.use(),c.production(),c.type()));RegionGroundLayout.grade(copy);
            check(copy.flatY==80 && copy.cells.values().stream().allMatch(c->c.ground().getY()==80),type+" saved height stable",checks);
            check(ProductionRegion.acceptsWorkplace(type,type) && !ProductionRegion.acceptsWorkplace(type,BuildingType.FARM_FIELD),type+" same type merge only",checks);
        }
        check(!ProductionRegion.acceptsWorkplace(BuildingType.TREE_FARM,BuildingType.PASTURE_AREA),"different plain types remain separate",checks);
        var r=new ProductionRegion(BuildingType.TREE_FARM,new BlockPos(0,80,0),new BlockPos(1,80,1),Direction.NORTH);
        for(var p:List.of(new BlockPos(0,80,0),new BlockPos(1,80,0),new BlockPos(0,80,1)))r.cells.put(ProductionRegion.key(p.getX(),p.getZ()),new ProductionRegion.Cell(p,ProductionRegion.Use.FOREST,BuildingProductionService.defaultFor(r.type),r.type));
        r.rebuildUses();check(r.cells.size()==3 && r.cell(new BlockPos(1,80,1))==null,"L membership preserved",checks);
        check(CitizenProfession.LUMBERJACK.serializedName().equals("lumberjack") && CitizenProfession.forWorkplace(BuildingType.TREE_FARM)==CitizenProfession.LUMBERJACK,"single lumberjack role",checks);
        boolean oldRejected=false;try{CitizenProfession.fromSerializedName("forester");}catch(IllegalArgumentException expected){oldRejected=true;}
        check(oldRejected,"no old profession alias",checks);
        var origin=CORE.east(120);var settlement=new SettlementRecord(UUID.randomUUID(),"Ground79 detached",origin,UUID.randomUUID());
        for(var type:List.of(BuildingType.TREE_FARM,BuildingType.PASTURE_AREA)){
            var at=origin.offset(type==BuildingType.TREE_FARM?8:-8,0,8);
            var region=new ProductionRegion(type,at,at.offset(4,0,4),Direction.NORTH);
            for(int x=0;x<5;x++)for(int z=0;z<5;z++)region.cells.put(ProductionRegion.key(at.getX()+x,at.getZ()+z),new ProductionRegion.Cell(at.offset(x,-1,z),type==BuildingType.TREE_FARM?ProductionRegion.Use.FOREST:ProductionRegion.Use.PASTURE,BuildingProductionService.defaultFor(type),type));
            region.surveyed=true;region.layoutReady=true;region.rebuildUses();
            var parent=settlement.addAreaBlueprint(type,at,at.above(50),Direction.NORTH,settlement.ownerId(),false,region.min(),region.max());parent.withRegion(region);
            var upper=at.above(5);var child=settlement.addBlueprint(BuildingType.HOUSE,upper,at.above(51),Direction.NORTH,settlement.ownerId(),false);
            var project=new TerrainProject(child.id(),"区域局部土层与支撑",upper,List.of(new TerrainProject.Step(upper,Blocks.AIR.defaultBlockState(),Blocks.DIRT.defaultBlockState())));project.explicit=true;project.dependencies.add(parent.id());child.withTerrainProject(project);settlement.terrain().index(project);
            check(WorkTaskService.priority(settlement,parent)==0 && WorkTaskService.defaultWorkers(parent)==2,type+" new parent P0 two workers",checks);
            check(WorkTaskService.priorityAt(settlement,child,upper)==0 && WorkTaskService.mother(settlement,child,upper).equals(parent.id()),type+" upper clearance P0 child",checks);
            check(!TerrainService.dependenciesReady(settlement,parent.id()),type+" real preparation required",checks);
            region.groundMaintenance=true;check(WorkTaskService.priority(settlement,parent)==2 && WorkTaskService.priorityAt(settlement,child,upper)==2,type+" maintenance P2 child",checks);
            region.groundPaused=true;check(PastureGroundSafety.guard(l,settlement,child,project.steps.getFirst()).equals("GROUND_MAINTENANCE_PAUSED"),type+" pause blocks action",checks);
            region.groundPaused=false;project.mark(0);check(TerrainService.dependenciesReady(settlement,parent.id()),type+" preparation completion releases parent",checks);
        }
        var safe=new ProductionRegion(BuildingType.PASTURE_AREA,origin,origin.offset(8,0,8),Direction.NORTH);safe.flatY=249;
        safe.animalSafety.putInt("Phase",1);check(!PastureGroundSafety.columnWait(l,safe,origin).isEmpty(),"pasture waits for physical holding pen",checks);
        safe.animalSafety.putInt("Phase",3);safe.animalSafety.putInt("MinX",origin.getX()+2);safe.animalSafety.putInt("MaxX",origin.getX()+6);safe.animalSafety.putInt("MinZ",origin.getZ()+2);safe.animalSafety.putInt("MaxZ",origin.getZ()+6);
        check(!PastureGroundSafety.columnWait(l,safe,origin.offset(4,-1,4)).isEmpty(),"holding soil is retained during outside work",checks);
        safe.animalSafety.putInt("Phase",7);check(PastureGroundSafety.finished(safe) && PastureGroundSafety.columnWait(l,safe,origin.offset(4,-1,4)).isEmpty(),"holding soil released after real transfer",checks);
        return "PASS "+checks.size()+" "+String.join("; ",checks);
    }
    public static String start(ServerLevel l)throws Exception{return start(l,CORE);}
    public static String startFresh(ServerLevel l)throws Exception{return start(l,new BlockPos(40000,250,40000));}
    private static String start(ServerLevel l,BlockPos core)throws Exception{
        if(!SaveAcceptanceService.enabled())throw new IllegalStateException("TEST_ONLY");var data=SettlementSavedData.get(l);
        if(data.findByCore(core).isPresent())throw new IllegalStateException("ALREADY_EXISTS");
        for(int x=-3;x<=3;x++)for(int z=-3;z<=3;z++)l.setChunkForced((core.getX()>>4)+x,(core.getZ()>>4)+z,true);
        for(var p:BlockPos.betweenClosed(core.offset(-24,-5,-24),core.offset(24,12,24)))if(!l.getBlockState(p).isAir())throw new IllegalStateException("UNUSED_AIR_REQUIRED");
        for(int x=-24;x<=24;x++)for(int z=-24;z<=24;z++){for(int y=-5;y<=-2;y++)l.setBlock(core.offset(x,y,z),Blocks.STONE.defaultBlockState(),2);l.setBlock(core.offset(x,-1,z),Blocks.GRASS_BLOCK.defaultBlockState(),2);}
        l.setBlock(core,ModBlocks.SETTLEMENT_CORE.get().defaultBlockState(),2);var s=data.createOrRecover(core,UUID.randomUUID(),"Ground79 physical regions").value();s.upgradeLevel();s.upgradeLevel();s.terrain().enabled=true;s.autoRoads().enabled=false;while(s.citizens().size()<12)s.addMigrant();
        var storage=core.offset(-7,0,-12);l.setBlock(storage,Blocks.BARREL.defaultBlockState(),2);var warehouse=s.addBuilding(BuildingType.WAREHOUSE,storage,Direction.NORTH,storage.offset(-3,0,-3),storage.offset(3,2,3),0,1);warehouse.updateFunction(true,BuildingStatus.OPERATIONAL,1,List.of(storage));
        for(var item:List.of(Items.DIRT,Items.COBBLESTONE,Items.OAK_FENCE))WarehouseInventoryService.insert(l,warehouse,new ItemStack(item,2048));
        for(var item:List.of(Items.OAK_FENCE_GATE,Items.OAK_SAPLING,Items.DARK_OAK_SAPLING,Items.BREAD,Items.LEAD))WarehouseInventoryService.insert(l,warehouse,new ItemStack(item,64));
        for(var item:List.of(Items.DIAMOND_AXE,Items.DIAMOND_PICKAXE,Items.DIAMOND_SHOVEL,Items.IRON_HOE,Items.SHEARS,Items.IRON_SWORD,Items.FISHING_ROD))WarehouseInventoryService.insert(l,warehouse,new ItemStack(item,20));
        for(var kind:List.of(BuildingType.TREE_FARM,BuildingType.PASTURE_AREA)){
            var first=core.offset(4,0,kind==BuildingType.TREE_FARM?-19:7);var last=first.offset(10,0,10);
            for(int dx=0;dx<3;dx++)for(int dz=0;dz<3;dz++)for(int y=0;y<3;y++)l.setBlock(first.offset(dx+5,y,dz+4),Blocks.DIRT.defaultBlockState(),2);
            for(int y=-3;y<=-1;y++)l.setBlock(first.offset(3,y,3),Blocks.AIR.defaultBlockState(),2);
            if(kind==BuildingType.TREE_FARM){var root=first.offset(6,3,5);for(int h=0;h<4;h++)l.setBlock(root.above(h),Blocks.OAK_LOG.defaultBlockState(),2);for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++)l.setBlock(root.offset(dx,4,dz),Blocks.OAK_LEAVES.defaultBlockState(),2);}
            var placed=ProductionRegionService.place(l,kind,first,last,Direction.NORTH,s.ownerId(),null);if(!placed.success())throw new IllegalStateException(placed.reason());
        }
        var first=core.offset(-22,0,6);var last=first.offset(12,0,12);var r=new ProductionRegion(BuildingType.PASTURE_AREA,first,last,Direction.NORTH);
        for(int x=first.getX();x<=last.getX();x++)for(int z=first.getZ();z<=last.getZ();z++)r.cells.put(ProductionRegion.key(x,z),new ProductionRegion.Cell(new BlockPos(x,249,z),ProductionRegion.Use.PASTURE,ResourceLocation.withDefaultNamespace("sheep"),BuildingType.PASTURE_AREA));
        r.surveyed=true;r.waterSurveyDone=true;r.rebuildUses();r.groundVersion=RegionGroundLayout.VERSION;r.flatY=249;r.prepared=true;
        for(var p:r.template().placements())if(p.position().getY()>249)l.setBlock(p.position(),p.state(),2);
        for(int dx=0;dx<2;dx++)for(int dz=0;dz<2;dz++)for(int y=0;y<2;y++)l.setBlock(first.offset(9+dx,y,9+dz),Blocks.DIRT.defaultBlockState(),2);
        for(int y=-3;y<=-1;y++)l.setBlock(first.offset(8,y,4),Blocks.AIR.defaultBlockState(),2);
        var old=s.addBuilding(BuildingType.PASTURE_AREA,first,Direction.NORTH,r.min(),r.max(),0,1);old.withRegion(r);old.updateFunction(true,BuildingStatus.OPERATIONAL,2,List.of(first));old.setWorkerTarget(1,old.configurationRevision());
        var ids=new ArrayList<UUID>();for(int i=0;i<3;i++){var sheep=EntityType.SHEEP.create(l);sheep.setPos(first.getX()+9.5+i%2,250,first.getZ()+3.5+i);l.addFreshEntity(sheep);ids.add(sheep.getUUID());}
        int n=0;for(var c:s.citizens()){
            c.setManualAssignment(n==10?CitizenProfession.LUMBERJACK:n==11?CitizenProfession.HERDSMAN:CitizenProfession.WORKER,n==11?old.id():null);c.updateProjection(null,core.offset(-3,0,-5+n%6));
            c.insertIntoInventory(new ItemStack(Items.BREAD,16));c.insertIntoInventory(new ItemStack(Items.DIAMOND_SHOVEL));c.insertIntoInventory(new ItemStack(Items.DIAMOND_PICKAXE));c.insertIntoInventory(new ItemStack(Items.DIAMOND_AXE));if(n==11)c.insertIntoInventory(new ItemStack(Items.SHEARS));n++;
        }
        ForceWorkDebugService.set(l,s,true);CitizenProjectionService.ensureSettlementCitizens(l,s);SaveAcceptanceService.load(l,s,3);data.touch();
        return "OK settlement="+s.id()+" oldPasture="+old.id()+" animals="+ids+"; finite fixture materials; setup blocks/population/animals are not resident outcomes";
    }
    public static String inspect(ServerLevel l){return inspect(l,CORE);}
    public static String inspectFresh(ServerLevel l){return inspect(l,new BlockPos(40000,250,40000));}
    private static String inspect(ServerLevel l,BlockPos core){
        var s=SettlementSavedData.get(l).findByCore(core).orElseThrow();var j=new JsonObject();j.addProperty("settlement",s.id().toString());var regions=new JsonArray();
        for(var b:s.buildings())if(b.active() && b.region()!=null){var r=b.region();var a=new JsonObject();a.addProperty("id",b.id().toString());a.addProperty("type",b.type().name());a.addProperty("flatY",r.flatY);a.addProperty("members",r.cells.size());a.addProperty("soilReady",r.cells.values().stream().filter(c->r.groundReady(l,c)).count());a.addProperty("standing",r.cells.values().stream().filter(c->FarmConnectivity.standing(l,c.ground())).count());a.addProperty("phase",r.animalSafety.getInt("Phase"));a.addProperty("operational",b.operational());a.addProperty("status",RegionGroundService.summary(l,s,r));regions.add(a);}j.add("regions",regions);
        var herd=new JsonArray();for(var a:l.getEntitiesOfClass(Sheep.class,new AABB(core).inflate(30),e->true)){var v=new JsonObject();v.addProperty("id",a.getUUID().toString());v.addProperty("alive",a.isAlive());v.addProperty("x",a.getX());v.addProperty("y",a.getY());v.addProperty("z",a.getZ());v.addProperty("leashed",a.isLeashed());v.addProperty("aiControlled",PastureGroundSafety.controlsAi(a));v.addProperty("noAi",a.isNoAi());v.addProperty("navigationDone",a.getNavigation().isDone());v.addProperty("navigationTarget",String.valueOf(a.getNavigation().getTargetPos()));v.addProperty("runningGoals",a.goalSelector.getAvailableGoals().stream().filter(g->g.isRunning()).map(g->g.getGoal().getClass().getSimpleName()).toList().toString());v.addProperty("wanted",a.getMoveControl().getWantedX()+","+a.getMoveControl().getWantedY()+","+a.getMoveControl().getWantedZ());herd.add(v);}j.add("animals",herd);
        var work=new JsonArray();for(var p:s.blueprints())if(p.terrainProject()!=null && !p.cancellationQueued()){
            var project=p.terrainProject();for(int i=project.cursor;i<project.steps.size();i++)if(!project.finished.get(i)){
                var step=project.steps.get(i);var v=new JsonObject();v.addProperty("project",p.id().toString());v.addProperty("name",project.name);v.addProperty("position",step.position().toShortString());v.addProperty("target",step.target().toString());v.addProperty("actual",l.getBlockState(step.position()).toString());v.addProperty("available",BlueprintService.pendingAt(l,p,step.position())!=null);work.add(v);
            }
        }j.add("remainingWork",work);
        int realLeads=0;
        for(var b:s.buildings())if(b.active() && b.type().isStorage())realLeads+=WarehouseInventoryService.countItem(l,b,Items.LEAD);
        for(var c:s.citizens())for(var stack:c.inventory())if(stack.is(Items.LEAD))realLeads+=stack.getCount();
        for(var b:s.buildings())if(b.region()!=null)realLeads+=b.region().animalSafety.getCompound("Leads").getAllKeys().size();
        for(var item:l.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,new AABB(core).inflate(30),e->e.getItem().is(Items.LEAD)))realLeads+=item.getItem().getCount();
        j.addProperty("realLeadTotal",realLeads);
        return j.toString();
    }
    public static String repairFixtureLead(ServerLevel l){
        var data=SettlementSavedData.get(l);var s=data.findByCore(CORE).orElseThrow();int total=0;
        for(var b:s.buildings())if(b.active() && b.type().isStorage())total+=WarehouseInventoryService.countItem(l,b,Items.LEAD);
        for(var c:s.citizens())for(var stack:c.inventory())if(stack.is(Items.LEAD))total+=stack.getCount();
        for(var b:s.buildings())if(b.region()!=null)total+=b.region().animalSafety.getCompound("Leads").getAllKeys().size();
        var dropped=l.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,new AABB(CORE).inflate(30),e->e.getItem().is(Items.LEAD));
        for(var item:dropped)total+=item.getItem().getCount();int excess=total-64;if(excess<0)throw new IllegalStateException("Fixture lead loss: "+total);int correction=excess;
        for(var c:s.citizens())while(excess>0 && c.hasInventoryItem(stack->stack.is(Items.LEAD))){var taken=c.extractInventoryItem(stack->stack.is(Items.LEAD),excess);excess-=taken.getCount();}
        for(var item:dropped)if(excess>0){int n=Math.min(excess,item.getItem().getCount());item.getItem().shrink(n);excess-=n;if(item.getItem().isEmpty())item.discard();}
        if(excess!=0)throw new IllegalStateException("Fixture correction exceeds prototype carried/dropped surplus");data.touch();return "FIXTURE_ONLY removed "+correction+" rejected-prototype surplus leads; initial real total restored to 64; not counted as native consumption";
    }
    public static String staff(ServerLevel l){return staff(l,CORE);}
    public static String staffFresh(ServerLevel l){return staff(l,new BlockPos(40000,250,40000));}
    private static String staff(ServerLevel l,BlockPos core){
        var data=SettlementSavedData.get(l);var s=data.findByCore(core).orElseThrow();var tree=s.buildings().stream().filter(b->b.active() && b.type()==BuildingType.TREE_FARM).findFirst().orElseThrow();
        var citizen=s.citizens().stream().filter(c->c.profession()==CitizenProfession.LUMBERJACK).findFirst().orElseThrow();
        var entity=CitizenProjectionService.findProjection(l,citizen);if(entity==null || !entity.onGround() || entity.hasActiveConstructionMovement())throw new IllegalStateException("WAIT_REAL_SAFE_CANCEL_BOUNDARY");
        citizen.clearWork();citizen.clearManualAssignment();entity.getNavigation().stop();
        String reason=BuildingManagementActions.staff(data,s,s.ownerId(),tree,citizen.id(),false,tree.configurationRevision());
        if(reason!=null)throw new IllegalStateException(reason);return "OK real UI staffing business; lumberjack="+citizen.id()+"; tree="+tree.id();
    }
    public static String pause(ServerLevel l,boolean paused){
        return pause(l,paused,CORE);
    }
    public static String pauseFresh(ServerLevel l,boolean paused){return pause(l,paused,new BlockPos(40000,250,40000));}
    private static String pause(ServerLevel l,boolean paused,BlockPos core){
        if(!SaveAcceptanceService.enabled())throw new IllegalStateException("TEST_ONLY");
        var data=SettlementSavedData.get(l);var s=data.findByCore(core).orElseThrow();
        var b=s.buildings().stream().filter(v->v.region()!=null && v.region().type==BuildingType.PASTURE_AREA && v.region().cells.size()==169).findFirst().orElseThrow();
        if(b.region().groundPaused!=paused && !RegionGroundService.toggle(s,b.id()))throw new IllegalStateException("INVALID_REGION");
        data.touch();return "OK same ground-management toggle; paused="+b.region().groundPaused+"; building="+b.id();
    }
}
