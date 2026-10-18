package com.hkin.exiledsettlement.road;

import com.hkin.exiledsettlement.blueprint.*;
import com.hkin.exiledsettlement.building.BuildingType;
import com.hkin.exiledsettlement.settlement.*;
import com.hkin.exiledsettlement.terrain.*;
import net.minecraft.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import java.util.*;

/** Frozen geometry, separately admitted work. No planner operation places a world block. */
public final class PlayerBridgePlan {
    public final BlockPos first,second;
    public final boolean alongX;
    public final int width,start,end,sideMin;
    public int surface;
    public final boolean automaticHeight;
    public final boolean buildingModel;
    public int workBottom;
    private BridgeBankSurvey bankSurvey;
    private int nearRampStart,farRampStart;
    public final List<TerrainProject.Step> steps=new ArrayList<>();
    public final List<Integer> phases=new ArrayList<>(),rows=new ArrayList<>();
    public final Set<BlockPos> beams=new LinkedHashSet<>(),anchors=new LinkedHashSet<>(),deck=new LinkedHashSet<>(),rails=new LinkedHashSet<>(),walk=new LinkedHashSet<>();
    public final BitSet admitted=new BitSet();
    public int frontier,phase,suggestedSurface;
    public boolean paused;
    public BridgeConstructionSequence sequence;
    private int cachedProgress=-1;
    
    private int column,nearLength,farLength,nearGround,farGround;
    private boolean initialized;
    public boolean ready;
    public String problem="";
    public BlockPos conflict;
    public PlayerBridgePlan(BlockPos a,BlockPos b,boolean alongX,int surface){
        this(a,b,alongX,surface,true);
    }
    public PlayerBridgePlan(BlockPos a,BlockPos b,boolean alongX,int surface,boolean buildingModel){
        this.buildingModel=buildingModel;
        suggestedSurface=surface;first=a.immutable();second=b.immutable();this.alongX=alongX;automaticHeight=surface==Integer.MIN_VALUE;this.surface=automaticHeight?(buildingModel?a.getY()+1:Math.max(a.getY(),b.getY())+1):surface;
        workBottom=this.surface-2;
        suggestedSurface=this.surface;
        nearRampStart=alongX?Math.min(a.getX(),b.getX()):Math.min(a.getZ(),b.getZ());farRampStart=alongX?Math.max(a.getX(),b.getX()):Math.max(a.getZ(),b.getZ());
        start=Math.min(longitudinal(a),longitudinal(b));end=Math.max(longitudinal(a),longitudinal(b));
        sideMin=Math.min(side(a),side(b));width=Math.abs(side(a)-side(b))+1;
        if(width<(buildingModel?3:5) || !buildingModel && end-start<1)problem=buildingModel?"桥面至少需要3格宽（含两侧护栏和1格通道）":"桥体至少需要2格长、3格净宽（含两侧护栏共5格）；桥头由系统适配";
    }
    public int longitudinal(BlockPos p){return alongX?p.getX():p.getZ();}
    private int side(BlockPos p){return alongX?p.getZ():p.getX();}
    public BlockPos at(int row,int lateral,int y){return alongX?new BlockPos(row,y,lateral):new BlockPos(lateral,y,row);}
    public int minRow(){return start-nearLength;}
    public int maxRow(){return end+farLength;}
    public int columnCount(){return (maxRow()-minRow()+1)*width;}
    private void fail(String why,BlockPos at){problem=why+"："+at.toShortString();conflict=at.immutable();}
    public void survey(ServerLevel l,SettlementRecord s,int budget){
        if(ready || !problem.isEmpty())return;
        if(sequence!=null){problem=sequence.verifySlice(l,this);ready=problem.isEmpty() && sequence.verified();return;}
        if(!initialized){
            if(buildingModel){nearGround=surface;farGround=surface;initialized=true;return;}
            if(bankSurvey==null)bankSurvey=new BridgeBankSurvey();
            if(!bankSurvey.advance(l,s,this,budget)){
                if(!bankSurvey.problem.isEmpty()){problem=bankSurvey.problem;conflict=bankSurvey.conflict;}
                return;
            }
            nearGround=bankSurvey.near.ground();farGround=bankSurvey.far.ground();
            nearLength=start-bankSurvey.near.outer();farLength=bankSurvey.far.outer()-end;
            nearRampStart=bankSurvey.near.rampStart();farRampStart=bankSurvey.far.rampStart();
            suggestedSurface=Math.max(surface,suggestedSurface);initialized=true;
            return;
        }
        long deadline=System.nanoTime()+2_000_000;
        for(int n=0;n<budget && column<columnCount() && (n==0 || System.nanoTime()<deadline);n++){
            int r=minRow()+column/width,t=sideMin+column%width;
            int y=heightAt(r);
            if(!(buildingModel?surveyBuildingColumn(l,s,r,t,y):surveyColumn(l,s,r,t,y)))return;
            column++;
        }
        if(column==columnCount()){
            // All cuts in a row precede its beams; phases never publish distant deck/rails early.
            var order=new ArrayList<Integer>();for(int i=0;i<steps.size();i++)order.add(i);
            order.sort(Comparator.comparingInt((Integer i)->phases.get(i)).thenComparingInt(i->direction()*rows.get(i))
                    .thenComparingInt(i->steps.get(i).fill()?1:0)
                    .thenComparingInt(i->steps.get(i).fill()?steps.get(i).position().getY():-steps.get(i).position().getY()));
            var copy=new ArrayList<>(steps);var ps=new ArrayList<>(phases);var rs=new ArrayList<>(rows);
            steps.clear();phases.clear();rows.clear();for(int i:order){steps.add(copy.get(i));phases.add(ps.get(i));rows.add(rs.get(i));}
            var overlap=orderConflict(s);if(!overlap.isEmpty()){problem=overlap;return;}
            sequence=new BridgeConstructionSequence(this);problem=sequence.verifySlice(l,this);ready=problem.isEmpty() && sequence.verified();
        }
    }
    private boolean permitted(ServerLevel l,SettlementRecord s,BlockPos p){return obstruction(l,s,p).isEmpty();}
    private String obstruction(ServerLevel l,SettlementRecord s,BlockPos p){
        if(!s.contains(p))return "超出聚落领地";
        if(!l.hasChunkAt(p))return "地形尚未加载";
        if(l.isOutsideBuildHeight(p))return "超出世界高度";
        if(s.community().danger(p) || !buildingModel && (s.decorations().forbidden(p) || s.terrain().suppressed.contains(p.asLong())) || p.equals(s.corePosition()))return "此处已禁止施工";
        if(s.buildings().stream().anyMatch(b->b.active() && b.containsColumn(p))
                || s.formalBlueprints().stream().anyMatch(b->!b.cancellationQueued() && b.containsColumn(p)))return "占用已有建筑或建筑预留范围";
        if(BridgeService.column(s,p))return "占用已有桥梁";
        var v=l.getBlockState(p);var name=net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(v.getBlock()).toString();
        if(!v.getFluidState().isEmpty() && !(buildingModel && p.getY()<surface && TerrainService.replaceableWater(v)))return "桥面通行空间进入水体，请提高桥面（"+name+"）";
        // Player explicitly authorizes non-building clearance in the confirmed
        // bridge volume. SiteClearance performs real breaking and captures
        // container contents; its item conservation has an independent GameTest.
        // World/permission checks and conflicting order checks remain in force.
        if(v.getDestroySpeed(l,p)<0)return "原版不可破坏方块无法施工（"+name+"）";
        return "";
    }
    record Obstruction(String reason,BlockPos position) {}
    Obstruction columnObstruction(ServerLevel l,SettlementRecord s,int row,int lateral,int y,boolean bank,int floor){
        if(bank && !FarmEarthworkPlan.support(l,at(row,lateral,floor)))return new Obstruction("岸基没有稳定支撑",at(row,lateral,floor));
        for(int h=bank?Math.min(floor+1,y-2):y-2;h<=Math.max(y+3,bank?floor:y+3);h++){
            var p=at(row,lateral,h);var why=obstruction(l,s,p);
            if(!why.isEmpty())return new Obstruction(why,p);
        }
        return null;
    }
    private void add(ServerLevel l,BlockPos p,BlockState target,int phase,int row,BlockState original){
        if(!target.isAir() || !original.isAir()){steps.add(new TerrainProject.Step(p,target,original));phases.add(phase);rows.add(row);}
    }
    /** Selected rectangle is the complete deck. Only sparse edge pillars reach the ground. */
    private boolean surveyBuildingColumn(ServerLevel l,SettlementRecord s,int row,int lateral,int y){
        var beam=at(row,lateral,y-2);
        for(int h=y-2;h<=y+3;h++){
            var p=beam.atY(h);var why=obstruction(l,s,p);
            if(!why.isEmpty()){fail(why,p);return false;}
        }
        boolean pillar=(lateral==sideMin || lateral==sideMin+width-1)
                && (row==start || row==end || (row-start)%6==0);
        if(pillar){
            int bottom=y-3;
            for(;bottom>=l.getMinBuildHeight();bottom--){
                var p=beam.atY(bottom);var why=obstruction(l,s,p);
                if(!why.isEmpty()){fail(why,p);return false;}
                var state=l.getBlockState(p);
                if(state.getFluidState().isEmpty() && state.isFaceSturdy(l,p,Direction.UP)
                        && !state.is(net.minecraft.tags.BlockTags.LOGS) && !state.is(net.minecraft.tags.BlockTags.LEAVES)
                        && !state.hasBlockEntity())break;
            }
            if(bottom<l.getMinBuildHeight()){fail("支撑柱下方在世界高度内没有地面",beam);return false;}
            workBottom=Math.min(workBottom,bottom+1);
            for(int h=bottom+1;h<y-2;h++){
                var p=beam.atY(h);var original=l.getBlockState(p);
                if(TerrainService.replaceableWater(original))add(l,p,BridgePalette.abutment.defaultBlockState(),0,row,original);
                else {
                    if(!original.isAir())add(l,p,Blocks.AIR.defaultBlockState(),0,row,original);
                    add(l,p,BridgePalette.abutment.defaultBlockState(),0,row,Blocks.AIR.defaultBlockState());
                }
                anchors.add(p);beams.add(p);
            }
        }
        for(int h=y+3;h>=y-1;h--){var p=beam.atY(h);var existing=l.getBlockState(p);
            if(!TerrainService.replaceableWater(existing))add(l,p,Blocks.AIR.defaultBlockState(),0,row,existing);}
        var original=l.getBlockState(beam);
        if(!original.isAir() && !TerrainService.replaceableWater(original))add(l,beam,Blocks.AIR.defaultBlockState(),0,row,original);
        var timber=BridgePalette.beam.defaultBlockState();
        if(timber.hasProperty(RotatedPillarBlock.AXIS))timber=timber.setValue(RotatedPillarBlock.AXIS,alongX?Direction.Axis.X:Direction.Axis.Z);
        add(l,beam,timber,0,row,TerrainService.replaceableWater(original)?original:Blocks.AIR.defaultBlockState());beams.add(beam);
        var top=beam.above();var topOriginal=l.getBlockState(top);
        add(l,top,BridgePalette.deck.defaultBlockState(),1,row,TerrainService.replaceableWater(topOriginal)?topOriginal:Blocks.AIR.defaultBlockState());deck.add(top);
        if(lateral==sideMin || lateral==sideMin+width-1){var rail=top.above();add(l,rail,BridgePalette.rail.defaultBlockState(),2,row,Blocks.AIR.defaultBlockState());rails.add(rail);}
        else walk.add(top);
        return true;
    }
    private boolean surveyColumn(ServerLevel l,SettlementRecord s,int row,int lateral,int y){
        var beam=at(row,lateral,y-2);
        var sampled=bankSurvey.column(row,lateral);
        int floor=sampled.floor();boolean bank=sampled.dry() && (row<=nearRampStart || row>=farRampStart);
        var blocked=columnObstruction(l,s,row,lateral,y,bank,floor);
        if(blocked!=null){fail(blocked.reason(),blocked.position());return false;}
        if(!l.getFluidState(beam).isEmpty())suggestedSurface=Math.max(suggestedSurface,l.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,beam.getX(),beam.getZ())+2);
        if(bank){
            var soil=beam.atY(floor);if(!l.getBlockState(soil).getFluidState().isEmpty() || !FarmEarthworkPlan.support(l,soil)){
                fail("岸基或接坡没有全宽陆地支撑，请延长两端",soil);return false;}
            for(int h=Math.min(floor+1,y-2);h<y-2;h++){
                var p=beam.atY(h);
                var existing=l.getBlockState(p);
                if(!existing.isAir())add(l,p,Blocks.AIR.defaultBlockState(),0,row,existing);
                add(l,p,BridgePalette.abutment.defaultBlockState(),0,row,Blocks.AIR.defaultBlockState());anchors.add(p);beams.add(p);
            }
        }else{
            // The suspended body may cross water, dry valleys, roads or islands.
            // It never grows pillars down to the terrain below it.
        }
        // Clear the lower construction walk and final four-block headroom, locally as each row arrives.
        for(int h=Math.max(y+3,bank?floor:y+3);h>=y-1;h--){
            var p=beam.atY(h);
            add(l,p,Blocks.AIR.defaultBlockState(),0,row,l.getBlockState(p));
        }
        var original=l.getBlockState(beam);if(!original.isAir())add(l,beam,Blocks.AIR.defaultBlockState(),0,row,original);
        var timber=BridgePalette.beam.defaultBlockState();if(timber.hasProperty(RotatedPillarBlock.AXIS))timber=timber.setValue(RotatedPillarBlock.AXIS,alongX?Direction.Axis.X:Direction.Axis.Z);
        add(l,beam,bank?BridgePalette.abutment.defaultBlockState():timber,0,row,Blocks.AIR.defaultBlockState());beams.add(beam);if(bank)anchors.add(beam);
        var top=beam.above();BlockState target=BridgePalette.deck.defaultBlockState();
        boolean ramp=row<nearRampStart || row>farRampStart;int delta=row<nearRampStart?nearGround-surface:farGround-surface;
        int distance=row<nearRampStart?nearRampStart-row:row-farRampStart;
        if(ramp && delta!=0 && distance<=2*Math.abs(delta) && distance%2==1){
            Direction out=alongX?(row<nearRampStart?Direction.WEST:Direction.EAST):(row<nearRampStart?Direction.NORTH:Direction.SOUTH);
            target=BridgePalette.stair.defaultBlockState().setValue(StairBlock.FACING,delta>0?out:out.getOpposite());
        }
        add(l,top,target,1,row,Blocks.AIR.defaultBlockState());deck.add(top);
        if(lateral==sideMin || lateral==sideMin+width-1){
            // Rails continue over the final landing but never close either end.
            var rail=top.above();add(l,rail,BridgePalette.rail.defaultBlockState(),2,row,Blocks.AIR.defaultBlockState());rails.add(rail);
        }else walk.add(top);
        return true;
    }
    public int direction(){return longitudinal(first)<=longitudinal(second)?1:-1;}
    public boolean originHigh(){return direction()<0;}
    public int heightAt(int row){
        if(row<nearRampStart)return surface+Integer.signum(nearGround-surface)*Math.min(Math.abs(nearGround-surface),(nearRampStart-row)/2);
        if(row>farRampStart)return surface+Integer.signum(farGround-surface)*Math.min(Math.abs(farGround-surface),(row-farRampStart)/2);
        return surface;
    }
    public boolean inWorkArea(BlockPos feet){
        if(buildingModel)return longitudinal(feet)>=start-4 && longitudinal(feet)<=end+4
                && side(feet)>=sideMin-4 && side(feet)<=sideMin+width+3
                && feet.getY()>=workBottom-4 && feet.getY()<=surface+4;
        int distance=direction()*(longitudinal(feet)-(originHigh()?maxRow():minRow()));
        return distance>=-5 && distance<=-1 && side(feet)>=sideMin-2 && side(feet)<=sideMin+width+1
                && Math.abs(feet.getY()-shore(originHigh()).getY())<=2;
    }
    public List<BlockPos> stagingArea(){
        if(buildingModel)return List.of();
        var area=new ArrayList<BlockPos>();int row=originHigh()?maxRow():minRow(),ground=shore(originHigh()).getY();
        for(int back=1;back<=5;back++)for(int lateral=sideMin-2;lateral<=sideMin+width+1;lateral++)
            for(int dy=-2;dy<=2;dy++)area.add(at(row-direction()*back,lateral,ground+dy));
        return List.copyOf(area);
    }
    public boolean supplyingBank(BlockPos at){return buildingModel || (originHigh()?longitudinal(at)>end:longitudinal(at)<start);}
    public List<Integer> activeIndices(TerrainProject p){refresh(p);var batch=sequence.forAction(frontier);return batch==null?List.of():batch.actions();}
    public List<BlockPos> workArea(TerrainProject p,BlockPos target){
        refresh(p);var batch=sequence.forAction(frontier);
        if(batch==null || batch.actions().stream().noneMatch(i->p.steps.get(i).position().equals(target)))return List.of();
        return batch.workArea();
    }
    public BlockPos shore(boolean high){int row=high?maxRow()+1:minRow()-1;return at(row,sideMin+width/2,high?farGround:nearGround);}
    public Map<net.minecraft.world.item.Item,Integer> requirements(){var out=new LinkedHashMap<net.minecraft.world.item.Item,Integer>();for(var step:steps)if(step.fill())out.merge(step.target().getBlock().asItem(),1,Integer::sum);return out;}
    public String description(){if(buildingModel)return "所选范围就是桥面：长"+(end-start+1)+"格，净宽"+(width-2)+"格（含护栏"+width+"格），桥面站立高度"+surface
            +"；支撑柱在桥面两侧下方自动规划，不外延桥头或接坡。工人到工地附近后计时施工，无指定桥头站位；从可用仓库真实领料。";
        return "所选桥体长"+(end-start+1)+"格，净宽"+(width-2)+"格（含护栏"+width+"格），桥面站立高度"+surface
            + (initialized || ready?"；自动接岸范围向两端延伸"+nearLength+" / "+farLength+"格（黄色预览）":"；桥头尚未适配成功，最终延伸范围未确定")
            +"；无需框选岸基。首个选点方向为施工起岸；工人到桥头工作区后按时间推进整桥，真实领料并消耗。";}
    public CompoundTag save(){var t=new CompoundTag();t.putLong("First",first.asLong());t.putLong("Second",second.asLong());t.putBoolean("X",alongX);t.putInt("Surface",surface);
        t.putBoolean("BuildingModel",buildingModel);t.putInt("WorkBottom",workBottom);
        t.putBoolean("Paused",paused);t.putInt("SequenceVersion",buildingModel?4:3);t.putInt("NearRampStart",nearRampStart);t.putInt("FarRampStart",farRampStart);t.putInt("NearGround",nearGround);t.putInt("FarGround",farGround);t.putInt("Near",nearLength);t.putInt("Far",farLength);t.putIntArray("Phases",phases);t.putIntArray("Rows",rows);t.putLongArray("Admitted",admitted.toLongArray());t.putInt("Frontier",frontier);t.putInt("Phase",phase);return t;}
    public static PlayerBridgePlan load(CompoundTag t,TerrainProject p){var b=new PlayerBridgePlan(BlockPos.of(t.getLong("First")),BlockPos.of(t.getLong("Second")),t.getBoolean("X"),t.getInt("Surface"),t.getBoolean("BuildingModel"));
        b.workBottom=t.contains("WorkBottom")?t.getInt("WorkBottom"):b.surface-2;
        b.nearRampStart=t.contains("NearRampStart")?t.getInt("NearRampStart"):b.start;b.farRampStart=t.contains("FarRampStart")?t.getInt("FarRampStart"):b.end;
        b.paused=t.getBoolean("Paused");b.nearGround=t.getInt("NearGround");b.farGround=t.getInt("FarGround");b.nearLength=t.getInt("Near");b.farLength=t.getInt("Far");b.steps.addAll(p.steps);for(int n:t.getIntArray("Phases"))b.phases.add(n);for(int n:t.getIntArray("Rows"))b.rows.add(n);
        b.beams.addAll(p.bridgeBeams);b.anchors.addAll(p.bridgeAnchors);b.deck.addAll(p.bridgeDeck);b.rails.addAll(p.bridgeRails);b.walk.addAll(p.accessSurfaces);
        b.admitted.or(BitSet.valueOf(t.getLongArray("Admitted")));b.frontier=t.getInt("Frontier");b.phase=t.getInt("Phase");b.ready=true;b.sequence=new BridgeConstructionSequence(b);return b;}
    public void refresh(TerrainProject p){
        int progress=p.finished.cardinality();if(cachedProgress==progress && frontier>=p.cursor)return;cachedProgress=progress;
        frontier=p.cursor;while(frontier<p.steps.size() && p.finished.get(frontier))frontier++;phase=frontier>=phases.size()?3:phases.get(frontier);
    }
    public boolean current(TerrainProject p,int i){refresh(p);return sequence.forAction(i)!=null && sequence.forAction(i)==sequence.forAction(frontier);}
    private static boolean transferable(TerrainProject p){
        return p.regionOwner==null && !p.bridge && (p.publicGround || p.road || p.roadReclaim);
    }
    private String orderConflict(SettlementRecord s){
        // Inspect every owner: the primary cell index can hide a formal/region order.
        for(var at:steps.stream().map(TerrainProject.Step::position).distinct().toList())
            for(var old:s.terrain().projectsAt(at))if(!old.cancelled && !transferable(old)
                    && old.indices(at).stream().anyMatch(i->!old.abandoned.get(i)))
                return "正式建筑或独立工程占用："+old.name+" @ "+at.toShortString();
        return "";
    }
    private void transferPublicTargets(SettlementRecord s){
        var affected=new LinkedHashSet<TerrainProject>();
        for(var at:steps.stream().map(TerrainProject.Step::position).distinct().toList())
            for(var old:s.terrain().projectsAt(at))if(!old.cancelled && transferable(old)){
                for(int i:old.indices(at))if(!old.abandoned.get(i)){
                    // Only actual completed fills carry payment evidence. No blocks or cargo move here.
                    if(old.steps.get(i).fill() && old.finished.get(i))old.retiredPaid.set(i);
                    old.abandon(i);
                }
                old.groundSurfaces.remove(at);affected.add(old);s.terrain().cells.remove(at,old.id);
            }
        for(var old:affected){old.connectionWarning="重叠位置已转交玩家桥梁，范围外工程继续";s.terrain().index(old);}
    }
    public static BlueprintRecord publish(ServerLevel l,SettlementRecord s,PlayerBridgePlan plan){
        if(!plan.ready || !plan.problem.isEmpty())throw new IllegalStateException("Bridge survey incomplete");
        // Confirmation must compare every surveyed original cell; no mutations before all checks pass.
        var seen=new HashSet<BlockPos>();for(var step:plan.steps)if(seen.add(step.position())){
            var reason=plan.obstruction(l,s,step.position());
            if(!reason.isEmpty())throw new IllegalStateException(reason+"："+step.position().toShortString());
            if(!l.getBlockState(step.position()).equals(step.original()))throw new IllegalStateException("预览地形已改变："+step.position().toShortString()+"（原来 "+step.original()+"，现在 "+l.getBlockState(step.position())+"）");}
        var error=plan.orderConflict(s);if(!error.isEmpty())throw new IllegalStateException(error);
        plan.transferPublicTargets(s);
        var anchor=plan.at(plan.minRow(),plan.sideMin+plan.width/2,plan.surface-2);var marker=anchor.above(96);while(s.blueprintAt(marker)!=null)marker=marker.above();
        var b=s.addBlueprint(BuildingType.HOUSE,anchor,marker,Direction.NORTH,s.ownerId(),false,1,Map.of());
        var p=new TerrainProject(b.id(),plan.buildingModel?"玩家桥梁：桥面与自动支撑柱":"玩家桥梁：单岸连续施工",anchor,plan.steps);p.bridge=true;p.explicit=true;p.playerBridge=plan;
        p.bridgeBeams.addAll(plan.beams);p.bridgeAnchors.addAll(plan.anchors);p.bridgeDeck.addAll(plan.deck);p.bridgeRails.addAll(plan.rails);p.accessSurfaces.addAll(plan.walk);
        b.withTerrainProject(p);s.terrain().index(p);SettlementSavedData.get(l).touch();return b;
    }
}
