package com.hkin.exiledsettlement.terrain;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.world.level.block.state.BlockState;
import java.util.*;

/** Public terrain persists independently of the building that requested it. */
public final class TerrainState {
    public boolean enabled=true,publicPaused;
    public int surveyCursor;
    public String status="等待居民修整居住区";
    public final PublicGroundService.State publicGround=new PublicGroundService.State();
    public final Map<UUID,TerrainProject> projects=new LinkedHashMap<>();
    private final Set<UUID> playerBridgeIds=new LinkedHashSet<>();
    /** Rebuilt by index/load; historic soil orders do not enter every bridge-road probe. */
    public List<TerrainProject> playerBridges(){return playerBridgeIds.stream().map(projects::get)
            .filter(p->p!=null&&p.playerBridge!=null&&!p.cancelled).toList();}
    public final Set<UUID> surveyed=new LinkedHashSet<>();
    public final Set<Long> suppressed=new LinkedHashSet<>();
    public final Map<BlockPos,UUID> cells=new HashMap<>();
    private final Set<UUID> unfinishedProjects=new LinkedHashSet<>();
    private void workChanged(TerrainProject p){
        if(projects.get(p.id)!=p)return;
        if(!p.cancelled && !p.completed && !p.done())unfinishedProjects.add(p.id);else unfinishedProjects.remove(p.id);
    }
    /** Live frontier only; completed history does not enter admission scans. */
    public List<TerrainProject> unfinishedProjects(){return unfinishedProjects.stream().map(projects::get)
            .filter(p->p!=null && !p.cancelled && !p.completed && !p.done()).toList();}
    private final Map<BlockPos,Set<UUID>> projectsByPosition=new HashMap<>();
    private final Map<Long,Set<UUID>> projectsByColumn=new HashMap<>();
    private final Map<UUID,Set<UUID>> projectsByDependency=new HashMap<>();
    private final Map<UUID,Set<UUID>> indexedDependencies=new HashMap<>();
    private final Map<UUID,List<TerrainProject>> dependencySnapshots=new HashMap<>();
    private final Set<UUID> legacyFoundationIds=new LinkedHashSet<>();
    private final Map<UUID,List<TerrainProject.Step>> indexedSteps=new HashMap<>();
    private final Set<BlockPos> connectionMembership=new HashSet<>();
    private final List<BlockPos> connectionPositions=new ArrayList<>();
    private int connectionCursor;
    private void trackConnection(BlockPos at,BlockState state){
        if(com.hkin.exiledsettlement.blueprint.BlueprintBlockStateResolver.connectionState(state)
                && connectionMembership.add(at))connectionPositions.add(at.immutable());
    }
    /** Derived, bounded maintenance for paid terrain rails, including completed and retired history. */
    public void refreshPaidConnections(net.minecraft.server.level.ServerLevel level){
        long deadline=System.nanoTime()+500_000L;
        for(int n=0;n<Math.min(32,connectionPositions.size()) && (n==0 || System.nanoTime()<deadline);n++){
            if(connectionCursor>=connectionPositions.size())connectionCursor=0;
            var at=connectionPositions.get(connectionCursor++);
            if(!level.hasChunkAt(at))continue;
            var current=level.getBlockState(at);
            if(!com.hkin.exiledsettlement.blueprint.BlueprintBlockStateResolver.connectionState(current))continue;
            var evidence=supersededPaid.get(at);
            boolean paid=evidence!=null && evidence.getBlock()==current.getBlock();
            if(!paid)paid=projectsAt(at).stream().anyMatch(p->p.indices(at).stream().anyMatch(i->{
                var step=p.steps.get(i);
                return step.target().getBlock()==current.getBlock() && step.fill()
                        && (p.retiredPaid.get(i) || !p.abandoned.get(i) && (i<p.cursor || p.finished.get(i)));
            }));
            if(paid)com.hkin.exiledsettlement.blueprint.BlueprintBlockStateResolver.refreshAround(level,at);
        }
    }
    private void indexPositions(TerrainProject project){
        if(projects.get(project.id)!=project)return;
        var before=indexedSteps.put(project.id,project.steps);
        if(before==project.steps)return;
        if(before!=null)for(var step:before){var owners=projectsByPosition.get(step.position());if(owners!=null){owners.remove(project.id);if(owners.isEmpty())projectsByPosition.remove(step.position());}
            long column=BlockPos.asLong(step.position().getX(),0,step.position().getZ());var columns=projectsByColumn.get(column);if(columns!=null){columns.remove(project.id);if(columns.isEmpty())projectsByColumn.remove(column);}}
        for(var step:project.steps){trackConnection(step.position(),step.target());projectsByPosition.computeIfAbsent(step.position(),k->new LinkedHashSet<>()).add(project.id);
            projectsByColumn.computeIfAbsent(BlockPos.asLong(step.position().getX(),0,step.position().getZ()),k->new LinkedHashSet<>()).add(project.id);}
    }
    public List<TerrainProject> projectsInColumn(BlockPos at){return projectsByColumn.getOrDefault(BlockPos.asLong(at.getX(),0,at.getZ()),Set.of()).stream().map(projects::get).filter(Objects::nonNull).toList();}
    /** All overlapping historical candidates; callers still check current status/evidence. */
    public List<TerrainProject> projectsAt(BlockPos at){
        var ids=projectsByPosition.get(at);if(ids==null)return List.of();
        return ids.stream().map(projects::get).filter(p->p!=null && !p.indices(at).isEmpty()).toList();
    }
    public List<TerrainProject> dependingOn(UUID id){
        return dependencySnapshots.computeIfAbsent(id,key->{
            var ids=projectsByDependency.get(key);if(ids==null)return List.of();
            // Membership is maintained synchronously by ProjectDependencies.
            // Keep live project objects so completion/cancellation is observed
            // immediately without rebuilding a historical list at every action.
            return ids.stream().map(projects::get).filter(Objects::nonNull).toList();
        });
    }
    public List<TerrainProject> legacyFoundations(){
        return legacyFoundationIds.stream().map(projects::get).filter(p->p!=null && !p.cancelled && !p.road && !p.publicGround
                && p.layoutVersion>0 && p.layoutVersion<TerrainService.LAYOUT_VERSION).toList();
    }
    private void dependenciesChanged(TerrainProject p){
        if(projects.get(p.id)!=p)return;
        var before=indexedDependencies.getOrDefault(p.id,Set.of());
        before.forEach(dependencySnapshots::remove);p.dependencies.forEach(dependencySnapshots::remove);
        for(var id:before)if(!p.dependencies.contains(id)){
            var owners=projectsByDependency.get(id);if(owners!=null){owners.remove(p.id);if(owners.isEmpty())projectsByDependency.remove(id);}
        }
        for(var id:p.dependencies)if(!before.contains(id))projectsByDependency.computeIfAbsent(id,k->new LinkedHashSet<>()).add(p.id);
        indexedDependencies.put(p.id,new HashSet<>(p.dependencies));
    }
    // Derived on publish/load, never saved. Shared headroom can obscure a deck
    // in cells, so walking surfaces need their own overlap-preserving index.
    private final Map<BlockPos,Set<UUID>> bridgeSurfaces=new HashMap<>();
    private final Map<Long,Set<UUID>> bridgeColumns=new HashMap<>();
    public TerrainProject bridgeSurface(BlockPos at) {
        for(var id:bridgeSurfaces.getOrDefault(at,Set.of())) {
            var p=projects.get(id);
            if(p!=null && p.bridge && (!p.cancelled || p.completed) && p.accessSurfaces.contains(at))return p;
        }
        return null;
    }
    public boolean bridgeColumn(BlockPos at) {
        for(var id:bridgeColumns.getOrDefault(BlockPos.asLong(at.getX(),0,at.getZ()),Set.of())) {
            var p=projects.get(id);
            if(p!=null && p.bridge && (!p.cancelled || p.completed)
                    && p.bridgeDeck.stream().anyMatch(q->q.getX()==at.getX() && q.getZ()==at.getZ()))return true;
        }
        return false;
    }
    public final Map<UUID,String> blockers=new LinkedHashMap<>();
    public final Map<UUID,CompoundTag> surveys=new LinkedHashMap<>();
    public final Map<UUID,java.util.function.Supplier<CompoundTag>> surveySnapshots=new HashMap<>();
    public final Map<Long,Integer> platformPlanes=new LinkedHashMap<>();
    private record GroundPlane(UUID project,BlockPos position) { }
    private final Map<Long,GroundPlane> publicPlanes=new HashMap<>();
    final Map<Long,Map<Integer,BlockState>> supersededOriginals=new HashMap<>();
    private final Map<BlockPos,BlockState> supersededPaid=new HashMap<>();
    private final Map<BlockPos,BlockState> escapeSupports=new LinkedHashMap<>();
    public void recordEscapeSupport(BlockPos at,BlockState state){escapeSupports.put(at.immutable(),state);}
    public boolean escapeSupport(BlockPos at,BlockState current){return current.equals(escapeSupports.get(at));}
    private Map<Long,List<BlockPos>> supersededColumns;
    public boolean supersededPaid(BlockPos at,BlockState current) {
        var target=supersededPaid.get(at);return target!=null && TerrainService.satisfied(current,target);
    }
    public boolean registeredPublicPaid(BlockPos at,BlockState current){
        return projectsAt(at).stream().filter(p->!p.cancelled && p.publicGround && !p.roadReclaim)
                .anyMatch(p->p.indices(at).stream().anyMatch(i->p.steps.get(i).fill() && p.finished.get(i)
                    && !p.abandoned.get(i) && TerrainService.satisfied(current,p.steps.get(i).target())));
    }
    /** A paid fill existed here; its material may change in a later shared plan. */
    public boolean paidFillEvidence(BlockPos at){return supersededPaid.containsKey(at);}
    public Set<BlockPos> supersededPositions(){return Set.copyOf(supersededPaid.keySet());}
    public List<BlockPos> supersededColumn(BlockPos at){
        if(supersededColumns==null){supersededColumns=new HashMap<>();for(var p:supersededPaid.keySet())
            supersededColumns.computeIfAbsent(BlockPos.asLong(p.getX(),0,p.getZ()),k->new ArrayList<>()).add(p);}
        return supersededColumns.getOrDefault(BlockPos.asLong(at.getX(),0,at.getZ()),List.of());
    }
    public boolean supersededChanged(BlockPos at,BlockState current) {
        var target=supersededPaid.get(at);return target!=null && !current.isAir() && !TerrainService.satisfied(current,target) && !currentPaid(at,current);
    }
    private boolean currentPaid(BlockPos at,BlockState current){
        var project=projects.get(cells.get(at));if(project==null || project.cancelled)return false;
        var goal=project.template().placementAt(at);if(goal==null || !TerrainService.satisfied(current,goal.state()))return false;
        return project.indices(at).stream().anyMatch(i->!project.abandoned.get(i) && project.steps.get(i).fill() && (i<project.cursor || project.finished.get(i)));
    }
    /** Derived from frozen projects, including after loading; cancellation removes authority. */
    public BlockPos publicPlane(BlockPos column) {
        var plane=publicPlanes.get(BlockPos.asLong(column.getX(),0,column.getZ()));
        if(plane==null)return null;
        var project=projects.get(plane.project());
        return project!=null && !project.cancelled && !project.heightSuperseded
                && project.publicGroundVersion>=PublicGroundService.HEIGHT_VERSION
                && project.groundSurfaces.contains(plane.position())?plane.position():null;
    }
    public void index(TerrainProject project) {
        projects.put(project.id,project);
        if(project.playerBridge!=null)playerBridgeIds.add(project.id);else playerBridgeIds.remove(project.id);
        // Earlier column retirement marked an entire multi-column carrier obsolete.
        // Other saved, unfinished columns remain committed work, without changing any step.
        if(project.publicGround && project.heightSuperseded && !project.cancelled && !project.done()
                && project.publicGroundVersion>=PublicGroundService.HEIGHT_VERSION
                && project.hasUnfinishedGroundColumn())project.heightSuperseded=false;
        project.workChanged=()->workChanged(project);workChanged(project);
        if(!project.cancelled && !project.road && !project.publicGround && project.layoutVersion>0
                && project.layoutVersion<TerrainService.LAYOUT_VERSION)legacyFoundationIds.add(project.id);
        else legacyFoundationIds.remove(project.id);
        project.geometryChanged=()->{indexPositions(project);workChanged(project);};indexPositions(project);
        ((ProjectDependencies)project.dependencies).changed=()->dependenciesChanged(project);
        dependenciesChanged(project);
        if(project.bridge) {
            for(var at:project.accessSurfaces)bridgeSurfaces.computeIfAbsent(at,k->new LinkedHashSet<>()).add(project.id);
            for(var at:project.bridgeDeck)bridgeColumns.computeIfAbsent(BlockPos.asLong(at.getX(),0,at.getZ()),k->new LinkedHashSet<>()).add(project.id);
        }
        if(project.heightSuperseded || project.publicGround)for(int i=0;i<project.steps.size();i++){
            var step=project.steps.get(i);var at=step.position();
            supersededOriginals.computeIfAbsent(BlockPos.asLong(at.getX(),0,at.getZ()),k->new HashMap<>()).putIfAbsent(at.getY(),step.original());
            if(step.fill() && (project.retiredPaid.get(i) || project.heightSuperseded && !project.abandoned.get(i) && (i<project.cursor || project.finished.get(i)))){supersededPaid.put(at,step.target());supersededColumns=null;}
        }
        if(project.publicGround && !project.cancelled)for(var p:project.groundSurfaces)publicPlanes.put(BlockPos.asLong(p.getX(),0,p.getZ()),new GroundPlane(project.id,p));
        if(!project.cancelled)for(int i=0;i<project.steps.size();i++){
            if(project.abandoned.get(i))continue;
            var step=project.steps.get(i);var previous=projects.get(cells.get(step.position()));
            if(previous==null || previous.completed || previous.cancelled || previous.template().placementAt(step.position())==null)
                cells.put(step.position(),project.id);
        }
        else for(int i=0;i<project.cursor;i++)if(project.steps.get(i).fill())cells.putIfAbsent(project.steps.get(i).position(),project.id);
    }
    public CompoundTag save() {
        var t=new CompoundTag();t.putInt("DefaultOnVersion",1);t.putBoolean("Enabled",enabled);t.putBoolean("PublicPaused",publicPaused);t.putInt("SurveyCursor",surveyCursor);t.putString("Status",status);t.put("PublicGround",publicGround.save());
        var evidence=new ListTag();supersededPaid.forEach((at,state)->{var a=new CompoundTag();a.putLong("At",at.asLong());a.put("State",NbtUtils.writeBlockState(state));evidence.add(a);});t.put("SupersededPaidEvidence",evidence);
        var escape=new ListTag();escapeSupports.forEach((at,state)->{var a=new CompoundTag();a.putLong("At",at.asLong());a.put("State",NbtUtils.writeBlockState(state));escape.add(a);});t.put("EscapeSupports",escape);
        TerrainProjectPages.save(t,projects.values());
        var pending=new ListTag();surveys.forEach((id,tag)->pending.add(surveySnapshots.containsKey(id)?surveySnapshots.get(id).get():tag.copy()));t.put("Surveys",pending);
        var ids=new ListTag();surveyed.forEach(id->{var a=new CompoundTag();a.putUUID("Id",id);ids.add(a);});t.put("Surveyed",ids);
        t.putLongArray("Suppressed",suppressed.stream().mapToLong(Long::longValue).toArray());
        var planes=new ListTag();platformPlanes.forEach((column,y)->{var a=new CompoundTag();a.putLong("Column",column);a.putInt("Y",y);planes.add(a);});t.put("PlatformPlanes",planes);return t;
    }
    public void load(CompoundTag t) {
        if(t.contains("PublicGround"))publicGround.load(t.getCompound("PublicGround"));
        enabled=!t.contains("DefaultOnVersion") || t.getBoolean("Enabled");publicPaused=t.contains("DefaultOnVersion") && t.getBoolean("PublicPaused");surveyCursor=t.getInt("SurveyCursor");if(t.contains("Status"))status=t.getString("Status");
        TerrainProjectPages.visit(t,record->index(TerrainProject.load(record)));
        var evidence=t.getList("SupersededPaidEvidence",Tag.TAG_COMPOUND);for(int i=0;i<evidence.size();i++){var a=evidence.getCompound(i);supersededPaid.putIfAbsent(BlockPos.of(a.getLong("At")),NbtUtils.readBlockState(net.minecraft.core.registries.BuiltInRegistries.BLOCK.asLookup(),a.getCompound("State")));}supersededColumns=null;
        supersededPaid.forEach(this::trackConnection);
        escapeSupports.clear();var escape=t.getList("EscapeSupports",Tag.TAG_COMPOUND);for(int i=0;i<escape.size();i++){var a=escape.getCompound(i);escapeSupports.put(BlockPos.of(a.getLong("At")),NbtUtils.readBlockState(net.minecraft.core.registries.BuiltInRegistries.BLOCK.asLookup(),a.getCompound("State")));}
        var pending=t.getList("Surveys",Tag.TAG_COMPOUND);for(int i=0;i<pending.size();i++){var a=pending.getCompound(i);surveys.put(a.getUUID("Dependency"),a);}
        var ids=t.getList("Surveyed",Tag.TAG_COMPOUND);for(int i=0;i<ids.size();i++)surveyed.add(ids.getCompound(i).getUUID("Id"));
        for(long at:t.getLongArray("Suppressed"))suppressed.add(at);
        var planes=t.getList("PlatformPlanes",Tag.TAG_COMPOUND);for(int i=0;i<planes.size();i++){var a=planes.getCompound(i);platformPlanes.put(a.getLong("Column"),a.getInt("Y"));}
    }
}
