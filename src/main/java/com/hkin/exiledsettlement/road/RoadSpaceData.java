package com.hkin.exiledsettlement.road;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/** Persistent invisible road coordinates. The save key intentionally reads legacy roadboost worlds. */
public final class RoadSpaceData extends SavedData {
    private static final String DATA_NAME = "roadboost_road_space";
    private static final String ROADS_TAG = "Roads";
    private static final String ROAD_LEVELS_TAG = "RoadLevels";
    private static final Factory<RoadSpaceData> FACTORY = new Factory<>(RoadSpaceData::new, RoadSpaceData::load);

    private java.util.List<BlockPos> decorationPositions;
    public java.util.List<BlockPos> decorationPositions() {
        if (decorationPositions == null) decorationPositions = roads.keySet().stream().sorted().map(BlockPos::of).toList();
        return decorationPositions;
    }

    private final Map<Long, Byte> roads = new HashMap<>();
    private final java.util.Set<Long> manual=new java.util.HashSet<>();
    private final Map<Long,BlockPos> playerSurfaces=new HashMap<>();
    private static long column(BlockPos p){return BlockPos.asLong(p.getX(),0,p.getZ());}
    public BlockPos playerSurface(BlockPos p){return playerSurfaces.get(column(p));}
    public boolean playerSurfaceAt(BlockPos p){return p.equals(playerSurface(p));}
    public void rememberPlayerSurface(BlockPos p){playerSurfaces.put(column(p),p.immutable());setDirty();}
    public void forgetPlayerSurface(BlockPos p){if(playerSurfaces.remove(column(p))!=null)setDirty();}
    /** Move only the invisible registration; this never builds or removes a block. */
    public void adoptPlayerSurface(BlockPos old,BlockPos actual){
        var tier=tierAt(old);boolean wasManual=manual(old);remove(old);addAll(java.util.List.of(actual),tier);
        if(wasManual)pinManual(java.util.List.of(actual));rememberPlayerSurface(actual);
    }
    private Map<Long,Integer> manualColumns;
    private Map<Long,Integer> roadColumns;
    public Integer columnHeight(BlockPos p){
        if(roadColumns==null){roadColumns=new HashMap<>();for(long at:roads.keySet()){var q=BlockPos.of(at);roadColumns.merge(new BlockPos(q.getX(),0,q.getZ()).asLong(),q.getY(),Math::max);}}
        return roadColumns.get(new BlockPos(p.getX(),0,p.getZ()).asLong());
    }
    public boolean manual(BlockPos at){return manual.contains(at.asLong());}
    public void pinManual(Iterable<BlockPos> positions){for(var p:positions)manual.add(p.asLong());manualColumns=null;setDirty();}
    public Integer manualHeight(BlockPos p){
        if(manualColumns==null){manualColumns=new HashMap<>();for(long at:manual){var q=BlockPos.of(at);manualColumns.merge(new BlockPos(q.getX(),0,q.getZ()).asLong(),q.getY(),Math::max);}}
        return manualColumns.get(new BlockPos(p.getX(),0,p.getZ()).asLong());
    }

    public static RoadSpaceData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    public static RoadSpaceData load(CompoundTag tag, HolderLookup.Provider registries) {
        RoadSpaceData data = new RoadSpaceData();
        for(long at:tag.getLongArray("PlayerSurfaces")){var p=BlockPos.of(at);data.playerSurfaces.put(column(p),p);}
        for(long at:tag.getLongArray("Manual"))data.manual.add(at);
        Arrays.stream(tag.getLongArray(ROADS_TAG)).forEach(value -> data.roads.put(value, (byte) 1));
        CompoundTag levels = tag.getCompound(ROAD_LEVELS_TAG);
        for (RoadTier tier : RoadTier.values()) {
            Arrays.stream(levels.getLongArray("Level" + tier.level()))
                    .forEach(value -> data.roads.put(value, (byte) tier.level()));
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putLongArray("Manual",manual.stream().mapToLong(Long::longValue).toArray());
        tag.putLongArray("PlayerSurfaces",playerSurfaces.values().stream().mapToLong(BlockPos::asLong).toArray());
        tag.putLongArray(ROADS_TAG, roads.keySet().stream().mapToLong(Long::longValue).toArray());
        CompoundTag levels = new CompoundTag();
        for (RoadTier tier : RoadTier.values()) {
            levels.putLongArray("Level" + tier.level(), roads.entrySet().stream()
                    .filter(entry -> entry.getValue() == tier.level())
                    .mapToLong(Map.Entry::getKey).toArray());
        }
        tag.put(ROAD_LEVELS_TAG, levels);
        return tag;
    }

    public boolean contains(BlockPos position) {
        return roads.containsKey(position.asLong());
    }

    public RoadTier tierAt(BlockPos position) {
        return RoadTier.fromLevel(roads.getOrDefault(position.asLong(), (byte) 1));
    }

    public int levelAt(BlockPos position) {
        return roads.getOrDefault(position.asLong(), (byte) 0);
    }

    public int addAll(Iterable<BlockPos> positions) {
        return addAll(positions, RoadTier.LEVEL_1);
    }

    public int addAll(Iterable<BlockPos> positions, RoadTier tier) {
        int added = 0;
        for (BlockPos position : positions) {
            long key = position.asLong();
            int current = roads.getOrDefault(key, (byte) 0);
            if (tier.level() > current) {
                roads.put(key, (byte) tier.level());
                added++;
            }
        }
        if (added > 0) {
            roadColumns=null;
            decorationPositions = null;
            setDirty();
        }
        return added;
    }

    public void remove(BlockPos position) {
        roadColumns=null;
        manual.remove(position.asLong());
        manualColumns=null;
        if (roads.remove(position.asLong()) != null) {
            decorationPositions = null;
            setDirty();
        }
    }

    public int size() {
        return roads.size();
    }
}
