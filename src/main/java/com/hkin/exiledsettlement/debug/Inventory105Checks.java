package com.hkin.exiledsettlement.debug;
import com.hkin.exiledsettlement.citizen.*;
import com.hkin.exiledsettlement.building.BuildingType;
import com.hkin.exiledsettlement.settlement.*;
import net.minecraft.core.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.*;
import java.util.*;

/** Explicit inventory inputs only; does not credit resident delivery or construction. */
public final class Inventory105Checks {
    private static void check(boolean ok,String label,List<String> checks){if(!ok)throw new IllegalStateException(label);checks.add(label);}
    public static String run(ServerLevel l){
        if(!SaveAcceptanceService.enabled())throw new IllegalStateException("TEST_ONLY");
        var core=new BlockPos(59400,250,59400);var data=SettlementSavedData.get(l);
        if(data.findByCore(core).isPresent())throw new IllegalStateException("FIXTURE_EXISTS");
        var s=data.createOrRecover(core,UUID.randomUUID(),"Bounded construction stock inputs").value();
        var c=s.citizens().iterator().next();c.setManualAssignment(CitizenProfession.WORKER,null);
        var b=s.addBlueprint(BuildingType.HOUSE,core,core.above(40),Direction.NORTH,s.ownerId(),false);
        var checks=new ArrayList<String>();
        try {
            c.insertIntoInventory(new ItemStack(Items.SCAFFOLDING,64));
            c.insertIntoInventory(new ItemStack(Items.SCAFFOLDING,64));
            c.insertIntoInventory(new ItemStack(Items.SCAFFOLDING,19));
            c.insertIntoInventory(new ItemStack(Items.DIRT,64));
            c.insertIntoInventory(new ItemStack(Items.DIRT,64));
            c.insertIntoInventory(new ItemStack(Items.IRON_SHOVEL));
            c.reservePitBlocks(st->st.is(Items.DIRT),16);
            int before=c.inventory().stream().mapToInt(ItemStack::getCount).sum();
            check(CitizenWorkService.constructionDeliveryCount(s,c,0)==0,"one real scaffold stack stays reserved",checks);
            check(CitizenWorkService.constructionDeliveryCount(s,c,1)==64 && CitizenWorkService.constructionDeliveryCount(s,c,2)==19,"duplicate scaffold stacks become real deliverable cargo",checks);
            int dirt=0;for(int i=0;i<CitizenRecord.INVENTORY_SIZE;i++)if(c.inventoryItem(i).is(Items.DIRT))dirt+=CitizenWorkService.constructionDeliveryCount(s,c,i);
            check(dirt==48,"retain normal fill stack plus separate 16 paid stepping blocks",checks);
            check(c.pitReserve().getCount()==16 && java.util.stream.IntStream.range(0,CitizenRecord.INVENTORY_SIZE).filter(c::pitReserveSlot).allMatch(slot->CitizenWorkService.constructionDeliveryCount(s,c,slot)==0),"reserved stepping slot never becomes cargo",checks);
            check(CitizenWorkService.constructionDeliveryCount(s,c,5)==0,"actual construction tool stays reserved",checks);
            check(before==c.inventory().stream().mapToInt(ItemStack::getCount).sum() && !b.cancellationQueued(),"delivery classification neither removes stock nor cancels any project",checks);
            c.extractInventoryItem(stack->stack.is(Items.SCAFFOLDING),32);
            check(CitizenWorkService.constructionDeliveryCount(s,c,1)==32,"partial first stack reserves only the remaining useful amount",checks);
            return "PASS "+checks.size()+": "+String.join("; ",checks)+"; explicit inputs, autonomous delivery separate";
        } finally {s.deactivate();data.touch();}
    }
    private Inventory105Checks(){}
}
