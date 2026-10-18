package com.hkin.exiledsettlement.gametest;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hkin.exiledsettlement.ExiledSettlementMod;
import com.hkin.exiledsettlement.debug.BuildingLabService;
import com.hkin.exiledsettlement.debug.BuildingLabTemplate;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.UUID;

@GameTestHolder(ExiledSettlementMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class BuildingLabGameTests {
    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty", batch = "building_lab")
    public static void eightExactCandidatesLoadAndCutawayKeepsFurniture(GameTestHelper helper) throws Exception {
        // User-edited live candidates must survive seedFiles; validate packaged geometry in an isolated fixture.
        Path canonical = Files.createTempDirectory("exiledsettlement-canonical-lab-");
        for (String id : BuildingLabTemplate.IDS) {
            BuildingLabTemplate candidate;
            try (var stream = BuildingLabService.class.getResourceAsStream("/data/exiledsettlement/building_lab/" + id + ".json")) {
                helper.assertTrue(stream != null, "Missing packaged exact candidate: " + id);
                byte[] bytes = stream.readAllBytes();
                Files.write(canonical.resolve(id + ".json"), bytes);
                candidate = BuildingLabTemplate.load(canonical, id);
                var packaged = BuildingLabTemplate.parse(id, JsonParser.parseString(new String(bytes, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject());
                helper.assertTrue(candidate.blocks().equals(packaged.blocks()), "Candidate differs from complete packaged structure: " + id);
            }
            var full = candidate.at(BlockPos.ZERO, false);
            var cut = candidate.at(BlockPos.ZERO, true);
            helper.assertTrue(cut.size() < full.size() && cut.size() > 500, "Invalid interior cutaway: " + id);
            candidate.blocks().forEach((pos, entry) -> {
                if (entry.role().equals("furniture") || entry.role().equals("light") || entry.role().equals("desk")) {
                    helper.assertTrue(cut.containsKey(pos), "Cutaway removed interior program: " + id + pos);
                }
                if (entry.role().equals("roof") && pos.getY() > 1) {
                    var below = candidate.blocks().get(pos.below());
                    boolean lanternSupport = below != null && below.role().equals("light");
                    helper.assertTrue(!cut.containsKey(pos) || lanternSupport, "Cutaway kept roof: " + id);
                }
            });
            Files.delete(canonical.resolve(id + ".json"));
        }
        Files.delete(canonical);
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty", batch = "building_lab_live")
    public static void liveDeltaBudgetCutawayAndPlayerEditsAreProtected(GameTestHelper helper) throws Exception {
        String id = "sawmill_round3_v2";
        BuildingLabService.seedFiles();
        Path file = BuildingLabService.directory().resolve(id + ".json");
        String original = Files.readString(file);
        UUID owner = UUID.randomUUID();
        BlockPos origin = helper.absolutePos(new BlockPos(1, 5, 1));
        try {
            JsonObject fixture = fixture(id);
            publish(file, fixture, 1000);
            BuildingLabService.start(helper.getLevel(), owner, id, origin);
            int placed = BuildingLabService.advancePending(helper.getLevel(), owner, id, 1);
            helper.assertTrue(placed == 1 && helper.getLevel().getBlockState(origin).is(Blocks.STONE),
                    "Initial placement did not honor the mutation budget");
            BuildingLabService.advancePending(helper.getLevel(), owner, id, 128);
            helper.assertTrue(helper.getLevel().getBlockState(origin.above(3)).is(Blocks.SPRUCE_PLANKS),
                    "Roof was not placed");

            fixture.getAsJsonArray("blocks").get(0).getAsJsonObject().addProperty("block", "minecraft:polished_andesite");
            publish(file, fixture, 2000);
            helper.assertTrue(BuildingLabService.pollChangedCandidate(helper.getLevel(), owner, id),
                    "File stamp change did not trigger a live update");
            helper.assertTrue(BuildingLabService.advancePending(helper.getLevel(), owner, id, 128) == 1,
                    "Small revision rewrote unchanged structure");
            helper.assertTrue(helper.getLevel().getBlockState(origin).is(Blocks.POLISHED_ANDESITE),
                    "Live revision did not reach the world");

            BuildingLabService.view(helper.getLevel(), owner, id, true);
            BuildingLabService.advancePending(helper.getLevel(), owner, id, 128);
            helper.assertTrue(helper.getLevel().getBlockState(origin.above(3)).isAir()
                    && helper.getLevel().getBlockState(origin.offset(1, 1, 0)).is(Blocks.CHEST),
                    "Interior cutaway removed furniture or retained roof");
            BuildingLabService.view(helper.getLevel(), owner, id, false);
            BuildingLabService.advancePending(helper.getLevel(), owner, id, 128);
            helper.assertTrue(helper.getLevel().getBlockState(origin.above(3)).is(Blocks.SPRUCE_PLANKS),
                    "Full view could not restore roof");

            helper.getLevel().setBlock(origin, Blocks.DIAMOND_BLOCK.defaultBlockState(), 2);
            fixture.getAsJsonArray("blocks").get(0).getAsJsonObject().addProperty("block", "minecraft:oak_planks");
            publish(file, fixture, 3000);
            boolean protectedEdit = false;
            try { BuildingLabService.pollChangedCandidate(helper.getLevel(), owner, id); }
            catch (IllegalArgumentException expected) { protectedEdit = true; }
            helper.assertTrue(protectedEdit && helper.getLevel().getBlockState(origin).is(Blocks.DIAMOND_BLOCK),
                    "Live reload overwrote a player's edit");

            Container chest = (Container) helper.getLevel().getBlockEntity(origin.offset(1, 1, 0));
            chest.setItem(0, new ItemStack(Items.DIAMOND));
            fixture.getAsJsonArray("blocks").get(0).getAsJsonObject().addProperty("block", "minecraft:polished_andesite");
            fixture.getAsJsonArray("blocks").remove(1);
            publish(file, fixture, 4000);
            boolean protectedInventory = false;
            try { BuildingLabService.pollChangedCandidate(helper.getLevel(), owner, id); }
            catch (IllegalArgumentException expected) { protectedInventory = true; }
            helper.assertTrue(protectedInventory && chest.getItem(0).is(Items.DIAMOND),
                    "Live reload destroyed used inventory");
            helper.succeed();
        } finally {
            BuildingLabService.stop(helper.getLevel(), owner, id);
            Files.writeString(file, original);
        }
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty", batch = "building_lab")
    public static void malformedCandidatesFailBeforeWorldWrites(GameTestHelper helper) {
        JsonObject source = fixture("town_hall_round3_v2");
        source.getAsJsonArray("blocks").add(source.getAsJsonArray("blocks").get(0).deepCopy());
        boolean duplicateRejected = false;
        try { BuildingLabTemplate.parse("town_hall_round3_v2", source); }
        catch (IllegalArgumentException expected) { duplicateRejected = true; }
        helper.assertTrue(duplicateRejected, "Duplicate candidate coordinates accepted");
        source = fixture("town_hall_round3_v2");
        source.getAsJsonObject("size").addProperty("x", 10000);
        boolean boundsRejected = false;
        try { BuildingLabTemplate.parse("town_hall_round3_v2", source); }
        catch (IllegalArgumentException expected) { boundsRejected = true; }
        helper.assertTrue(boundsRejected, "Unbounded candidate accepted");
        helper.succeed();
    }

    private static JsonObject fixture(String id) {
        String json = """
                {"schema_version":1,"id":"%s","display_name":"Lab regression",
                 "size":{"x":3,"y":4,"z":3},"blocks":[
                 {"pos":[0,0,0],"block":"minecraft:stone","role":"floor"},
                 {"pos":[1,1,0],"block":"minecraft:chest","role":"container"},
                 {"pos":[0,3,0],"block":"minecraft:spruce_planks","role":"roof"}]}
                """.formatted(id);
        return JsonParser.parseString(json).getAsJsonObject();
    }

    private static void publish(Path file, JsonObject content, long stamp) throws Exception {
        Files.writeString(file, content.toString());
        Files.setLastModifiedTime(file, FileTime.fromMillis(stamp));
    }
}
