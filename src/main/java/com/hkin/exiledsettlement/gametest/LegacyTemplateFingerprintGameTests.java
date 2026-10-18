package com.hkin.exiledsettlement.gametest;

import com.hkin.exiledsettlement.blueprint.*;
import com.hkin.exiledsettlement.building.BuildingType;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.neoforged.neoforge.gametest.*;
import java.util.*;

@GameTestHolder("exiledsettlement_templatebaseline")
@PrefixGameTestTemplate(false)
@net.neoforged.fml.common.EventBusSubscriber(modid="exiledsettlement", bus=net.neoforged.fml.common.EventBusSubscriber.Bus.MOD)
public final class LegacyTemplateFingerprintGameTests {
    public static final Map<BuildingType,Integer> VERSIONS = Map.ofEntries(
            Map.entry(BuildingType.HUNTING_LODGE,2), Map.entry(BuildingType.TOOL_WORKSHOP,3),
            Map.entry(BuildingType.TOWN_HALL,5), Map.entry(BuildingType.FISHING_HUT,2),
            Map.entry(BuildingType.SAWMILL,3), Map.entry(BuildingType.SCHOOL,3),
            Map.entry(BuildingType.MARKET,1), Map.entry(BuildingType.TAVERN,1),
            Map.entry(BuildingType.RESTAURANT,1), Map.entry(BuildingType.HOUSE,4),
            Map.entry(BuildingType.HOUSE_2,1), Map.entry(BuildingType.WAREHOUSE,7));
    @net.neoforged.bus.api.SubscribeEvent
    public static void register(net.neoforged.neoforge.event.RegisterGameTestsEvent event) {
        if ("distinct_baseline".equals(System.getProperty("exiledsettlement.testSuite"))) event.register(LegacyTemplateFingerprintGameTests.class);
    }
    public static Map<String,String> fingerprints() {
        Map<String,String> result = new TreeMap<>();
        for (var entry : VERSIONS.entrySet()) for (int v=1; v<=entry.getValue(); v++) {
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                var template = BlueprintTemplates.create(entry.getKey(), new BlockPos(127,80,253), direction,v);
                StringBuilder contents = new StringBuilder(template.minCorner()+"|"+template.maxCorner()+"|"+template.entrances()+"|"+template.transitionMargin()+"\n");
                template.placements().stream().map(p -> p.position()+"|"+p.state()+"|"+p.material()+"|"+p.mode())
                        .sorted().forEach(p -> contents.append(p).append('\n'));
                try {
                    result.put(entry.getKey().serializedName()+":"+v+":"+direction.getName(),
                            HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                                    .digest(contents.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8))));
                } catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
            }
        }
        return result;
    }
    @GameTest(templateNamespace="exiledsettlement_templatebaseline",template="empty",timeoutTicks=300)
    public static void captureOriginalTest44Templates(GameTestHelper h) throws Exception {
        java.nio.file.Files.writeString(java.nio.file.Path.of("legacy-template-fingerprints.json"),
                new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(fingerprints()));
        h.succeed();
    }
}
