package com.hkin.exiledsettlement.terrain;

import com.hkin.exiledsettlement.blueprint.*;
import com.hkin.exiledsettlement.building.BuildingType;
import com.hkin.exiledsettlement.debug.SaveAcceptanceService;
import com.hkin.exiledsettlement.settlement.*;
import net.minecraft.core.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import java.util.*;

/** Detached excavation regression; fixture blocks are restored, never credited as resident work. */
public final class PublicDig87Checks {
    private static void check(boolean ok,String name,List<String> out){if(!ok)throw new IllegalStateException(name);out.add(name);}
    public static String run(ServerLevel l){
        if(!SaveAcceptanceService.enabled())throw new IllegalStateException("TEST_ONLY");
        var core=new BlockPos(50500,250,50500);var at=core.offset(3,-1,0);
        var s=new SettlementRecord(UUID.randomUUID(),"PublicDig87 detached",core,UUID.randomUUID());
        var before=new LinkedHashMap<BlockPos,BlockState>();var out=new ArrayList<String>();
        java.util.function.BiConsumer<BlockPos,BlockState> put=(p,state)->{before.putIfAbsent(p,l.getBlockState(p));l.setBlock(p,state,2);};
        try{
            put.accept(at,Blocks.GRASS_BLOCK.defaultBlockState());put.accept(at.below(),Blocks.STONE.defaultBlockState());put.accept(at.above(),Blocks.STONE.defaultBlockState());
            var site=s.addBlueprint(BuildingType.HOUSE,at,core.above(40),Direction.NORTH,s.ownerId(),false);
            var p=new TerrainProject(site.id(),"Old public lowering",at,List.of(new TerrainProject.Step(at,Blocks.AIR.defaultBlockState(),Blocks.GRASS_BLOCK.defaultBlockState())));
            p.publicGround=true;p.groundSurfaces.add(at.below());site.withTerrainProject(p);
            var w=s.terrain().publicGround;long k=at.atY(0).asLong();w.goals.put(k,at.getY());w.phase=0;w.roadsIntegrated=true;
            check("CONSTRUCTION_TARGET_CHANGED".equals(TerrainService.guard(l,s,site,p.steps.getFirst())),"old lowering cannot dig unchanged flat plane",out);
            p.groundSurfaces.clear();p.groundSurfaces.add(at);
            check("CONSTRUCTION_TARGET_CHANGED".equals(TerrainService.guard(l,s,site,p.steps.getFirst())),"pure dig cannot remove target surface",out);
            var under=new TerrainProject.Step(at.below(),Blocks.AIR.defaultBlockState(),Blocks.STONE.defaultBlockState());
            check("CONSTRUCTION_TARGET_CHANGED".equals(TerrainService.guard(l,s,site,under)),"pure dig cannot remove underground support",out);
            var high=new TerrainProject.Step(at.above(),Blocks.AIR.defaultBlockState(),Blocks.STONE.defaultBlockState());
            check(TerrainService.guard(l,s,site,high).isEmpty(),"valid obstacle above committed plane remains removable",out);
            for(int phase:List.of(1,4,10,14)){w.phase=phase;check("CONSTRUCTION_TARGET_CHANGED".equals(TerrainService.guard(l,s,site,high)),"excavation paused in planning phase "+phase,out);}
            w.phase=5;w.roadsIntegrated=false;
            check("CONSTRUCTION_TARGET_CHANGED".equals(TerrainService.guard(l,s,site,high)),"provisional solution cannot excavate",out);
            w.roadsIntegrated=true;
            check(TerrainService.guard(l,s,site,high).isEmpty(),"committed publication phase permits valid excavation",out);
            w.goals.clear();check("CONSTRUCTION_TARGET_CHANGED".equals(TerrainService.guard(l,s,site,high)),"unsourced removed column cannot retain old dig",out);
            w.goals.put(k,at.getY()-1);p.groundSurfaces.clear();p.groundSurfaces.add(at.below());
            check(TerrainService.guard(l,s,site,p.steps.getFirst()).isEmpty(),"committed necessary lowering remains allowed",out);
            w.goals.put(k,at.getY());p.groundSurfaces.clear();p.groundSurfaces.add(at);
            var region=s.addBlueprint(BuildingType.PASTURE_AREA,at,at.east(2).south(2),Direction.NORTH,s.ownerId(),false);
            check("PROTECTED_AREA".equals(TerrainService.guard(l,s,site,high)),"new region reservation stops old public excavation",out);
            region.queueCancellation();
            var restored=TerrainProject.load(p.save());site.withTerrainProject(restored);
            check("CONSTRUCTION_TARGET_CHANGED".equals(TerrainService.guard(l,s,site,restored.steps.getFirst())),"saved stale dig remains blocked",out);
            check(l.getBlockState(at).is(Blocks.GRASS_BLOCK) && l.getBlockState(at.below()).is(Blocks.STONE) && p.finished.isEmpty(),"checks never dig world or credit completion",out);
            return "PASS "+out.size()+" public excavation checks: "+String.join(", ",out);
        }finally{before.forEach((p,state)->l.setBlock(p,state,2));}
    }
    public static String live(ServerLevel l){
        if(!SaveAcceptanceService.enabled())throw new IllegalStateException("TEST_ONLY");
        var core=new BlockPos(50700,250,50700);var data=SettlementSavedData.get(l);
        data.findByCore(new BlockPos(50600,250,50600)).filter(old->old.name().equals("PublicDig87 native flat ground")).ifPresent(SettlementRecord::deactivate);
        if(data.findByCore(core).isPresent())throw new IllegalStateException("FIXTURE_ALREADY_EXISTS");
        for(var at:BlockPos.betweenClosed(core.offset(-7,-2,-7),core.offset(7,3,7)))if(!l.getBlockState(at).isAir())throw new IllegalStateException("UNUSED_AIR_REQUIRED");
        for(int x=-7;x<=7;x++)for(int z=-7;z<=7;z++){l.setBlock(core.offset(x,-2,z),Blocks.STONE.defaultBlockState(),2);l.setBlock(core.offset(x,-1,z),Blocks.GRASS_BLOCK.defaultBlockState(),2);}
        l.setBlock(core,com.hkin.exiledsettlement.registry.ModBlocks.SETTLEMENT_CORE.get().defaultBlockState(),2);
        var settlement=data.createOrRecover(core,UUID.randomUUID(),"PublicDig87 native flat ground").value();
        settlement.terrain().enabled=false;settlement.terrain().publicPaused=true;settlement.autoRoads().enabled=false;settlement.decorations().enabled=false;
        var w=settlement.terrain().publicGround;w.phase=0;w.roadsIntegrated=true;
        var flat=core.offset(3,-1,0);var high=core.offset(-3,0,0);l.setBlock(high,Blocks.STONE.defaultBlockState(),2);
        for(var at:List.of(flat,high)){
            var ground=at.atY(249);w.goals.put(ground.atY(0).asLong(),249);
            var site=settlement.addBlueprint(BuildingType.HOUSE,at,at.above(40),Direction.NORTH,settlement.ownerId(),false);
            var project=new TerrainProject(site.id(),at.equals(flat)?"Obsolete lowering on flat ground":"Valid clearance above flat ground",at,List.of(new TerrainProject.Step(at,Blocks.AIR.defaultBlockState(),l.getBlockState(at))));
            project.publicGround=true;project.explicit=true;project.groundSurfaces.add(at.equals(flat)?ground.below():ground);project.dependencies.add(settlement.id());site.withTerrainProject(project);settlement.terrain().index(project);
        }
        int n=0;for(var c:settlement.citizens()){
            c.clearWork();c.setManualAssignment(n<2?com.hkin.exiledsettlement.citizen.CitizenProfession.WORKER:com.hkin.exiledsettlement.citizen.CitizenProfession.HERDSMAN,null);
            c.updateProjection(null,core.offset(n-3,0,3));c.insertIntoInventory(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.BREAD,16));
            if(n<2){c.insertIntoInventory(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND_PICKAXE));c.insertIntoInventory(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND_SHOVEL));}n++;
        }
        com.hkin.exiledsettlement.citizen.CitizenProjectionService.ensureSettlementCitizens(l,settlement);SaveAcceptanceService.load(l,settlement,2);data.touch();
        return "OK "+settlement.id()+"; two workers, finite real tools and bread, normal schedule; one stale floor dig and one valid obstacle; fixture edits excluded";
    }
    private PublicDig87Checks(){}
}
