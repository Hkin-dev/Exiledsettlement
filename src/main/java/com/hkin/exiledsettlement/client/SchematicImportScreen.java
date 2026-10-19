package com.hkin.exiledsettlement.client;

import com.hkin.exiledsettlement.blueprint.*;
import com.hkin.exiledsettlement.building.BuildingType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Local browser, independent of external projection mods and their placement state. */
public final class SchematicImportScreen extends Screen {
    private final Screen parent;
    private final BlockPos core;
    private final int radius;
    private Path directory;
    private List<Path> files = List.of();
    private int page, materialPage, typeIndex;
    private EditBox path, name, entry;
    private SchematicDocument document;
    private Direction outward = Direction.NORTH;
    private Component status = Component.empty();
    private List<String> materials = List.of();
    private CompletableFuture<SchematicDocument> reading;
    private int request;
    private static final BuildingType[] TYPES = java.util.stream.Stream.concat(
            java.util.stream.Stream.of(BuildingType.BENCH), Arrays.stream(BuildingType.values())
                    .filter(t -> t != BuildingType.BENCH && t != BuildingType.WAGON && t != BuildingType.STARTER_CAMP))
            .toArray(BuildingType[]::new);

    public SchematicImportScreen(Screen parent, BlockPos core, int radius) {
        super(t("title")); this.parent = parent; this.core = core.immutable(); this.radius = radius;
        directory = Minecraft.getInstance().gameDirectory.toPath().resolve("schematics").toAbsolutePath().normalize();
        browse(directory);
    }
    private static Component t(String key, Object... args) { return Component.translatable("gui.exiledsettlement.schematic." + key, args); }
    private int left() { return width / 2 - 170; }
    private int top() { return Math.max(4, height / 2 - 115); }
    private void button(Component label, int x, int y, int width, Runnable action) {
        addRenderableWidget(Button.builder(label, b -> action.run()).bounds(left() + x, top() + y, width, 20).build());
    }
    private void browse(Path folder) {
        try {
            directory = folder.toAbsolutePath().normalize();
            if (Files.isDirectory(directory)) try (var stream = Files.list(directory)) {
                files = stream.filter(p -> Files.isDirectory(p) || supported(p)).sorted(
                        Comparator.comparing((Path p) -> !Files.isDirectory(p)).thenComparing(p -> p.getFileName().toString(), String.CASE_INSENSITIVE_ORDER)).toList();
            } else files = List.of();
            page = 0; status = files.isEmpty() ? t("empty") : Component.empty();
        } catch (Exception error) { files = List.of(); status = Component.literal(error.getMessage()); }
    }
    private static boolean supported(Path p) {
        String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".litematic") || name.endsWith(".schem");
    }
    @Override protected void init() {
        int x = left(), y = top();
        if (document == null) {
            path = new EditBox(font, x, y + 22, 270, 20, t("path")); path.setMaxLength(4096);
            path.setValue(directory.toString()); addRenderableWidget(path);
            button(t("open"), 275, 22, 65, () -> {
                try { choose(Path.of(path.getValue())); } catch (RuntimeException error) { status = Component.literal(error.getMessage()); }
            });
            for (int i = 0; i < 6 && page * 6 + i < files.size(); i++) {
                Path file = files.get(page * 6 + i);
                String label = (Files.isDirectory(file) ? "▸ " : "") + file.getFileName();
                button(Component.literal(font.plainSubstrByWidth(label, 320)), 0, 47 + 22 * i, 340, () -> choose(file));
            }
            button(t("up"), 0, 181, 70, () -> { if (directory.getParent() != null) { browse(directory.getParent()); rebuildWidgets(); } });
            button(Component.literal("‹"), 75, 181, 35, () -> { if (page > 0) { page--; rebuildWidgets(); } });
            button(Component.literal((page + 1) + "/" + Math.max(1, (files.size() + 5) / 6)), 115, 181, 70, () -> { browse(directory); rebuildWidgets(); });
            button(Component.literal("›"), 190, 181, 35, () -> { if ((page + 1) * 6 < files.size()) { page++; rebuildWidgets(); } });
            button(t("back"), 230, 181, 110, this::onClose);
        } else {
            String oldName = name == null ? document.name() : name.getValue();
            String oldEntry = entry == null ? document.defaultEntrance().position().toShortString() : entry.getValue();
            name = new EditBox(font, x, y + 35, 340, 20, t("name")); name.setMaxLength(32); name.setValue(oldName); addRenderableWidget(name);
            button(typeLabel(), 0, 59, 340, () -> { typeIndex = (typeIndex + 1) % TYPES.length; refreshMaterials(); rebuildWidgets(); });
            entry = new EditBox(font, x, y + 97, 230, 20, t("entry")); entry.setMaxLength(64); entry.setValue(oldEntry); addRenderableWidget(entry);
            button(t("outward", Component.translatable("direction.exiledsettlement." + outward.getName())), 240, 97, 100,
                    () -> { outward = outward.getClockWise(); rebuildWidgets(); });
            button(t("materials"), 240, 123, 100, () -> materialPage = (materialPage + 1) % Math.max(1, (materials.size() + 3) / 4));
            button(t("preview"), 0, 181, 220, this::preview);
            button(t("choose_again"), 230, 181, 110, () -> { document = null; name = null; entry = null; status = Component.empty(); rebuildWidgets(); });
        }
    }
    private Component typeLabel() { return t("use", TYPES[typeIndex] == BuildingType.BENCH ? t("decoration") : Component.translatable(TYPES[typeIndex].translationKey())); }
    private void choose(Path file) {
        if (reading != null && !reading.isDone()) { status = t("reading"); return; }
        if (Files.isDirectory(file)) { browse(file); rebuildWidgets(); return; }
        status = t("reading");
        int token = ++request;
        reading = CompletableFuture.supplyAsync(() -> {
            try { return SchematicFileReader.read(file); }
            catch (Exception error) { throw new java.util.concurrent.CompletionException(error); }
        });
        reading.whenComplete((loaded, error) -> Minecraft.getInstance().execute(() -> {
            if (token != request || Minecraft.getInstance().screen != this) return;
            if (error != null) {
                Throwable cause = error.getCause() == null ? error : error.getCause();
                status = t("failed", cause.getMessage()); return;
            }
            document = loaded; name = null; entry = null; typeIndex = 0; materialPage = 0;
            outward = loaded.defaultEntrance().outward(); status = Component.empty();
            rebuildWidgets(); refreshMaterials();
        }));
    }
    private SchematicDocument.Draft draft() {
        String[] xyz = entry.getValue().split(",", -1);
        if (xyz.length != 3) throw new IllegalArgumentException("入口格式：x, y, z（相对投影左下角）");
        BlockPos pos = new BlockPos(Integer.parseInt(xyz[0].strip()), Integer.parseInt(xyz[1].strip()), Integer.parseInt(xyz[2].strip()));
        return document.draft(name.getValue(), TYPES[typeIndex], new BlueprintEntrance(pos, outward));
    }
    private void refreshMaterials() {
        if (entry == null) return;
        try {
            materials = draft().template(BlockPos.ZERO, Direction.NORTH).requirements().entrySet().stream()
                    .map(e -> e.getKey().getDescription().getString() + " × " + e.getValue()).sorted().toList();
            materialPage = 0;
        } catch (RuntimeException error) { status = t("failed", error.getMessage()); }
    }
    private void preview() {
        try {
            SchematicDocument.Draft draft = draft();
            draft.snapshot(BlockPos.ZERO, Direction.NORTH);
            if (minecraft.player == null || minecraft.level == null) return;
            minecraft.player.closeContainer(); minecraft.setScreen(null);
            ClientPlacementController.beginSchematic(draft, core, radius);
        } catch (RuntimeException error) { status = t("failed", error.getMessage()); }
    }
    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        renderBackground(graphics, mouseX, mouseY, delta); super.render(graphics, mouseX, mouseY, delta);
        int x = left(), y = top();
        graphics.drawString(font, title, x, y, 0xffffff);
        if (document != null) {
            graphics.drawString(font, t("size", document.width(), document.height(), document.depth(), document.count()), x, y + 20, 0xcccccc);
            graphics.drawString(font, t("entry"), x, y + 84, 0xcccccc);
            for (int i = 0; i < 4 && materialPage * 4 + i < materials.size(); i++)
                graphics.drawString(font, font.plainSubstrByWidth(materials.get(materialPage * 4 + i), 230), x, y + 125 + i * 12, 0xeeeeaa);
        }
        graphics.drawWordWrap(font, status, x, y + 207, 340, 0xdddddd);
    }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { request++; if (reading != null) reading.cancel(false); minecraft.setScreen(parent); }
}
