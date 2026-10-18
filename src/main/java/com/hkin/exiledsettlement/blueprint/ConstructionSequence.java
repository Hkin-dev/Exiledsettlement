package com.hkin.exiledsettlement.blueprint;

import com.google.gson.JsonParser;
import com.hkin.exiledsettlement.building.BuildingType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.*;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Template-local phase preferences. No world writes, material grants or navigation permissions. */
public final class ConstructionSequence {
    private static final List<String> PHASES = List.of("foundation", "furnishings", "shell", "roof", "attachments", "entrance");
    private static final Map<String, Profile> PROFILES = load();
    private ConstructionSequence() { }

    public static boolean hasProfile(BuildingType type) { return PROFILES.containsKey(type.serializedName()); }

    public static Map<BlockPos, Integer> compile(BuildingBlueprintTemplate template) {
        Profile profile = PROFILES.get(template.type().serializedName());
        if (profile == null) return Map.of(); // Unfamiliar content retains the generic planner.
        Map<BlockPos, Integer> result = new HashMap<>();
        for (BlueprintPlacement placement : template.placements()) {
            if (template.type() == BuildingType.FARM_FIELD || template.type() == BuildingType.MELON_FIELD) {
                result.put(placement.position(), agriculturalRank(placement));
                continue;
            }
            String phase = phase(template, placement, profile.roofStart);
            int rank = placement.mode() == BlueprintPlacementMode.CLEARANCE ? -2
                    : placement.mode() == BlueprintPlacementMode.OPTIONAL_SUPPORT ? -1 : profile.phases.indexOf(phase);
            result.put(placement.position(), rank);
        }
        return Map.copyOf(result);
    }

    /** Fence, paths, contained irrigation, then soil; geometry remains free to vary by field size. */
    private static int agriculturalRank(BlueprintPlacement placement) {
        if (placement.mode() == BlueprintPlacementMode.CLEARANCE) return -2;
        Block block = placement.state().getBlock();
        if (block instanceof FenceBlock || block instanceof FenceGateBlock) return 0;
        if (block == Blocks.GRAVEL || block == Blocks.DIRT) return 1;
        if (placement.state().hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED)
                && placement.state().getValue(
                net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED)) return 2;
        if (block == Blocks.WATER || placement.mode() == BlueprintPlacementMode.FARM_WATER) return 2;
        if (block == Blocks.FARMLAND || placement.mode() == BlueprintPlacementMode.FARM_TILL) return 3;
        return 1;
    }

    private static String phase(BuildingBlueprintTemplate template, BlueprintPlacement placement, int roofStart) {
        Block block = placement.state().getBlock();
        int height = placement.position().getY() - template.minCorner().getY();
        if (height <= 0 || placement.mode() == BlueprintPlacementMode.FARM_TILL
                || placement.mode() == BlueprintPlacementMode.FARM_WATER) return "foundation";
        if (block instanceof DoorBlock || block instanceof FenceGateBlock) return "entrance";
        if (block instanceof LanternBlock || block instanceof BellBlock || block instanceof TrapDoorBlock
                || block instanceof TorchBlock) return "attachments";
        if (height >= roofStart) return "roof";
        if (block instanceof BedBlock || placement.state().hasBlockEntity()) return "furnishings";
        return "shell";
    }

    private static Map<String, Profile> load() {
        String path = "/data/exiledsettlement/construction_sequences.json";
        try (var stream = ConstructionSequence.class.getResourceAsStream(path)) {
            if (stream == null) throw new IllegalStateException("Missing " + path);
            var root = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
            if (root.get("version").getAsInt() != 1) throw new IllegalStateException("Unsupported construction sequence version");
            Map<String, Profile> result = new HashMap<>();
            for (var entry : root.getAsJsonObject("profiles").entrySet()) {
                var value = entry.getValue().getAsJsonObject();
                List<String> phases = new ArrayList<>();
                value.getAsJsonArray("phases").forEach(p -> phases.add(p.getAsString()));
                int roof = value.get("roof_start").getAsInt();
                if (phases.size() != PHASES.size() || !new HashSet<>(phases).equals(new HashSet<>(PHASES))
                        || roof < 1 || roof > 64) throw new IllegalStateException("Invalid sequence " + entry.getKey());
                result.put(entry.getKey(), new Profile(roof, List.copyOf(phases)));
            }
            return Map.copyOf(result);
        } catch (java.io.IOException failure) { throw new IllegalStateException("Cannot read " + path, failure); }
    }

    private record Profile(int roofStart, List<String> phases) { }
}
