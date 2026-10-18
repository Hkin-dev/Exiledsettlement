package com.hkin.exiledsettlement.network;
import com.hkin.exiledsettlement.ExiledSettlementMod;
import com.hkin.exiledsettlement.citizen.*;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import java.util.UUID;

public record CitizenThermalPayload(UUID citizen,float body,float ambient,float wet,float change,int garment,int damage,int maxDamage,String reason) implements CustomPacketPayload {
    public static final Type<CitizenThermalPayload> TYPE=new Type<>(ExiledSettlementMod.id("citizen_thermal"));
    public static final StreamCodec<RegistryFriendlyByteBuf,CitizenThermalPayload> STREAM_CODEC=StreamCodec.of(
      (b,p)->{b.writeUUID(p.citizen);b.writeFloat(p.body);b.writeFloat(p.ambient);b.writeFloat(p.wet);b.writeFloat(p.change);
        b.writeVarInt(p.garment);b.writeVarInt(p.damage);b.writeVarInt(p.maxDamage);b.writeUtf(p.reason,128);},
      b->new CitizenThermalPayload(b.readUUID(),b.readFloat(),b.readFloat(),b.readFloat(),b.readFloat(),b.readVarInt(),b.readVarInt(),b.readVarInt(),b.readUtf(128)));
    public static CitizenThermalPayload of(CitizenRecord c) {
        var t=c.thermal();return new CitizenThermalPayload(c.id(),t.body,t.ambient,t.wet,t.change,CitizenClothingService.kind(t.garment),
                t.garment.getDamageValue(),t.garment.getMaxDamage(),t.reason);
    }
    @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
}
