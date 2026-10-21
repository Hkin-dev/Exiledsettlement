package com.hkin.exiledsettlement.settlement;

import com.hkin.exiledsettlement.blueprint.BlueprintTemplates;
import com.hkin.exiledsettlement.building.*;
import com.hkin.exiledsettlement.citizen.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BrewingStandBlockEntity;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/** Residents transport real stacks. Vanilla owns brewing, fuel use, events and container returns. */
public final class PriestProductionService {
    private static final String OWNER="ExiledSettlementPriestBatch";
    private static final Map<CitizenRecord,Long> RETRY=new WeakHashMap<>();
    private static final Map<CitizenRecord,String> REASONS=new WeakHashMap<>();
    private static final Map<CitizenRecord,Long> SUPPLY_RETRY=new WeakHashMap<>();
    private static final Map<CitizenRecord,Long> ROUTE_RETRY=new WeakHashMap<>();
    private static final Map<CitizenRecord,BlockPos> WALK_TARGET=new WeakHashMap<>();
    private record SupplyTrip(ResourceLocation item,ResourceLocation potion,UUID warehouse,long until) {}
    private static final Map<CitizenRecord,SupplyTrip> SUPPLY_TRIPS=new WeakHashMap<>();
    private static final class Counts {
        long revision;
        final Map<String,Integer> values=new HashMap<>();
        Counts(long revision) {this.revision=revision;}
    }
    private static final Map<WarehouseStock,Counts> EXACT_COUNTS=new WeakHashMap<>();
    private PriestProductionService() {}

    public static boolean handles(CitizenRecord citizen) {return citizen.life().brewing!=null;}
    /** Exact batch-owned material, including potion components; never treats all food/equipment as reserved. */
    public static boolean reservedCargo(CitizenRecord citizen,ItemStack stack) {
        return reservationBudget(citizen,stack)>0;
    }
    /** Only the first matching physical units are reserved, leaving unrelated surplus usable. */
    public static int reservedCount(CitizenRecord citizen,int slot) {
        if(slot<0 || slot>=CitizenRecord.INVENTORY_SIZE || citizen.pitReserveSlot(slot))return 0;
        var stack=citizen.inventoryItem(slot);
        int budget=reservationBudget(citizen,stack);
        for(int earlier=0;earlier<slot && budget>0;earlier++)
            if(!citizen.pitReserveSlot(earlier) && BrewingRecipes.matches(citizen.inventoryItem(earlier),stack))
                budget-=citizen.inventoryItem(earlier).getCount();
        return Math.max(0,Math.min(stack.getCount(),budget));
    }
    private static int reservationBudget(CitizenRecord citizen,ItemStack stack) {
        var task=citizen.life().brewing;
        if(task==null || stack.isEmpty())return 0;
        if(task.bagProduct!=null && BrewingRecipes.matches(stack,BrewingRecipes.stack(task.bagProduct)))
            return task.bottlesInBag;
        int count=task.cargo.getOrDefault(BuiltInRegistries.ITEM.getKey(stack.getItem()),0);
        return count>0 && BrewingRecipes.matches(stack,new ItemStack(stack.getItem()))?count:0;
    }
    public static String waitingReason(CitizenRecord citizen) {return REASONS.getOrDefault(citizen,"PRIEST_WAITING");}
    private static boolean reject(CitizenRecord citizen,String reason) {REASONS.put(citizen,reason);return false;}
    private static void blocked(CitizenRecord citizen,SettlementCitizenEntity entity,String reason) {
        REASONS.put(citizen,reason);CommunityLifeService.blocked(citizen,entity,reason);
    }
    public static List<BlockPos> stations(ServerLevel level,BuildingRecord church) {
        if(church.type()!=BuildingType.CHURCH)return List.of();
        return BlueprintTemplates.createForBuilding(church).placements().stream()
                .filter(p->p.mode().isFinalStructure() && p.state().is(Blocks.BREWING_STAND))
                .map(p->p.position()).distinct()
                .filter(p->level.hasChunkAt(p) && level.getBlockEntity(p) instanceof BrewingStandBlockEntity)
                .filter(p->!standingPositions(level,church,p).isEmpty()).limit(2).toList();
    }
    public static List<BlockPos> standingPositions(ServerLevel level,BuildingRecord church,BlockPos stand) {
        var points=new ArrayList<BlockPos>();
        if(!level.hasChunkAt(stand) || !(level.getBlockEntity(stand) instanceof BrewingStandBlockEntity))return points;
        for(var direction:Direction.Plane.HORIZONTAL)for(int dy=-1;dy<=1;dy++) {
            var feet=stand.relative(direction).above(dy);
            if(church.contains(feet) && feet.getX()>church.minCorner().getX() && feet.getX()<church.maxCorner().getX()
                    && feet.getZ()>church.minCorner().getZ() && feet.getZ()<church.maxCorner().getZ()
                    && WarehouseInventoryService.canStand(level,feet))points.add(feet);
        }
        return List.copyOf(points);
    }
    public static boolean validateChurch(ServerLevel level,BuildingRecord church) {
        var stations=stations(level,church);
        return church.updateFunction(!stations.isEmpty(),stations.isEmpty()?BuildingStatus.MISSING_CONTAINER:BuildingStatus.OPERATIONAL,
                stations.size(),stations);
    }
    private static boolean atStation(ServerLevel level,BuildingRecord church,BlockPos stand,SettlementCitizenEntity entity) {
        return (church==null || church.contains(entity.blockPosition())) && entity.onGround() && level.noCollision(entity)
                && !entity.isInWater() && (church==null?recoveryPositions(level,stand):standingPositions(level,church,stand)).stream()
                .anyMatch(p->entity.distanceToSqr(Vec3.atBottomCenterOf(p))<=0.64);
    }
    /** Only used to retrieve owned batches after their building registration has disappeared. */
    private static List<BlockPos> recoveryPositions(ServerLevel level,BlockPos stand) {
        var points=new ArrayList<BlockPos>();
        for(var direction:Direction.Plane.HORIZONTAL)for(int dy=-1;dy<=1;dy++) {
            var at=stand.relative(direction).above(dy);
            if(WarehouseInventoryService.canStand(level,at))points.add(at);
        }
        return points;
    }
    private static boolean approach(ServerLevel level,BuildingRecord church,BlockPos stand,
                                    CitizenRecord citizen,SettlementCitizenEntity entity) {
        if(atStation(level,church,stand,entity)) {
            ROUTE_RETRY.remove(citizen);
            entity.getNavigation().stop();citizen.setActivity(CitizenActivity.BREWING,"");entity.setActivity(CitizenActivity.BREWING);return true;
        }
        var points=church==null?recoveryPositions(level,stand):standingPositions(level,church,stand);
        var target=WALK_TARGET.get(citizen);
        if(target==null || !points.contains(target) || entity.navigationRetryStage()>=3) {
            if(ROUTE_RETRY.getOrDefault(citizen,Long.MIN_VALUE)>level.getGameTime())return false;
            BlockPos previous=target;WALK_TARGET.remove(citizen);
            var candidates=points.stream().filter(p->!p.equals(previous))
                    .sorted(Comparator.comparingDouble(p->p.distSqr(entity.blockPosition()))).toList();
            target=entity.findReachableBedStation(candidates);
            if(target==null) {
                ROUTE_RETRY.put(citizen,level.getGameTime()+100);
                blocked(citizen,entity,"PRIEST_ROUTE_BLOCKED");return false;
            }
            ROUTE_RETRY.remove(citizen);
            WALK_TARGET.put(citizen,target);
        }
        CommunityLifeService.move(citizen,entity,target,CitizenActivity.TO_CHURCH,"");return false;
    }
    public static int stockCount(ServerLevel level,SettlementRecord settlement,ItemStack expected) {
        if(expected.isEmpty())return 0;
        long count=0;
        for(var warehouse:SettlementInventoryService.warehouses(settlement))count+=warehouseCount(level,warehouse,expected);
        return (int)Math.min(Integer.MAX_VALUE,count);
    }
    private static int warehouseCount(ServerLevel level,BuildingRecord warehouse,ItemStack expected) {
        // Components are counted exactly. A stock mutation invalidates every cached answer.
        if(WarehouseStock.applies(warehouse)) {
            var stock=WarehouseInventoryService.stock(level,warehouse);
            var counts=EXACT_COUNTS.computeIfAbsent(stock,s->new Counts(s.revision()));
            if(counts.revision!=stock.revision()) {counts.values.clear();counts.revision=stock.revision();}
            var potion=BrewingRecipes.identity(expected);
            String key=potion!=null?"p:"+potion:ItemStack.isSameItemSameComponents(expected,new ItemStack(expected.getItem()))
                    ?"i:"+BuiltInRegistries.ITEM.getKey(expected.getItem()):null;
            if(key!=null && counts.values.containsKey(key))return counts.values.get(key);
            int total=0;
            for(int slot=0;slot<stock.getContainerSize();slot++)
                if(BrewingRecipes.matches(stock.getItem(slot),expected))total+=stock.getItem(slot).getCount();
            if(key!=null)counts.values.put(key,total);
            return total;
        }
        int total=0;
        for(var at:WarehouseInventoryService.loadedContainers(level,warehouse))
            for(int slot=0;slot<at.container().getContainerSize();slot++) {
                var actual=at.container().getItem(slot);
                if(BrewingRecipes.matches(actual,expected))total+=actual.getCount();
            }
        return total;
    }
    public static int promised(SettlementRecord settlement,ResourceLocation product) {
        return settlement.citizens().stream().filter(CitizenRecord::alive).map(c->c.life().brewing)
                .filter(t->t!=null && product.equals(t.product))
                .mapToInt(t->!t.cancelRequested?Math.max(0,t.amount-t.delivered)
                        : product.equals(t.bagProduct)?t.bottlesInBag
                        : (t.phase==PriestBrewingTask.BREW || t.phase==PriestBrewingTask.COLLECT)
                        && t.step+1==t.totalSteps?t.amount:0).sum();
    }
    private static boolean unrelatedCargo(CitizenRecord citizen) {
        for(int slot=0;slot<CitizenRecord.INVENTORY_SIZE;slot++)
            if(CitizenEquipmentService.deliverableCount(citizen,slot)>0
                    && !citizen.inventoryItem(slot).has(net.minecraft.core.component.DataComponents.TOOL))return true;
        return false;
    }
    private static BuildingRecord assignedChurch(SettlementRecord settlement,CitizenRecord citizen) {
        var building=settlement.building(citizen.workBuildingId());
        return citizen.canWork() && citizen.profession()==CitizenProfession.PRIEST && building!=null
                && building.type()==BuildingType.CHURCH && building.active() && !building.demolitionQueued()
                && retainsPosition(settlement,building,citizen)?building:null;
    }
    private static boolean retainsPosition(SettlementRecord settlement,BuildingRecord church,CitizenRecord citizen) {
        int slots=Math.max(0,Math.min(2,church.workerTarget()));
        if(slots==0 || citizen.manualReleasePending())return false;
        // Follow assignment priority, but keep explicitly reduced targets authoritative even for manual locks.
        // Temporary validation capacity zero during repairs does not discard an already funded batch.
        return settlement.citizens().stream().filter(CitizenRecord::canWork)
                .filter(c->c.profession()==CitizenProfession.PRIEST && church.id().equals(c.workBuildingId()) && !c.manualReleasePending())
                .sorted(Comparator.comparingInt((CitizenRecord c)->c.hasManualAssignment()?0:
                        c.life().brewing!=null && !c.life().brewing.cancelRequested?1:2).thenComparing(c->c.id().toString()))
                .limit(slots).anyMatch(c->c==citizen);
    }
    private static BrewingStandBlockEntity stand(ServerLevel level,BlockPos pos) {
        return pos!=null && level.hasChunkAt(pos) && level.getBlockEntity(pos) instanceof BrewingStandBlockEntity be?be:null;
    }
    private static int fuel(ServerLevel level,BrewingStandBlockEntity be) {
        return Byte.toUnsignedInt(be.saveWithoutMetadata(level.registryAccess()).getByte("Fuel"));
    }
    private static int brewTime(ServerLevel level,BrewingStandBlockEntity be) {
        return be.saveWithoutMetadata(level.registryAccess()).getShort("BrewTime");
    }
    private static boolean owns(BrewingStandBlockEntity be,PriestBrewingTask task) {
        var tag=be.getPersistentData().getCompound(OWNER);
        return tag.hasUUID("Token") && task.token.equals(tag.getUUID("Token"));
    }
    private static boolean emptyWorkSlots(BrewingStandBlockEntity be) {
        for(int i=0;i<4;i++)if(!be.getItem(i).isEmpty())return false;
        return true;
    }
    private static boolean claimable(ServerLevel level,SettlementRecord settlement,BrewingStandBlockEntity be) {
        if(!emptyWorkSlots(be) || brewTime(level,be)>0)return false;
        if(!be.getItem(4).isEmpty() && !BrewingRecipes.matches(be.getItem(4),new ItemStack(Items.BLAZE_POWDER)))return false;
        var old=be.getPersistentData().getCompound(OWNER);
        if(!old.hasUUID("Token"))return true;
        var token=old.getUUID("Token");
        return settlement.citizens().stream().filter(CitizenRecord::alive)
                .noneMatch(c->c.life().brewing!=null && token.equals(c.life().brewing.token));
    }
    private static void claim(BrewingStandBlockEntity be,PriestBrewingTask task) {
        var tag=new CompoundTag();tag.putUUID("Token",task.token);tag.putUUID("Church",task.church);
        be.getPersistentData().put(OWNER,tag);be.setChanged();
    }
    private static void release(BrewingStandBlockEntity be,PriestBrewingTask task) {
        if(be!=null && owns(be,task) && emptyWorkSlots(be)) {be.getPersistentData().remove(OWNER);be.setChanged();}
    }
    public static boolean assign(ServerLevel level,SettlementRecord settlement,CitizenRecord citizen) {
        if(handles(citizen) || citizen.manualReleasePending() || !citizen.life().task.isEmpty()
                || citizen.workState()!=CitizenWorkState.IDLE)return false;
        if(RETRY.getOrDefault(citizen,Long.MIN_VALUE)>level.getGameTime())return false;
        RETRY.put(citizen,level.getGameTime()+100);
        var church=assignedChurch(settlement,citizen);
        if(church==null)return reject(citizen,"PRIEST_NO_CHURCH");
        if(CitizenWorkService.schedulePhase(level.getDayTime(),citizen)!=CitizenWorkService.SchedulePhase.WORK)
            return reject(citizen,"PRIEST_RESTING");
        if(!church.operational() || !church.repairPositions().isEmpty())return reject(citizen,"PRIEST_FACILITY_UNAVAILABLE");
        if(settlement.community().danger(church.position()) || settlement.community().evacuation)return reject(citizen,"PRIEST_DANGER");
        if(unrelatedCargo(citizen))return reject(citizen,"PRIEST_RETURNING_CARGO");
        var plan=BrewingRecipes.plan(level,church.productionId());
        if(plan.isEmpty())return reject(citizen,"PRIEST_RECIPE_CHANGED");
        int shortage=church.brewStockTarget()-stockCount(level,settlement,BrewingRecipes.stack(church.productionId()))
                -promised(settlement,church.productionId());
        if(shortage<=0)return reject(citizen,"PRIEST_STOCK_REACHED");
        var free=stations(level,church).stream().map(p->stand(level,p)).filter(Objects::nonNull)
                .filter(be->claimable(level,settlement,be)).findFirst().orElse(null);
        if(free==null)return reject(citizen,"PRIEST_STATION_BUSY");
        int water=stockCount(level,settlement,BrewingRecipes.stack(BrewingRecipes.WATER));
        if(water<=0)return reject(citizen,"PRIEST_WATER_MISSING");
        var task=new PriestBrewingTask();task.church=church.id();task.stand=free.getBlockPos().immutable();
        task.product=church.productionId();task.amount=Math.min(3,Math.min(shortage,water));
        task.totalSteps=plan.size();
        task.signature=BrewingRecipes.signature(plan);task.bagProduct=BrewingRecipes.WATER;
        citizen.life().brewing=task;citizen.life().task="brew:"+task.product;
        citizen.life().destination=task.church;citizen.life().taskStarted=level.getGameTime();
        claim(free,task);REASONS.remove(citizen);return true;
    }
    public static boolean shift(ServerLevel level,SettlementSavedData data,SettlementRecord settlement,
                                CitizenRecord citizen,SettlementCitizenEntity entity) {
        if(handles(citizen))return work(level,data,settlement,citizen,entity);
        if(unrelatedCargo(citizen))return false;
        if(assign(level,settlement,citizen)) {data.touch();return work(level,data,settlement,citizen,entity);}
        entity.stopSettlementNavigation();citizen.setActivity(CitizenActivity.PRIEST_IDLE,waitingReason(citizen));
        entity.setActivity(CitizenActivity.PRIEST_IDLE);return true;
    }
    public static boolean isBrewing(ServerLevel level,SettlementRecord settlement,CitizenRecord citizen,SettlementCitizenEntity entity) {
        var task=citizen.life().brewing;if(task==null || task.cancelRequested)return false;
        var church=settlement.building(task.church);
        var be=stand(level,task.stand);
        return church!=null && assignedChurch(settlement,citizen)==church && church.operational()
                && church.repairPositions().isEmpty() && be!=null && owns(be,task)
                && task.phase==PriestBrewingTask.BREW && citizen.activity()==CitizenActivity.BREWING
                && CitizenWorkService.schedulePhase(level.getDayTime(),citizen)==CitizenWorkService.SchedulePhase.WORK
                && !settlement.community().danger(church.position()) && !settlement.community().evacuation
                && atStation(level,church,task.stand,entity);
    }
    public static boolean work(ServerLevel level,SettlementSavedData data,SettlementRecord settlement,
                               CitizenRecord citizen,SettlementCitizenEntity entity) {
        var task=citizen.life().brewing;if(task==null)return false;
        if(CitizenWorkService.schedulePhase(level.getDayTime(),citizen)!=CitizenWorkService.SchedulePhase.WORK)return false;
        citizen.life().task="brew:"+task.product;citizen.life().destination=task.church;
        var church=settlement.building(task.church);
        if(church==null || assignedChurch(settlement,citizen)!=church || citizen.manualReleasePending())task.cancelRequested=true;
        var plan=BrewingRecipes.plan(level,task.product);
        if(plan.isEmpty() || !task.signature.equals(BrewingRecipes.signature(plan)) || task.step>=plan.size()) {
            task.cancelRequested=true;
            if(task.phase==PriestBrewingTask.BREW || task.phase==PriestBrewingTask.COLLECT) {
                blocked(citizen,entity,"PRIEST_RECIPE_CHANGED");data.touch();return true;
            }
        }
        var be=stand(level,task.stand);
        if(task.phase!=PriestBrewingTask.DELIVER && (be==null || !owns(be,task))) {
            if(!level.hasChunkAt(task.stand)) {blocked(citizen,entity,"PRIEST_FACILITY_UNAVAILABLE");return true;}
            // A replaced/broken stand has normal world drops; never recreate its former contents.
            task.cancelRequested=true;task.phase=PriestBrewingTask.DELIVER;
        }
        if(task.cancelRequested && (task.phase==PriestBrewingTask.FETCH || task.phase==PriestBrewingTask.LOAD)) {
            release(be,task);task.phase=PriestBrewingTask.DELIVER;
        }
        if(task.phase==PriestBrewingTask.DELIVER) {
            release(be,task);deliver(level,data,settlement,citizen,entity,task);data.touch();return true;
        }
        if(settlement.community().danger(task.stand) || settlement.community().evacuation) {
            blocked(citizen,entity,"PRIEST_DANGER");data.touch();return true;
        }
        if(!task.cancelRequested && (church==null || !church.operational() || !church.repairPositions().isEmpty())) {
            blocked(citizen,entity,"PRIEST_FACILITY_UNAVAILABLE");data.touch();return true;
        }
        var step=plan.get(task.step);
        switch(task.phase) {
            case PriestBrewingTask.FETCH -> fetchBatch(level,settlement,church,citizen,entity,task,be,plan);
            case PriestBrewingTask.LOAD -> {
                if(!emptyWorkSlots(be) || brewTime(level,be)>0) {
                    task.interference=true;blocked(citizen,entity,"PRIEST_INTERFERENCE");
                } else if(approach(level,church,task.stand,citizen,entity))loadStep(level,citizen,entity,task,be,step,plan.size()-task.step);
            }
            case PriestBrewingTask.BREW, PriestBrewingTask.COLLECT -> processStand(level,settlement,church,citizen,entity,task,be,step,plan);
            default -> blocked(citizen,entity,"PRIEST_INTERFERENCE");
        }
        data.touch();return true;
    }
    private static int inBag(CitizenRecord citizen,ItemStack expected) {
        int total=0;
        for(int slot=0;slot<CitizenRecord.INVENTORY_SIZE;slot++)
            if(!citizen.pitReserveSlot(slot) && BrewingRecipes.matches(citizen.inventoryItem(slot),expected))
                total+=citizen.inventoryItem(slot).getCount();
        return total;
    }
    private static Item item(ResourceLocation id) {return BuiltInRegistries.ITEM.get(id);}
    private static void cargo(PriestBrewingTask task,Item item,int change) {
        var id=BuiltInRegistries.ITEM.getKey(item);
        int now=task.cargo.getOrDefault(id,0)+change;if(now<=0)task.cargo.remove(id);else task.cargo.put(id,now);
    }
    private static int carried(PriestBrewingTask task,Item item) {return task.cargo.getOrDefault(BuiltInRegistries.ITEM.getKey(item),0);}
    private static boolean cargoIntact(CitizenRecord citizen,PriestBrewingTask task) {
        if(task.bottlesInBag>0 && inBag(citizen,BrewingRecipes.stack(task.bagProduct))<task.bottlesInBag)return false;
        for(var entry:task.cargo.entrySet())
            if(inBag(citizen,new ItemStack(item(entry.getKey())))<entry.getValue())return false;
        return true;
    }
    private static void fetchBatch(ServerLevel level,SettlementRecord settlement,BuildingRecord church,
                                   CitizenRecord citizen,SettlementCitizenEntity entity,PriestBrewingTask task,
                                   BrewingStandBlockEntity be,List<BrewingRecipes.Step> plan) {
        if(!cargoIntact(citizen,task)) {
            task.interference=true;task.cancelRequested=true;task.phase=PriestBrewingTask.DELIVER;
            release(be,task);blocked(citizen,entity,"PRIEST_INTERFERENCE");return;
        }
        if(SUPPLY_RETRY.getOrDefault(citizen,Long.MIN_VALUE)>level.getGameTime())return;
        var water=BrewingRecipes.stack(BrewingRecipes.WATER);
        if(task.step==0 && task.bottlesInBag<task.amount) {
            int fetched=fetch(level,settlement,citizen,entity,water,task.amount-task.bottlesInBag,false);
            task.bottlesInBag+=fetched;return;
        }
        var required=new LinkedHashMap<Item,Integer>();
        for(var step:plan.subList(task.step,plan.size()))required.merge(step.ingredient(),1,Integer::sum);
        int availableFuel=fuel(level,be)+(BrewingRecipes.matches(be.getItem(4),new ItemStack(Items.BLAZE_POWDER))?be.getItem(4).getCount()*20:0);
        if(availableFuel<plan.size()-task.step)required.merge(Items.BLAZE_POWDER,1,Integer::sum);
        for(var entry:required.entrySet()) {
            int missing=entry.getValue()-carried(task,entry.getKey());
            if(missing>0) {
                int fetched=fetch(level,settlement,citizen,entity,new ItemStack(entry.getKey()),missing,entry.getKey()==Items.BLAZE_POWDER);
                cargo(task,entry.getKey(),fetched);return;
            }
        }
        task.phase=PriestBrewingTask.LOAD;WarehouseInventoryService.releaseInteractionPosition(level,entity);
    }
    private static int fetch(ServerLevel level,SettlementRecord settlement,CitizenRecord citizen,
                             SettlementCitizenEntity entity,ItemStack expected,int wanted,boolean fuel) {
        var itemId=BuiltInRegistries.ITEM.getKey(expected.getItem());var potion=BrewingRecipes.identity(expected);
        var trip=SUPPLY_TRIPS.get(citizen);
        BuildingRecord source=trip!=null && trip.until()>level.getGameTime() && trip.item().equals(itemId)
                && Objects.equals(trip.potion(),potion)?settlement.building(trip.warehouse()):null;
        if(source!=null && (!source.active() || !source.operational() || settlement.community().danger(source.position())))source=null;
        if(source==null) {
            source=SettlementInventoryService.warehouses(settlement).stream()
                    .filter(b->!settlement.community().danger(b.position()) && warehouseCount(level,b,expected)>0)
                    .min(Comparator.comparingDouble(b->b.position().distSqr(citizen.lastPosition()))).orElse(null);
            if(source!=null)SUPPLY_TRIPS.put(citizen,new SupplyTrip(itemId,potion,source.id(),level.getGameTime()+100));
        }
        if(source==null) {
            blocked(citizen,entity,(fuel?"PRIEST_FUEL_MISSING":Objects.equals(potion,BrewingRecipes.WATER)?
                    "PRIEST_WATER_MISSING":"PRIEST_INPUT_MISSING")+"|"+itemId+"|"+wanted);
            SUPPLY_RETRY.put(citizen,level.getGameTime()+100);return 0;
        }
        if(!CommunityLifeService.atStorage(level,citizen,entity,source,expected.getItem(),CitizenActivity.FETCHING_BREWING_SUPPLIES))return 0;
        int capacity=citizen.inventoryCapacityFor(expected);
        if(capacity<=0) {blocked(citizen,entity,"PRIEST_BAG_FULL");SUPPLY_RETRY.put(citizen,level.getGameTime()+100);return 0;}
        var taken=WarehouseInventoryService.extractItemForResident(level,source,entity,expected.getItem(),Math.min(wanted,capacity),
                actual->BrewingRecipes.matches(actual,expected)?Math.min(wanted,citizen.inventoryCapacityFor(actual)):0);
        if(taken.isEmpty()) {SUPPLY_TRIPS.remove(citizen);return 0;}
        var remainder=citizen.insertIntoInventory(taken);
        if(!remainder.isEmpty())WarehouseInventoryService.insertForResident(level,source,entity,remainder);
        CommunityStock.invalidate(level,settlement);return taken.getCount()-remainder.getCount();
    }
    private static void loadStep(ServerLevel level,CitizenRecord citizen,SettlementCitizenEntity entity,
                                 PriestBrewingTask task,BrewingStandBlockEntity be,BrewingRecipes.Step step,int remainingSteps) {
        if(!cargoIntact(citizen,task) || task.bottlesInBag!=task.amount || !Objects.equals(task.bagProduct,BrewingRecipes.identity(step.input()))
                || inBag(citizen,step.input())<task.amount || carried(task,step.ingredient())<1
                || inBag(citizen,new ItemStack(step.ingredient()))<1) {
            // No item has entered this empty stand. Return only surviving real cargo instead of wedging the slot.
            task.interference=true;task.cancelRequested=true;task.phase=PriestBrewingTask.DELIVER;
            release(be,task);blocked(citizen,entity,"PRIEST_INTERFERENCE");return;
        }
        boolean addFuel=fuel(level,be)<=0 && be.getItem(4).isEmpty();
        int powderNeeded=(step.ingredient()==Items.BLAZE_POWDER?1:0)+(addFuel?1:0);
        if(addFuel && (carried(task,Items.BLAZE_POWDER)<powderNeeded || inBag(citizen,new ItemStack(Items.BLAZE_POWDER))<powderNeeded)) {
            task.phase=PriestBrewingTask.FETCH;blocked(citizen,entity,"PRIEST_FUEL_MISSING");return;
        }
        if(!be.getItem(4).isEmpty() && !BrewingRecipes.matches(be.getItem(4),new ItemStack(Items.BLAZE_POWDER))) {
            task.interference=true;blocked(citizen,entity,"PRIEST_INTERFERENCE");return;
        }
        // All transfers are preflighted on the server thread. The ingredient is inserted last.
        for(int i=0;i<task.amount;i++)be.setItem(i,citizen.extractInventoryItem(s->BrewingRecipes.matches(s,step.input()),1));
        task.bottlesInBag=0;task.bagProduct=null;
        if(addFuel) {
            be.setItem(4,citizen.extractInventoryItem(s->BrewingRecipes.matches(s,new ItemStack(Items.BLAZE_POWDER)),1));
            cargo(task,Items.BLAZE_POWDER,-1);
        }
        be.setItem(3,citizen.extractInventoryItem(s->BrewingRecipes.matches(s,new ItemStack(step.ingredient())),1));
        cargo(task,step.ingredient(),-1);be.setChanged();task.phase=PriestBrewingTask.BREW;task.interference=false;
    }
    private static boolean bottles(BrewingStandBlockEntity be,ItemStack expected,int amount) {
        for(int i=0;i<3;i++)
            if(i<amount ? be.getItem(i).getCount()!=1 || !BrewingRecipes.matches(be.getItem(i),expected) : !be.getItem(i).isEmpty())return false;
        return true;
    }
    private static ItemStack returned(BrewingRecipes.Step step) {
        var ingredient=new ItemStack(step.ingredient());
        return ingredient.hasCraftingRemainingItem()?ingredient.getCraftingRemainingItem():ItemStack.EMPTY;
    }
    private static boolean returnMatches(BrewingStandBlockEntity be,BrewingRecipes.Step step) {
        ItemStack expected=returned(step),actual=be.getItem(3);
        return expected.isEmpty()?actual.isEmpty():BrewingRecipes.matches(actual,expected) && actual.getCount()==expected.getCount();
    }
    private static void processStand(ServerLevel level,SettlementRecord settlement,BuildingRecord church,
                                     CitizenRecord citizen,SettlementCitizenEntity entity,PriestBrewingTask task,
                                     BrewingStandBlockEntity be,BrewingRecipes.Step step,List<BrewingRecipes.Step> plan) {
        boolean complete=bottles(be,step.output(),task.amount) && returnMatches(be,step) && brewTime(level,be)==0;
        boolean running=bottles(be,step.input(),task.amount) && BrewingRecipes.matches(be.getItem(3),new ItemStack(step.ingredient()))
                && be.getItem(3).getCount()==1;
        if(!complete && !running) {
            task.interference=true;
            if(emptyWorkSlots(be) && brewTime(level,be)==0) {
                task.cancelRequested=true;task.phase=PriestBrewingTask.DELIVER;release(be,task);
            } else blocked(citizen,entity,"PRIEST_INTERFERENCE");
            return;
        }
        task.interference=false;
        if(!approach(level,church,task.stand,citizen,entity))return;
        if(!complete)return;
        task.phase=PriestBrewingTask.COLLECT;
        var remainder=returned(step);
        if(citizen.inventoryCapacityFor(step.output())<task.amount
                || !remainder.isEmpty() && citizen.inventoryCapacityFor(remainder)<remainder.getCount()) {
            blocked(citizen,entity,"PRIEST_BAG_FULL");return;
        }
        // Potion slots may use the last free bag slots, so reserve a distinct slot for an unstackable return.
        int free=0;for(int i=0;i<CitizenRecord.INVENTORY_SIZE;i++)if(!citizen.pitReserveSlot(i) && citizen.inventoryItem(i).isEmpty())free++;
        int returnRoom=0;for(var actual:citizen.inventory())if(BrewingRecipes.matches(actual,remainder))returnRoom+=actual.getMaxStackSize()-actual.getCount();
        if(!remainder.isEmpty() && returnRoom<remainder.getCount() && free<task.amount+1) {
            blocked(citizen,entity,"PRIEST_BAG_FULL");return;
        }
        for(int i=0;i<task.amount;i++)citizen.insertIntoInventory(be.removeItem(i,1));
        task.bottlesInBag=task.amount;task.bagProduct=BrewingRecipes.identity(step.output());
        if(!remainder.isEmpty()) {var actual=be.removeItem(3,remainder.getCount());citizen.insertIntoInventory(actual);cargo(task,actual.getItem(),actual.getCount());}
        be.setChanged();
        if(task.cancelRequested || task.step==plan.size()-1) {task.phase=PriestBrewingTask.DELIVER;release(be,task);}
        else {task.step++;task.phase=PriestBrewingTask.LOAD;}
    }
    private static void deliver(ServerLevel level,SettlementSavedData data,SettlementRecord settlement,
                                CitizenRecord citizen,SettlementCitizenEntity entity,PriestBrewingTask task) {
        if(task.bottlesInBag>0) {
            var expected=BrewingRecipes.stack(task.bagProduct);
            int actual=inBag(citizen,expected);
            if(actual<task.bottlesInBag) {task.bottlesInBag=actual;task.cancelRequested=true;task.interference=true;}
            if(task.bottlesInBag>0) {
                int moved=deliverStack(level,settlement,citizen,entity,expected,task.bottlesInBag);
                task.bottlesInBag-=moved;
                if(Objects.equals(task.product,task.bagProduct))task.delivered+=moved;
                return;
            }
        }
        for(var entry:new ArrayList<>(task.cargo.entrySet())) {
            var ingredient=item(entry.getKey());var expected=new ItemStack(ingredient);
            int actual=Math.min(entry.getValue(),inBag(citizen,expected));
            if(actual<=0) {task.cargo.remove(entry.getKey());continue;}
            task.cargo.put(entry.getKey(),actual);
            int moved=deliverStack(level,settlement,citizen,entity,expected,actual);cargo(task,ingredient,-moved);return;
        }
        finish(level,data,citizen,entity);
    }
    private static int deliverStack(ServerLevel level,SettlementRecord settlement,CitizenRecord citizen,
                                    SettlementCitizenEntity entity,ItemStack expected,int limit) {
        var available=citizen.inventory().stream().filter(s->BrewingRecipes.matches(s,expected)).findFirst().orElse(ItemStack.EMPTY);
        if(available.isEmpty())return 0;
        var offered=available.copyWithCount(Math.min(limit,available.getCount()));
        var destination=SettlementInventoryService.warehouses(settlement).stream()
                .filter(b->!settlement.community().danger(b.position()) && WarehouseInventoryService.canAccept(level,b,offered))
                .min(Comparator.comparingDouble(b->b.position().distSqr(citizen.lastPosition()))).orElse(null);
        if(destination==null) {blocked(citizen,entity,"PRIEST_STORAGE_FULL");return 0;}
        var station=WarehouseInventoryService.approachPosition(level,destination,entity);
        if(station==null)station=WarehouseInventoryService.reserveInsertionPosition(level,destination,entity,offered);
        if(station==null) {blocked(citizen,entity,"PRIEST_ROUTE_BLOCKED");return 0;}
        CommunityLifeService.move(citizen,entity,station,CitizenActivity.DELIVERING_POTIONS,"");
        if(!WarehouseInventoryService.canInteract(level,destination,entity))return 0;
        var remaining=WarehouseInventoryService.insertForResident(level,destination,entity,offered);
        int moved=offered.getCount()-remaining.getCount();
        if(moved>0) {
            citizen.extractInventoryItem(s->BrewingRecipes.matches(s,expected),moved);
            CommunityStock.invalidate(level,settlement);
        }
        return moved;
    }
    private static void finish(ServerLevel level,SettlementSavedData data,CitizenRecord citizen,SettlementCitizenEntity entity) {
        citizen.life().brewing=null;citizen.life().clearTask();WarehouseInventoryService.releaseInteractionPosition(level,entity);
        WALK_TARGET.remove(citizen);ROUTE_RETRY.remove(citizen);SUPPLY_RETRY.remove(citizen);SUPPLY_TRIPS.remove(citizen);RETRY.put(citizen,level.getGameTime()+20);
        citizen.setActivity(CitizenActivity.PRIEST_IDLE,"");entity.setActivity(CitizenActivity.PRIEST_IDLE);data.touch();
    }
}
