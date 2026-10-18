package com.hkin.exiledsettlement.debug;

import com.hkin.exiledsettlement.citizen.*;
import com.hkin.exiledsettlement.registry.ModEntities;
import com.hkin.exiledsettlement.settlement.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import java.util.*;

/** Reproduce the actual scaffold landing epsilon without changing an actor during movement. */
public final class Scaffold102Checks {
    private static void check(boolean ok,String message,List<String> checks){if(!ok)throw new IllegalStateException(message);checks.add(message);}
    public static String run(ServerLevel l) {
        if(!SaveAcceptanceService.enabled())throw new IllegalStateException("TEST_ONLY");
        var core=new BlockPos(59200,250,59200);var data=SettlementSavedData.get(l);
        if(data.findByCore(core).isPresent())throw new IllegalStateException("FIXTURE_EXISTS");
        var s=data.createOrRecover(core,UUID.randomUUID(),"Scaffold epsilon landing inputs").value();
        var saved=new LinkedHashMap<BlockPos,BlockState>();var checks=new ArrayList<String>();SettlementCitizenEntity e=null;
        try {
            for(var raw:BlockPos.betweenClosed(core.offset(-1,-1,-1),core.offset(3,5,1))){var at=raw.immutable();
                if(!l.getBlockState(at).isAir())throw new IllegalStateException("UNUSED_AIR_REQUIRED");saved.put(at,l.getBlockState(at));}
            l.setBlock(core.below(),Blocks.STONE.defaultBlockState(),2);l.setBlock(core,Blocks.SCAFFOLDING.defaultBlockState(),2);
            var target=core.east().above(2);l.setBlock(target.below(),Blocks.STONE.defaultBlockState(),2);
            var c=s.citizens().iterator().next();c.setManualAssignment(CitizenProfession.WORKER,null);
            e=ModEntities.SETTLEMENT_CITIZEN.get().create(l);e.getPersistentData().putString(CitizenProjectionService.SETTLEMENT_ID_TAG,s.id().toString());
            e.getPersistentData().putString(CitizenProjectionService.CITIZEN_ID_TAG,c.id().toString());
            e.setPos(core.getX()+.5,Math.nextDown(251D),core.getZ()+.5);
            if(!l.addFreshEntity(e))throw new IllegalStateException("ENTITY_SPAWN_FAILED");data.updateProjection(s,c,e.getUUID(),e.blockPosition());
            check(l.getEntities().get(e.getUUID())==e,"actor participates in native world physics",checks);
            check(e.blockPosition().getY()==250 && e.navigationFeetPosition().getY()==251,"fixture reproduces epsilon-below-integer scaffold landing",checks);
            var before=e.position();
            check(e.normalizeConstructionStandingPosition().equals(core.above()),"construction and navigation agree on the actual feet cell",checks);
            check(e.position().equals(before),"cell normalization does not reposition the actor",checks);
            check(e.beginAdjacentConstructionMovement(target,.65),"real one-block rise starts instead of being misclassified as two",checks);
            double maximumStep=0;int ticks=0;
            while(e.hasActiveConstructionMovement() && ticks++<160){var old=e.position();e.tick();maximumStep=Math.max(maximumStep,e.position().distanceTo(old));}
            for(int i=0;i<5;i++)e.tick();
            check(!e.hasActiveConstructionMovement() && e.navigationFeetPosition().equals(target) && e.onGround(),
                    "native movement reaches and lands on the existing bank:position="+e.position()+",feet="+e.navigationFeetPosition()
                            +",ground="+e.onGround()+",ticks="+ticks+",diagnostic="+e.constructionMovementDiagnostics(),checks);
            check(maximumStep<.5 && e.position().distanceTo(before)>1,"movement consists of bounded physical steps",checks);
            var landed=e.position();
            check(!e.beginAdjacentConstructionMovement(target.west().above(2),.65) && e.position().equals(landed),
                    "a genuine two-block rise stays rejected without repositioning",checks);
            return "PASS "+checks.size()+": "+String.join("; ",checks)+"; explicit fixture inputs restored; not autonomous ten-building acceptance";
        } finally {if(e!=null)e.discard();s.deactivate();saved.forEach((at,state)->l.setBlock(at,state,2));data.touch();}
    }
    private Scaffold102Checks(){}
}
