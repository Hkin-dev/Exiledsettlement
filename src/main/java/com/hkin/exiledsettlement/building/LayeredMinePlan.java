package com.hkin.exiledsettlement.building;

import com.google.gson.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Frozen approved cells and small repeatable excavation modules. No world mutation. */
public final class LayeredMinePlan {
    public record Cell(BlockPos position, BlockState state) {}
    private static JsonElement read(String name) {
        try(var stream=LayeredMinePlan.class.getResourceAsStream("/data/exiledsettlement/mine/"+name+".json")) {
            if(stream==null)throw new IllegalStateException("Missing mine snapshot: "+name);
            return JsonParser.parseReader(new InputStreamReader(stream,StandardCharsets.UTF_8));
        } catch(IOException e){throw new IllegalStateException(e);}
    }
    private static BlockPos pos(JsonObject row,String key) {
        var a=row.getAsJsonArray(key);return new BlockPos(a.get(0).getAsInt(),a.get(1).getAsInt(),a.get(2).getAsInt());
    }
    private static <T extends Comparable<T>> BlockState set(BlockState state,Property<T> property,String value) {
        return state.setValue(property,property.getValue(value).orElseThrow());
    }
    private static BlockState state(String text) {
        var parts=text.split("\\[",2);
        var id=ResourceLocation.parse(parts[0]);
        if(!BuiltInRegistries.BLOCK.containsKey(id))throw new IllegalStateException("Unknown mine block "+id);
        var block=BuiltInRegistries.BLOCK.get(id);var result=block.defaultBlockState();
        if(parts.length==2)for(var entry:parts[1].replace("]","").split(",")) {
            var pair=entry.split("=",2);var property=block.getStateDefinition().getProperty(pair[0]);
            if(property==null)throw new IllegalStateException("Unknown property "+entry);
            result=set(result,property,pair[1]);
        }
        return result;
    }
    private static final List<Cell> INITIAL=loadInitial();
    private static List<Cell> loadInitial() {
        var out=new ArrayList<Cell>();
        for(var row:read("initial").getAsJsonArray()){var obj=row.getAsJsonObject();
            out.add(new Cell(pos(obj,"position"),state(obj.get("state").getAsString())));}
        return List.copyOf(out);
    }
    public static List<Cell> initial(){return INITIAL;}
    private static final JsonObject MODULES=read("expansion").getAsJsonObject();
    public static int firstSize(){return MODULES.getAsJsonArray("first").size();}
    public static int repeatSize(){return MODULES.getAsJsonArray("repeat").size();}
    public static int completedLayers(int cursor){return cursor<firstSize()?0:1+(cursor-firstSize())/repeatSize();}
    public static List<MineExpansionService.Step> steps() {
        var out=new ArrayList<MineExpansionService.Step>();
        // 64 descents cover vanilla's height range. Build-height/bedrock guards
        // stop the current mine without generating unbounded world coordinates.
        for(int layer=1;layer<=64;layer++) {
            int dy=layer==1?0:-6*(layer-2);
            for(var value:MODULES.getAsJsonArray(layer==1?"first":"repeat")) {
                var row=value.getAsJsonObject();
                out.add(new MineExpansionService.Step(pos(row,"position").offset(0,dy,0),
                        state(row.get("state").getAsString()),pos(row,"station").offset(0,dy,0),
                        pos(row,"second").offset(0,dy,0),pos(row,"floor").offset(0,dy,0)));
            }
        }
        return List.copyOf(out);
    }
}
