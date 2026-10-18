package com.hkin.exiledsettlement.settlement;

import com.hkin.exiledsettlement.blueprint.*;
import com.hkin.exiledsettlement.building.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.*;
import net.minecraft.core.registries.BuiltInRegistries;
import java.util.*;

/** Read-only material accounting for the existing management report. Never accesses player inventory. */
public final class ConstructionShortageReport {
    private ConstructionShortageReport() { }
    public static List<String> lines(ServerLevel level, SettlementRecord settlement) {
        var needed=new LinkedHashMap<Item,Integer>();
        var locations=new LinkedHashMap<Item,LinkedHashSet<String>>();
        for(var site:settlement.blueprints()) {
            if(site.cancellationQueued() || !BlueprintRecord.supportsStructureVersion(site.structureVersion()))continue;
            var template=BlueprintTemplates.create(site);
            var workKind=com.hkin.exiledsettlement.network.WorksiteDisplayKind.of(settlement,site);
            String label=workKind.publicWorks()?"{{"+workKind.translationKey()+"}}":"{{"+site.type().translationKey()+"}}";
            for(var original:template.placements()) {
                var p=original;
                if(level.hasChunkAt(p.position()) && IrrigationConstructionService.applies(template,p)){
                    for(var item:IrrigationConstructionService.remainingMaterials(level,template,p))add(needed,locations,item,label+" @ "+site.anchor().toShortString());
                    continue;
                }
                var terrain=site.terrainProject();
                if(terrain!=null)for(int i:terrain.indices(p.position())){
                    var step=terrain.steps.get(i);
                    if(!terrain.finished.get(i) && step.fill() && com.hkin.exiledsettlement.terrain.BuriedFillMaterials.flexible(level,settlement,terrain,step)){
                        var state=com.hkin.exiledsettlement.terrain.BuriedFillMaterials.choose(level,settlement,terrain,step,null);
                        p=new BlueprintPlacement(p.position(),state,state.getBlock().asItem(),p.mode());break;
                    }
                }
                if(p.material()==null || p.material()==Items.AIR || p.mode()==BlueprintPlacementMode.CLEARANCE
                        || p.mode()==BlueprintPlacementMode.OPTIONAL_SUPPORT || !level.hasChunkAt(p.position())
                        || BlueprintPlacementGroups.isPaired(p.state()) && !BlueprintPlacementGroups.isPrimary(p.state())
                        || BlueprintPlacementGroups.satisfied(level,template,p))continue;
                add(needed,locations,p.material(),label+" @ "+site.anchor().toShortString());
            }
        }
        for(var building:settlement.buildings()) {
            if(!building.active() || building.demolitionQueued() || building.repairPositions().isEmpty()
                    || !BlueprintTemplates.supports(building.type()))continue;
            var template=BlueprintTemplates.createForBuilding(building);
            for(BlockPos at:building.repairPositions()) {
                if(com.hkin.exiledsettlement.region.FarmConstructionPolicy.farm(building)
                        && !com.hkin.exiledsettlement.terrain.TerrainService.productionStructure(building,at))continue;
                var p=template.placementAt(at);
                if(p!=null && level.hasChunkAt(at) && IrrigationConstructionService.applies(template,p)){
                    for(var item:IrrigationConstructionService.remainingMaterials(level,template,p))
                        add(needed,locations,item,"维修 {{"+building.type().translationKey()+"}} @ "+building.position().toShortString());
                    continue;
                }
                if(p!=null && p.material()!=null && level.hasChunkAt(at)
                        && !BlueprintPlacementGroups.satisfied(level,template,p))
                    add(needed,locations,p.material(),"维修 "+"{{"+building.type().translationKey()+"}}"+" @ "+building.position().toShortString());
            }
        }
        // Include temporary support requests even when they are not final template blocks.
        for(var citizen:settlement.citizens()) {
            if(!citizen.alive())continue;
            if(citizen.workFailure().equals("FOOTING_MATERIAL_MISSING") || citizen.workFailure().equals("SCAFFOLD_FRAME_MATERIAL_MISSING"))
                needed.merge(Items.SCAFFOLDING,1,Integer::sum);
            for(int slot=0;slot<citizen.inventory().size();slot++) {
                var stack=citizen.inventoryItem(slot);
                if(!citizen.pitReserveSlot(slot) && !stack.isEmpty() && needed.containsKey(stack.getItem()))
                    needed.computeIfPresent(stack.getItem(),(item,count)->Math.max(0,count-stack.getCount()));
            }
        }
        var missing=WarehouseConstructionSupply.shortages(level,settlement,needed);
        var lines=new ArrayList<String>();
        lines.add("—— 工程缺料 ——");
        if(missing.isEmpty())lines.add("已加载工程暂无建材缺口；材料由公共仓储供应。");
        else {
            lines.add("已计入居民携带、公共库存、同组替代与可用配方；缺料工程保留等待。未加载区域不计入。");
            var rows=new ArrayList<String>();
            for(var entry:missing.entrySet().stream().sorted(Comparator.comparing(e->BuiltInRegistries.ITEM.getKey(e.getKey()).toString())).toList()) {
                String name="{{"+entry.getKey().getDescriptionId()+"}}";
                var at=locations.get(entry.getKey());
                rows.add(name+"：缺 "+entry.getValue()+"，待用 "+needed.getOrDefault(entry.getKey(),0)
                        +(at==null?"（临时支撑）":"；"+String.join("、",at.stream().limit(2).toList())+(at.size()>2?" 等"+at.size()+"处":"")));
            }
            // Pack related rows under the existing report packet's bounded line count.
            for(int i=0;i<rows.size();i+=3)lines.add(String.join("\n",rows.subList(i,Math.min(i+3,rows.size()))));
        }
        return lines;
    }
    private static void add(Map<Item,Integer> need,Map<Item,LinkedHashSet<String>> sites,Item item,String site) {
        need.merge(item,1,Integer::sum);sites.computeIfAbsent(item,k->new LinkedHashSet<>()).add(site);
    }
}
