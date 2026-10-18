package com.hkin.exiledsettlement.gametest;

import com.hkin.exiledsettlement.ExiledSettlementMod;
import com.hkin.exiledsettlement.blueprint.*;
import com.hkin.exiledsettlement.building.BuildingType;
import com.hkin.exiledsettlement.citizen.*;
import com.hkin.exiledsettlement.settlement.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.util.*;

@GameTestHolder(ExiledSettlementMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class SiteConstructionGameTests {
    @GameTest(templateNamespace="minecraft", template="bastion/mobs/empty", batch="site_paid", timeoutTicks=400)
    public static void groundWorkersBuildPaidFrameOncePerTenTicks(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos core = helper.absolutePos(new BlockPos(32000, 2, 0));
        var forced = new ArrayList<net.minecraft.world.level.ChunkPos>();
        for (int x = (core.getX()-24)>>4; x <= (core.getX()+24)>>4; x++)
            for (int z = (core.getZ()-24)>>4; z <= (core.getZ()+24)>>4; z++) {
                level.setChunkForced(x,z,true);
                forced.add(new net.minecraft.world.level.ChunkPos(x,z));
            }
        for (BlockPos p : BlockPos.betweenClosed(core.offset(-24,-1,-24),core.offset(24,-1,24)))
            level.setBlock(p, Blocks.STONE.defaultBlockState(), 2);
        level.setDayTime(6000);
        var settlement = new SettlementRecord(UUID.randomUUID(), "Paid site", core, UUID.randomUUID());
        var data = new SettlementSavedData();
        var site = settlement.addBlueprint(BuildingType.HOUSE, core, core, Direction.NORTH, settlement.ownerId(), false);
        var template = BlueprintTemplates.create(site);
        // This test isolates paid access-frame timing. Complete the foundation
        // fixture rather than leaving stone obstacles in declared structure cells;
        // real site clearance is tested separately and now rechecks late obstacles.
        for (BlueprintPlacement p : template.placements())
            if (p.position().getY() < core.getY() && p.mode().isFinalStructure())
                level.setBlock(p.position(), p.state(), 2);
        var workers = new ArrayList<CitizenRecord>(settlement.citizens());
        for (int i=3;i<workers.size();i++) workers.get(i).markDead(core);
        var actors = new ArrayList<SettlementCitizenEntity>();
        for (int i=0;i<3;i++) {
            var worker=workers.get(i);
            BlockPos station = new BlockPos(template.minCorner().getX()-2,core.getY(),template.minCorner().getZ()+2+i*2);
            worker.updateProjection(null, station);
            worker.assignProfession(CitizenProfession.WORKER,null);
            worker.startTask(CitizenWorkState.TO_BLUEPRINT, core, null, site.id());
        }
        // All three use the first worker's real stock, including the last unit.
        workers.getFirst().insertIntoInventory(new ItemStack(Items.SCAFFOLDING,6));
        Runnable advance=()->{for(int i=0;i<3;i++) CitizenWorkService.advanceSiteConstruction(level,data,settlement,workers.get(i),actors.get(i));};
        helper.startSequence().thenWaitUntil(()->{
            CitizenProjectionService.ensureSettlementCitizens(level,settlement);
            for(int i=0;i<3;i++) helper.assertTrue(CitizenProjectionService.findProjection(level,workers.get(i))!=null,"Projection not tracked yet");
        }).thenExecute(()->{
            for(int i=0;i<3;i++) {
                var actor=CitizenProjectionService.findProjection(level,workers.get(i));
                BlockPos p=workers.get(i).lastPosition();
                actor.moveTo(p.getX()+0.5,p.getY(),p.getZ()+0.5,0,0);
                actor.setOnGround(true);
                actor.setNoAi(true);
                actors.add(actor);
            }
            advance.run();
            helper.assertTrue(site.temporaryFootings().size()==3,"Three present workers must build three paid frame cells: "+site.temporaryFootings().size());
            for (int i=0;i<3;i++) helper.assertTrue(WorkforceStatusService.working(level,settlement,workers.get(i)),
                    "A paid ground action was not reflected in working-worker counts");
            advance.run();
            helper.assertTrue(site.temporaryFootings().size()==3,"Repeated same-tick calls increased speed");
        }).thenIdle(11).thenExecute(()->{
            advance.run();
            helper.assertTrue(site.temporaryFootings().size()==6,"Ten ticks should allow three more cells: " + site.temporaryFootings().size()
                    + " workers=" + workers.stream().limit(3).map(w->w.workFailure()+":"+w.workState()).toList()
                    + " actors="+actors.stream().map(e->e.position()+":"+e.onGround()).toList());
            helper.assertTrue(workers.stream().flatMap(w->w.inventory().stream()).noneMatch(s->s.is(Items.SCAFFOLDING)),"Scaffold materials not consumed exactly");
        }).thenIdle(11).thenExecute(()->{
            advance.run();
            helper.assertTrue(site.temporaryFootings().size()==6,"Missing materials must stop construction");
            workers.getFirst().insertIntoInventory(new ItemStack(Items.SCAFFOLDING,3));
            level.setDayTime(15000);
        }).thenIdle(11).thenExecute(()->{
            advance.run();
            helper.assertTrue(site.temporaryFootings().size()==6,"Resting workers advanced construction");
            level.setDayTime(6000);
            actors.get(2).moveTo(core.getX()+40,core.getY(),core.getZ(),0,0);
            advance.run();
            helper.assertTrue(site.temporaryFootings().size()==8,"Absent worker contributed speed or supplied workers failed to resume");
            actors.forEach(e->e.discard());
            forced.forEach(p->level.setChunkForced(p.x,p.z,false));
        }).thenSucceed();
    }
}
