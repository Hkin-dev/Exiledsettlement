package com.hkin.exiledsettlement.settlement;

import com.google.gson.JsonObject;
import com.hkin.exiledsettlement.ExiledSettlementMod;
import com.hkin.exiledsettlement.blueprint.BlueprintTemplates;
import com.hkin.exiledsettlement.building.BuildingRecord;
import com.hkin.exiledsettlement.building.BuildingType;
import com.hkin.exiledsettlement.citizen.CitizenProfession;
import com.hkin.exiledsettlement.citizen.CitizenProjectionService;
import com.hkin.exiledsettlement.citizen.CitizenWorkService;
import com.hkin.exiledsettlement.citizen.SettlementCitizenEntity;
import com.hkin.exiledsettlement.region.ProductionRegionService;
import com.hkin.exiledsettlement.road.RoadSpaceData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.SpawnPlacements;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.pathfinder.PathFinder;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.event.EventHooks;

import java.util.Comparator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

/** Bounded habitat replenishment. Hunters still pursue, kill, collect, and deliver real animals. */
public final class SettlementWildlifeService {
    private static final int CHECK_INTERVAL_TICKS = 200;
    private static final int MAX_POSITION_ATTEMPTS = 12;
    private static final int MAX_PATH_CHECKS = 2;
    private static final int MAX_PATH_NODES = 1024;
    private static final int HUNT_RADIUS_BLOCKS = 48;
    private static final int HUNT_HEIGHT_BLOCKS = 12;
    private static final Set<EntityType<?>> SPECIES = Set.of(EntityType.COW, EntityType.PIG,
            EntityType.SHEEP, EntityType.CHICKEN, EntityType.RABBIT);
    private static final String ORIGIN_TAG = "exiledsettlement.wildlife_settlement";
    private static Settings settings = new Settings(true, 2400, 2, 8, 16, 32, 64, 20, 44);
    private static final Map<ServerLevel,State> LOADED=new WeakHashMap<>();

    public record Settings(boolean enabled, int intervalTicks, int batchSize, int localCap,
                           int settlementCap, int localCrowdingCap, int settlementCrowdingCap,
                           int minDistanceBlocks, int maxDistanceBlocks) { }
    public record Snapshot(long nextAttemptGameTick, long attempts, long spawned, String result,
                           int eligibleLodges, int nearbyAnimals, int allNearbyAnimals) { }
    private record Lodge(BuildingRecord building, SettlementCitizenEntity hunter) { }
    private static final class Entry {
        long next = -1, attempts, spawned;
        String result = "waiting";
        int lodges, animals, allAnimals;
    }

    private SettlementWildlifeService() { }
    public static Settings settings() { return settings; }

    /** A malformed data pack cannot weaken hard budgets or partially update the balance. */
    public static void reload(JsonObject json) {
        if(!json.has("enabled") || !json.get("enabled").isJsonPrimitive()
                || !json.getAsJsonPrimitive("enabled").isBoolean())throw new IllegalArgumentException("enabled must be boolean");
        var next = new Settings(json.get("enabled").getAsBoolean(),
                bounded(json,"interval_ticks",1200,24000), bounded(json,"batch_size",1,2),
                bounded(json,"local_cap",1,12), bounded(json,"settlement_cap",1,24),
                bounded(json,"local_crowding_cap",16,32), bounded(json,"settlement_crowding_cap",32,64),
                bounded(json,"min_distance_blocks",16,40), bounded(json,"max_distance_blocks",20,44));
        if(next.minDistanceBlocks()>=next.maxDistanceBlocks() || next.localCap()>next.settlementCap()
                || next.localCrowdingCap()>next.settlementCrowdingCap() || next.localCap()>next.localCrowdingCap()
                || next.settlementCap()>next.settlementCrowdingCap())
            throw new IllegalArgumentException("Invalid wildlife range or cap order");
        settings=next;
    }
    private static int bounded(JsonObject json,String key,int min,int max) {
        if(!json.has(key) || !json.get(key).isJsonPrimitive() || !json.getAsJsonPrimitive(key).isNumber())
            throw new IllegalArgumentException(key+" must be an integer");
        int value=json.get(key).getAsBigDecimal().intValueExact();
        if(value<min || value>max)throw new IllegalArgumentException(key+" outside ["+min+", "+max+"]");
        return value;
    }

    /** Called by the normal server tick; one settlement per ten seconds per dimension. */
    public static void tickLevel(ServerLevel level) {
        if(level.getGameTime()%CHECK_INTERVAL_TICKS!=73)return;
        var settlements=SettlementSavedData.get(level).settlements().stream().filter(SettlementRecord::active)
                .sorted(Comparator.comparing(s->s.id().toString())).toList();
        if(settlements.isEmpty())return;
        State state=state(level);if(state.lastCheck==level.getGameTime())return;
        state.lastCheck=level.getGameTime();
        int index=Math.floorMod(state.cursor++,settlements.size());
        review(level,settlements.get(index),state);state.setDirty();
    }

    private static void review(ServerLevel level,SettlementRecord settlement,State state) {
        Entry entry=state.entries.computeIfAbsent(settlement.id(),ignored->new Entry());
        Settings config=settings;long now=level.getGameTime();
        if(!config.enabled()){entry.result="disabled";return;}
        if(!level.getGameRules().getBoolean(GameRules.RULE_DOMOBSPAWNING)){entry.result="mob_spawning_disabled";return;}
        if(!level.isDay()){entry.result="night";return;}
        List<Lodge> lodges=activeLodges(level,settlement);entry.lodges=lodges.size();
        if(lodges.isEmpty()){entry.result="no_active_hunter";return;}
        // Do not undercount animals whose chunks/entities are still arriving after a world reload.
        for(var lodge:lodges) {
            var center=lodge.building().position();
            if(!loaded(level,center.offset(-HUNT_RADIUS_BLOCKS,0,-HUNT_RADIUS_BLOCKS),
                    center.offset(HUNT_RADIUS_BLOCKS,0,HUNT_RADIUS_BLOCKS),1)) {
                entry.result="habitat_unloaded";return;
            }
        }
        if(entry.next<0 || entry.next>now+config.intervalTicks()*2L) {
            entry.next=now+config.intervalTicks();entry.result="cooldown";return;
        }
        if(now<entry.next){entry.result="cooldown";return;}
        // Commit the cooldown before attempting work, including denied or unsuitable sites.
        entry.next=now+config.intervalTicks();entry.attempts++;
        Map<UUID,Animal> allAnimals=nearbyAnimals(level,lodges,config.settlementCrowdingCap());
        entry.allAnimals=allAnimals.size();entry.animals=0;
        // Reject crowding before any bounded enclosure flood. Never examine more than 64 animals.
        if(allAnimals.size()>=config.settlementCrowdingCap()){entry.result="settlement_crowding";return;}
        var openLodges=lodges.stream().filter(lodge->allAnimals.values().stream()
                .filter(a->huntArea(lodge.building().position()).contains(a.position())).count()<config.localCrowdingCap()).toList();
        if(openLodges.isEmpty()){entry.result="local_crowding";return;}
        Map<UUID,Animal> animals=new LinkedHashMap<>();
        for(var animal:allAnimals.values())if(!CitizenWorkService.protectedFromHunting(level,settlement,animal))animals.put(animal.getUUID(),animal);
        entry.animals=animals.size();
        if(animals.size()>=config.settlementCap()){entry.result="settlement_cap";return;}
        if(openLodges.stream().allMatch(lodge->animals.values().stream().filter(a->huntArea(lodge.building().position()).contains(a.position())).count()>=config.localCap())) {
            entry.result="local_cap";return;
        }
        int start=level.random.nextInt(openLodges.size()),created=0,pathChecks=0;
        entry.result="no_safe_habitat";
        for(int attempt=0;attempt<MAX_POSITION_ATTEMPTS && created<config.batchSize();attempt++) {
            Lodge lodge=openLodges.get((start+attempt)%openLodges.size());
            AABB range=huntArea(lodge.building().position());
            if(animals.values().stream().filter(a->range.contains(a.position())).count()>=config.localCap())continue;
            if(allAnimals.values().stream().filter(a->range.contains(a.position())).count()>=config.localCrowdingCap())continue;
            int radius=config.minDistanceBlocks()+level.random.nextInt(config.maxDistanceBlocks()-config.minDistanceBlocks()+1);
            double angle=level.random.nextDouble()*Math.PI*2;
            int x=lodge.building().position().getX()+(int)Math.round(Math.cos(angle)*radius);
            int z=lodge.building().position().getZ()+(int)Math.round(Math.sin(angle)*radius);
            BlockPos horizontal=new BlockPos(x,lodge.building().position().getY(),z);
            if(!loaded(level,horizontal,horizontal,1))continue;
            BlockPos feet=level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,horizontal);
            if(Math.abs(feet.getY()-lodge.building().position().getY())>HUNT_HEIGHT_BLOCKS
                    || !level.canSeeSky(feet) || !level.getWorldBorder().isWithinBounds(feet)
                    || protectedColumn(level,feet) || level.getNearestPlayer(x+.5,feet.getY(),z+.5,24,false)!=null
                    || !level.getEntitiesOfClass(Animal.class,new AABB(feet).inflate(4),Animal::isAlive).isEmpty())continue;
            var potential=EventHooks.getPotentialSpawns(level,MobCategory.CREATURE,feet,
                    level.getChunkSource().getGenerator().getMobsAt(level.getBiome(feet),level.structureManager(),MobCategory.CREATURE,feet));
            var choice=potential.getRandom(level.random).orElse(null);
            if(choice==null || !SPECIES.contains(choice.type) || !SpawnPlacements.isSpawnPositionOk(choice.type,level,feet)
                    || !SpawnPlacements.checkSpawnRules(choice.type,level,MobSpawnType.NATURAL,feet,level.random))continue;
            if(!(choice.type.create(level) instanceof Animal animal))continue;
            animal.moveTo(x+.5,feet.getY(),z+.5,level.random.nextFloat()*360,0);
            if(!level.noCollision(animal) || !EventHooks.checkSpawnPosition(animal,level,MobSpawnType.NATURAL)
                    || CitizenWorkService.protectedFromHunting(level,settlement,animal))continue;
            if(pathChecks>=MAX_PATH_CHECKS)break;
            pathChecks++;
            if(!reachable(level,lodge.hunter(),feet))continue;
            EventHooks.finalizeMobSpawn(animal,level,level.getCurrentDifficultyAt(feet),MobSpawnType.NATURAL,null);
            // Finalize or other mods may have moved/named/tamed the mob; recheck the resulting animal.
            if(!animal.blockPosition().equals(feet) || CitizenWorkService.protectedFromHunting(level,settlement,animal)
                    || !level.noCollision(animal))continue;
            animal.getPersistentData().putUUID(ORIGIN_TAG,settlement.id());
            if(level.addFreshEntity(animal) && animal.isAddedToLevel()) {
                animals.put(animal.getUUID(),animal);allAnimals.put(animal.getUUID(),animal);created++;entry.spawned++;
                entry.animals=animals.size();entry.allAnimals=allAnimals.size();entry.result="spawned";
                if(animals.size()>=config.settlementCap() || allAnimals.size()>=config.settlementCrowdingCap())break;
            }
        }
        if(created>0)ExiledSettlementMod.LOGGER.debug("WILDLIFE_REPLENISH settlement={} spawned={} nearby={} nextGameTick={}",
                settlement.id(),created,entry.animals,entry.next);
    }

    private static List<Lodge> activeLodges(ServerLevel level,SettlementRecord settlement) {
        Map<UUID,Lodge> result=new LinkedHashMap<>();
        for(var citizen:settlement.citizens()) {
            if(!citizen.canWork() || citizen.profession()!=CitizenProfession.HUNTER
                    || CitizenWorkService.naturalSchedulePhase(level.getDayTime(),citizen)!=CitizenWorkService.SchedulePhase.WORK)continue;
            var building=settlement.building(citizen.workBuildingId());
            if(building==null || building.type()!=BuildingType.HUNTING_LODGE || !building.active() || !building.operational()
                    || !level.hasChunkAt(building.position()) || ProductionRegionService.constructionPending(settlement,building))continue;
            var hunter=CitizenProjectionService.findProjection(level,citizen);
            if(hunter==null || !hunter.isAlive() || hunter.isSleeping() || hunter.isDefending()
                    || !huntArea(building.position()).contains(hunter.position()))continue;
            result.putIfAbsent(building.id(),new Lodge(building,hunter));
        }
        return List.copyOf(result.values());
    }
    private static AABB huntArea(BlockPos center) {
        return new AABB(center).inflate(HUNT_RADIUS_BLOCKS,HUNT_HEIGHT_BLOCKS,HUNT_RADIUS_BLOCKS);
    }
    private static Map<UUID,Animal> nearbyAnimals(ServerLevel level,List<Lodge> lodges,int limit) {
        Map<UUID,Animal> result=new LinkedHashMap<>();
        for(var lodge:lodges) {
            List<Animal> found=new ArrayList<>();
            level.getEntities(net.minecraft.world.level.entity.EntityTypeTest.forClass(Animal.class),huntArea(lodge.building().position()),
                    animal->animal.isAlive() && !result.containsKey(animal.getUUID()),found,limit-result.size());
            for(var animal:found)result.put(animal.getUUID(),animal);
            if(result.size()>=limit)return result;
        }
        return result;
    }
    private static boolean protectedColumn(ServerLevel level,BlockPos feet) {
        for(var settlement:SettlementSavedData.get(level).settlements()) {
            for(var building:settlement.buildings())if(inBounds(feet,building.minCorner(),building.maxCorner(),3))return true;
            for(var blueprint:settlement.blueprints()) {
                if(BlueprintTemplates.supports(blueprint.type())) {
                    var template=BlueprintTemplates.create(blueprint);
                    if(inBounds(feet,template.minCorner(),template.maxCorner(),3))return true;
                }else if(inBounds(feet,blueprint.areaMin(),blueprint.areaMax(),3))return true;
            }
        }
        return RoadSpaceData.get(level).columnHeight(feet)!=null;
    }
    private static boolean inBounds(BlockPos p,BlockPos min,BlockPos max,int margin) {
        return p.getX()>=min.getX()-margin && p.getX()<=max.getX()+margin
                && p.getZ()>=min.getZ()-margin && p.getZ()<=max.getZ()+margin;
    }
    private static boolean loaded(ServerLevel level,BlockPos a,BlockPos b,int margin) {
        int minX=(Math.min(a.getX(),b.getX())-margin)>>4,maxX=(Math.max(a.getX(),b.getX())+margin)>>4;
        int minZ=(Math.min(a.getZ(),b.getZ())-margin)>>4,maxZ=(Math.max(a.getZ(),b.getZ())+margin)>>4;
        for(int x=minX;x<=maxX;x++)for(int z=minZ;z<=maxZ;z++)
            if(!level.hasChunk(x,z) || !level.areEntitiesLoaded(ChunkPos.asLong(x,z)))return false;
        return true;
    }
    private static boolean reachable(ServerLevel level,SettlementCitizenEntity hunter,BlockPos target) {
        BlockPos from=hunter.blockPosition();
        if(from.distSqr(target)>64*64 || !loaded(level,from,target,4))return false;
        BlockPos min=new BlockPos(Math.min(from.getX(),target.getX())-4,Math.min(from.getY(),target.getY())-4,Math.min(from.getZ(),target.getZ())-4);
        BlockPos max=new BlockPos(Math.max(from.getX(),target.getX())+4,Math.max(from.getY(),target.getY())+4,Math.max(from.getZ(),target.getZ())+4);
        var evaluator=new WalkNodeEvaluator();evaluator.setCanOpenDoors(true);evaluator.setCanPassDoors(true);
        // A separate pathfinder never replaces the hunter's active navigation target or route.
        var path=new PathFinder(evaluator,MAX_PATH_NODES).findPath(new PathNavigationRegion(level,min,max),hunter,Set.of(target),64,0,1);
        return path!=null && path.canReach();
    }

    public static Snapshot snapshot(ServerLevel level,SettlementRecord settlement) {
        State state=LOADED.get(level);Entry entry=state==null?null:state.entries.get(settlement.id());
        return entry==null?new Snapshot(-1,0,0,"waiting",0,0,0)
                :new Snapshot(entry.next,entry.attempts,entry.spawned,entry.result,entry.lodges,entry.animals,entry.allAnimals);
    }
    /** Read-only management text: no state creation, entity queries, or terrain scans. */
    public static List<String> report(ServerLevel level,SettlementRecord settlement) {
        if(settlement.buildings().stream().noneMatch(b->b.type()==BuildingType.HUNTING_LODGE))return List.of();
        var view=snapshot(level,settlement);
        String status=!settings.enabled()?"已关闭":switch(view.result()) {
            case "disabled" -> "已关闭";
            case "mob_spawning_disabled" -> "世界已关闭生物生成";
            case "night" -> "夜间暂停";
            case "no_active_hunter" -> "暂无在岗猎人";
            case "habitat_unloaded" -> "等待猎区加载";
            case "settlement_cap" -> "猎区动物已达上限";
            case "local_cap" -> "猎屋周边动物已达上限";
            case "settlement_crowding", "local_crowding" -> "附近动物过多，暂停补充";
            case "no_safe_habitat" -> "暂未找到合适的安全地表";
            case "spawned", "cooldown" -> "冷却剩余 "+Math.max(0,(view.nextAttemptGameTick()-level.getGameTime()+19)/20)+" 秒";
            default -> "等待巡查";
        };
        return List.of("野生动物补充："+status+"；累计真实补充 "+view.spawned()+" 只",
                "补充规则：每聚落每 "+(settings.intervalTicks()/20)+" 秒最多 "+settings.batchSize()+" 只；可猎动物单猎区上限 "+settings.localCap()+" 只，共享上限 "+settings.settlementCap()
                        +" 只；全部动物单猎区/共享上限 "+settings.localCrowdingCap()+"/"+settings.settlementCrowdingCap()+" 只");
    }
    private static State state(ServerLevel level) {
        return LOADED.computeIfAbsent(level,key->key.getDataStorage().computeIfAbsent(State.FACTORY,"exiledsettlement_wildlife"));
    }
    private static final class State extends SavedData {
        static final Factory<State> FACTORY=new Factory<>(State::new,State::load);
        final Map<UUID,Entry> entries=new LinkedHashMap<>();
        int cursor;
        long lastCheck=Long.MIN_VALUE;
        static State load(CompoundTag tag,HolderLookup.Provider registries) {
            var result=new State();result.cursor=tag.getInt("Cursor");
            for(Tag raw:tag.getList("Settlements",Tag.TAG_COMPOUND)) {
                var item=(CompoundTag)raw;if(!item.hasUUID("Id"))continue;
                var entry=new Entry();entry.next=item.contains("Next")?item.getLong("Next"):-1;entry.attempts=Math.max(0,item.getLong("Attempts"));
                entry.spawned=Math.max(0,item.getLong("Spawned"));entry.result=item.getString("Result");
                entry.lodges=Math.max(0,item.getInt("Lodges"));entry.animals=Math.max(0,item.getInt("Animals"));
                entry.allAnimals=Math.max(0,item.getInt("AllAnimals"));
                result.entries.put(item.getUUID("Id"),entry);
            }
            return result;
        }
        @Override public CompoundTag save(CompoundTag tag,HolderLookup.Provider registries) {
            tag.putInt("Cursor",cursor);var list=new ListTag();
            entries.forEach((id,entry)->{
                var item=new CompoundTag();item.putUUID("Id",id);item.putLong("Next",entry.next);item.putLong("Attempts",entry.attempts);
                item.putLong("Spawned",entry.spawned);item.putString("Result",entry.result);item.putInt("Lodges",entry.lodges);item.putInt("Animals",entry.animals);item.putInt("AllAnimals",entry.allAnimals);list.add(item);
            });tag.put("Settlements",list);return tag;
        }
    }
}
