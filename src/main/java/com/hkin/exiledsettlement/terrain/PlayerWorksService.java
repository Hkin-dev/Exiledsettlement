package com.hkin.exiledsettlement.terrain;

import com.hkin.exiledsettlement.blueprint.*;
import com.hkin.exiledsettlement.building.*;
import com.hkin.exiledsettlement.decoration.*;
import com.hkin.exiledsettlement.network.*;
import com.hkin.exiledsettlement.road.*;
import com.hkin.exiledsettlement.settlement.*;
import net.minecraft.core.*;
import net.minecraft.server.level.*;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.*;
import net.neoforged.neoforge.network.PacketDistributor;
import java.util.*;

/** Player intent -> bounded read-only survey -> explicit confirmation -> saved real resident work. */
public final class PlayerWorksService {
    private static final Map<UUID,Selection> SELECTIONS=new HashMap<>();
    private static final class Selection {
        final UUID settlement,token=UUID.randomUUID(); final String mode; final ServerLevel level;
        final long expires; BlockPos first,second,min,max; long cursor,total; int excluded;
        boolean ready; AutoRoadState.Route bridge; PlayerBridgePlan plan;
        final List<TerrainProject.Step> steps=new ArrayList<>();
        final Set<String> modules=new LinkedHashSet<>();
        Selection(ServerPlayer player,SettlementRecord s,String mode){this(player.serverLevel(),s,mode);}
        Selection(ServerLevel level,SettlementRecord s,String mode){this.settlement=s.id();this.level=level;this.mode=mode;expires=level.getGameTime()+6000;}
    }
    public static void begin(ServerPlayer player,SettlementRecord s,String mode){
        if(!Set.of("bridge","clear_plants","clear_modules","clear_blocks").contains(mode) || !s.isMember(player.getUUID()))return;
        PlacementSessionService.cancel(player,false);
        SELECTIONS.put(player.getUUID(),new Selection(player,s,mode));
        PacketDistributor.sendToPlayer(player,new WorksSelectionPayload(s.corePosition(),mode,s.boundaryRadiusChunks()));
    }
    public static boolean active(ServerPlayer player){return SELECTIONS.containsKey(player.getUUID());}
    public static void cancel(ServerPlayer player){SELECTIONS.remove(player.getUUID());}
    public static void select(ServerPlayer player,BlockPos first,BlockPos second){
        var job=SELECTIONS.get(player.getUUID());if(job==null)return;
        var s=job.level==player.level()?SettlementSavedData.get(job.level).findById(job.settlement).orElse(null):null;
        if(s==null || !s.active() || !s.isMember(player.getUUID()) || !s.contains(player.blockPosition())
                || !s.contains(first) || !s.contains(second) || job.level.getGameTime()>job.expires){cancel(player);return;}
        // A new selection invalidates the previous confirmation token.
        job=new Selection(player,s,job.mode);SELECTIONS.put(player.getUUID(),job);
        job.first=first.immutable();job.second=second.immutable();
        int height=job.mode.equals("clear_plants")?16:8;
        job.min=new BlockPos(Math.min(first.getX(),second.getX()),Math.min(first.getY(),second.getY())+1,Math.min(first.getZ(),second.getZ()));
        job.max=new BlockPos(Math.max(first.getX(),second.getX()),Math.min(job.level.getMaxBuildHeight()-1,Math.max(first.getY(),second.getY())+height),Math.max(first.getZ(),second.getZ()));
        job.total=(long)(job.max.getX()-job.min.getX()+1)*(job.max.getZ()-job.min.getZ()+1)*(job.max.getY()-job.min.getY()+1);
        if(job.mode.equals("bridge")){
            message(player,"请使用矩形桥梁规划窗口确认桥面范围、方向和高度");cancel(player);return;
        }
        message(player,"正在分片勘察所选区域，完成后显示确认窗口；尚未安排施工");
    }
    public static void selectBridge(ServerPlayer player,PlanBridgePayload request){
        var old=SELECTIONS.get(player.getUUID());if(old==null || !old.mode.equals("bridge") || old.level!=player.level())return;
        var s=SettlementSavedData.get(old.level).findById(old.settlement).orElse(null);
        if(s==null || !s.active() || !s.isMember(player.getUUID()) || !s.contains(player.blockPosition()) || !s.contains(request.first()) || !s.contains(request.second()))return;
        var j=new Selection(player,s,"bridge");j.first=request.first();j.second=request.second();
        j.plan=new PlayerBridgePlan(j.first,j.second,request.alongX(),request.surface()==Integer.MIN_VALUE?Integer.MIN_VALUE:Math.clamp(request.surface(),old.level.getMinBuildHeight()+3,old.level.getMaxBuildHeight()-5));
        SELECTIONS.put(player.getUUID(),j);message(player,"正在勘察所选桥面并规划下方支撑柱，尚未发布施工任务");
    }
    private static void bridgePreview(ServerPlayer player,Selection j,SettlementRecord s){
        var p=j.plan;var rows=p.deck.stream().filter(at->(p.alongX?at.getZ():at.getX())==p.sideMin).toList();
        var cuts=p.steps.stream().filter(v->!v.fill()).map(TerrainProject.Step::position).distinct().toList();
        var supports=List.copyOf(p.anchors);
        for(int offset=0;offset<Math.max(1,Math.max(supports.size(),Math.max(rows.size(),cuts.size())));offset+=256)
            PacketDistributor.sendToPlayer(player,new BridgePreviewPayload(j.token,offset==0,p.alongX,p.width,rows.subList(Math.min(offset,rows.size()),Math.min(offset+256,rows.size())),cuts.subList(Math.min(offset,cuts.size()),Math.min(offset+256,cuts.size())),supports.subList(Math.min(offset,supports.size()),Math.min(offset+256,supports.size())),p.conflict==null?List.of():List.of(p.conflict),p.suggestedSurface));
        var text=new StringBuilder();
        if(!p.problem.isEmpty()){
            text.append("无法施工：").append(p.problem).append("\n").append(p.description());
            com.hkin.exiledsettlement.ExiledSettlementMod.LOGGER.info("PLAYER_BRIDGE_PREVIEW_REJECTED first={} second={} surface={} reason={}",p.first,p.second,p.surface,p.problem);
        }
        else {text.append(p.description()).append("\n材料（需要 / 仓库库存）：");for(var e:p.requirements().entrySet()){
            int available=SettlementInventoryService.warehouses(s).stream().filter(w->p.supplyingBank(w.position())).mapToInt(w->WarehouseInventoryService.countItem(j.level,w,e.getKey())).sum();
            text.append("\n").append(e.getKey().getDescription().getString()).append(" ").append(e.getValue()).append(" / ").append(available);}
            text.append("\n清理 ").append(cuts.size()).append(" 格。库存不足可以排队；橙色为清理，蓝色为桥面与梁，黄色为自动支撑柱。");}
        PacketDistributor.sendToPlayer(player,new WorksPreviewPayload(j.token,text.toString(),p.ready && p.problem.isEmpty()));
    }
    private static String surveyBridge(Selection j,SettlementRecord s){
        var a=j.first;var b=j.second;
        if(a.getX()!=b.getX() && a.getZ()!=b.getZ())return "桥梁两岸端点须沿同一条直线，请重新选择";
        if(a.equals(b) || Math.abs(a.getY()-b.getY())>1)return "请选择两个高差不超过一格的实际岸面；更大高差先规划岸边道路";
        if(!FarmEarthworkPlan.support(j.level,a) || !FarmEarthworkPlan.support(j.level,b))return "桥梁端点必须是两岸真实支撑面";
        int length=Math.abs(a.getX()-b.getX())+Math.abs(a.getZ()-b.getZ());
        int dx=Integer.signum(b.getX()-a.getX()),dz=Integer.signum(b.getZ()-a.getZ()),deckY=Math.max(a.getY(),b.getY())+1;
        var line=new ArrayList<BlockPos>();var cells=new LinkedHashSet<BlockPos>();boolean water=false;
        for(int i=0;i<=length;i++){
            int y=i==0?a.getY():i==length?b.getY():deckY;
            var p=new BlockPos(a.getX()+dx*i,y,a.getZ()+dz*i);line.add(p);
            for(int side=-1;side<=1;side++){
                var q=p.offset(dz*side,0,dx*side);
                if(!s.contains(q) || !j.level.hasChunkAt(q) || !RoadEarthworkService.permitted(j.level,s,q))return "桥梁走廊有未加载、保护或已有设施位置："+q.toShortString();
                if(i>0 && i<length && !BridgeService.candidate(j.level,s,q))return "桥跨需要连续水面和净空，受阻位置："+q.toShortString();
                water|=BridgeService.waterSpan(j.level,q);cells.add(q);
            }
        }
        if(!water)return "两岸之间没有适合架桥的水面，请改用道路规划";
        j.bridge=new AutoRoadState.Route(UUID.randomUUID(),b,List.copyOf(cells));
        j.bridge.centerline=List.copyOf(line);j.bridge.width=3;j.bridge.manualBridge=true;j.bridge.areaOptimized=true;
        return "";
    }
    public static OperationResult<AutoRoadState.Route> previewBridge(ServerLevel level,SettlementRecord s,BlockPos first,BlockPos second){
        var job=new Selection(level,s,"bridge");job.first=first;job.second=second;
        String error=surveyBridge(job,s);return error.isEmpty()?OperationResult.success(job.bridge):OperationResult.failure(error);
    }
    public static void tick(ServerLevel level){
        int budget=512;
        for(var entry:List.copyOf(SELECTIONS.entrySet())){
            var j=entry.getValue();if(j.level!=level)continue;
            var player=level.getServer().getPlayerList().getPlayer(entry.getKey());
            if(player==null || player.level()!=level || level.getGameTime()>j.expires){SELECTIONS.remove(entry.getKey());continue;}
            if(j.first==null || j.ready)continue;
            var s=SettlementSavedData.get(level).findById(j.settlement).orElse(null);
            if(s==null || !s.active() || !s.isMember(player.getUUID())){cancel(player);continue;}
            if(j.plan!=null){j.plan.survey(level,s,32);if(j.plan.ready || !j.plan.problem.isEmpty()){j.ready=j.plan.ready;bridgePreview(player,j,s);if(!j.plan.ready)j.first=null;}continue;}
            long width=(long)j.max.getX()-j.min.getX()+1,depth=(long)j.max.getZ()-j.min.getZ()+1;
            while(budget>0 && j.cursor<j.total){
                long n=j.cursor++;budget--;
                // Top down: clearing foliage or loose structures never starts by cutting supporting ground.
                var p=new BlockPos((int)(j.min.getX()+n%width),(int)(j.max.getY()-n/(width*depth)),(int)(j.min.getZ()+n/width%depth));
                if(!level.hasChunkAt(p)){message(player,"选区有未加载位置，请靠近后重新选择："+p.toShortString());cancel(player);break;}
                var state=level.getBlockState(p);if(state.isAir())continue;
                var feature=moduleAt(s,p);
                if(j.mode.equals("clear_modules")){
                    if(feature!=null && completeModuleInside(j,feature))j.modules.add(featureKey(s,feature));
                    else j.excluded++;
                }else if(clearable(j,s,p) && pairedPartSafe(j,s,p,state)){
                    j.steps.add(new TerrainProject.Step(p,Blocks.AIR.defaultBlockState(),state));
                }else j.excluded++;
            }
            if(SELECTIONS.get(entry.getKey())==j && j.cursor>=j.total){j.ready=true;preview(player,j);}
            if(budget<=0)break;
        }
    }
    private static boolean vegetation(net.minecraft.world.level.block.state.BlockState state){
        return state.is(BlockTags.LOGS) || state.is(BlockTags.LEAVES) || state.is(BlockTags.FLOWERS)
                || state.getBlock() instanceof BushBlock && !(state.getBlock() instanceof CropBlock)
                && !(state.getBlock() instanceof StemBlock) && !(state.getBlock() instanceof AttachedStemBlock);
    }
    private static boolean clearable(Selection j,SettlementRecord s,BlockPos p){
        var state=j.level.getBlockState(p);
        return !protectedAt(s,p) && !RoadSpaceData.get(j.level).contains(p) && state.getFluidState().isEmpty()
                && state.getDestroySpeed(j.level,p)>=0 && (j.mode.equals("clear_blocks") || vegetation(state));
    }
    private static boolean pairedPartSafe(Selection j,SettlementRecord s,BlockPos p,net.minecraft.world.level.block.state.BlockState state){
        BlockPos other=null;
        if(state.getBlock() instanceof DoorBlock || state.getBlock() instanceof DoublePlantBlock)
            other=state.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.DOUBLE_BLOCK_HALF)==net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER?p.above():p.below();
        else if(state.getBlock() instanceof BedBlock)
            other=p.relative(state.getValue(BedBlock.PART)==net.minecraft.world.level.block.state.properties.BedPart.FOOT?state.getValue(BedBlock.FACING):state.getValue(BedBlock.FACING).getOpposite());
        if(other==null || !j.level.getBlockState(other).is(state.getBlock()))return true;
        return other.getX()>=j.min.getX() && other.getX()<=j.max.getX() && other.getY()>=j.min.getY() && other.getY()<=j.max.getY()
                && other.getZ()>=j.min.getZ() && other.getZ()<=j.max.getZ() && clearable(j,s,other);
    }
    public static boolean protectedAt(SettlementRecord s,BlockPos p){
        return protectedAt(s,p,null);
    }
    /** Only the owning mine's exact next saved excavation cell can open its own shell.
     * All existing settlement, blueprint, road, bridge and terrain protections remain identical.
     * Other buildings, including ones overlapping that cell, are never exempted. */
    public static boolean protectedForMineAdvance(SettlementRecord s,BuildingRecord mine,BlockPos p){
        var next=MineExpansionService.next(mine);
        if(s.building(mine.id())!=mine || !MineExpansionService.enabled(mine) || next==null
                || !MineExpansionService.world(mine,next.local()).equals(p))return true;
        return protectedAt(s,p,mine);
    }
    private static boolean protectedAt(SettlementRecord s,BlockPos p,BuildingRecord openingMine){
        if(!s.contains(p) || p.equals(s.corePosition()) || s.community().danger(p) || s.decorations().forbidden(p))return true;
        if(s.buildings().stream().anyMatch(b->b!=openingMine && b.active() && b.contains(p)))return true;
        if(s.blueprints().stream().anyMatch(b->!b.cancellationQueued() && b.terrainProject()==null && b.containsColumn(p)))return true;
        if(s.terrain().projectsAt(p).stream().anyMatch(b->!b.cancelled && !b.areaClearance))return true;
        return BridgeService.column(s,p) || s.autoRoads().routes.values().stream().anyMatch(r->!r.suppressed && r.cellInColumn(p)!=null);
    }
    private static DecorationState.Feature moduleAt(SettlementRecord s,BlockPos p){
        for(var f:s.decorations().features.values()){
            var t=BlueprintTemplates.create(f.type,f.anchor,f.facing,f.version);
            if(p.getX()>=t.minCorner().getX() && p.getX()<=t.maxCorner().getX() && p.getZ()>=t.minCorner().getZ() && p.getZ()<=t.maxCorner().getZ())return f;
        }return null;
    }
    private static String featureKey(SettlementRecord s,DecorationState.Feature f){return s.decorations().features.entrySet().stream().filter(e->e.getValue()==f).findFirst().orElseThrow().getKey();}
    private static boolean completeModuleInside(Selection j,DecorationState.Feature f){
        var t=BlueprintTemplates.create(f.type,f.anchor,f.facing,f.version);
        return t.minCorner().getX()>=j.min.getX() && t.maxCorner().getX()<=j.max.getX() && t.minCorner().getZ()>=j.min.getZ() && t.maxCorner().getZ()<=j.max.getZ();
    }
    private static void preview(ServerPlayer player,Selection j){
        String text=j.mode.equals("bridge")?"三格通行桥面，两侧护栏；共 "+j.bridge.centerline.size()+" 格长。由居民从真实岸基逐段取料施工，保留河道。"
            :"范围 "+j.min.toShortString()+" 至 "+j.max.toShortString()+"；清理 "+j.steps.size()+" 个方块、"+j.modules.size()+" 个完整模块；排除 "+j.excluded+" 个位置。正式建筑、道路、桥梁、农业、流体及保护区域保留。不挖平地面；居民到场清理并收集掉落。";
        PacketDistributor.sendToPlayer(player,new WorksPreviewPayload(j.token,text));
    }
    public static void confirm(ServerPlayer player,UUID token,boolean accept){
        var j=SELECTIONS.get(player.getUUID());if(j==null || !j.token.equals(token))return;
        if(j.plan!=null){
            if(!accept){cancel(player);return;}
            var s=SettlementSavedData.get(j.level).findById(j.settlement).orElse(null);
            if(!j.ready || j.level.getGameTime()>j.expires || s==null || !s.active() || !s.isMember(player.getUUID()) || player.level()!=j.level || !s.contains(player.blockPosition()))return;
            try{PlayerBridgePlan.publish(j.level,s,j.plan);cancel(player);PacketDistributor.sendToPlayer(player,new ClearPlacementPayload());message(player,"独立桥梁工程已登记：工人从首个选点所在岸取料、逐排施工");}
            catch(IllegalStateException ex){j.plan.problem=ex.getMessage();bridgePreview(player,j,s);}return;
        }
        cancel(player);if(!accept || !j.ready || player.level()!=j.level || j.level.getGameTime()>j.expires)return;
        var data=SettlementSavedData.get(j.level);var s=data.findById(j.settlement).orElse(null);
        if(s==null || !s.active() || !s.isMember(player.getUUID()) || !s.contains(player.blockPosition()))return;
        if(!j.mode.equals("bridge")){
            var steps=j.steps.stream().filter(step->!protectedAt(s,step.position())
                    && j.level.getBlockState(step.position()).equals(step.original())
                    && pairedPartSafe(j,s,step.position(),step.original())
                    && !RoadSpaceData.get(j.level).contains(step.position())).toList();
            if(!steps.isEmpty()){
                var marker=j.min.above(96);while(s.blueprintAt(marker)!=null)marker=marker.above();
                var site=s.addBlueprint(BuildingType.HOUSE,j.min,marker,Direction.NORTH,player.getUUID(),false,1,Map.of());
                var project=new TerrainProject(site.id(),"玩家区域清理",j.min,steps);project.explicit=true;project.areaClearance=true;
                site.withTerrainProject(project);s.terrain().index(project);
            }
            for(var key:j.modules){var f=s.decorations().features.get(key);if(f==null || !completeModuleInside(j,f))continue;
                f.suppressed=true;var b=s.building(f.building);if(b!=null)b.queueDemolition();
                var site=s.blueprint(f.blueprint);if(site!=null)site.queueCancellation();
            }
            message(player,"已安排区域清理："+steps.size()+" 格、"+j.modules.size()+" 个模块；可撤销附近整地停止待做任务");
        }
        data.touch();PacketDistributor.sendToPlayer(player,new ClearPlacementPayload());
    }
    private static void message(ServerPlayer player,String text){player.displayClientMessage(Component.literal(text),false);}
    private PlayerWorksService(){}
}
