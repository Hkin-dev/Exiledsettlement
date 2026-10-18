package com.hkin.exiledsettlement.decoration;

import com.google.gson.*;
import com.hkin.exiledsettlement.building.BuildingType;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Frozen component versions and bounded planning parameters. */
public final class DecorationCatalog {
    public record Entry(String id, BuildingType type, int version, int family, int cost,int usageClearance) {
        public String displayName() { return switch (id) {
            case "lamp_small" -> "木柱路灯"; case "lamp_arm" -> "悬臂路灯";
            case "bench" -> "长椅"; case "signpost" -> "路牌"; case "flowerbed" -> "花坛";
            case "woodpile" -> "原木堆"; case "workbench" -> "加工台"; case "toolrack" -> "工具架";
            case "laundry" -> "晾衣架"; case "natural_patch" -> "自然石景";
            case "composter" -> "堆肥桶"; case "hay" -> "干草堆"; default -> id;
        }; }
    }
    public static final Map<String, Entry> ENTRIES = new LinkedHashMap<>();
    public static final int ATTEMPTS, PENDING_LIMIT, WOOD_RESERVE;
    static {
        try (var input = DecorationCatalog.class.getResourceAsStream("/data/exiledsettlement/decoration/catalog.json")) {
            if (input == null) throw new IllegalStateException("Missing decoration catalog");
            var root = JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
            if (root.get("schema_version").getAsInt() != 1) throw new IllegalStateException("Decoration catalog version");
            ATTEMPTS = Math.clamp(root.get("attempts_per_cycle").getAsInt(), 1, 16);
            PENDING_LIMIT = Math.clamp(root.get("pending_limit").getAsInt(), 1, 8);
            WOOD_RESERVE = Math.clamp(root.get("wood_reserve").getAsInt(), 0, 1024);
            for (var element : root.getAsJsonArray("entries")) {
                var e = element.getAsJsonObject(); var id = e.get("id").getAsString();
                ENTRIES.put(id, new Entry(id, BuildingType.fromSerializedName(e.get("type").getAsString()), e.get("version").getAsInt(), e.get("family").getAsInt(), e.get("cost").getAsInt(),e.has("usage_clearance")?Math.clamp(e.get("usage_clearance").getAsInt(),0,3):0));
            }
        } catch (IOException e) { throw new IllegalStateException(e); }
    }
    private DecorationCatalog() { }
}
