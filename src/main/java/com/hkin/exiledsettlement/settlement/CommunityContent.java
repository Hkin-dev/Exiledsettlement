package com.hkin.exiledsettlement.settlement;

import com.google.gson.*;
import com.hkin.exiledsettlement.ExiledSettlementMod;
import net.minecraft.server.packs.resources.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.profiling.ProfilerFiller;
import java.util.*;

/** Data-pack overrides are validated as a whole; invalid files retain the previous good values. */
public final class CommunityContent extends SimpleJsonResourceReloadListener {
    public static int seasonDays = 30, adulthoodDays = 32, birthIntervalDays = 32;
    public static int needIntervalTicks = 2400, nutritionLoss = 3, dietWindowDays = 8;
    public static int carriedFoodTarget = 16;
    public static int homeFuelTarget = 4, homeGarmentTarget = 1;
    public static int recoveryIntervalTicks = 100, recoveryNutritionMinimum = 80, recoveryNutritionCost = 2;
    public static int farmerWorkStartTick = 0, farmerWorkEndTick = 14000, scheduleOffsetTicks = 1000;
    public static boolean crisesEnabled = true;
    private static List<String> given = List.of("Mara", "Tomas", "Elin", "Rowan", "Ada", "Bram");
    private static List<String> surnames = List.of("Brook", "Field", "Reed", "Vale");
    public CommunityContent() { super(new Gson(), "community"); }
    @Override protected void apply(Map<ResourceLocation, JsonElement> files, ResourceManager manager, ProfilerFiller profiler) {
        var wildlife=files.get(ExiledSettlementMod.id("wildlife"));
        if(wildlife!=null)try {SettlementWildlifeService.reload(wildlife.getAsJsonObject());}
        catch(RuntimeException e){ExiledSettlementMod.LOGGER.warn("Rejected settlement wildlife settings",e);}
        var bridge=files.get(ExiledSettlementMod.id("bridge"));
        if(bridge!=null)try {com.hkin.exiledsettlement.road.BridgePalette.reload(bridge.getAsJsonObject());}
        catch(RuntimeException e){ExiledSettlementMod.LOGGER.warn("Rejected bridge material palette",e);}
        var thermal=files.get(ExiledSettlementMod.id("thermal"));
        if(thermal!=null)try {ThermalSettings.reload(thermal.getAsJsonObject());}
        catch(RuntimeException e){ExiledSettlementMod.LOGGER.warn("Rejected thermal balance",e);}
        var weather = files.get(ExiledSettlementMod.id("weather"));
        if (weather != null) {
            try { SeasonWeatherSettings.reload(weather.getAsJsonObject()); }
            catch (RuntimeException error) { ExiledSettlementMod.LOGGER.warn("Rejected season weather settings", error); }
        }
        JsonElement meals = files.get(ExiledSettlementMod.id("meals"));
        if (meals != null) {
            try { CommunityCookingService.reload(meals.getAsJsonArray()); }
            catch (RuntimeException e) { ExiledSettlementMod.LOGGER.warn("Rejected community meals", e); }
        }
        JsonElement names = files.get(ExiledSettlementMod.id("names"));
        if (names != null) {
            try {
                List<String> newGiven = names(names.getAsJsonObject().getAsJsonArray("given_names"));
                List<String> newSurnames = names(names.getAsJsonObject().getAsJsonArray("surnames"));
                if (newGiven.size() < 6 || newSurnames.isEmpty()) throw new IllegalArgumentException("Empty name pool");
                given = newGiven; surnames = newSurnames;
            } catch (RuntimeException e) { ExiledSettlementMod.LOGGER.warn("Rejected resident name pool", e); }
        }
        JsonElement balance = files.get(ExiledSettlementMod.id("balance"));
        if (balance != null) {
            try {
                JsonObject b = balance.getAsJsonObject();
                int s = bounded(b, "season_days", 30, 64), a = bounded(b, "adulthood_days", 4, 4096);
                int births = bounded(b, "birth_interval_days", 4, 4096);
                int interval = bounded(b, "need_interval_ticks", 200, 24000);
                int loss = bounded(b, "nutrition_loss", 1, 10), window = bounded(b, "diet_window_days", 1, 128);
                boolean crises = b.get("crises_enabled").getAsBoolean();
                int rations = b.has("carried_food_target") ? bounded(b, "carried_food_target", 0, 64) : 16;
                int homeFuel=b.has("home_fuel_target")?bounded(b,"home_fuel_target",0,64):4;
                int homeGarments=b.has("home_garment_target")?bounded(b,"home_garment_target",0,8):1;
                int healInterval = b.has("recovery_interval_ticks") ? bounded(b, "recovery_interval_ticks", 0, 24000) : 100;
                int healMinimum = b.has("recovery_nutrition_minimum") ? bounded(b, "recovery_nutrition_minimum", 1, 100) : 80;
                int healCost = b.has("recovery_nutrition_cost") ? bounded(b, "recovery_nutrition_cost", 1, 100) : 2;
                int farmStart = b.has("farmer_work_start_tick") ? bounded(b, "farmer_work_start_tick", 0, 23000) : 0;
                int farmEnd = b.has("farmer_work_end_tick") ? bounded(b, "farmer_work_end_tick", 1, 24000) : 14000;
                int offset = b.has("schedule_offset_ticks") ? bounded(b, "schedule_offset_ticks", 0, 1000) : 1000;
                if (farmStart >= farmEnd || farmEnd > 14000) throw new IllegalArgumentException("Farm work must leave nightly sleep");
                seasonDays = s; adulthoodDays = a; birthIntervalDays = births;
                needIntervalTicks = interval; nutritionLoss = loss; dietWindowDays = window; crisesEnabled = crises;
                carriedFoodTarget = rations;
                homeFuelTarget=homeFuel;homeGarmentTarget=homeGarments;
                recoveryIntervalTicks = healInterval; recoveryNutritionMinimum = healMinimum; recoveryNutritionCost = healCost;
                farmerWorkStartTick = farmStart; farmerWorkEndTick = farmEnd; scheduleOffsetTicks = offset;
            } catch (RuntimeException e) { ExiledSettlementMod.LOGGER.warn("Rejected community balance", e); }
        }
    }
    private static int bounded(JsonObject object, String key, int min, int max) {
        int value = object.get(key).getAsInt();
        if (value < min || value > max) throw new IllegalArgumentException(key); return value;
    }
    private static List<String> names(JsonArray array) {
        Set<String> values = new LinkedHashSet<>();
        for (JsonElement entry : array) {
            String value = entry.getAsString().strip();
            if (value.isEmpty() || value.length() > 24 || value.codePoints().anyMatch(Character::isISOControl))
                throw new IllegalArgumentException("Invalid resident name");
            values.add(value); if (values.size() > 1024) throw new IllegalArgumentException("Oversized name pool");
        }
        return List.copyOf(values);
    }
    public static int nameCapacity() { return given.size() * surnames.size(); }
    public static String newName(UUID id, Collection<com.hkin.exiledsettlement.citizen.CitizenRecord> citizens) {
        Set<String> used = new HashSet<>(); citizens.forEach(c -> used.add(c.name()));
        int count = nameCapacity(); int start = Math.floorMod(id.hashCode(), count);
        for (int i = 0; i < count; i++) {
            int slot = (start + i) % count;
            String candidate = given.get(slot % given.size()) + " " + surnames.get(slot / given.size());
            if (!used.contains(candidate)) return candidate;
        }
        String base = given.get(start % given.size());
        int suffix = 2; while (used.contains(base + " " + suffix)) suffix++;
        return base + " " + suffix;
    }
}
