package com.hkin.exiledsettlement.citizen;

import net.minecraft.core.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import java.util.*;

/** Read-only, bounded tree identity shared by planning and real felling. No entity or loot effects. */
public final class TreeSurvey {
    private TreeSurvey() {}
    private static final int LIMIT=4096;
    public enum Kind { TREE, ORDINARY, PENDING, BLOCKED }
    public record Result(Kind kind, BlockPos root, Map<BlockPos,BlockState> parts, String reason) {
        public boolean tree(){return kind==Kind.TREE;}
    }
    private static Result result(Kind kind,String reason){return new Result(kind,null,Map.of(),reason);}
    private static final Map<ServerLevel,Cache> CACHES=new WeakHashMap<>();
    private static final class Cache {
        final Map<BlockPos,Scan> scans=new LinkedHashMap<>();
        long tick=Long.MIN_VALUE; int budget;
    }
    public static boolean leaf(BlockState s){return s.is(BlockTags.LEAVES) && (!s.hasProperty(LeavesBlock.PERSISTENT) || !s.getValue(LeavesBlock.PERSISTENT));}
    private static boolean rootWood(BlockState s){return s.is(Blocks.MANGROVE_ROOTS)||s.is(Blocks.MUDDY_MANGROVE_ROOTS);}
    public static boolean candidate(BlockState s){return s.is(BlockTags.LOGS)||leaf(s)||rootWood(s);}
    private static boolean wood(BlockState s){return candidate(s)&&!leaf(s);}
    private static boolean sameWood(BlockState a,BlockState b){
        if(rootWood(a)||rootWood(b))return (rootWood(a)||a.is(Blocks.MANGROVE_LOG))&&(rootWood(b)||b.is(Blocks.MANGROVE_LOG));
        return a.getBlock()==b.getBlock();
    }
    /** Planning can yield; execution always requests a fresh complete survey. Cache never grants permission. */
    public static Result inspect(ServerLevel level,BlockPos at){
        return query(level,at,false);
    }
    public static Result prepare(ServerLevel level,BlockPos at){return query(level,at,true);}
    private static Result query(ServerLevel level,BlockPos at,boolean sliced){
        long start=TreeWorkMetrics.ENABLED?System.nanoTime():0;
        try{return queryWork(level,at,sliced);}finally{TreeWorkMetrics.record(sliced?"survey_sliced":"survey_cached",start);}
    }
    private static Result queryWork(ServerLevel level,BlockPos at,boolean sliced){
        if(!level.hasChunkAt(at))return result(Kind.BLOCKED,"BLUEPRINT_AREA_NOT_LOADED");
        if(!candidate(level.getBlockState(at)))return result(Kind.ORDINARY,"");
        var cache=CACHES.computeIfAbsent(level,k->new Cache());
        if(cache.tick!=level.getGameTime()){cache.tick=level.getGameTime();cache.budget=256;}
        var scan=cache.scans.get(at);
        if(scan!=null && scan.done!=null && (scan.done.kind()!=Kind.TREE && level.getGameTime()-scan.completedAt>40 || !scan.valid(level))) {var old=scan;cache.scans.values().removeIf(s->s==old);scan=null;}
        if(scan==null){
            if(cache.scans.size()>32768)cache.scans.clear();
            scan=new Scan(at);cache.scans.put(at.immutable(),scan);
        }
        if(sliced){int n=Math.min(128,cache.budget);cache.budget-=scan.advance(level,n);}
        else while(scan.done==null)scan.advance(level,256);
        if(scan.done==null)return result(Kind.PENDING,"TREE_SURVEY_PENDING");
        if(scan.completedAt==Long.MIN_VALUE)scan.completedAt=level.getGameTime();
        var answer=scan.done;
        if(answer.tree())for(var p:answer.parts().keySet())cache.scans.put(p,scan);
        return answer.tree()&&!answer.parts().containsKey(at)?result(Kind.BLOCKED,"TREE_IDENTITY_UNCERTAIN"):answer;
    }
    public static Result fresh(ServerLevel level,BlockPos at){
        long start=TreeWorkMetrics.ENABLED?System.nanoTime():0;
        try{var scan=new Scan(at);while(scan.done==null)scan.advance(level,256);return scan.done;}
        finally{TreeWorkMetrics.record("survey_fresh",start);}
    }
    /** Prefetch one search frontier before the road search removes that node from its queue. */
    public static boolean prepareArea(ServerLevel level,BlockPos center){
        return prepareArea(level,center,p->false);
    }
    public static boolean prepareArea(ServerLevel level,BlockPos center,java.util.function.Predicate<BlockPos> registeredStructure){
        for(int x=-2;x<=2;x++)for(int z=-2;z<=2;z++)for(int y=-1;y<=5;y++){
            var p=center.offset(x,y,z);
            if(level.hasChunkAt(p)&&candidate(level.getBlockState(p))&&!registeredStructure.test(p)&&prepare(level,p).kind()==Kind.PENDING)return false;
        }return true;
    }
    public static Set<BlockPos> claimed(ServerLevel level,Set<BlockPos> positions){
        var expanded=new HashSet<>(positions);
        for(var p:positions){if(expanded.size()>32768)break;var tree=inspect(level,p);if(tree.tree())expanded.addAll(tree.parts().keySet());}
        return expanded;
    }
    public static void invalidate(ServerLevel level){CACHES.remove(level);}

    private static final class Scan {
        final BlockPos at;
        final Map<BlockPos,BlockState> observed=new LinkedHashMap<>();
        final Set<BlockPos> visited=new HashSet<>(),logs=new LinkedHashSet<>();
        final Map<BlockPos,Integer> distance=new HashMap<>(),halo=new HashMap<>(),other=new HashMap<>();
        final ArrayDeque<BlockPos> queue=new ArrayDeque<>();
        BlockState family; BlockPos root,seed; int stage; long validated=Long.MIN_VALUE;
        Result done;long completedAt=Long.MIN_VALUE;
        Scan(BlockPos p){at=p.immutable();queue.add(at);visited.add(at);distance.put(at,0);}
        BlockState read(ServerLevel l,BlockPos p){
            if(l.isOutsideBuildHeight(p))return Blocks.AIR.defaultBlockState();
            if(!l.hasChunkAt(p)){done=result(Kind.BLOCKED,"BLUEPRINT_AREA_NOT_LOADED");return Blocks.AIR.defaultBlockState();}
            var state=l.getBlockState(p);observed.putIfAbsent(p.immutable(),state);return state;
        }
        boolean valid(ServerLevel l){
            if(validated==l.getGameTime())return true;
            for(var e:observed.entrySet())if(!l.hasChunkAt(e.getKey())||!l.getBlockState(e.getKey()).equals(e.getValue()))return false;
            validated=l.getGameTime();return true;
        }
        int advance(ServerLevel l,int budget){
            int used=0;
            while(done==null && used<budget){
                used++;
                if(queue.isEmpty()){next(l);continue;}
                var p=queue.removeFirst();var state=read(l,p);if(done!=null)break;
                if(stage==0){
                    if(wood(state)){family=state;seed=p;stage=1;queue.clear();visited.clear();visited.add(p);queue.add(p);distance.clear();continue;}
                    if(!leaf(state)){done=result(Kind.ORDINARY,"");continue;}
                    int d=distance.get(p);if(d>=7)continue;
                    for(var dir:Direction.values()){var n=p.relative(dir);var s=read(l,n);
                        if((wood(s)||leaf(s))&&visited.add(n)){distance.put(n,d+1);queue.add(n);}}
                    if(visited.size()>LIMIT)done=result(Kind.BLOCKED,"FORESTRY_TREE_TOO_LARGE");
                }else if(stage==1){
                    if(!wood(state)||!sameWood(state,family))continue;
                    logs.add(p);
                    if(logs.size()>LIMIT){done=result(Kind.BLOCKED,"FORESTRY_TREE_TOO_LARGE");continue;}
                    for(int x=-1;x<=1;x++)for(int y=-1;y<=1;y++)for(int z=-1;z<=1;z++){
                        var n=p.offset(x,y,z);if(visited.add(n))queue.add(n);
                    }
                }else if(stage==2){
                    int d=distance.get(p)+1;if(d>7)continue;
                    for(var dir:Direction.values()){var n=p.relative(dir);if(distance.containsKey(n))continue;
                        if(leaf(read(l,n))){distance.put(n,d);queue.add(n);}}
                    if(distance.size()-logs.size()>LIMIT)done=result(Kind.BLOCKED,"FORESTRY_TREE_TOO_LARGE");
                }else if(stage==3){
                    int d=halo.get(p)+1;
                    for(var dir:Direction.values()){var n=p.relative(dir);if(logs.contains(n))continue;var s=read(l,n);
                        if(wood(s))other.put(p,1);
                        else if(d<=6&&leaf(s)&&!halo.containsKey(n)){halo.put(n,d);queue.add(n);}}
                    if(halo.size()>LIMIT)done=result(Kind.BLOCKED,"FORESTRY_TREE_TOO_LARGE");
                }else if(stage==4){
                    int d=other.get(p)+1;if(d>7)continue;
                    for(var dir:Direction.values()){var n=p.relative(dir);
                        if(halo.containsKey(n)&&d<other.getOrDefault(n,8)){other.put(n,d);queue.add(n);}}
                }
            }
            return used;
        }
        void next(ServerLevel l){
            if(stage==0){done=result(Kind.ORDINARY,"");return;}
            if(stage==1){
                var roots=logs.stream().filter(p->{var s=read(l,p.below());return s.is(BlockTags.DIRT)||s.is(Blocks.MUD);}).toList();
                if(done!=null)return;
                root=roots.stream().min(Comparator.comparingDouble((BlockPos p)->p.distSqr(at)).thenComparingLong(BlockPos::asLong)).orElse(null);
                if(root==null){distance.clear();for(var p:logs){distance.put(p,0);queue.add(p);}stage=2;return;}
                var own=new ArrayList<BlockPos>();own.add(root);
                // Four adjacent independent oaks are never treated as a giant trunk.
                if(family.is(Blocks.DARK_OAK_LOG)||family.is(Blocks.SPRUCE_LOG)||family.is(Blocks.JUNGLE_LOG)){
                    outer:for(int x=-1;x<=0;x++)for(int z=-1;z<=0;z++){
                        var square=List.of(root.offset(x,0,z),root.offset(x+1,0,z),root.offset(x,0,z+1),root.offset(x+1,0,z+1));
                        if(square.stream().allMatch(p->roots.contains(p)&&logs.contains(p.above())&&logs.contains(p.above(2)))){own.clear();own.addAll(square);break outer;}
                    }
                }
                if(rootWood(family)||family.is(Blocks.MANGROVE_LOG)){
                    // Root networks can touch between different mangroves. Partition from
                    // actual log stems, never treat all connected root feet as one tree.
                    var stems=logs.stream().filter(p->observed.get(p).is(Blocks.MANGROVE_LOG)
                            && (!logs.contains(p.below()) || rootWood(observed.get(p.below())))).toList();
                    if(stems.isEmpty()){done=result(Kind.BLOCKED,"TREE_IDENTITY_UNCERTAIN");return;}
                    var fromTarget=distances(logs,List.of(seed));
                    var stem=stems.stream().min(Comparator.comparingInt(p->fromTarget.getOrDefault(p,Integer.MAX_VALUE))).orElseThrow();
                    if(stems.size()>1){
                        var a=distances(logs,List.of(stem));var b=distances(logs,stems.stream().filter(p->!p.equals(stem)).toList());
                        logs.removeIf(p->a.getOrDefault(p,Integer.MAX_VALUE)>=b.getOrDefault(p,Integer.MAX_VALUE));
                    }
                    own.clear();for(var p:roots)if(logs.contains(p))own.add(p);
                    if(own.isEmpty()){done=result(Kind.BLOCKED,"TREE_IDENTITY_UNCERTAIN");return;}
                }
                var remaining=roots.stream().filter(p->logs.contains(p)&&!own.contains(p)).toList();
                if(!remaining.isEmpty()){
                    var a=distances(logs,own);var b=distances(logs,remaining);
                    logs.removeIf(p->a.getOrDefault(p,Integer.MAX_VALUE)>=b.getOrDefault(p,Integer.MAX_VALUE));
                }
                root=own.stream().min(Comparator.comparingLong(BlockPos::asLong)).orElse(root);
                distance.clear();for(var p:logs){distance.put(p,0);queue.add(p);}stage=2;
            }else if(stage==2){
                for(var p:distance.keySet())if(!logs.contains(p)){halo.put(p,0);queue.add(p);}
                if(halo.isEmpty()){done=result(Kind.ORDINARY,"");return;}stage=3;
            }else if(stage==3){queue.addAll(other.keySet());stage=4;
            }else{
                var parts=new LinkedHashMap<BlockPos,BlockState>();
                distance.forEach((p,d)->{if(d>0&&other.getOrDefault(p,8)>d)parts.put(p,observed.get(p));});
                if(root==null){done=result(Kind.BLOCKED,"TREE_IDENTITY_UNCERTAIN");return;}
                for(var p:logs)parts.put(p,observed.get(p));
                if(!parts.containsKey(at)){done=result(Kind.BLOCKED,"TREE_IDENTITY_UNCERTAIN");return;}
                if(!valid(l)){done=result(Kind.BLOCKED,"CONSTRUCTION_TARGET_CHANGED");return;}
                done=new Result(Kind.TREE,root,Collections.unmodifiableMap(parts),"");
            }
        }
    }
    private static Map<BlockPos,Integer> distances(Set<BlockPos> logs,List<BlockPos> roots){
        record Node(BlockPos p,int d) {}
        var out=new HashMap<BlockPos,Integer>();var q=new PriorityQueue<Node>(Comparator.comparingInt(Node::d));
        for(var p:roots){out.put(p,0);q.add(new Node(p,0));}
        while(!q.isEmpty()){var entry=q.remove();var p=entry.p();if(entry.d()!=out.get(p))continue;
            for(int x=-1;x<=1;x++)for(int y=-1;y<=1;y++)for(int z=-1;z<=1;z++){
                var n=p.offset(x,y,z);int d=entry.d()+1+3*(Math.abs(x)+Math.abs(z));
                if(logs.contains(n)&&d<out.getOrDefault(n,Integer.MAX_VALUE)){out.put(n,d);q.add(new Node(n,d));}
            }
        }return out;
    }
}
