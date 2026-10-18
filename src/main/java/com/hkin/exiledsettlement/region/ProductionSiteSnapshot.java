package com.hkin.exiledsettlement.region;

import com.hkin.exiledsettlement.blueprint.BuildingBlueprintTemplate;
import com.hkin.exiledsettlement.building.BuildingProductionService;
import com.hkin.exiledsettlement.building.BuildingType;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import java.util.*;

/** Immutable legacy workplace geometry. It carries no survey, readiness or maintenance lifecycle. */
public final class ProductionSiteSnapshot {
    public record Cell(BlockPos ground, ProductionRegion.Use use, ResourceLocation production, BuildingType type) {}
    private static final Set<String> GEOMETRY_KEYS=Set.of("Version","Type","Facing","First","Second",
            "FlatY","GridX","GridZ","Pages","GateFacing","GateFacings","Stairs","GradeOrigins",
            "LegacyWater","WaterBarriers","AnimalFloors");
    private final CompoundTag geometry;
    private final BuildingBlueprintTemplate template;
    private final List<Cell> cells,workCells;
    private final Map<Long,Cell> columns;
    private final Map<Long,Integer> gradeOrigins;
    private final Map<ResourceLocation,Integer> animalFloors;
    private final Set<ResourceLocation> productions;
    private final int planeY,gridX,gridZ;

    private ProductionSiteSnapshot(CompoundTag input) {
        geometry=new CompoundTag();
        for(var key:GEOMETRY_KEYS)if(input.contains(key))geometry.put(key,input.get(key).copy());
        var original=ProductionRegion.load(geometry.copy());
        template=original.template();planeY=original.planeY();gridX=original.gridX;gridZ=original.gridZ;
        cells=original.ordered().stream().map(c->new Cell(c.ground().immutable(),c.use(),c.production(),c.type())).toList();
        workCells=cells.stream().filter(c->switch(c.use()){case CROP,FRUIT,FOREST,PASTURE->true;default->false;}).toList();
        var lookup=new LinkedHashMap<Long,Cell>();cells.forEach(c->lookup.put(key(c.ground()),c));columns=Map.copyOf(lookup);
        gradeOrigins=Map.copyOf(original.gradeOrigins);animalFloors=Map.copyOf(original.animalFloors);
        var products=new LinkedHashSet<ResourceLocation>();workCells.forEach(c->products.add(c.production()));productions=Collections.unmodifiableSet(products);
    }
    public static ProductionSiteSnapshot fromRegion(ProductionRegion region){return new ProductionSiteSnapshot(region.save());}
    public static ProductionSiteSnapshot load(CompoundTag tag){
        if(tag.getInt("Version")!=1)throw new IllegalArgumentException("Unknown production site version");
        return new ProductionSiteSnapshot(tag.getCompound("Geometry"));
    }
    public CompoundTag save(){var tag=new CompoundTag();tag.putInt("Version",1);tag.put("Geometry",geometry.copy());return tag;}
    public BuildingType type(){return template.type();}
    public BuildingBlueprintTemplate template(){return template;}
    public List<Cell> cells(){return cells;}
    public List<Cell> workCells(){return workCells;}
    public Cell cell(BlockPos at){return columns.get(key(at));}
    public boolean containsColumn(BlockPos at){return cell(at)!=null;}
    public boolean contains(BlockPos at){var c=cell(at);return c!=null && at.getY()>=c.ground().getY()-1 && at.getY()<=c.ground().getY()+4;}
    public ResourceLocation productionAt(BlockPos at,ResourceLocation fallback){var c=cell(at);return c==null?fallback:c.production();}
    public Set<ResourceLocation> productions(){return productions;}
    public int planeY(){return planeY;}
    public int gridX(){return gridX;}
    public int gridZ(){return gridZ;}
    public boolean plantingGrid(BlockPos at){return Math.floorMod(at.getX()-gridX,5)==0 && Math.floorMod(at.getZ()-gridZ,5)==0;}
    public int minimumRootY(BlockPos at){var c=cell(at);return c==null?at.getY():Math.min(c.ground().getY(),gradeOrigins.getOrDefault(key(at),c.ground().getY()))+1;}
    public int usableArea(){return workCells.size();}
    public int animalFloor(ResourceLocation type){return animalFloors.getOrDefault(type,0);}
    public int animalFloorTotal(){return animalFloors.values().stream().mapToInt(Integer::intValue).sum();}
    /** Ground, crops and naturally generated trees are production state, not structural repair targets. */
    public boolean structural(BlockPos at){var p=template.placementAt(at);var c=cell(at);return p!=null && !p.state().isAir()
            && c!=null && switch(c.use()){case BOUNDARY,GATE->at.getY()>c.ground().getY();case WATER,PATH->true;default->false;};}
    public ProductionSiteSnapshot withProduction(ResourceLocation selected){
        var copy=ProductionRegion.load(geometry.copy());
        copy.cells.replaceAll((key,c)->new ProductionRegion.Cell(c.ground(),c.use(),
                BuildingProductionService.supported(c.type(),selected)?selected:c.production(),c.type()));
        return fromRegion(copy);
    }
    private static long key(BlockPos at){return BlockPos.asLong(at.getX(),0,at.getZ());}
}
