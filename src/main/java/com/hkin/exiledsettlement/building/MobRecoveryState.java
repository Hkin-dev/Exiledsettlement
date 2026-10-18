package com.hkin.exiledsettlement.building;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.world.level.block.state.BlockState;
import java.util.*;

/** Evidence and physical goals are saved together; an empty old save has no inferred damage. */
public final class MobRecoveryState {
    public enum Status { OBSERVING, PENDING, DONE, UNCHANGED, CONFLICT, UNSUPPORTED }
    public static final class Cell {
        public final BlockPos pos;
        public final BlockState original;
        public BlockState damaged;
        public Status status=Status.OBSERVING;
        public String reason="";
        public boolean blockEntity;
        public long retryAt;
        public Cell(BlockPos pos,BlockState original){this.pos=pos.immutable();this.original=original;this.damaged=original;}
    }
    public static final class Incident {
        public final UUID id,source;
        public final String cause;
        public final long time;
        public final Map<BlockPos,Cell> cells=new LinkedHashMap<>();
        public Incident(UUID id,UUID source,String cause,long time){this.id=id;this.source=source;this.cause=cause;this.time=time;}
        public boolean unresolved(){return cells.values().stream().anyMatch(c->c.status!=Status.DONE && c.status!=Status.UNCHANGED);}
    }
    public final Map<UUID,Incident> incidents=new LinkedHashMap<>();
    private final Map<BlockPos,Incident> owners=new HashMap<>();
    public final Deque<Map.Entry<Incident,Cell>> observations=new ArrayDeque<>();
    public Incident owner(BlockPos p){return owners.get(p);}
    public void index(Incident event,Cell c){
        if(c.status==Status.DONE || c.status==Status.UNCHANGED)owners.remove(c.pos,event);
        else owners.put(c.pos,event);
    }
    public void add(Incident event,Cell c){event.cells.put(c.pos,c);index(event,c);observations.add(Map.entry(event,c));}
    public CompoundTag save(){
        var root=new CompoundTag();root.putInt("Version",1);var list=new ListTag();
        for(var e:incidents.values()){
            var tag=new CompoundTag();tag.putUUID("Id",e.id);tag.putUUID("Source",e.source);tag.putString("Cause",e.cause);tag.putLong("Time",e.time);
            var cells=new ListTag();for(var c:e.cells.values()){
                var t=new CompoundTag();t.putLong("Pos",c.pos.asLong());t.put("Original",NbtUtils.writeBlockState(c.original));
                t.put("Damaged",NbtUtils.writeBlockState(c.damaged));t.putString("Status",c.status.name());t.putString("Reason",c.reason);
                t.putBoolean("BlockEntity",c.blockEntity);t.putLong("RetryAt",c.retryAt);cells.add(t);
            }tag.put("Cells",cells);list.add(tag);
        }root.put("Incidents",list);return root;
    }
    public void load(CompoundTag root){
        incidents.clear();owners.clear();observations.clear();
        var list=root.getList("Incidents",Tag.TAG_COMPOUND);
        for(int i=0;i<list.size();i++){
            var t=list.getCompound(i);var e=new Incident(t.getUUID("Id"),t.getUUID("Source"),t.getString("Cause"),t.getLong("Time"));
            incidents.put(e.id,e);var cells=t.getList("Cells",Tag.TAG_COMPOUND);
            for(int j=0;j<cells.size();j++){
                var ctag=cells.getCompound(j);var c=new Cell(BlockPos.of(ctag.getLong("Pos")),NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(),ctag.getCompound("Original")));
                c.damaged=NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(),ctag.getCompound("Damaged"));
                try{c.status=Status.valueOf(ctag.getString("Status"));}catch(IllegalArgumentException ex){c.status=Status.UNSUPPORTED;c.reason="RECOVERY_RECORD_UNKNOWN";}
                if(c.reason.isEmpty())c.reason=ctag.getString("Reason");c.blockEntity=ctag.getBoolean("BlockEntity");c.retryAt=ctag.getLong("RetryAt");
                e.cells.put(c.pos,c);index(e,c);if(c.status==Status.OBSERVING)observations.add(Map.entry(e,c));
            }
        }
    }
}
