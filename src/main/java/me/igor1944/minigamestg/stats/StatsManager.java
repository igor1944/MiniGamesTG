package me.igor1944.minigamestg.stats;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import me.igor1944.minigamestg.MiniGamesTGPlugin;
import me.igor1944.minigamestg.game.Game;
import me.igor1944.minigamestg.game.GameType;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Хранит профили игроков и историю игр в plugins/MiniGamesTG/data.json.
 * Все методы, изменяющие данные, синхронизированы — потокобезопасно
 * (Telegram-бот читает статистику из своего потока).
 */
public class StatsManager {

    /** Сколько последних игр хранить в истории. */
    private static final int HISTORY_LIMIT = 500;

    private final MiniGamesTGPlugin plugin;
    private final File dataFile;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    private final Map<UUID, PlayerRecord> players = new LinkedHashMap<>();
    private final Deque<GameHistoryEntry> history = new ArrayDeque<>();
    private long totalGames;

    /** Корневой объект файла data.json. */
    private static class Data {
        Map<UUID, PlayerRecord> players = new LinkedHashMap<>();
        List<GameHistoryEntry> history = new ArrayList<>();
        long totalGames;
    }

    public StatsManager(MiniGamesTGPlugin plugin) {
        this.plugin = plugin;
        this.dataFile = new File(plugin.getDataFolder(), "data.json");
    }

    // ---------- загрузка / сохранение ----------

    public synchronized void load() {
        if (!dataFile.exists()) {
            return;
        }
        try {
            String json = Files.readString(dataFile.toPath(), StandardCharsets.UTF_8);
            Data data = gson.fromJson(json, Data.class);
            if (data == null) {
                return;
            }
            players.clear();
            history.clear();
            if (data.players != null) {
                players.putAll(data.players);
            }
            if (data.history != null) {
                history.addAll(data.history);
            }
            totalGames = data.totalGames;
            plugin.getLogger().info("Загружена статистика: " + players.size()
                    + " игроков, " + history.size() + " игр в истории.");
        } catch (IOException | JsonSyntaxException e) {
            plugin.getLogger().warning("Не удалось прочитать data.json (" + e.getMessage()
                    + "). Файл сохранён как data.json.bak, статистика начата заново.");
            try {
                Files.copy(dataFile.toPath(), new File(plugin.getDataFolder(), "data.json.bak").toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException io) {
                plugin.getLogger().warning("Не удалось создать резервную копию data.json: " + io.getMessage());
            }
        }
    }

    /** Асинхронное сохранение (не блокирует основной поток сервера). */
    public void saveAsync() {
        final String json;
        synchronized (this) {
            json = toJson();
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> writeJson(json));
    }

    /** Синхронное сохранение (при выключении сервера). */
    public synchronized void saveNow() {
        writeJson(toJson());
    }

    private synchronized String toJson() {
        Data data = new Data();
        data.players.putAll(players);
        data.history.addAll(history);
        data.totalGames = totalGames;
        return gson.toJson(data);
    }

    private void writeJson(String json) {
        try {
            if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
                plugin.getLogger().warning("Не удалось создать папку плагина для data.json");
                return;
            }
            File tmp = new File(plugin.getDataFolder(), "data.json.tmp");
            Files.writeString(tmp.toPath(), json, StandardCharsets.UTF_8);
            Files.move(tmp.toPath(), dataFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            plugin.getLogger().warning("Не удалось сохранить data.json: " + e.getMessage());
        }
    }

    // ---------- работа с записями игроков ----------

    /** Обновляет ник игрока при входе на сервер. */
    public synchronized void touchPlayer(Player player) {
        ensureRecord(player.getUniqueId(), player.getName());
    }

    /** Возвращает запись игрока или null, если тот ещё не играл. */
    public synchronized PlayerRecord getRecord(UUID uuid) {
        return players.get(uuid);
    }

    /** Возвращает запись, создавая при необходимости. */
    public synchronized PlayerRecord ensureRecord(UUID uuid, String name) {
        PlayerRecord record = players.get(uuid);
        if (record == null) {
            record = new PlayerRecord(uuid, name);
            players.put(uuid, record);
        } else {
            record.setName(name);
        }
        return record;
    }

    /** Привязывает Telegram-чат к игроку. Возвращает false, если чат уже занят другим игроком. */
    public synchronized boolean link(UUID uuid, String name, long chatId, String telegramUsername) {
        for (PlayerRecord r : players.values()) {
            if (r.getTelegramChatId() == chatId && !r.getUuid().equals(uuid)) {
                return false;
            }
        }
        PlayerRecord record = ensureRecord(uuid, name);
        record.setTelegramChatId(chatId);
        record.setTelegramUsername(telegramUsername);
        saveAsync();
        return true;
    }

    /** Отвязывает Telegram от игрока. */
    public synchronized boolean unlink(UUID uuid) {
        PlayerRecord record = players.get(uuid);
        if (record == null || !record.isLinked()) {
            return false;
        }
        record.setTelegramChatId(0L);
        record.setTelegramUsername("");
        saveAsync();
        return true;
    }

    /** Ищет игрока по Telegram chat_id. */
    public synchronized PlayerRecord findByChatId(long chatId) {
        if (chatId == 0L) {
            return null;
        }
        for (PlayerRecord r : players.values()) {
            if (r.getTelegramChatId() == chatId) {
                return r;
            }
        }
        return null;
    }

    // ---------- учёт результатов ----------

    /** Записывает итоги завершённой игры и обновляет статистику участников. */
    public synchronized void recordGame(Game game, List<GameResult> results) {
        String typeName = game.getType().name();
        for (GameResult r : results) {
            PlayerRecord record = ensureRecord(r.getUuid(), r.getName());
            PlayerRecord.TypeStats stats = record.statsFor(typeName);
            stats.games++;
            stats.points += r.getPoints();
            if (r.isWinner()) {
                stats.wins++;
            }
        }
        totalGames++;
        history.addFirst(new GameHistoryEntry(System.currentTimeMillis(), game.getId(),
                typeName, game.getName(), results));
        while (history.size() > HISTORY_LIMIT) {
            history.removeLast();
        }
        saveAsync();
    }

    public synchronized long getTotalGames() {
        return totalGames;
    }

    /** Топ игроков. type == null — общий топ, иначе по конкретному типу игр. */
    public synchronized List<PlayerRecord> top(int limit, GameType type) {
        List<PlayerRecord> list = new ArrayList<>(players.values());
        Comparator<PlayerRecord> cmp = (type == null)
                ? Comparator.comparingInt(PlayerRecord::getTotalPoints)
                        .thenComparingInt(PlayerRecord::getTotalWins)
                : Comparator.comparingInt((PlayerRecord r) -> r.statsFor(type.name()).points)
                        .thenComparingInt(r -> r.statsFor(type.name()).wins);
        list.sort(cmp.reversed());
        int wins = 0;
        // Убираем игроков с нулевыми очками (ещё не играли).
        List<PlayerRecord> result = new ArrayList<>();
        for (PlayerRecord r : list) {
            int points = (type == null) ? r.getTotalPoints() : r.statsFor(type.name()).points;
            int games = (type == null) ? r.getTotalGames() : r.statsFor(type.name()).games;
            if (games <= 0 || points <= 0) {
                continue;
            }
            result.add(r);
            wins++;
            if (wins >= limit) {
                break;
            }
        }
        return result;
    }

    /** Последние игры конкретного игрока (максимум limit). */
    public synchronized List<GameHistoryEntry> historyOf(UUID uuid, int limit) {
        List<GameHistoryEntry> result = new ArrayList<>();
        for (GameHistoryEntry entry : history) {
            if (entry.resultOf(uuid) != null) {
                result.add(entry);
                if (result.size() >= limit) {
                    break;
                }
            }
        }
        return result;
    }

    /** Последние игры на сервере (максимум limit). */
    public synchronized List<GameHistoryEntry> lastGames(int limit) {
        List<GameHistoryEntry> result = new ArrayList<>();
        for (GameHistoryEntry entry : history) {
            result.add(entry);
            if (result.size() >= limit) {
                break;
            }
        }
        return result;
    }
}
