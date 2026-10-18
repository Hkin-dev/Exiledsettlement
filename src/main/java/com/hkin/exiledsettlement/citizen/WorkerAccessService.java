package com.hkin.exiledsettlement.citizen;

import com.hkin.exiledsettlement.blueprint.*;
import com.hkin.exiledsettlement.settlement.*;
import com.hkin.exiledsettlement.terrain.*;
import com.hkin.exiledsettlement.road.*;
import net.minecraft.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Block;
import net.minecraft.tags.*;
import net.minecraft.resources.ResourceLocation;
import java.util.*;

/** A local paid passage cut after normal detours fail. The resident keeps its parent work goal. */
public final class WorkerAccessService {
    private WorkerAccessService() {}
    private record Attempt(BlockPos block,BlockPos staging){}
    private static final Map<CitizenRecord,Map<Attempt,Long>> FAILED_ATTEMPTS=new WeakHashMap<>();
    private static boolean deferred(ServerLevel l,CitizenRecord c,BlockPos block,BlockPos staging){
        var failed=FAILED_ATTEMPTS.get(c);if(failed==null)return false;
        failed.values().removeIf(until->until<=l.getGameTime());return failed.containsKey(new Attempt(block,staging));
    }
    /** Release a failed local attempt, keeping its mother's work and actual inventory intact. */
    public static void abandonStalledAttempt(ServerLevel l,CitizenRecord c){
        var work=c.workerAccess;
        if(work.block!=null && work.fillSite==null)FAILED_ATTEMPTS.computeIfAbsent(c,k->new HashMap<>())
                .put(new Attempt(work.block,work.staging),l.getGameTime()+400);
        work.clear();
    }
    public static final class State {
        public BlockPos block,goal,staging,advance;
        public UUID fillSite;
        public boolean personalSource;
        public String original="";
        public int ticks;
        public long lastTick;
        public void clear(){block=null;goal=null;staging=null;advance=null;fillSite=null;personalSource=false;original="";ticks=0;lastTick=0;}
        public CompoundTag save(){var t=new CompoundTag();if(block!=null)t.putLong("Block",block.asLong());if(goal!=null)t.putLong("Goal",goal.asLong());if(staging!=null)t.putLong("Staging",staging.asLong());if(advance!=null)t.putLong("Advance",advance.asLong());if(fillSite!=null)t.putUUID("FillSite",fillSite);t.putBoolean("PersonalSource",personalSource);t.putString("Original",original);t.putInt("Ticks",ticks);t.putLong("LastTick",lastTick);return t;}
        public void load(CompoundTag t){clear();if(t.contains("Block"))block=BlockPos.of(t.getLong("Block"));if(t.contains("Goal"))goal=BlockPos.of(t.getLong("Goal"));if(t.contains("Staging"))staging=BlockPos.of(t.getLong("Staging"));if(t.contains("Advance"))advance=BlockPos.of(t.getLong("Advance"));if(t.hasUUID("FillSite"))fillSite=t.getUUID("FillSite");personalSource=t.getBoolean("PersonalSource");original=t.getString("Original");ticks=Math.max(0,t.getInt("Ticks"));lastTick=Math.max(0,t.getLong("LastTick"));}
    }
    public static boolean allowed(ServerLevel level,SettlementRecord s,BlockPos p) {
        return allowed(level,s,p,null);
    }
    public static boolean allowed(ServerLevel level,SettlementRecord s,BlockPos p,CitizenRecord worker) {
        var state=level.getBlockState(p);
        if(!level.hasChunkAt(p) || !s.contains(p) || p.equals(s.corePosition()) || s.community().danger(p)
                || com.hkin.exiledsettlement.blueprint.BuildingWorkPolicy.automaticForbidden(s,com.hkin.exiledsettlement.blueprint.BuildingWorkPolicy.assigned(s,worker),p)
                || com.hkin.exiledsettlement.blueprint.BuildingWorkPolicy.suppressed(s,com.hkin.exiledsettlement.blueprint.BuildingWorkPolicy.assigned(s,worker),p)
                || !state.getFluidState().isEmpty() || level.getBlockEntity(p)!=null || state.getDestroySpeed(level,p)<0
                || !(TerrainService.natural(state)||TerrainService.soft(state)) || RoadSpaceData.get(level).contains(p) || BridgeService.column(s,p))return false;
        if(s.buildings().stream().anyMatch(b->b.active() && b.contains(p)))return false;
        // Access is a consumer of the shared work geometry, not permission to
        // undo its mother's footing. Completion does not release a live fill.
        if(requiredFill(level,s,p))return false;
        // Future structure is cleared by its own paid preparation, never by an unrelated passing worker.
        var task=worker==null?null:WorkTaskService.task(s,worker);
        for(var b:s.formalBlueprints())if(!b.cancellationQueued() && BlueprintTemplates.create(b).placementLayout().reserves(p)) {
            var target=BlueprintTemplates.create(b).placementAt(p);
            if(task==null || !b.id().equals(task.mother()) || target==null || BlueprintService.matchesPlacedState(state,target.state()))return false;
        }
        if(s.terrain().projectsAt(p).stream().anyMatch(order->!order.completed && !order.cancelled
                && (task==null || !order.dependencies.contains(task.mother())) && !sharedPublicClearance(level,s,order,p)))return false;
        return true;
    }
    private static boolean sharedPublicClearance(ServerLevel level,SettlementRecord s,TerrainProject order,BlockPos at){
        if(!order.publicGround || order.bridge || order.roadReclaim || order.areaClearance)return false;
        var site=s.blueprint(order.id);var height=PublicGroundService.targetAt(s,at);
        if(site==null || height==null || at.getY()<=height)return false;
        var goal=order.template().placementAt(at);if(goal==null || !goal.state().isAir())return false;
        return order.indices(at).stream().anyMatch(i->!order.finished.get(i) && !order.steps.get(i).fill()
                && TerrainService.guard(level,s,site,order.steps.get(i)).isEmpty());
    }
    public static boolean requiredFill(ServerLevel level,SettlementRecord s,BlockPos p) {
        var owner=s.terrain().projects.get(s.terrain().cells.get(p));
        var current=level.getBlockState(p);var height=PublicGroundService.targetAt(s,p);
        if(current.getCollisionShape(level,p).isEmpty())return false;
        if(s.terrain().escapeSupport(p,current))return true;
        // The solved ground is a volume, not just a paid surface block. Cutting
        // its existing natural support would create a fresh refill job too.
        if(height!=null && p.getY()<=height)return true;
        // Replanning releases old reservations but keeps paid support below
        // the new shared plane. It is still needed even between publications.
        if(s.terrain().paidFillEvidence(p) && PublicGroundService.planning(s))return true;
        // The existing bottom of a planned column is its real support too,
        // even when surveying needed no replacement order at that position.
        for(var column:s.terrain().projectsAt(p.above()))if(!column.cancelled && !column.heightSuperseded
                && !column.roadReclaim && !column.areaClearance){
            var above=column.template().placementAt(p.above());
            if(above!=null && !above.state().isAir() && Block.isShapeFullBlock(above.state().getCollisionShape(level,p.above()))
                    && (!column.publicGround || height==null || p.getY()<height))return true;
        }
        if(owner==null || owner.cancelled || owner.heightSuperseded)return false;
        if(owner.publicGround && height!=null && p.getY()>height)return false;
        var goal=owner.template().placementAt(p);
        return goal!=null && !goal.state().isAir();
    }
    public static boolean request(ServerLevel level,SettlementCitizenEntity entity,BlockPos goal,int failures) {
        return request(level,entity,goal,failures,false);
    }
    /** Called only after the real core interaction candidates all failed ordinary routing. */
    public static boolean requestCoreAccess(ServerLevel level,SettlementRecord s,CitizenRecord c,SettlementCitizenEntity e){
        if(!s.creativeCoreInstalled() || c.profession()!=CitizenProfession.WORKER || e.isSleeping() || e.isDefending()
                || CitizenInteractionService.held(e) || SettlementCoreInteractionService.canReceiveSupply(level,s,e))return false;
        return request(level,e,s.corePosition(),3,true);
    }
    private static boolean request(ServerLevel level,SettlementCitizenEntity entity,BlockPos goal,int failures,boolean personal) {
        if(failures<3 || goal==null)return false;
        var sid=CitizenProjectionService.settlementId(entity);var cid=CitizenProjectionService.citizenId(entity);
        var s=SettlementSavedData.get(level).settlements().stream().filter(a->a.id().equals(sid)).findFirst().orElse(null);
        var c=s==null?null:s.citizen(cid);
        if(c==null || c.profession()!=CitizenProfession.WORKER || c.workState()==CitizenWorkState.IDLE && !personal)return false;
        if(c.pitEscape.exit!=null || c.workerAccess.block!=null || c.workerAccess.advance!=null)return true;
        var p=previewCut(level,s,c,entity.navigationFeetPosition(),goal,entity);
        if(p!=null && deferred(level,c,p,entity.navigationFeetPosition()))p=null;
        if(p!=null)c.workerAccess.staging=entity.navigationFeetPosition().immutable();
        if(p==null){
            var lift=previewLift(level,s,c,entity,goal);
            if(lift!=null){p=entity.navigationFeetPosition();c.workerAccess.fillSite=lift.id();}
            else {
                if(PitEscapeService.request(level,s,c,entity))return true;
                var stage=cutStaging(level,s,c,entity,goal);if(stage==null)return false;
                c.workerAccess.staging=stage;
                p=previewCut(level,s,c,stage,goal,entity);if(p==null){c.workerAccess.clear();return false;}
            }
        }
        c.workerAccess.block=p;c.workerAccess.goal=goal.immutable();c.workerAccess.original=net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(level.getBlockState(p).getBlock()).toString();
        c.workerAccess.personalSource=personal;
        c.workerAccess.ticks=0;c.workerAccess.lastTick=level.getGameTime();SettlementSavedData.get(level).touch();
        return true;
    }
    /** Only an existing paid soil column at the worker's feet may precede unrelated layers. */
    private static BlueprintRecord previewLift(ServerLevel level,SettlementRecord s,CitizenRecord c,SettlementCitizenEntity e,BlockPos goal){
        var feet=e.navigationFeetPosition();
        if(!e.onGround() || e.isInWater() || e.hasActiveConstructionMovement() || e.isConstructionAccessRetreating()
                || c.findInventorySlot(stack->stack.is(ItemTags.SHOVELS))<0
                || !level.getBlockState(feet.below()).isFaceSturdy(level,feet.below(),Direction.UP))return null;
        for(int rise=1;rise<=16;rise++){
            var landing=feet.above(rise);boolean exit=false;
            for(var d:Direction.Plane.HORIZONTAL)for(int dy:new int[]{0,1,-1}){var next=landing.relative(d).above(dy);
                // Leaving a pit may temporarily increase distance to a low
                // supply goal. A physically usable exit is still real progress.
                if(e.canStandAtPosition(next)
                        && level.getFluidState(next).isEmpty() && level.getFluidState(next.below()).isEmpty()
                        && e.canTraverseRecordedConstructionEdge(landing,next))exit=true;
            }
            if(!exit && !paidExitClearance(level,s,c,e,landing,goal))continue;
            for(var site:s.blueprints()){
                var p=site.terrainProject();
                if(p==null || site.cancellationQueued() || p.cancelled || p.completed || p.bridge || p.areaClearance || p.roadReclaim
                        || !TerrainService.allowed(s,site))continue;
                FoundationSupportService.review(level,s,site);
                var needed=new HashMap<Item,Integer>();boolean valid=true;
                for(int dy=0;dy<rise;dy++){
                    var at=feet.above(dy);var step=p.indices(at).stream().filter(i->i>=p.cursor && !p.finished.get(i))
                            .map(p.steps::get).filter(a->FoundationPlacement.applies(p,a)).findFirst().orElse(null);
                    if(step==null || !level.getBlockState(at).isAir() || !TerrainService.guard(level,s,site,step).isEmpty()) {valid=false;break;}
                    var item=BuriedFillMaterials.choose(level,s,p,step,c).getBlock().asItem();needed.merge(item,1,Integer::sum);
                }
                if(valid && level.getBlockState(landing).getCollisionShape(level,landing).isEmpty()
                        && level.getBlockState(landing.above()).getCollisionShape(level,landing.above()).isEmpty()
                        && needed.entrySet().stream().allMatch(a->c.inventory().stream().filter(stack->stack.is(a.getKey())).mapToInt(ItemStack::getCount).sum()>=a.getValue()))return site;
            }
        }
        return null;
    }
    public static boolean accessFill(SettlementRecord s,BlueprintRecord site,TerrainProject.Step step){
        return step.fill() && s.citizens().stream().anyMatch(c->c.profession()==CitizenProfession.WORKER
                && site.id().equals(c.workerAccess.fillSite) && step.position().equals(c.workerAccess.block));
    }
    /** The registered fill cap is below the worker's eventual feet, not the building anchor. */
    public static Integer foundationColumnSurface(BlueprintRecord site,BlockPos feet){
        var p=site.terrainProject();if(p==null)return null;Integer top=null;
        for(int y=feet.getY();y<=feet.getY()+16;y++){
            var at=feet.atY(y);if(p.indices(at).stream().map(p.steps::get).noneMatch(step->FoundationPlacement.applies(p,step)))break;
            top=y;
        }
        return top;
    }
    /** A complete paid column lets a worker leave a low foundation without abandoning its layer. */
    public static boolean canRaiseFoundation(ServerLevel l,SettlementRecord s,CitizenRecord c,SettlementCitizenEntity e,
            BlueprintRecord site,BlockPos feet,int surface){
        var p=site.terrainProject();
        if(p==null || p.bridge || p.areaClearance || p.roadReclaim || p.cancelled || p.completed
                || feet.getY()>surface || surface-feet.getY()>16 || !l.hasChunkAt(feet)
                || !l.getBlockState(feet.below()).isFaceSturdy(l,feet.below(),Direction.UP))return false;
        var needed=new HashMap<Item,Integer>();
        for(int y=feet.getY();y<=surface;y++) {
            var at=feet.atY(y);var step=p.indices(at).stream().filter(i->i>=p.cursor && !p.finished.get(i))
                    .map(p.steps::get).filter(a->FoundationPlacement.applies(p,a)).findFirst().orElse(null);
            if(step==null || !FoundationPlacement.replaces(l,p,step) || !TerrainService.guard(l,s,site,step).isEmpty()
                    || !l.getBlockState(at).getCollisionShape(l,at).isEmpty())return false;
            var state=l.getBlockState(at);
            var tag=state.is(BlockTags.MINEABLE_WITH_PICKAXE)?ItemTags.PICKAXES:state.is(BlockTags.MINEABLE_WITH_AXE)?ItemTags.AXES:ItemTags.SHOVELS;

            var item=BuriedFillMaterials.choose(l,s,p,step,c).getBlock().asItem();needed.merge(item,1,Integer::sum);
        }
        var landing=feet.atY(surface+1);
        boolean exit=false;for(var d:Direction.Plane.HORIZONTAL)for(int dy:new int[]{0,1,-1}){
            var next=landing.relative(d).above(dy);
            if(e.canStandAtPosition(next) && l.getFluidState(next).isEmpty() && l.getFluidState(next.below()).isEmpty()
                    && e.canTraverseRecordedConstructionEdge(landing,next))exit=true;
        }
        return (exit || paidExitClearance(l,s,c,e,landing,c.targetPosition()==null?s.corePosition():c.targetPosition()))
                && l.getFluidState(landing).isEmpty() && l.getFluidState(landing.above()).isEmpty()
                && l.getBlockState(landing).getCollisionShape(l,landing).isEmpty()
                && l.getBlockState(landing.above()).getCollisionShape(l,landing.above()).isEmpty()
                && needed.entrySet().stream().allMatch(a->c.inventory().stream().filter(stack->stack.is(a.getKey()))
                    .mapToInt(ItemStack::getCount).sum()>=a.getValue());
    }
    /** A registered paid head-clearance can provide the next workface above a supported column. */
    private static boolean paidExitClearance(ServerLevel l,SettlementRecord s,CitizenRecord c,SettlementCitizenEntity e,BlockPos landing,BlockPos goal){
        var cut=previewCut(l,s,c,landing,goal,e);if(cut==null)return false;
        boolean registered=s.terrain().projectsAt(cut).stream().anyMatch(p->{
            if(p.cancelled || p.completed || p.heightSuperseded || p.bridge || p.roadReclaim || p.areaClearance)return false;
            var site=s.blueprint(p.id);var target=p.template().placementAt(cut);
            return site!=null && TerrainService.allowed(s,site) && target!=null && target.state().isAir()
                    && p.indices(cut).stream().anyMatch(i->i>=p.cursor && !p.finished.get(i) && !p.abandoned.get(i)
                            && !p.steps.get(i).fill() && TerrainService.guard(l,s,site,p.steps.get(i)).isEmpty());
        });
        if(!registered)return false;
        var state=l.getBlockState(cut);
        var tag=state.is(BlockTags.MINEABLE_WITH_PICKAXE)?ItemTags.PICKAXES:state.is(BlockTags.MINEABLE_WITH_AXE)?ItemTags.AXES:state.is(BlockTags.MINEABLE_WITH_SHOVEL)?ItemTags.SHOVELS:null;
        return true;
    }
    public static boolean requestFoundationRaise(ServerLevel l,SettlementRecord s,CitizenRecord c,SettlementCitizenEntity e,
            BlueprintRecord site,int surface){
        var feet=e.navigationFeetPosition();
        if(c.profession()!=CitizenProfession.WORKER || c.workerAccess.block!=null || !e.onGround() || e.isInWater()
                || e.hasActiveConstructionMovement() || !canRaiseFoundation(l,s,c,e,site,feet,surface))return false;
        c.workerAccess.block=feet;c.workerAccess.goal=feet.atY(surface+1);c.workerAccess.fillSite=site.id();
        c.workerAccess.lastTick=l.getGameTime();SettlementSavedData.get(l).touch();return true;
    }
    /** Read-only local candidate, also used to select a bank that can actually be prepared. */
    public static BlockPos previewCut(ServerLevel level,SettlementRecord s,CitizenRecord c,BlockPos feet,BlockPos goal) {
        return previewCut(level,s,c,feet,goal,null);
    }
    private static BlockPos previewCut(ServerLevel level,SettlementRecord s,CitizenRecord c,BlockPos feet,BlockPos goal,SettlementCitizenEntity entity) {
        var choices=new ArrayList<BlockPos>();
        for(var d:Direction.Plane.HORIZONTAL){var next=feet.relative(d);
            if(next.distSqr(goal)>=feet.distSqr(goal) || !level.getBlockState(next.below()).isFaceSturdy(level,next.below(),Direction.UP))continue;
            if(!level.getBlockState(next).getFluidState().isEmpty() || !level.getBlockState(next.above()).getFluidState().isEmpty())continue;
            boolean footBlocked=!level.getBlockState(next).getCollisionShape(level,next).isEmpty();
            boolean headBlocked=!level.getBlockState(next.above()).getCollisionShape(level,next.above()).isEmpty();
            // A one-block ascent keeps the next cell as its footing. Clear the
            // two cells above that footing, rather than trying to dig the very
            // support protected by the committed ground plan.
            if(footBlocked && level.getBlockState(next).isFaceSturdy(level,next,Direction.UP)){
                var head=next.above(2);boolean upperBlocked=!level.getBlockState(head).getCollisionShape(level,head).isEmpty();
                if(level.getFluidState(head).isEmpty() && (!headBlocked || allowed(level,s,next.above(),c))
                        && (!upperBlocked || allowed(level,s,head,c))){
                    if(upperBlocked)choices.add(head);if(headBlocked)choices.add(next.above());
                }
            }
            if(footBlocked && !allowed(level,s,next,c) || headBlocked && !allowed(level,s,next.above(),c))continue;
            if(headBlocked)choices.add(next.above());else if(footBlocked)choices.add(next);
            else if(goal.getY()<feet.getY()
                    && (goal.getY()<feet.getY()-2 || registeredConstructionAccessGoal(level,s,c,goal))
                    && next.distSqr(goal)<feet.distSqr(goal)
                    && Math.abs(next.getX()-goal.getX())<=4 && Math.abs(next.getZ()-goal.getZ())<=4
                    && allowed(level,s,next.below(),c) && !level.getBlockState(next.below()).isAir()
                    && (level.getBlockState(next.below(2)).isFaceSturdy(level,next.below(2),Direction.UP)
                        || registeredConstructionAccessGoal(level,s,c,goal)))choices.add(next.below());
        }
        return choices.stream().filter(p->entity==null || cutVisible(level,entity,feet,p))
                .min(Comparator.comparingInt((BlockPos at)->at.getY()).reversed().thenComparingDouble(a->a.distSqr(goal))).orElse(null);
    }
    private static boolean registeredConstructionAccessGoal(ServerLevel l,SettlementRecord s,CitizenRecord c,BlockPos goal) {
        var site=s.blueprint(c.targetBlueprintId());
        if(site==null || site.cancellationQueued())return false;
        if(site.hasClearanceTarget(goal))return true;
        // A missing low structural cell can itself be air while ordinary soil
        // blocks its working face. The clearance belongs to this approved
        // building, not to a speculative public terrain target.
        if(site.terrainProject()!=null || site.region()!=null || goal.getY()>site.anchor().getY())return false;
        var finalCell=BlueprintTemplates.create(site).placementAt(goal);
        return finalCell!=null && finalCell.mode().isFinalStructure() && !finalCell.state().isAir()
                && !BlueprintService.placementSatisfied(l,site,finalCell);
    }
    private static boolean cutVisible(ServerLevel l,SettlementCitizenEntity e,BlockPos feet,BlockPos at){
        if(Math.abs(at.getY()-feet.getY())>2)return false;
        var eye=new net.minecraft.world.phys.Vec3(feet.getX()+.5,feet.getY()+e.getEyeHeight(),feet.getZ()+.5);
        var hit=l.clip(new net.minecraft.world.level.ClipContext(eye,net.minecraft.world.phys.Vec3.atCenterOf(at),
                net.minecraft.world.level.ClipContext.Block.COLLIDER,net.minecraft.world.level.ClipContext.Fluid.NONE,e));
        return hit.getType()==net.minecraft.world.phys.HitResult.Type.MISS || hit.getBlockPos().equals(at);
    }
    /** Walk to a nearby reachable frontier before cutting; no world changes in this search. */
    private static BlockPos cutStaging(ServerLevel l,SettlementRecord s,CitizenRecord c,SettlementCitizenEntity e,BlockPos goal){
        var feet=e.navigationFeetPosition();var candidates=new ArrayList<BlockPos>();
        for(int dx=-6;dx<=6;dx++)for(int dz=-6;dz<=6;dz++){
            if(dx==0 && dz==0)continue;
            var seed=feet.offset(dx,0,dz);if(!l.hasChunkAt(seed))continue;
            int top=l.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,seed.getX(),seed.getZ());
            for(int y:new int[]{feet.getY(),top}){
                var at=seed.atY(y);if(at.distSqr(goal)>feet.distSqr(goal)+32 || !s.contains(at) || !e.canStandAtPosition(at)
                        || !l.getFluidState(at).isEmpty() || !l.getFluidState(at.below()).isEmpty()
                        || !l.getBlockState(at.below()).isFaceSturdy(l,at.below(),Direction.UP)
                        || previewCut(l,s,c,at,goal,e)==null)continue;
                if(deferred(l,c,previewCut(l,s,c,at,goal,e),at))continue;
                candidates.add(at);
            }
        }
        for(var at:candidates.stream().distinct().sorted(Comparator.comparingDouble(p->p.distSqr(feet))).toList())
            if(e.probeConstructionReturnPath(at)!=null && TerrainWorkSafety.hasReturn(e,at,feet.getY()-1))return at;
        return null;
    }
    /** Bounded read-only collision/protection evidence, used only by acceptance diagnostics. */
    public static com.google.gson.JsonObject observation(ServerLevel l,SettlementRecord s,CitizenRecord c,SettlementCitizenEntity e,BlockPos goal){
        var out=new com.google.gson.JsonObject();if(!com.hkin.exiledsettlement.debug.SaveAcceptanceService.enabled())return out;
        var feet=e.navigationFeetPosition();out.addProperty("committedPlane",String.valueOf(s.terrain().publicPlane(feet)));
        var banks=new com.google.gson.JsonArray();for(var d:Direction.Plane.HORIZONTAL){var at=feet.relative(d);var bank=new com.google.gson.JsonObject();bank.addProperty("column",at.toShortString());bank.addProperty("actualTop",l.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,at.getX(),at.getZ()));banks.add(bank);}out.add("banks",banks);
        out.addProperty("goal",goal.toShortString());
        out.addProperty("directCut",String.valueOf(previewCut(l,s,c,feet,goal,e)));
        var lift=previewLift(l,s,c,e,goal);out.addProperty("paidLiftSite",lift==null?"":lift.id().toString());
        var cells=new com.google.gson.JsonArray();
        for(var d:Direction.Plane.HORIZONTAL)for(int dy=-1;dy<=2;dy++){
            var at=feet.relative(d).above(dy);var row=new com.google.gson.JsonObject();row.addProperty("at",at.toShortString());
            row.addProperty("actual",l.getBlockState(at).toString());row.addProperty("cutAllowed",allowed(l,s,at,c));
            row.addProperty("requiredSupport",requiredFill(l,s,at));row.addProperty("publicY",PublicGroundService.targetAt(s,at));
            row.addProperty("stand",e.canStandAtPosition(at));row.addProperty("step",Math.abs(dy)<=1 && e.canTraverseRecordedConstructionEdge(feet,at));
            cells.add(row);
        }
        out.add("neighbours",cells);return out;
    }
    public static boolean tick(ServerLevel level,SettlementSavedData data,SettlementRecord s,CitizenRecord c,SettlementCitizenEntity entity) {
        var cut=c.workerAccess;
        if(cut.advance!=null){
            if(entity.navigationFeetPosition().equals(cut.advance) && entity.onGround()){
                cut.clear();data.touch();return false;
            }
            if(entity.hasActiveConstructionMovement())return true;
            // Revalidate a saved one-cell move against the current physical world.
            if(level.getGameTime()-cut.lastTick>=160 || !entity.canStandAtPosition(cut.advance)
                    || !entity.beginAdjacentConstructionMovement(cut.advance,.65)){
                cut.clear();data.touch();return false;
            }
            data.touch();return true;
        }
        var p=cut.block;if(p==null)return false;
        if(cut.fillSite!=null){
            var site=s.blueprint(cut.fillSite);
            if(site==null || site.cancellationQueued() || !p.equals(entity.navigationFeetPosition())
                    || !entity.onGround() || entity.isInWater() || !CitizenInteractionRange.contains(entity,p)){
                cut.clear();data.touch();return false;
            }
            if(level.getGameTime()-cut.lastTick<10)return true;
            CitizenWorkService.advanceAccessFill(level,data,s,site,c,entity,p);
            cut.clear();entity.navigationGeometryChanged(level.getGameTime());data.touch();return true;
        }
        var state=level.getBlockState(p);
        if(!allowed(level,s,p,c) || !cut.original.equals(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString())){
            cut.clear();data.touch();return false;
        }
        var tag=state.is(BlockTags.MINEABLE_WITH_PICKAXE)?ItemTags.PICKAXES:state.is(BlockTags.MINEABLE_WITH_AXE)?ItemTags.AXES:state.is(BlockTags.MINEABLE_WITH_SHOVEL)?ItemTags.SHOVELS:null;
        int slot=tag==null?-1:c.findInventorySlot(stack->stack.is(tag));

        // A candidate seen from a neighbouring bank may be the worker's current
        // footing. Walk onto that bank before cutting; never remove support in place.
        boolean standingOnTarget=p.equals(entity.navigationFeetPosition().below());
        if(!CitizenInteractionRange.contains(entity,p) || standingOnTarget){
            var stage=cut.staging;
            if(stage==null || !entity.canStandAtPosition(stage)
                    || level.getGameTime()-cut.lastTick>=400){abandonStalledAttempt(level,c);data.touch();return false;}
            if(entity.hasActiveConstructionMovement())return true;
            var feet=entity.navigationFeetPosition();
            if(feet.equals(stage)){
                if(standingOnTarget){abandonStalledAttempt(level,c);data.touch();return false;}
                if(!entity.isCenteredOnConstructionBlock(stage) && entity.beginConstructionCentering(stage,.65))return true;
                abandonStalledAttempt(level,c);data.touch();return false;
            }
            if(feet.distManhattan(stage)<=2 && entity.beginAdjacentConstructionMovement(stage,.65))return true;
            entity.navigateTo(stage,.65);
            if(entity.navigationRetryStage()>=3)abandonStalledAttempt(level,c);
            data.touch();return true;
        }
        entity.stopSettlementNavigation();
        var tool=slot<0?ItemStack.EMPTY:c.inventoryItem(slot);
        long now=level.getGameTime();cut.ticks+=(int)Math.min(10,Math.max(0,now-cut.lastTick));cut.lastTick=now;
        c.updateActivity(CitizenActivity.BUILDING);c.updateWorkFailure("");entity.setActivity(CitizenActivity.BUILDING);
        if(slot>=0)c.selectActiveSlot(slot);
        entity.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        int duration=Math.max(24,(int)Math.ceil(state.getDestroySpeed(level,p)*30/CitizenToolSpeed.multiplier(tool)));
        if(cut.ticks<duration){data.touch();return true;}
        // Never remove a footing used by any live resident, even if its own worker moved meanwhile.
        if(!level.getEntitiesOfClass(SettlementCitizenEntity.class,new net.minecraft.world.phys.AABB(p).inflate(1,2,1),a->a.isAlive() && a.blockPosition().below().equals(p)).isEmpty()){
            abandonStalledAttempt(level,c);data.touch();return false;
        }
        var result=SiteClearance.clear(level,p,entity,tool,part->allowed(level,s,part,c));
        if(!result.failure().isEmpty()){c.updateWorkFailure(result.failure());cut.clear();data.touch();return true;}
        for(var drop:result.drops()){var left=c.insertIntoInventory(drop);if(!left.isEmpty())Block.popResource(level,p,left);}
        if(slot>=0)c.damageInventoryItem(slot,1);
        com.hkin.exiledsettlement.debug.SaveAcceptanceService.action(level,c,"worker_access_cut",p,cut.original,1);
        CitizenWorkService.recordActualEngineeringAction(level,entity);
        entity.navigationGeometryChanged(level.getGameTime());
        var feet=entity.navigationFeetPosition();var goal=cut.goal;boolean personal=cut.personalSource;
        BlockPos advance=null;
        if(goal!=null && Math.abs(feet.getX()-p.getX())+Math.abs(feet.getZ()-p.getZ())==1)
            for(int dy:new int[]{0,1,-1}){
                var next=new BlockPos(p.getX(),feet.getY()+dy,p.getZ());
                if(next.distSqr(goal)<feet.distSqr(goal) && entity.canStandAtPosition(next)
                        && level.getFluidState(next).isEmpty() && level.getFluidState(next.below()).isEmpty()
                        && entity.canTraverseRecordedConstructionEdge(feet,next)){advance=next;break;}
            }
        cut.clear();
        if(advance!=null && entity.beginAdjacentConstructionMovement(advance,.65)){
            cut.advance=advance;cut.goal=goal;cut.personalSource=personal;cut.lastTick=level.getGameTime();
        }
        data.touch();return true;
    }
}
