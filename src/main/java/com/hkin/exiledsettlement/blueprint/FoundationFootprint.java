package com.hkin.exiledsettlement.blueprint;

import com.hkin.exiledsettlement.building.BuildingType;
import com.hkin.exiledsettlement.region.ProductionRegion;
import com.hkin.exiledsettlement.region.ProductionSiteSnapshot;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import java.util.*;

/** Planned bearing columns, shared by survey and P0 scheduling. No world edits or completed-building scans. */
public final class FoundationFootprint {
    private FoundationFootprint() {}
    public record GroundColumn(BlockPos top, boolean surface) {}
    private record Entry(BuildingBlueprintTemplate template,Map<Long,GroundColumn> columns) {}
    private static final Map<BlueprintRecord,Entry> CACHE=new WeakHashMap<>();
    private static final Set<BlueprintRecord> LEGACY_SURVEYS=Collections.newSetFromMap(new WeakHashMap<>());
    public static boolean legacySurveyInitialized(BlueprintRecord site) {
        synchronized(LEGACY_SURVEYS){return site==null || LEGACY_SURVEYS.contains(site);}
    }
    /** Pending sites refresh narrower pre-rectangle proofs once after load. Never called for buildings. */
    public static boolean startLegacySurvey(BlueprintRecord site) {
        synchronized(LEGACY_SURVEYS){return site!=null && LEGACY_SURVEYS.add(site);}
    }
    public static int floor(BuildingBlueprintTemplate t) {
        return t.entrances().stream().mapToInt(e->e.position().getY()-1).min().orElse(t.anchor().getY()-1);
    }
    /** Only the standard mine's six descending stair headroom cells cross its reference plane. */
    public static boolean functionalOpening(BuildingBlueprintTemplate t,BlockPos at) {
        if(t.type()!=BuildingType.MINE || t.width()!=9 || t.depth()!=9)return false;
        for(int x=-3;x<=-2;x++)for(int z=-1;z<=1;z++) {
            var opening=MineBlueprint.position(t.anchor(),t.facing(),x,-1,z);
            if(at.getX()==opening.getX() && at.getZ()==opening.getZ())return true;
        }
        return false;
    }
    /** Reuse existing dry walking surfaces, including a path/farmland's slightly lowered top. */
    public static boolean usableSurface(net.minecraft.world.level.LevelReader level,BlockPos at,
            net.minecraft.world.level.block.state.BlockState state) {
        return !state.hasBlockEntity() && state.getFluidState().isEmpty()
                && !(state.getBlock() instanceof net.minecraft.world.level.block.FallingBlock)
                && com.hkin.exiledsettlement.terrain.TerrainSurface.ground(level,at,state);
    }
    /** Air entries below a new ground cap are temporary work bounds, not permanent holes. */
    public static boolean groundClearance(BuildingBlueprintTemplate t,BlockPos at) {
        if(t.terrainWork() || at.getY()>floor(t) || functionalOpening(t,at))return false;
        if(at.getX()<t.minCorner().getX() || at.getX()>t.maxCorner().getX()
                || at.getZ()<t.minCorner().getZ() || at.getZ()>t.maxCorner().getZ())return false;
        // An actual lower structure defines a basement/shaft rather than a missing surface.
        for(var p:t.placements())if(p.position().getX()==at.getX() && p.position().getZ()==at.getZ()
                && !p.state().isAir() && p.mode().isFinalStructure() && p.position().getY()<floor(t))return false;
        var atFloor=t.placementAt(at.atY(floor(t)));
        return atFloor==null || atFloor.state().isAir();
    }
    public static boolean isBase(BuildingBlueprintTemplate t,ProductionSiteSnapshot snapshot,int floor,BlueprintPlacement placement) {
        if(placement.state().isAir() || !placement.mode().isFinalStructure())return false;
        boolean plain=t.type()==BuildingType.TREE_FARM || t.type()==BuildingType.PASTURE_AREA;
        if(snapshot!=null) {
            var cell=snapshot.cell(placement.position());
            if(plain && cell!=null && (cell.use()==ProductionRegion.Use.FOREST || cell.use()==ProductionRegion.Use.PASTURE))return false;
            if(cell!=null && placement.position().getY()<=cell.ground().getY())return true;
        }
        // Elevated window sills and attached wall trim are not extra ground columns.
        return placement.position().getY()<=floor || t.type().isDynamicArea()
                && (placement.state().is(BlockTags.FENCES) || placement.state().is(BlockTags.FENCE_GATES))
                && (snapshot!=null || placement.position().getY()==floor+1);
    }
    /** The retired v3 warehouse had true standing perimeter posts one block above its floor. */
    public static boolean isBase(BlueprintRecord site,BuildingBlueprintTemplate t,int floor,BlueprintPlacement placement) {
        if(isBase(t,site.productionSite(),floor,placement))return true;
        if(site.type()!=BuildingType.WAREHOUSE || site.templateVersion()!=3 || site.customTemplate()!=null
                || placement.mode()!=BlueprintPlacementMode.STRUCTURE || placement.position().getY()!=floor+1)return false;
        var state=placement.state();
        boolean upright=state.is(net.minecraft.world.level.block.Blocks.OAK_LOG)
                && state.getValue(net.minecraft.world.level.block.RotatedPillarBlock.AXIS)==net.minecraft.core.Direction.Axis.Y;
        if(!upright && !state.is(net.minecraft.world.level.block.Blocks.STONE_BRICKS))return false;
        for(int up=1;up<=2;up++) {
            var next=t.placementAt(placement.position().above(up));
            if(next==null || next.mode()!=BlueprintPlacementMode.STRUCTURE || !next.state().equals(state))return false;
        }
        return true;
    }
    public static Map<Long,GroundColumn> columns(BlueprintRecord site,BuildingBlueprintTemplate t) {
        synchronized(CACHE) {
            var entry=CACHE.get(site);
            if(entry==null || entry.template()!=t) {
                var bases=new HashMap<Long,Integer>();
                int floor=floor(t);
                for(var p:t.placements())if(isBase(site,t,floor,p))
                    bases.merge(BlockPos.asLong(p.position().getX(),0,p.position().getZ()),p.position().getY(),Math::min);
                var result=new LinkedHashMap<Long,GroundColumn>();
                for(var b:bases.entrySet()) {
                    var at=BlockPos.of(b.getKey()).atY(b.getValue()-1);
                    result.put(b.getKey(),new GroundColumn(at,at.getY()==floor));
                }
                if(site.productionSite()!=null) {
                    // Legacy sloped/irregular sites retain exact membership and per-cell elevation.
                    for(var cell:site.productionSite().cells()) {
                        var ground=cell.ground();long key=BlockPos.asLong(ground.getX(),0,ground.getZ());
                        var goal=t.placementAt(ground);
                        boolean defined=goal!=null && !goal.state().isAir() && goal.mode().isFinalStructure();
                        result.put(key,new GroundColumn(defined?ground.below():ground,!defined));
                    }
                } else for(int x=t.minCorner().getX();x<=t.maxCorner().getX();x++)
                    for(int z=t.minCorner().getZ();z<=t.maxCorner().getZ();z++) {
                        var at=new BlockPos(x,floor,z);
                        if(!functionalOpening(t,at))result.putIfAbsent(BlockPos.asLong(x,0,z),new GroundColumn(at,true));
                    }
                entry=new Entry(t,Collections.unmodifiableMap(result));CACHE.put(site,entry);
            }
            return entry.columns();
        }
    }
    public static boolean belowBase(BlueprintRecord site,BlockPos at) {
        if(site==null || at==null)return false;
        var column=columns(site,BlueprintTemplates.create(site)).get(BlockPos.asLong(at.getX(),0,at.getZ()));
        return column!=null && at.getY()<=column.top().getY();
    }
}
