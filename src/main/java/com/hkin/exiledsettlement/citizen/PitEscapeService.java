package com.hkin.exiledsettlement.citizen;

import com.hkin.exiledsettlement.blueprint.*;
import com.hkin.exiledsettlement.building.*;
import com.hkin.exiledsettlement.settlement.*;
import com.hkin.exiledsettlement.terrain.*;
import com.hkin.exiledsettlement.road.*;
import net.minecraft.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.phys.AABB;

/** Personal paid pit escape. Placed supports remain in the world, with no recovery job. */
public final class PitEscapeService {
    public static final int RESERVE = 16;
    private static final java.util.Set<Item> FILLER = java.util.Set.of(Items.DIRT, Items.COBBLESTONE,
            Items.STONE, Items.ANDESITE, Items.DIORITE, Items.GRANITE, Items.COBBLED_DEEPSLATE);
    public static boolean ordinary(ItemStack stack) {
        return !stack.isEmpty() && FILLER.contains(stack.getItem())
                && ItemStack.isSameItemSameComponents(stack, new ItemStack(stack.getItem()));
    }
    public static final class State {
        public BlockPos column, exit, cut;
        public int ticks;
        public long lastTick, exitStarted;
        // Saved exits rediscover their route. Each placement rechecks only that short physical route.
        private java.util.List<BlockPos> exitRoute=java.util.List.of();
        public void clear() { column=null;exit=null;cut=null;ticks=0;lastTick=0;exitStarted=0;exitRoute=java.util.List.of(); }
        public CompoundTag save() {
            var t=new CompoundTag();if(column!=null)t.putLong("Column",column.asLong());
            if(exit!=null)t.putLong("Exit",exit.asLong());if(cut!=null)t.putLong("Cut",cut.asLong());
            t.putInt("Ticks",ticks);t.putLong("LastTick",lastTick);t.putLong("ExitStarted",exitStarted);return t;
        }
        public void load(CompoundTag t) {
            clear();if(t.contains("Column"))column=BlockPos.of(t.getLong("Column"));
            if(t.contains("Exit"))exit=BlockPos.of(t.getLong("Exit"));if(t.contains("Cut"))cut=BlockPos.of(t.getLong("Cut"));
            ticks=Math.max(0,t.getInt("Ticks"));lastTick=Math.max(0,t.getLong("LastTick"));
            exitStarted=Math.max(0,t.getLong("ExitStarted"));
        }
    }
    /** Fill the protected slot from carried stock, then from a physically reached supply source. */
    public static void topUp(ServerLevel l, SettlementRecord s, CitizenRecord c, SettlementCitizenEntity e) {
        if(c.profession()!=CitizenProfession.WORKER)return;
        int moved=c.reservePitBlocks(PitEscapeService::ordinary,RESERVE);
        var reserve=c.pitReserve();int missing=RESERVE-reserve.getCount();
        if(missing>0){
            Item material=reserve.isEmpty()?Items.DIRT:reserve.getItem();
            var warehouse=SettlementInventoryService.warehouses(s).stream()
                    .filter(w->WarehouseInventoryService.canInteract(l,w,e)
                            && (WarehouseInventoryService.countItem(l,w,material)>0 || WarehouseInventoryService.hasCreativeSupply(l,w)))
                    .findFirst().orElse(null);
            boolean core=s.creativeCoreInstalled() && SettlementCoreInteractionService.canReceiveSupply(l,s,e);
            if((warehouse!=null || core) && c.inventoryCapacityFor(new ItemStack(material))>=missing){
                var stock=warehouse==null?new ItemStack(material,missing)
                        :WarehouseInventoryService.extractItemOrCreativeForResident(l,warehouse,e,material,missing);
                if(!stock.isEmpty()){
                    var left=c.insertIntoInventory(stock);
                    if(!left.isEmpty() && warehouse!=null)WarehouseInventoryService.insertForResident(l,warehouse,e,left);
                    int received=stock.getCount()-left.getCount();
                    moved+=c.reservePitBlocks(PitEscapeService::ordinary,RESERVE);
                    if(received>0)com.hkin.exiledsettlement.debug.SaveAcceptanceService.action(l,c,"pit_reserve_pickup",
                            warehouse==null?s.corePosition():warehouse.position(),material.toString(),received);
                }
            }
        }
        if(moved>0)SettlementSavedData.get(l).touch();
    }
    private static boolean placeAllowed(ServerLevel l, SettlementRecord s, BlockPos at) {
        if(!l.hasChunkAt(at) || l.isOutsideBuildHeight(at) || !s.contains(at) || at.equals(s.corePosition())
                || !l.getBlockState(at).isAir() || s.community().danger(at) || s.decorations().forbidden(at)
                || s.terrain().suppressed.contains(at.asLong()) || BridgeService.column(s,at)
                || RoadSpaceData.get(l).contains(at) || RoadSpaceData.get(l).playerSurface(at)!=null)return false;
        if(s.buildings().stream().anyMatch(b->b.active() && b.contains(at)))return false;
        for(var b:s.formalBlueprints())if(!b.cancellationQueued()){
            var goal=BlueprintTemplates.create(b).placementAt(at);
            if(goal!=null && (goal.state().isAir() || goal.mode().isFinalStructure()))return false;
        }
        return true;
    }
    private static boolean clearable(ServerLevel l, SettlementRecord s, CitizenRecord c, BlockPos at) {
        var state=l.getBlockState(at);
        if(!l.getFluidState(at).isEmpty())return false;
        if(state.getCollisionShape(l,at).isEmpty())return state.isAir();
        if(!WorkerAccessService.allowed(l,s,at,c))return false;
        var tag=state.is(BlockTags.MINEABLE_WITH_PICKAXE)?ItemTags.PICKAXES:
                state.is(BlockTags.MINEABLE_WITH_AXE)?ItemTags.AXES:state.is(BlockTags.MINEABLE_WITH_SHOVEL)?ItemTags.SHOVELS:null;
        return tag==null || c.hasInventoryItem(stack->stack.is(tag)
                && (!state.requiresCorrectToolForDrops() || stack.isCorrectToolForDrops(state)));
    }
    /** Read-only search for an existing bank, never a promised public ground height. */
    public static BlockPos preview(ServerLevel l, SettlementRecord s, CitizenRecord c, SettlementCitizenEntity e) {
        return preview(l,s,c,e,false);
    }
    private static BlockPos preview(ServerLevel l,SettlementRecord s,CitizenRecord c,SettlementCitizenEntity e,boolean navigationFailed){
        var feet=e.navigationFeetPosition();
        if(c.profession()!=CitizenProfession.WORKER || !e.onGround() || e.isInWater()
                || e.hasActiveConstructionMovement() || e.isConstructionAccessRetreating()
                || !l.getBlockState(feet.below()).isFaceSturdy(l,feet.below(),Direction.UP))return null;
        // A normal adjacent way out takes precedence over building a pillar.
        if(!navigationFailed)for(var d:Direction.Plane.HORIZONTAL)for(int dy:new int[]{0,-1}){
            var next=feet.relative(d).above(dy);
            if(e.canStandAtPosition(next) && l.getFluidState(next.below()).isEmpty()
                    && e.canTraverseRecordedConstructionEdge(feet,next))return null;
        }
        // A real one-block bank needs a bounded physical step, not a pillar.
        // This also works inside a protected building where placing at the
        // resident's feet would correctly be forbidden.
        for(var d:Direction.Plane.HORIZONTAL){
            var next=feet.relative(d).above();
            if(e.canStandAtPosition(next) && l.getFluidState(next.below()).isEmpty()
                    && e.canTraverseRecordedConstructionEdge(feet,next))return next;
        }
        // Failure to reach a work/supply goal is not evidence of a pit. Search
        // the actual connected footing before changing the resident's column.
        if(groundWayOut(l,s,e,feet))return null;
        int stock=c.inventory().stream().filter(PitEscapeService::ordinary).mapToInt(ItemStack::getCount).sum();
        for(int rise=1;rise<=Math.min(RESERVE,stock);rise++){
            var landing=feet.above(rise);boolean valid=true;
            for(int y=0;y<rise;y++){
                var at=feet.above(y);
                if(l.getBlockState(at).isAir()?!placeAllowed(l,s,at):!clearable(l,s,c,at)){valid=false;break;}
            }
            if(!valid || !clearable(l,s,c,landing) || !clearable(l,s,c,landing.above()))continue;
            for(var d:Direction.Plane.HORIZONTAL)for(int dy:new int[]{0,-1}){
                var exit=landing.relative(d).above(dy);
                // A flat neighbour inside a wide pit did not provide the
                // failed journey's exit. Pillar only to a real raised bank,
                // never one block up followed by walking back to the pit floor.
                if(navigationFailed && exit.getY()<=feet.getY()+1)continue;
                if(e.canStandAtPosition(exit) && l.getFluidState(exit).isEmpty() && l.getFluidState(exit.below()).isEmpty()
                        && e.canTraverseRecordedConstructionEdge(landing,exit)
                        && groundExit(l,s,e,feet,exit))return exit;
            }
        }
        return null;
    }
    private static boolean dryStanding(ServerLevel l,SettlementRecord s,SettlementCitizenEntity e,BlockPos at){
        return l.hasChunkAt(at) && s.contains(at) && !s.community().danger(at)
                && l.getFluidState(at.below()).isEmpty() && e.canStandAtPosition(at);
    }
    private static boolean onBuilding(ServerLevel l,SettlementRecord s,BlockPos feet){
        var floor=feet.below();
        for(var b:s.buildings())if(b.active() && aboveFootprint(floor,b.minCorner(),b.maxCorner()))return true;
        for(var b:s.formalBlueprints())if(!b.cancellationQueued()){
            var t=BlueprintTemplates.create(b);
            if(aboveFootprint(floor,t.minCorner(),t.maxCorner()))return true;
        }
        return false;
    }
    private static boolean aboveFootprint(BlockPos at,BlockPos min,BlockPos max){
        return at.getX()>=min.getX() && at.getX()<=max.getX() && at.getZ()>=min.getZ()
                && at.getZ()<=max.getZ() && at.getY()>=min.getY();
    }
    /** Bounded, read-only local search, used only when requesting escape. Unknown space never proves entrapment. */
    private static boolean groundWayOut(ServerLevel l,SettlementRecord s,SettlementCitizenEntity e,BlockPos origin){
        var queue=new java.util.ArrayDeque<BlockPos>();var seen=new java.util.HashSet<BlockPos>();
        queue.add(origin);seen.add(origin);int visited=0;
        while(!queue.isEmpty() && visited++<64){
            var at=queue.removeFirst();
            if(Math.abs(at.getX()-origin.getX())+Math.abs(at.getZ()-origin.getZ())>=4)return true;
            for(var d:Direction.Plane.HORIZONTAL)for(int dy:new int[]{0,-1,1}){
                var next=at.relative(d).above(dy);
                if(Math.abs(next.getY()-origin.getY())>2 || seen.contains(next))continue;
                if(!l.hasChunkAt(next))return true;
                if(dryStanding(l,s,e,next) && e.canTraverseRecordedConstructionEdge(at,next)){seen.add(next);queue.addLast(next);}
            }
        }
        return !queue.isEmpty();
    }
    /** A raised cell must lead away onto supported land, not a roof or an isolated wall/pole. */
    private static boolean groundExit(ServerLevel l,SettlementRecord s,SettlementCitizenEntity e,BlockPos origin,BlockPos exit){
        return !groundRoute(l,s,e,origin,exit).isEmpty();
    }
    private static java.util.List<BlockPos> groundRoute(ServerLevel l,SettlementRecord s,SettlementCitizenEntity e,BlockPos origin,BlockPos exit){
        if(!dryStanding(l,s,e,exit) || onBuilding(l,s,exit))return java.util.List.of();
        var queue=new java.util.ArrayDeque<BlockPos>();var seen=new java.util.HashSet<BlockPos>();
        var previous=new java.util.HashMap<BlockPos,BlockPos>();
        queue.add(exit);seen.add(exit);int visited=0;
        while(!queue.isEmpty() && visited++<64){
            var at=queue.removeFirst();
            int distance=Math.abs(at.getX()-origin.getX())+Math.abs(at.getZ()-origin.getZ());
            if(distance>=3 && groundColumn(l,at,origin.getY()-1)){
                var path=new java.util.LinkedList<BlockPos>();
                for(var node=at;node!=null;node=previous.get(node))path.addFirst(node);
                return java.util.List.copyOf(path);
            }
            for(var d:Direction.Plane.HORIZONTAL)for(int dy:new int[]{0,-1,1}){
                var next=at.relative(d).above(dy);
                if(next.getY()<exit.getY()-1 || next.getY()>exit.getY()+1
                        || Math.abs(next.getX()-exit.getX())+Math.abs(next.getZ()-exit.getZ())>4 || seen.contains(next))continue;
                if(dryStanding(l,s,e,next) && !onBuilding(l,s,next)
                        && e.canTraverseRecordedConstructionEdge(at,next)){seen.add(next);previous.put(next,at);queue.addLast(next);}
            }
        }
        return java.util.List.of();
    }
    private static boolean groundColumn(ServerLevel l,BlockPos at,int bottom){
        for(int y=at.getY()-1;y>=bottom;y--){
            var p=new BlockPos(at.getX(),y,at.getZ());
            if(!l.hasChunkAt(p) || !l.getFluidState(p).isEmpty() || !l.getBlockState(p).isFaceSturdy(l,p,Direction.UP))return false;
        }
        return true;
    }
    public static boolean request(ServerLevel l, SettlementRecord s, CitizenRecord c, SettlementCitizenEntity e) {
        if(c.pitEscape.exit!=null)return true;
        var exit=preview(l,s,c,e,true);if(exit==null)return false;
        c.reservePitBlocks(PitEscapeService::ordinary,RESERVE);
        c.pitEscape.column=e.navigationFeetPosition().immutable();c.pitEscape.exit=exit;
        if(exit.getY()>c.pitEscape.column.getY()+1)c.pitEscape.exitRoute=groundRoute(l,s,e,c.pitEscape.column,exit);
        if(exit.getY()==c.pitEscape.column.getY()+1
                && e.canTraverseRecordedConstructionEdge(c.pitEscape.column,exit)){
            if(!e.beginAdjacentConstructionMovement(exit,.65)){c.pitEscape.clear();return false;}
            c.pitEscape.exitStarted=l.getGameTime();
            com.hkin.exiledsettlement.debug.SaveAcceptanceService.action(l,c,"worker_pit_step_start",exit,"",0);
        }
        c.pitEscape.lastTick=l.getGameTime();c.workerAccess.clear();SettlementSavedData.get(l).touch();return true;
    }
    public static String observation(ServerLevel l,SettlementRecord s,CitizenRecord c,SettlementCitizenEntity e) {
        var at=e.navigationFeetPosition();var work=c.pitEscape;
        return "column="+work.column+",exit="+work.exit+",onGround="+e.onGround()+",centered="+e.isCenteredOnConstructionBlock(at)
                +",moving="+e.hasActiveConstructionMovement()+",footFillAllowed="+placeAllowed(l,s,at)
                +",reserve="+c.pitReserve()+",ticks="+work.ticks+",exitStarted="+work.exitStarted
                +",personal="+CommunityLifeService.handles(c)+",constructionPaused="+CommunityLifeService.pausesConstruction(s,c);
    }
    public static boolean tick(ServerLevel l, SettlementSavedData data, SettlementRecord s, CitizenRecord c, SettlementCitizenEntity e) {
        var work=c.pitEscape;if(work.exit==null)return false;
        var feet=e.navigationFeetPosition();
        if(c.profession()!=CitizenProfession.WORKER || work.column==null || !e.canStandAtPosition(work.exit)
                || feet.getX()!=work.column.getX() || feet.getZ()!=work.column.getZ()
                || feet.getY()<work.column.getY() || feet.getY()>work.column.getY()+RESERVE){work.clear();data.touch();return false;}
        boolean pillar=work.exit.getY()>work.column.getY()+1;
        if(pillar){
            if(work.exitRoute.isEmpty()){
                if(feet.equals(work.column) && groundWayOut(l,s,e,feet)){
                    e.stopSettlementNavigation();work.clear();data.touch();return false;
                }
                work.exitRoute=groundRoute(l,s,e,work.column,work.exit);
            }
            // Recheck the few selected cells before any movement, head cutting,
            // or payment. No neighbourhood search is repeated for a live route.
            if(work.exitRoute.isEmpty() || !routeStillGrounded(l,s,e,work)){
                e.stopSettlementNavigation();work.clear();data.touch();return false;
            }
        }
        if(e.hasActiveConstructionMovement()){
            if(work.exitStarted==0)work.exitStarted=l.getGameTime();
            if(l.getGameTime()-work.exitStarted<=200)return true;
            e.stopSettlementNavigation();work.exitStarted=0;data.touch();return true;
        }
        if(!e.onGround() || e.isInWater())return true;
        if(!e.isCenteredOnConstructionBlock(feet)){
            if(e.beginConstructionCentering(feet,.65)){work.exitStarted=l.getGameTime();data.touch();return true;}
            work.clear();data.touch();return false;
        }
        if(feet.getY()>=work.exit.getY() && feet.getY()-work.exit.getY()<=1 && e.canTraverseRecordedConstructionEdge(feet,work.exit)){
            if(e.beginAdjacentConstructionMovement(work.exit,.65)){
                work.exitStarted=l.getGameTime();work.cut=null;work.ticks=0;data.touch();return true;
            }
            work.clear();data.touch();return false;
        }
        e.stopSettlementNavigation();
        final var head=l.getBlockState(feet.above(2)).isAir()?feet.above():feet.above(2);
        if(!l.getBlockState(head).isAir()){
            if(!clearable(l,s,c,head) || !CitizenInteractionRange.contains(e,head)
                    || !l.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class,new AABB(head).inflate(1,2,1),
                        a->a.isAlive() && a.blockPosition().below().equals(head)).isEmpty()){
                work.clear();data.touch();return false;
            }
            var state=l.getBlockState(head);
            var tag=state.is(BlockTags.MINEABLE_WITH_PICKAXE)?ItemTags.PICKAXES:
                    state.is(BlockTags.MINEABLE_WITH_AXE)?ItemTags.AXES:state.is(BlockTags.MINEABLE_WITH_SHOVEL)?ItemTags.SHOVELS:null;
            int slot=tag==null?-1:c.findInventorySlot(stack->stack.is(tag) && (!state.requiresCorrectToolForDrops() || stack.isCorrectToolForDrops(state)));
            if(!head.equals(work.cut)){work.cut=head;work.ticks=0;work.lastTick=l.getGameTime();}
            var tool=slot<0?ItemStack.EMPTY:c.inventoryItem(slot);long now=l.getGameTime();
            work.ticks+=(int)Math.min(10,Math.max(0,now-work.lastTick));work.lastTick=now;
            c.updateActivity(CitizenActivity.BUILDING);e.setActivity(CitizenActivity.BUILDING);
            if(slot>=0)c.selectActiveSlot(slot);e.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
            int duration=Math.max(24,(int)Math.ceil(state.getDestroySpeed(l,head)*30/Math.max(1,tool.getDestroySpeed(state))));
            if(work.ticks<duration){data.touch();return true;}
            var result=SiteClearance.clear(l,head,e,tool,part->WorkerAccessService.allowed(l,s,part,c));
            if(!result.failure().isEmpty()){c.updateWorkFailure(result.failure());work.clear();data.touch();return true;}
            for(var drop:result.drops()){var left=c.insertIntoInventory(drop);if(!left.isEmpty())Block.popResource(l,head,left);}
            if(slot>=0)c.damageInventoryItem(slot,1);
            com.hkin.exiledsettlement.debug.SaveAcceptanceService.action(l,c,"worker_pit_head_cut",head,state.getBlock().asItem().toString(),1);
            CitizenWorkService.recordActualEngineeringAction(l,e);e.navigationGeometryChanged(l.getGameTime());
            work.cut=null;work.ticks=0;data.touch();return true;
        }
        if(l.getGameTime()-work.lastTick<10)return true;
        work.lastTick=l.getGameTime();
        if(!placeAllowed(l,s,feet) || !l.getBlockState(feet.below()).isFaceSturdy(l,feet.below(),Direction.UP)
                || FoundationPlacement.occupied(l,feet,true)){
            work.clear();data.touch();return false;
        }
        // Vanilla block lifting queries active entities. An unloaded actor must
        // not receive a block while absent from that physical query.
        if(l.getEntities((net.minecraft.world.entity.Entity)null,new AABB(feet),actor->actor==e).isEmpty())return true;
        c.reservePitBlocks(PitEscapeService::ordinary,RESERVE);var paid=c.takePitBlock();
        if(paid.isEmpty())paid=c.extractInventoryItem(PitEscapeService::ordinary,1);
        if(paid.isEmpty()){work.clear();c.updateWorkFailure("MATERIALS_MISSING");data.touch();return false;}
        var target=((BlockItem)paid.getItem()).getBlock().defaultBlockState();var original=l.getBlockState(feet);
        var snapshot=net.neoforged.neoforge.common.util.BlockSnapshot.create(l.dimension(),l,feet,Block.UPDATE_CLIENTS);
        if(!l.setBlock(feet,target,Block.UPDATE_ALL) || net.neoforged.neoforge.event.EventHooks.onBlockPlace(e,snapshot,Direction.UP)
                || !l.getBlockState(feet).equals(target)){
            snapshot.restore(Block.UPDATE_CLIENTS);c.refundPitBlock(paid);work.clear();c.updateWorkFailure("PROTECTED_AREA");data.touch();return true;
        }
        Block.pushEntitiesUp(original,target,l,feet);
        s.terrain().recordEscapeSupport(feet,target);
        com.hkin.exiledsettlement.debug.SaveAcceptanceService.action(l,c,"worker_pit_fill",feet,paid.getItem().toString(),1);
        CitizenWorkService.recordActualEngineeringAction(l,e);e.navigationGeometryChanged(l.getGameTime());
        c.updateActivity(CitizenActivity.BUILDING);c.updateWorkFailure("");e.setActivity(CitizenActivity.BUILDING);
        e.swing(net.minecraft.world.InteractionHand.MAIN_HAND);data.touch();return true;
    }
    private static boolean routeStillGrounded(ServerLevel l,SettlementRecord s,SettlementCitizenEntity e,State work){
        BlockPos previous=null;
        for(var at:work.exitRoute){
            if(!dryStanding(l,s,e,at) || onBuilding(l,s,at)
                    || previous!=null && !e.canTraverseRecordedConstructionEdge(previous,at))return false;
            previous=at;
        }
        return previous!=null && groundColumn(l,previous,work.column.getY()-1);
    }
    private PitEscapeService() {}
}
