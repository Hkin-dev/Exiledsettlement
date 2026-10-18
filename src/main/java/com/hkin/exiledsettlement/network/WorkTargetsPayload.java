package com.hkin.exiledsettlement.network;

import com.hkin.exiledsettlement.ExiledSettlementMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.phys.Vec3;
import java.util.List;

public record WorkTargetsPayload(boolean enabled,List<Target> targets) implements CustomPacketPayload {
    public static final int MAX_TARGETS=512;
    public record Target(int entityId,Vec3 position,boolean blocked) {}
    public WorkTargetsPayload {targets=List.copyOf(targets);if(targets.size()>MAX_TARGETS)throw new IllegalArgumentException("Too many work targets");}
    public static final Type<WorkTargetsPayload> TYPE=new Type<>(ExiledSettlementMod.id("work_targets"));
    public static final StreamCodec<RegistryFriendlyByteBuf,WorkTargetsPayload> STREAM_CODEC=StreamCodec.of(
        (b,p)->{b.writeBoolean(p.enabled);b.writeVarInt(p.targets.size());for(var t:p.targets){b.writeVarInt(t.entityId);b.writeDouble(t.position.x);b.writeDouble(t.position.y);b.writeDouble(t.position.z);b.writeBoolean(t.blocked);}},
        b->{boolean enabled=b.readBoolean();int size=b.readVarInt();if(size<0 || size>MAX_TARGETS)throw new IllegalArgumentException("Invalid work target count");
            var targets=new java.util.ArrayList<Target>();for(int i=0;i<size;i++)targets.add(new Target(b.readVarInt(),new Vec3(b.readDouble(),b.readDouble(),b.readDouble()),b.readBoolean()));return new WorkTargetsPayload(enabled,targets);});
    public Type<? extends CustomPacketPayload> type(){return TYPE;}
}
