package com.hkin.exiledsettlement.region;

import com.hkin.exiledsettlement.blueprint.*;
import com.hkin.exiledsettlement.settlement.*;
import com.hkin.exiledsettlement.terrain.*;
import net.minecraft.core.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import java.util.*;

/** Paid local approach ramps keep a flat field accessible from its actual exterior ground. */
public final class FarmEntranceService {
    public static final String NAME="农田入口地坪衔接";
    private static final Map<ProductionRegion,String> STATUS=new WeakHashMap<>();
    private record RoadColumns(List<BlockPos> source,Set<Long> columns) {}
    private static final Map<com.hkin.exiledsettlement.road.RoadSpaceData,RoadColumns> ROADS=new WeakHashMap<>();
    private static boolean roadColumn(ServerLevel level,BlockPos p){
        var roads=com.hkin.exiledsettlement.road.RoadSpaceData.get(level);var source=roads.decorationPositions();var cached=ROADS.get(roads);
        if(cached==null || cached.source()!=source){var columns=new HashSet<Long>();for(var at:source)columns.add(ProductionRegion.key(at.getX(),at.getZ()));cached=new RoadColumns(source,columns);ROADS.put(roads,cached);}
        return cached.columns().contains(ProductionRegion.key(p.getX(),p.getZ()));
    }
    public static String status(ProductionRegion r){return STATUS.getOrDefault(r,"not reviewed");}
    private FarmEntranceService() { }
    public static void plan(ServerLevel level,SettlementRecord settlement,BlueprintRecord site) {
        var region=site.region();if(region==null || !region.levelled() || !region.surveyed)return;
        if(settlement.terrain().projects.values().stream().anyMatch(p->NAME.equals(p.name) && p.dependencies.contains(site.id()) && !p.cancelled && !p.completed))return;
        var notes=new ArrayList<String>();
        for(var cell:region.cells.values())if(cell.use()==ProductionRegion.Use.GATE) {
            var side=region.gateFacing(cell.ground());var outside=cell.ground().relative(side);
            if(!settlement.contains(outside) || !level.hasChunkAt(outside))continue;
            int actual=level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,outside.getX(),outside.getZ())-1;
            actual=FarmVegetation.ground(level,outside.atY(actual),region).getY();
            int difference=cell.ground().getY()-actual;notes.add(outside.toShortString()+" exterior="+actual+" delta="+difference);STATUS.put(region,String.join("; ",notes));if(Math.abs(difference)<=1)continue;
            var steps=new ArrayList<TerrainProject.Step>();var surfaces=new ArrayList<BlockPos>();boolean safe=true;
            for(int distance=1;distance<Math.abs(difference);distance++) {
                var p=cell.ground().relative(side,distance).atY(cell.ground().getY()+(difference>0?-distance:distance));
                if(!settlement.contains(p) || region.cell(p)!=null || settlement.community().danger(p) || BuildingWorkPolicy.automaticForbidden(settlement,site,p)) {safe=false;break;}
                if(region.plainSoil() && roadColumn(level,p)){
                    region.status="入口衔接保留已登记道路："+p.toShortString();safe=false;break;
                }
                int top=level.getHeight(Heightmap.Types.WORLD_SURFACE,p.getX(),p.getZ())-1;
                for(int y=top;y>p.getY();y--) {
                    var at=p.atY(y);var state=level.getBlockState(at);if(state.isAir())continue;
                    if(!TerrainService.natural(state) || !state.getFluidState().isEmpty()){safe=false;break;}
                    steps.add(new TerrainProject.Step(at,Blocks.AIR.defaultBlockState(),state));
                }
                if(!safe)break;
                int bottom=p.getY();while(bottom>level.getMinBuildHeight() && !FarmEarthworkPlan.support(level,p.atY(bottom)))bottom--;
                for(int y=bottom+1;y<=p.getY();y++) {
                    var at=p.atY(y);var state=level.getBlockState(at);
                    if(region.plainSoil() && !state.getFluidState().isEmpty()){region.status="入口水体需要独立桥梁，暂缓土方："+at.toShortString();safe=false;break;}
                    if(!state.isAir() && !TerrainService.soft(state) && !TerrainService.replaceableWater(state)){safe=false;break;}
                    steps.add(new TerrainProject.Step(at,Blocks.DIRT.defaultBlockState(),state));
                }
                if(!safe)break;surfaces.add(p);
            }
            notes.add("safe="+safe+" steps="+steps.size());STATUS.put(region,String.join("; ",notes));
            if(!safe || steps.isEmpty())continue;
            var project=ProductionRegionService.publish(level,settlement,site.id(),NAME,outside,steps);
            notes.add("publish="+(project==null?ProductionRegionService.conflict(settlement,steps):project.id));STATUS.put(region,String.join("; ",notes));
            if(project!=null){project.accessSurfaces.addAll(surfaces);SettlementSavedData.get(level).touch();return;}
        }
    }
}
