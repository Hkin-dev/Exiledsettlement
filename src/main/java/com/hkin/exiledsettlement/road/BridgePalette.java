package com.hkin.exiledsettlement.road;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.*;
/** Server data-pack material palette. Already frozen bridges keep their exact saved block states. */
public final class BridgePalette {
    public static Block beam=Blocks.OAK_LOG,deck=Blocks.OAK_PLANKS,abutment=Blocks.COBBLESTONE;
    public static FenceBlock rail=(FenceBlock)Blocks.OAK_FENCE;
    public static StairBlock stair=(StairBlock)Blocks.OAK_STAIRS;
    private BridgePalette() { }
    private static Block block(JsonObject j,String key) {
        var id=ResourceLocation.parse(j.get(key).getAsString());
        if(!BuiltInRegistries.BLOCK.containsKey(id))throw new IllegalArgumentException("Unknown bridge block "+id);
        var b=BuiltInRegistries.BLOCK.get(id);if(b.asItem()==net.minecraft.world.item.Items.AIR || b.defaultBlockState().hasBlockEntity())throw new IllegalArgumentException("Bridge material must be a real item without a block entity");return b;
    }
    public static void reload(JsonObject j) {
        var b=block(j,"beam");var d=block(j,"deck");var a=block(j,"abutment");var r=block(j,"rail");var t=block(j,"stair");
        for(var solid:new Block[]{b,d,a})if(!Block.isShapeFullBlock(solid.defaultBlockState().getCollisionShape(net.minecraft.world.level.EmptyBlockGetter.INSTANCE,net.minecraft.core.BlockPos.ZERO)) || !solid.defaultBlockState().getFluidState().isEmpty())throw new IllegalArgumentException("Bridge beam/deck/abutment must be solid and dry");
        if(!(r instanceof FenceBlock) || !(t instanceof StairBlock))throw new IllegalArgumentException("Bridge rail/stair shape is invalid");
        beam=b;deck=d;abutment=a;rail=(FenceBlock)r;stair=(StairBlock)t;
    }
}
