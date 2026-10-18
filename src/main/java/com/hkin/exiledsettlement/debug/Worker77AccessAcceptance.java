package com.hkin.exiledsettlement.debug;
import com.hkin.exiledsettlement.citizen.*;
import com.hkin.exiledsettlement.registry.ModBlocks;
import com.hkin.exiledsettlement.settlement.*;
import net.minecraft.core.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.*;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.Blocks;
import java.util.*;

/** Native pickup route through a sealed natural-stone wall. Finite setup is reported separately. */
public final class Worker77AccessAcceptance {
    public static String start(ServerLevel level)throws Exception{
        if(!SaveAcceptanceService.enabled())throw new IllegalStateException("TEST_ONLY");
        var core=new BlockPos(32000,250,32000);var data=SettlementSavedData.get(level);
        if(data.findByCore(core).isPresent())throw new IllegalStateException("ALREADY_EXISTS");
        for(int x=-2;x<=2;x++)for(int z=-2;z<=2;z++)level.setChunkForced((core.getX()>>4)+x,(core.getZ()>>4)+z,true);
        for(var p:BlockPos.betweenClosed(core.offset(-5,-1,-6),core.offset(18,4,6)))if(!level.getBlockState(p).isAir())throw new IllegalStateException("Unused air required");
        for(int x=-5;x<=18;x++)for(int z=-6;z<=6;z++)level.setBlock(core.offset(x,-1,z),Blocks.STONE.defaultBlockState(),2);
        level.setBlock(core,ModBlocks.SETTLEMENT_CORE.get().defaultBlockState(),2);
        var result=data.createOrRecover(core,UUID.randomUUID(),"Worker77 native passage");if(!result.success())throw new IllegalStateException(result.reason());var s=result.value();s.terrain().enabled=false;s.autoRoads().enabled=false;
        var actor=core.east(4);for(int x=-1;x<=1;x++)for(int z=-1;z<=1;z++)if(x!=0 || z!=0)for(int y=0;y<3;y++)level.setBlock(actor.offset(x,y,z),Blocks.STONE.defaultBlockState(),2);
        var item=new ItemEntity(level,core.getX()+12.5,250,core.getZ()+.5,new ItemStack(Items.APPLE,7));item.setPickUpDelay(0);level.addFreshEntity(item);
        int i=0;UUID worker=null;for(var c:s.citizens()) {
            c.setManualAssignment(i==0?CitizenProfession.WORKER:CitizenProfession.TRAVELING_MERCHANT,null);
            c.updateProjection(null,i==0?actor:core.offset(-2,0,-4+i));
            if(i==0){worker=c.id();c.insertIntoInventory(new ItemStack(Items.DIAMOND_PICKAXE));c.startTask(CitizenWorkState.TO_PUBLIC_ITEM,core.east(12),item.getUUID(),null);}i++;
        }
        ForceWorkDebugService.set(level,s,true);data.touch();CitizenProjectionService.ensureSettlementCitizens(level,s);SaveAcceptanceService.load(level,s,2);SaveAcceptanceService.watch(level,s,"worker77-access-live",4000,100);
        return "OK settlement="+s.id()+"; worker="+worker+"; item="+item.getUUID()+"; fixture gave one real pickaxe and 7 apples outside sealed wall; subsequent cuts and movement are native work";
    }
}
