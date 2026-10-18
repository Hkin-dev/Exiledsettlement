package com.hkin.exiledsettlement.blueprint;

import com.hkin.exiledsettlement.settlement.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import java.util.*;

/** Owns survey and scheduling, while workers own real tools, inventory and block events. */
public final class FarmEarthworkService {
    private static final Map<BlueprintRecord, Retry> RETRIES = new WeakHashMap<>();
    private record Retry(long at, FarmEarthworkPlan.Survey survey) { }
    private FarmEarthworkService() { }
    public static boolean applies(BlueprintRecord b) { return b.region()==null && b.dynamicArea() && b.customTemplate()==null && FarmEarthworkPlan.applies(b.type()); }
    public static boolean allowed(SettlementRecord s, BlueprintRecord own, BuildingBlueprintTemplate t, BlockPos p) {
        if(!s.contains(p)) return false;
        for(var b:s.buildings()) if(b.contains(p)) return false;
        for(var b:s.blueprints()) {
            if(b==own || b.cancellationQueued() || b.anchor().equals(t.anchor()) && own!=null) continue;
            var other=BlueprintTemplates.create(b);
            // A grading footprint cannot be shared with another blueprint at a different height.
            if(p.getX()>=other.minCorner().getX() && p.getX()<=other.maxCorner().getX()
                    && p.getZ()>=other.minCorner().getZ() && p.getZ()<=other.maxCorner().getZ()) return false;
        }
        return true;
    }
    public static FarmEarthworkPlan.Survey ensure(ServerLevel level, BlueprintRecord b) {
        if(b.earthworkPlan()!=null) return new FarmEarthworkPlan.Survey(b.earthworkPlan(), b.anchor(), "");
        long now=level.getGameTime(); var cached=RETRIES.get(b);
        if(cached!=null && cached.at()>now) return cached.survey();
        var data=SettlementSavedData.get(level); var s=data.findContaining(b.anchor()).orElse(null);
        var t=BlueprintTemplates.create(b);
        var survey=FarmEarthworkPlan.survey(level,t,p->s!=null && allowed(s,b,t,p));
        if(survey.valid()) {b.withEarthworkPlan(survey.plan());data.touch();RETRIES.remove(b);}
        else RETRIES.put(b,new Retry(now+100,survey));
        return survey;
    }
    public static List<BlueprintService.PendingPlacement> pending(ServerLevel level, BlueprintRecord b, Set<BlockPos> excluded) {
        var survey=ensure(level,b);
        if(!survey.valid()) return List.of(new BlueprintService.PendingPlacement(-1,
                new BlueprintPlacement(b.anchor(),net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(),null,BlueprintPlacementMode.CLEARANCE)));
        var plan=survey.plan(); int budget=128;
        while(!plan.done() && budget-->0) {
            var step=plan.next(); var state=level.getBlockState(step.position());
            if(!level.hasChunkAt(step.position()) || (step.fill() ? !state.is(net.minecraft.world.level.block.Blocks.DIRT) : !state.isAir())) break;
            plan.advance();SettlementSavedData.get(level).touch();
        }
        if(plan.done()) return List.of();
        List<BlueprintService.PendingPlacement> out=new ArrayList<>();
        // Bound supply planning to one backpack's planning window, not all earthwork on every tick.
        int index=plan.completed();
        for(var step:plan.remaining()) {
            if(!excluded.contains(step.position())) out.add(new BlueprintService.PendingPlacement(-1-index,step.placement()));
            if(out.size()>=ConstructionPlanService.MAX_SUPPLY_PLACEMENTS) break;
            index++;
        }
        // Excluded first work cannot unblock final structure: the other worker owns this frontier.
        if(out.isEmpty()) return List.of(new BlueprintService.PendingPlacement(-1,plan.next().placement()));
        return List.copyOf(out);
    }
    public static String detail(ServerLevel level, BlockPos p, String reason) {
        return "FARM_EARTHWORK|"+reason+"|"+p.getX()+"|"+p.getY()+"|"+p.getZ()+"|"
                +net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(level.getBlockState(p).getBlock());
    }
    public static Map<Item,Integer> requirements(ServerLevel level, BlueprintRecord b) {
        var requirements=new LinkedHashMap<>(BlueprintTemplates.create(b).requirements(level));
        if(applies(b) && b.earthworkPlan()!=null) {
            int dirt=(int)b.earthworkPlan().remaining().stream().filter(FarmEarthworkPlan.Step::fill).count();
            if(dirt>0) requirements.merge(Items.DIRT,dirt,Integer::sum);
        }
        return requirements;
    }
    /** Later obstruction or removed support re-enters the same frozen column bounds, never free soil conversion. */
    public static String prepareTarget(ServerLevel level, SettlementRecord s, BlueprintRecord b, BlueprintPlacement placement) {
        if(!applies(b) || b.earthworkPlan()==null || !b.earthworkPlan().done()) return "";
        var t=BlueprintTemplates.create(b); BlockPos p=placement==null ? FarmEarthworkPlan.missingSupport(level,t) : placement.position();
        if(p==null) return "";
        var ground=new BlockPos(p.getX(),b.earthworkPlan().soilY(),p.getZ());
        boolean needsSoil=placement!=null && (placement.mode()==BlueprintPlacementMode.FARM_TILL || placement.state().is(net.minecraft.world.level.block.Blocks.DIRT));
        boolean exactDirt=placement!=null && placement.state().is(net.minecraft.world.level.block.Blocks.DIRT);
        if(placement!=null && FarmEarthworkPlan.support(level,ground.below())
                && (exactDirt ? level.getBlockState(ground).is(net.minecraft.world.level.block.Blocks.DIRT)
                    : !needsSoil || FarmEarthworkPlan.soil(level.getBlockState(ground)))) return "";
        if(b.earthworkPlan().size()>=32730) return detail(level,p,"FARM_EARTHWORK_BUDGET_EXCEEDED");
        var survey=b.earthworkPlan().surveyColumn(level,t,pos->allowed(s,b,t,pos),p);
        if(!survey.valid()) return detail(level,survey.position(),survey.reason());
        b.earthworkPlan().append(survey.plan());SettlementSavedData.get(level).touch();return "";
    }
}
