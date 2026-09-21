package me.igor1944.minigamestg.arena;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import me.igor1944.minigamestg.MiniGamesTGPlugin;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Хранит дуэльные арены в plugins/MiniGamesTG/arenas.yml.
 */
public class ArenaManager {

    private final MiniGamesTGPlugin plugin;
    private final File file;
    private final Map<String, Arena> arenas = new LinkedHashMap<>();

    public ArenaManager(MiniGamesTGPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "arenas.yml");
    }

    public synchronized void load() {
        arenas.clear();
        if (!file.exists()) {
            return;
        }
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection section = cfg.getConfigurationSection("arenas");
        if (section == null) {
            return;
        }
        for (String name : section.getKeys(false)) {
            Arena arena = new Arena(name);
            arena.setPos1(section.getSerializable(name + ".pos1", Location.class));
            arena.setPos2(section.getSerializable(name + ".pos2", Location.class));
            arenas.put(name.toLowerCase(java.util.Locale.ROOT), arena);
        }
        plugin.getLogger().info("Загружено дуэльных арен: " + arenas.size());
    }

    public synchronized void save() {
        YamlConfiguration cfg = new YamlConfiguration();
        for (Arena arena : arenas.values()) {
            String path = "arenas." + arena.getName();
            if (arena.getPos1() != null) {
                cfg.set(path + ".pos1", arena.getPos1());
            }
            if (arena.getPos2() != null) {
                cfg.set(path + ".pos2", arena.getPos2());
            }
        }
        try {
            cfg.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Не удалось сохранить arenas.yml: " + e.getMessage());
        }
    }

    /** Создаёт арену. false — такое имя уже занято. */
    public synchronized boolean create(String name) {
        String key = name.toLowerCase(java.util.Locale.ROOT);
        if (arenas.containsKey(key)) {
            return false;
        }
        arenas.put(key, new Arena(name));
        save();
        return true;
    }

    /** Удаляет арену. false — не найдена. */
    public synchronized boolean delete(String name) {
        boolean removed = arenas.remove(name.toLowerCase(java.util.Locale.ROOT)) != null;
        if (removed) {
            save();
        }
        return removed;
    }

    public synchronized Arena get(String name) {
        return arenas.get(name.toLowerCase(java.util.Locale.ROOT));
    }

    public synchronized List<Arena> getAll() {
        return new ArrayList<>(arenas.values());
    }

    /** Случайная готовая арена (обе точки заданы) или null, если таких нет. */
    public synchronized Arena getRandomComplete() {
        List<Arena> complete = new ArrayList<>();
        for (Arena arena : arenas.values()) {
            if (arena.isComplete()) {
                complete.add(arena);
            }
        }
        if (complete.isEmpty()) {
            return null;
        }
        return complete.get(ThreadLocalRandom.current().nextInt(complete.size()));
    }

    /** Задаёт точку спавна (1 или 2) для арены. */
    public synchronized boolean setSpawn(String name, int index, Location location) {
        Arena arena = get(name);
        if (arena == null || (index != 1 && index != 2)) {
            return false;
        }
        if (index == 1) {
            arena.setPos1(location.clone());
        } else {
            arena.setPos2(location.clone());
        }
        save();
        return true;
    }
}
