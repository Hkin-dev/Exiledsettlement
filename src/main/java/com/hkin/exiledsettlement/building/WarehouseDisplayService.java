package com.hkin.exiledsettlement.building;
import com.hkin.exiledsettlement.settlement.SettlementSavedData;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

public final class WarehouseDisplayService {
    private WarehouseDisplayService() {}
    static net.minecraft.core.BlockPos representative(net.minecraft.server.level.ServerLevel level,net.minecraft.core.BlockPos p) {
        var state=level.getBlockState(p);
        if(state.getBlock() instanceof net.minecraft.world.level.block.ChestBlock
                && state.getValue(net.minecraft.world.level.block.ChestBlock.TYPE)!=net.minecraft.world.level.block.state.properties.ChestType.SINGLE) {
            var other=p.relative(net.minecraft.world.level.block.ChestBlock.getConnectedDirection(state));
            return p.asLong()<other.asLong()?p:other;
        }
        return p;
    }
    public static int displayRows(net.minecraft.server.level.ServerLevel level,net.minecraft.core.BlockPos pos) {
        var state=level.getBlockState(pos);
        if(state.getBlock() instanceof net.minecraft.world.level.block.ChestBlock chest) {
            var container=net.minecraft.world.level.block.ChestBlock.getContainer(chest,state,level,pos,true);
            if(container!=null && container.getContainerSize()==54)return 6;
        }
        return 3;
    }
    public static void onInteract(PlayerInteractEvent.RightClickBlock event) {
        if(!(event.getEntity() instanceof ServerPlayer player))return;
        var level=player.serverLevel();var pos=event.getPos();
        boolean box=level.getBlockEntity(pos) instanceof net.minecraft.world.Container;
        boolean bed=level.getBlockState(pos).getBlock() instanceof net.minecraft.world.level.block.BedBlock;
        if(!box && !(bed && player.isShiftKeyDown()))return;
        for(var s:SettlementSavedData.get(level).settlements())for(var b:s.buildings()) {
            boolean home=b.type().isHouse() && pos.getX()>=b.minCorner().getX() && pos.getX()<=b.maxCorner().getX()
                    && pos.getZ()>=b.minCorner().getZ() && pos.getZ()<=b.maxCorner().getZ()
                    && pos.getY()>=b.minCorner().getY() && pos.getY()<=b.maxCorner().getY();
            if(!b.active() || !WarehouseStock.applies(b) || !(home || b.servicePositions().contains(pos)))continue;
            if(home && WarehouseInventoryService.registeredPublicContainer(level,b,pos))continue;
            if(!home && player.isShiftKeyDown())return;
            event.setCanceled(true);event.setCancellationResult(InteractionResult.SUCCESS);
            if(!s.active() || !s.isMember(player.getUUID()) || !b.operational())return;
            player.openMenu(new SimpleMenuProvider((id,inv,p)->new WarehouseDisplayMenu(id,inv,player,s,b,pos,null),
                    Component.literal(home?"住宅储物":"仓库库存")),buf->buf.writeVarInt(displayRows(level,pos)));return;
        }
    }
}
