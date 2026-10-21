package com.hkin.exiledsettlement.building;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public final class BuildingProductionService {
    public static final List<ResourceLocation> FARM_CROPS = List.of(
            id("wheat"), id("carrots"), id("potatoes"), id("beetroots"));
    public static final List<ResourceLocation> PASTURE_ANIMALS = List.of(
            id("sheep"), id("cow"), id("pig"), id("chicken"));
    public static final List<ResourceLocation> KILN_PRODUCTS = KilnRecipes.PRODUCTS;
    public static boolean kilnUsesFuel(ResourceLocation product) {
        var recipe=KilnRecipes.forOutput(BuiltInRegistries.ITEM.get(product));
        return recipe!=null && recipe.usesFuel();
    }
    public static final ResourceLocation NONE = id("air");
    public static final ResourceLocation MELON = id("melon_stem");
    public static final ResourceLocation PUMPKIN = id("pumpkin_stem");
    public static final ResourceLocation MIXED_MELONS = ResourceLocation.fromNamespaceAndPath("exiledsettlement", "mixed_melons");
    public static final List<ResourceLocation> MELON_CROPS = List.of(MELON, PUMPKIN, MIXED_MELONS);

    public static final ResourceLocation MIXED_CROPS = ResourceLocation.fromNamespaceAndPath("exiledsettlement", "mixed_crops");
    public static final List<ResourceLocation> FARM_PRODUCTIONS = List.of(FARM_CROPS.get(0),FARM_CROPS.get(1),FARM_CROPS.get(2),FARM_CROPS.get(3),MIXED_CROPS);
    public static ResourceLocation cropAt(ResourceLocation selected,net.minecraft.core.BlockPos ground) {
        return selected.equals(MIXED_CROPS)?FARM_CROPS.get(Math.floorMod(ground.getZ(),FARM_CROPS.size())):selected;
    }
    private static boolean seedMatches(ResourceLocation selected,Item item) {
        if(selected.equals(MIXED_CROPS))return FARM_CROPS.stream().anyMatch(c->seed(c)==item);
        return selected.equals(MIXED_MELONS)?item==Items.MELON_SEEDS || item==Items.PUMPKIN_SEEDS:item==seed(selected);
    }

    private BuildingProductionService() {
    }

    public static ResourceLocation defaultFor(BuildingType type) {
        return type == BuildingType.CHURCH ? BrewingRecipes.DEFAULT_PRODUCT
                : type == BuildingType.BRICK_KILN ? KILN_PRODUCTS.getFirst()
                : type == BuildingType.FARM_FIELD ? FARM_CROPS.getFirst()
                : type == BuildingType.MELON_FIELD ? MELON
                : type == BuildingType.PASTURE_AREA ? PASTURE_ANIMALS.getFirst() : NONE;
    }

    public static boolean supported(BuildingType type, ResourceLocation id) {
        return type == BuildingType.CHURCH ? BrewingRecipes.supported(id)
                : type == BuildingType.BRICK_KILN ? KILN_PRODUCTS.contains(id)
                : type == BuildingType.FARM_FIELD ? FARM_PRODUCTIONS.contains(id)
                : type == BuildingType.MELON_FIELD ? MELON_CROPS.contains(id)
                : type == BuildingType.PASTURE_AREA && PASTURE_ANIMALS.contains(id);
    }

    public static CropBlock crop(ResourceLocation id) {
        Block block = BuiltInRegistries.BLOCK.get(id);
        return block instanceof CropBlock crop ? crop : (CropBlock) Blocks.WHEAT;
    }

    public static Item seed(ResourceLocation cropId) {
        if (cropId.equals(id("carrots"))) return Items.CARROT;
        if (cropId.equals(id("potatoes"))) return Items.POTATO;
        if (cropId.equals(id("beetroots"))) return Items.BEETROOT_SEEDS;
        if (cropId.equals(MELON)) return Items.MELON_SEEDS;
        if (cropId.equals(PUMPKIN)) return Items.PUMPKIN_SEEDS;
        if (cropId.equals(MIXED_MELONS)) return Items.MELON_SEEDS;
        return Items.WHEAT_SEEDS;
    }

    /** Stable alternating planting cells; old fields remain melon-only by default. */
    public static Item plantingSeed(BuildingRecord farm, net.minecraft.core.BlockPos ground) {
        var selected=productionAt(farm,ground);
        if (selected.equals(MIXED_MELONS)) {
            return Math.floorMod(ground.getX() + ground.getZ(), 2) == 0 ? Items.MELON_SEEDS : Items.PUMPKIN_SEEDS;
        }
        return seed(selected);
    }

    public static ResourceLocation productionAt(BuildingRecord farm,net.minecraft.core.BlockPos ground){return cropAt(farm.productionSite()!=null?farm.productionSite().productionAt(ground,farm.productionId()):farm.region()==null?farm.productionId():farm.region().production(ground,farm.productionId()),ground);}
    public static boolean isPlantingSeed(BuildingRecord farm, Item item) {
        if(farm.productionSite()!=null)return farm.productionSite().productions().stream().anyMatch(id->seedMatches(id,item));
        if(farm.region()!=null)return farm.region().productions().stream().anyMatch(id->seedMatches(id,item));
        return seedMatches(farm.productionId(),item);
    }

    @Nullable
    public static EntityType<? extends Animal> animal(ResourceLocation id) {
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.get(id);
        if (type == EntityType.SHEEP) return EntityType.SHEEP;
        if (type == EntityType.COW) return EntityType.COW;
        if (type == EntityType.PIG) return EntityType.PIG;
        if (type == EntityType.CHICKEN) return EntityType.CHICKEN;
        return null;
    }

    public static Item displayItem(BuildingType type, ResourceLocation id) {
        if (type == BuildingType.CHURCH) return BrewingRecipes.stack(id).getItem();
        if (type == BuildingType.BRICK_KILN) return BuiltInRegistries.ITEM.get(id);
        if (type == BuildingType.FARM_FIELD || type == BuildingType.MELON_FIELD) return seed(id);
        if (id.equals(id("cow"))) return Items.BEEF;
        if (id.equals(id("pig"))) return Items.PORKCHOP;
        if (id.equals(id("chicken"))) return Items.CHICKEN;
        return Items.WHITE_WOOL;
    }

    public static int animalCapacity(BuildingRecord building) {
        if(building.productionSite()!=null)return Math.max(Math.max(2,building.productionSite().usableArea()/8),building.productionSite().animalFloorTotal());
        int width = Math.max(1, building.maxCorner().getX() - building.minCorner().getX() + 1);
        int depth = Math.max(1, building.maxCorner().getZ() - building.minCorner().getZ() + 1);
        return building.region()!=null?Math.max(Math.max(2,building.region().readyPasture.size()/8),building.region().animalFloors.values().stream().mapToInt(Integer::intValue).sum()):Math.max(2,Math.max(1,width-2)*Math.max(1,depth-2)/8);
    }

    public static boolean matchesAnimal(Animal animal, ResourceLocation id) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(animal.getType()).equals(id);
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.withDefaultNamespace(path);
    }
}
