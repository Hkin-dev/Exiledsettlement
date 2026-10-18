package com.hkin.exiledsettlement.terrain;

import com.hkin.exiledsettlement.blueprint.*;
import com.hkin.exiledsettlement.building.BuildingType;
import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import java.util.*;

/** A saved public works order. It never registers a house or creates inventory. */
public final class TerrainProject {
    public record Step(BlockPos position, BlockState target, BlockState original) {
        public boolean fill() { return !target.isAir(); }
        public BlueprintPlacement placement() { return new BlueprintPlacement(position,target,fill()?target.getBlock().asItem():null,
                fill()?BlueprintPlacementMode.STRUCTURE:BlueprintPlacementMode.CLEARANCE); }
    }
    public final UUID id;
    public final String name;
    public final BlockPos anchor;
    public List<Step> steps;
    public final Set<UUID> dependencies = new ProjectDependencies();
    public int cursor,workCursor,layoutVersion;
    private List<Step> orderedSource;private List<Integer> layerOrder;private int layerFrontier;
    public List<Integer> layerOrder(){if(orderedSource!=steps){var indices=new ArrayList<Integer>();for(int i=0;i<steps.size();i++)indices.add(i);indices.sort(Comparator.comparingInt((Integer i)->steps.get(i).fill()?1:0).thenComparingInt(i->steps.get(i).fill()?steps.get(i).position().getY():-steps.get(i).position().getY()).thenComparingInt(i->i));layerOrder=List.copyOf(indices);orderedSource=steps;layerFrontier=0;}return layerOrder;}
    public int layerFrontier(){var order=layerOrder();while(layerFrontier<order.size() && finished.get(order.get(layerFrontier)))layerFrontier++;return layerFrontier;}
    public void recordFill(int index,BlockState actual){if(steps.get(index).target().equals(actual))return;var copy=new ArrayList<>(steps);var old=copy.get(index);copy.set(index,new Step(old.position(),actual,old.original()));steps=List.copyOf(copy);template=null;geometryChanged.run();}

    public int stairLayoutVersion=1;
    public String connectionWarning="";
    public BlockPos escapeFor;
    public UUID escapingCitizen;
    public final BitSet finished=new BitSet();
    public final BitSet abandoned=new BitSet();
    public void abandon(int index){abandoned.set(index);mark(index);template=null;}
    public void advance(){while(cursor<steps.size() && finished.get(cursor))cursor++;}
    public void mark(int index){finished.set(index);advance();workChanged.run();}
    /** Real damage restores the order to the live publication index as well as clearing progress. */
    public void reopen(int index){finished.clear(index);cursor=Math.min(cursor,index);layerFrontier=0;completed=false;workChanged.run();}
    public int remaining(){return Math.max(0,steps.size()-cursor-finished.get(cursor,steps.size()).cardinality());}
    public boolean cancelled, completed, explicit, road;
    public UUID regionOwner;
    public boolean roadReclaim;
    public boolean publicGround;
    public boolean entranceAccess;
    public boolean bridge;
    public com.hkin.exiledsettlement.road.PlayerBridgePlan playerBridge;
    public boolean areaClearance;
    public int bridgeReviewCursor;
    public final Set<BlockPos> bridgeBeams=new LinkedHashSet<>(),bridgeAnchors=new LinkedHashSet<>(),bridgeDeck=new LinkedHashSet<>(),bridgeRails=new LinkedHashSet<>();
    public final Set<BlockPos> bridgeOpenFaces=new LinkedHashSet<>();
    Runnable geometryChanged=()->{};
    Runnable workChanged=()->{};
    /** Paid fills retired column by column; abandoning a target never creates payment evidence. */
    public final BitSet retiredPaid=new BitSet();
    public void retireColumn(BlockPos at){
        for(int i=0;i<steps.size();i++)if(steps.get(i).position().getX()==at.getX() && steps.get(i).position().getZ()==at.getZ()){
            if(steps.get(i).fill() && finished.get(i) && !abandoned.get(i))retiredPaid.set(i);
            abandon(i);
        }
        groundSurfaces.removeIf(p->p.getX()==at.getX() && p.getZ()==at.getZ());
        heightSuperseded=groundSurfaces.isEmpty();
    }
    public void appendBridgeRepair(List<Step> extra){if(!bridge)throw new IllegalStateException("Not a bridge");var copy=new ArrayList<>(steps);copy.addAll(extra);steps=List.copyOf(copy);template=null;positionIndices=null;completed=false;geometryChanged.run();}
    public void appendFoundationSupport(List<Step> extra){if(bridge || extra.isEmpty())return;var copy=new ArrayList<>(steps);copy.addAll(extra);steps=List.copyOf(copy);template=null;positionIndices=null;completed=false;geometryChanged.run();}
    public void restorePlanningProgress(int savedCursor,BitSet done,BitSet ignored){cursor=savedCursor;finished.clear();finished.or(done);abandoned.clear();abandoned.or(ignored);layerFrontier=0;template=null;workChanged.run();}
    public boolean hasUnfinishedGroundColumn(){
        var pendingColumns=new HashSet<Long>();
        for(int i=cursor;i<steps.size();i++)if(!finished.get(i) && !abandoned.get(i)){
            var at=steps.get(i).position();pendingColumns.add(BlockPos.asLong(at.getX(),0,at.getZ()));
        }
        return groundSurfaces.stream().anyMatch(at->pendingColumns.contains(BlockPos.asLong(at.getX(),0,at.getZ())));
    }
    public int publicGroundVersion=PublicGroundService.HEIGHT_VERSION;
    public boolean heightSuperseded;
    public final Set<BlockPos> groundSurfaces=new LinkedHashSet<>();
    public final Set<BlockPos> accessSurfaces=new LinkedHashSet<>();
    private BuildingBlueprintTemplate template;
    private Map<BlockPos,List<Integer>> positionIndices;
    public List<Integer> indices(BlockPos at){if(positionIndices==null){positionIndices=new HashMap<>();for(int i=0;i<steps.size();i++)positionIndices.computeIfAbsent(steps.get(i).position(),key->new ArrayList<>()).add(i);}return positionIndices.getOrDefault(at,List.of());}
    public TerrainProject(UUID id,String name,BlockPos anchor,List<Step> steps) {
        this.id=id;this.name=name;this.anchor=anchor.immutable();this.steps=List.copyOf(steps);
    }
    public boolean done() { return cursor>=steps.size(); }
    public Step next() { return done()?null:steps.get(cursor); }
    public BuildingBlueprintTemplate template() {
        if(template!=null)return template;
        int minX=anchor.getX(),maxX=minX,minY=anchor.getY(),maxY=minY,minZ=anchor.getZ(),maxZ=minZ;
        Map<BlockPos,BlueprintPlacement> targets=new LinkedHashMap<>();
        for(int index=0;index<steps.size();index++) {if(abandoned.get(index))continue;var step=steps.get(index);var p=step.position();minX=Math.min(minX,p.getX());maxX=Math.max(maxX,p.getX());
            minY=Math.min(minY,p.getY());maxY=Math.max(maxY,p.getY());minZ=Math.min(minZ,p.getZ());maxZ=Math.max(maxZ,p.getZ());
            targets.put(p,step.placement());}
        template=new BuildingBlueprintTemplate(BuildingType.HOUSE,anchor,Direction.NORTH,maxX-minX+1,maxZ-minZ+1,maxY-minY+1,
                new BlockPos(minX,minY,minZ),new BlockPos(maxX,maxY,maxZ),0,List.copyOf(targets.values()),List.of()).asTerrainWork();
        return template;
    }
    public CompoundTag save() {
        // Optional fields preserve the existing public-project save format.
        var t=new CompoundTag();t.putUUID("Id",id);t.putString("Name",name);t.putLong("Anchor",anchor.asLong());
        if(regionOwner!=null)t.putUUID("RegionOwner",regionOwner);
        if(playerBridge!=null)t.put("PlayerBridge",playerBridge.save());
        t.putBoolean("Bridge",bridge);t.putBoolean("AreaClearance",areaClearance);t.putBoolean("EntranceAccess",entranceAccess);t.putInt("BridgeReviewCursor",bridgeReviewCursor);
        t.putLongArray("BridgeBeams",bridgeBeams.stream().mapToLong(BlockPos::asLong).toArray());t.putLongArray("BridgeAnchors",bridgeAnchors.stream().mapToLong(BlockPos::asLong).toArray());
        t.putLongArray("BridgeDeck",bridgeDeck.stream().mapToLong(BlockPos::asLong).toArray());t.putLongArray("BridgeRails",bridgeRails.stream().mapToLong(BlockPos::asLong).toArray());
        t.putLongArray("BridgeOpenFaces",bridgeOpenFaces.stream().mapToLong(BlockPos::asLong).toArray());
        if(escapeFor!=null)t.putLong("EscapeFor",escapeFor.asLong());if(escapingCitizen!=null)t.putUUID("EscapingCitizen",escapingCitizen);
        t.putInt("PublicGroundVersion",publicGroundVersion);t.putBoolean("HeightSuperseded",heightSuperseded);
        t.putLongArray("GroundSurfaces",groundSurfaces.stream().mapToLong(BlockPos::asLong).toArray());t.putBoolean("PublicGround",publicGround);t.putBoolean("RoadReclaim",roadReclaim);t.putLongArray("AccessSurfaces",accessSurfaces.stream().mapToLong(BlockPos::asLong).toArray());
        t.putInt("Version",4);t.putInt("LayoutVersion",layoutVersion);t.putInt("StairLayoutVersion",stairLayoutVersion);t.putString("ConnectionWarning",connectionWarning);t.putLongArray("Finished",finished.toLongArray());t.putInt("WorkCursor",workCursor);t.putInt("Cursor",cursor);t.putBoolean("Cancelled",cancelled);t.putBoolean("Completed",completed);t.putBoolean("Explicit",explicit);t.putBoolean("Road",road);
        // Preserve every ordered step and actual paid material, but store each
        // repeated block state once. Vanilla save snapshots deep-copy NBT; two
        // nested block-state compounds per step made long histories exhaust
        // the heap even though only a small frontier was still being worked.
        t.putLongArray("Abandoned",abandoned.toLongArray());t.putLongArray("RetiredPaid",retiredPaid.toLongArray());
        var palette=new LinkedHashMap<BlockState,Integer>();var states=new ListTag();
        long[] positions=new long[steps.size()];int[] targets=new int[steps.size()],originals=new int[steps.size()];
        for(int i=0;i<steps.size();i++){
            var step=steps.get(i);positions[i]=step.position().asLong();
            targets[i]=palette.computeIfAbsent(step.target(),state->{states.add(NbtUtils.writeBlockState(state));return palette.size();});
            originals[i]=palette.computeIfAbsent(step.original(),state->{states.add(NbtUtils.writeBlockState(state));return palette.size();});
        }
        t.put("StepPalette",states);t.putLongArray("StepPositions",positions);t.putIntArray("StepTargets",targets);t.putIntArray("StepOriginals",originals);
        var deps=new ListTag();for(var d:dependencies){var a=new CompoundTag();a.putUUID("Id",d);deps.add(a);}t.put("Dependencies",deps);return t;
    }
    public static TerrainProject load(CompoundTag t) {
        if(t.getInt("Version")>4)throw new IllegalArgumentException("Unsupported terrain work version");
        var steps=new ArrayList<Step>();
        if(t.getInt("Version")==4){
            var encoded=t.getList("StepPalette",Tag.TAG_COMPOUND);var palette=new ArrayList<BlockState>();
            for(int i=0;i<encoded.size();i++)palette.add(NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(),encoded.getCompound(i)));
            var positions=t.getLongArray("StepPositions");var targets=t.getIntArray("StepTargets");var originals=t.getIntArray("StepOriginals");
            if(targets.length!=positions.length || originals.length!=positions.length)throw new IllegalArgumentException("Terrain step array length mismatch");
            for(int i=0;i<positions.length;i++){
                if(targets[i]<0 || targets[i]>=palette.size() || originals[i]<0 || originals[i]>=palette.size())throw new IllegalArgumentException("Terrain step palette index invalid");
                steps.add(new Step(BlockPos.of(positions[i]),palette.get(targets[i]),palette.get(originals[i])));
            }
        }else{
        var list=t.getList("Steps",Tag.TAG_COMPOUND);
        if(t.contains("Pages")){list=new ListTag();var pages=t.getList("Pages",Tag.TAG_COMPOUND);for(int i=0;i<pages.size();i++)list.addAll(pages.getCompound(i).getList("Steps",Tag.TAG_COMPOUND));}
        for(int i=0;i<list.size();i++){var a=list.getCompound(i);steps.add(new Step(BlockPos.of(a.getLong("At")),
                NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(),a.getCompound("Target")),
                NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(),a.getCompound("Original"))));}
        }
        var p=new TerrainProject(t.getUUID("Id"),t.getString("Name"),BlockPos.of(t.getLong("Anchor")),steps);
        if(t.hasUUID("RegionOwner"))p.regionOwner=t.getUUID("RegionOwner");
        p.bridge=t.getBoolean("Bridge");p.areaClearance=t.getBoolean("AreaClearance");p.bridgeReviewCursor=Math.max(0,t.getInt("BridgeReviewCursor"));
        for(long at:t.getLongArray("BridgeBeams"))p.bridgeBeams.add(BlockPos.of(at));for(long at:t.getLongArray("BridgeAnchors"))p.bridgeAnchors.add(BlockPos.of(at));
        for(long at:t.getLongArray("BridgeDeck"))p.bridgeDeck.add(BlockPos.of(at));for(long at:t.getLongArray("BridgeRails"))p.bridgeRails.add(BlockPos.of(at));
        for(long at:t.getLongArray("BridgeOpenFaces"))p.bridgeOpenFaces.add(BlockPos.of(at));
        p.stairLayoutVersion=t.getInt("StairLayoutVersion");
        if(t.contains("EscapeFor"))p.escapeFor=BlockPos.of(t.getLong("EscapeFor"));if(t.hasUUID("EscapingCitizen"))p.escapingCitizen=t.getUUID("EscapingCitizen");
        p.publicGround=t.getBoolean("PublicGround");p.entranceAccess=t.getBoolean("EntranceAccess");p.roadReclaim=t.getBoolean("RoadReclaim");for(long at:t.getLongArray("AccessSurfaces"))p.accessSurfaces.add(BlockPos.of(at));
        p.publicGroundVersion=t.contains("PublicGroundVersion")?t.getInt("PublicGroundVersion"):1;p.heightSuperseded=t.getBoolean("HeightSuperseded");
        for(long at:t.getLongArray("GroundSurfaces"))p.groundSurfaces.add(BlockPos.of(at));
        p.abandoned.or(BitSet.valueOf(t.getLongArray("Abandoned")));p.layoutVersion=t.getInt("LayoutVersion");p.connectionWarning=t.getString("ConnectionWarning");p.cursor=Math.clamp(t.getInt("Cursor"),0,steps.size());p.finished.or(BitSet.valueOf(t.getLongArray("Finished")));p.finished.set(0,p.cursor);p.workCursor=t.getInt("WorkCursor");p.advance();p.cancelled=t.getBoolean("Cancelled");p.completed=t.getBoolean("Completed");p.explicit=t.getBoolean("Explicit");p.road=t.getBoolean("Road");
        if(t.contains("PlayerBridge"))p.playerBridge=com.hkin.exiledsettlement.road.PlayerBridgePlan.load(t.getCompound("PlayerBridge"),p);
        p.retiredPaid.or(BitSet.valueOf(t.getLongArray("RetiredPaid")));
        var deps=t.getList("Dependencies",Tag.TAG_COMPOUND);for(int i=0;i<deps.size();i++)p.dependencies.add(deps.getCompound(i).getUUID("Id"));return p;
    }
}
