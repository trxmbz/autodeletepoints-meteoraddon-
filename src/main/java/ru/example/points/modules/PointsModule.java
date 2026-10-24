package ru.example.points.modules;

import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.render.WaypointsModule;
import meteordevelopment.meteorclient.systems.waypoints.Waypoint;
import meteordevelopment.meteorclient.systems.waypoints.Waypoints;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.meteorclient.utils.world.Dimension;
import meteordevelopment.orbit.EventHandler;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import ru.example.points.PointsAddon;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Читает точки из файла  .minecraft/meteor-client/points.txt
 * Формат строки:  x y z [измерение] [радиус] [имя]
 *   100 64 -250
 *   0 70 0 minecraft:the_nether
 *   50 80 50 minecraft:overworld 3.5 База
 *   10 65 10 Алмазы
 * Порядок необязателен: после координат токены распознаются как измерение
 * (содержит ':' либо overworld/nether/end), число — радиус, остальное — имя.
 * Строки с # — комментарии.
 *
 * Для каждой точки создаётся путевая точка Meteor (значок + имя + расстояние
 * в мире, как в Xaero). Когда игрок подходит ближе радиуса, точка удаляется
 * и из файла, и из путевых точек.
 */
public class PointsModule extends Module {
    private static final String WP_PREFIX = "[P] ";

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<Double> defaultRadius = sgGeneral.add(new DoubleSetting.Builder()
        .name("default-radius")
        .description("Радиус удаления для точек, где он не указан в файле.")
        .defaultValue(2.0)
        .min(0.5)
        .sliderMax(20)
        .build()
    );

    private final Setting<Boolean> notify = sgGeneral.add(new BoolSetting.Builder()
        .name("notify")
        .description("Писать в чат, когда точка достигнута и удалена.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> labels = sgRender.add(new BoolSetting.Builder()
        .name("labels")
        .description("Показывать точки как путевые точки Meteor: значок, имя и расстояние (как в Xaero).")
        .defaultValue(true)
        .build()
    );

    private final Setting<SettingColor> color = sgRender.add(new ColorSetting.Builder()
        .name("color")
        .description("Цвет точек.")
        .defaultValue(new SettingColor(0, 255, 180, 255))
        .build()
    );

    private final Setting<Boolean> box = sgRender.add(new BoolSetting.Builder()
        .name("world-box")
        .description("Дополнительно рисовать кубик и луч вверх над точкой.")
        .defaultValue(false)
        .build()
    );

    private static class Entry {
        final String raw;
        final boolean point;
        final double x, y, z;
        final String dim;      // null = любое измерение
        final Double radius;   // null = брать из настройки
        final String name;     // null = координаты
        Waypoint waypoint;     // путевая точка Meteor (если создана)

        Entry(String raw) {
            this.raw = raw;
            this.point = false;
            this.x = this.y = this.z = 0;
            this.dim = null;
            this.radius = null;
            this.name = null;
        }

        Entry(String raw, double x, double y, double z, String dim, Double radius, String name) {
            this.raw = raw;
            this.point = true;
            this.x = x;
            this.y = y;
            this.z = z;
            this.dim = dim;
            this.radius = radius;
            this.name = name;
        }
    }

    private final List<Entry> entries = new ArrayList<>();
    private final Path file = FabricLoader.getInstance().getGameDir()
        .resolve("meteor-client").resolve("points.txt");

    private boolean needsSync;
    private String lastDim = "";

    public PointsModule() {
        super(PointsAddon.CATEGORY, "points", "Удаляет точки из points.txt, когда вы до них добираетесь.");
    }

    @Override
    public void onActivate() {
        load();
        info("Точек загружено: " + entries.stream().filter(e -> e.point).count());

        if (labels.get()) {
            WaypointsModule wm = Modules.get().get(WaypointsModule.class);
            if (wm != null && !wm.isActive()) {
                wm.toggle();
                info("Включил модуль Waypoints, чтобы точки были видны в мире.");
            }
        }

        needsSync = true;
        lastDim = "";
    }

    @Override
    public void onDeactivate() {
        clearWaypoints();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.level == null) return;

        String dimId = mc.level.dimension().identifier().toString();
        if (!dimId.equals(lastDim)) {
            lastDim = dimId;
            needsSync = true;
        }
        if (needsSync) {
            needsSync = false;
            syncWaypoints();
        }

        if (entries.isEmpty()) return;

        double px = mc.player.getX(), py = mc.player.getY(), pz = mc.player.getZ();
        boolean changed = false;

        Iterator<Entry> it = entries.iterator();
        while (it.hasNext()) {
            Entry e = it.next();
            if (!e.point) continue;
            if (e.dim != null && !e.dim.equals(dimId)) continue;

            double r = e.radius != null ? e.radius : defaultRadius.get();
            double dx = px - e.x, dy = py - e.y, dz = pz - e.z;

            if (dx * dx + dy * dy + dz * dz <= r * r) {
                it.remove();
                changed = true;
                if (e.waypoint != null) {
                    Waypoints.get().remove(e.waypoint);
                    e.waypoint = null;
                }
                if (notify.get()) info(String.format("Точка %.0f %.0f %.0f достигнута и удалена.", e.x, e.y, e.z));
            }
        }

        if (changed) save();
    }

    @EventHandler
    private void onRender3d(Render3DEvent event) {
        if (!box.get() || mc.level == null) return;

        String dimId = mc.level.dimension().identifier().toString();
        SettingColor c = color.get();
        Color side = new Color(c.r, c.g, c.b, Math.max(20, c.a / 4));

        for (Entry e : entries) {
            if (!e.point) continue;
            if (e.dim != null && !e.dim.equals(dimId)) continue;

            double bx = Math.floor(e.x), by = Math.floor(e.y), bz = Math.floor(e.z);
            event.renderer.box(bx, by, bz, bx + 1, by + 1, bz + 1, side, c, ShapeMode.Both, 0);
            event.renderer.line(bx + 0.5, by + 1, bz + 0.5, bx + 0.5, by + 100, bz + 0.5, c);
        }
    }

    // ---- путевые точки Meteor ----

    private void syncWaypoints() {
        clearWaypoints();
        if (!labels.get()) return;

        Dimension current = PlayerUtils.getDimension();

        for (Entry e : entries) {
            if (!e.point) continue;

            Dimension dim = dimensionOf(e.dim, current);
            String name = WP_PREFIX + (e.name != null
                ? e.name
                : String.format("%.0f %.0f %.0f", e.x, e.y, e.z));

            Waypoint wp = new Waypoint.Builder()
                .name(name)
                .pos(new BlockPos((int) Math.floor(e.x), (int) Math.floor(e.y), (int) Math.floor(e.z)))
                .dimension(dim)
                .build();
            wp.color.set(color.get());

            Waypoints.get().add(wp);
            e.waypoint = wp;
        }
    }

    /** Удаляет все наши путевые точки (в том числе оставшиеся от прошлых запусков). */
    private void clearWaypoints() {
        for (Entry e : entries) e.waypoint = null;

        List<Waypoint> stale = new ArrayList<>();
        for (Waypoint wp : Waypoints.get()) {
            if (wp.name.get().startsWith(WP_PREFIX)) stale.add(wp);
        }
        if (!stale.isEmpty()) Waypoints.get().removeAll(stale);
    }

    private static Dimension dimensionOf(String dimId, Dimension fallback) {
        if (dimId == null) return fallback;
        return switch (dimId) {
            case "minecraft:overworld" -> Dimension.Overworld;
            case "minecraft:the_nether" -> Dimension.Nether;
            case "minecraft:the_end" -> Dimension.End;
            default -> fallback;
        };
    }

    // ---- файл ----

    private void load() {
        entries.clear();
        try {
            Files.createDirectories(file.getParent());
            if (!Files.exists(file)) {
                Files.writeString(file,
                    "# Формат: x y z [измерение] [радиус] [имя]\n# Пример:\n# 100 64 -250 База\n",
                    StandardCharsets.UTF_8);
            }
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                entries.add(parse(line));
            }
        } catch (IOException ex) {
            PointsAddon.LOG.error("Не удалось прочитать points.txt", ex);
            error("Не удалось прочитать points.txt.");
        }
    }

    private Entry parse(String line) {
        String t = line.trim();
        if (t.isEmpty() || t.startsWith("#")) return new Entry(line);
        try {
            String[] p = t.replace(',', ' ').split("\\s+");
            double x = Double.parseDouble(p[0]);
            double y = Double.parseDouble(p[1]);
            double z = Double.parseDouble(p[2]);

            String dim = null;
            Double radius = null;
            StringBuilder name = new StringBuilder();

            for (int i = 3; i < p.length; i++) {
                String tok = p[i];
                String low = tok.toLowerCase();

                if (name.length() == 0 && dim == null && isDimensionToken(low)) {
                    dim = normalizeDim(low);
                } else if (name.length() == 0 && radius == null && isNumber(tok)) {
                    radius = Double.parseDouble(tok);
                } else {
                    if (name.length() > 0) name.append(' ');
                    name.append(tok);
                }
            }

            if (radius != null && radius <= 0) radius = null;
            return new Entry(line, x, y, z, dim, radius, name.length() > 0 ? name.toString() : null);
        } catch (Exception ex) {
            PointsAddon.LOG.warn("Не удалось разобрать строку в points.txt: {}", line);
            return new Entry(line);
        }
    }

    private static boolean isDimensionToken(String s) {
        return s.contains(":") || s.equals("overworld") || s.equals("nether") || s.equals("the_nether")
            || s.equals("end") || s.equals("the_end");
    }

    private static String normalizeDim(String s) {
        if (s.contains(":")) return s;
        return switch (s) {
            case "nether", "the_nether" -> "minecraft:the_nether";
            case "end", "the_end" -> "minecraft:the_end";
            default -> "minecraft:overworld";
        };
    }

    private static boolean isNumber(String s) {
        try {
            Double.parseDouble(s);
            return true;
        } catch (NumberFormatException ex) {
            return false;
        }
    }

    private void save() {
        List<String> out = new ArrayList<>();
        for (Entry e : entries) out.add(e.raw);
        try {
            Files.write(file, out, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            PointsAddon.LOG.error("Не удалось записать points.txt", ex);
            error("Не удалось записать points.txt.");
        }
    }
}
