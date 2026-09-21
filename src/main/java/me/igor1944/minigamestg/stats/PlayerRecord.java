package me.igor1944.minigamestg.stats;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Профиль игрока: ник, привязанный Telegram и статистика по типам игр.
 * Сериализуется в data.json через Gson.
 */
public class PlayerRecord {

    private UUID uuid;
    private String name;
    private long telegramChatId;
    private String telegramUsername;
    private final Map<String, TypeStats> byType = new LinkedHashMap<>();

    /** Статистика в рамках одного типа игр. */
    public static class TypeStats {
        public int games;
        public int wins;
        public int points;
    }

    public PlayerRecord() {
        // Для Gson.
    }

    public PlayerRecord(UUID uuid, String name) {
        this.uuid = uuid;
        this.name = name;
        this.telegramChatId = 0L;
        this.telegramUsername = "";
    }

    public UUID getUuid() {
        return uuid;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        if (name != null && !name.isEmpty()) {
            this.name = name;
        }
    }

    public long getTelegramChatId() {
        return telegramChatId;
    }

    public void setTelegramChatId(long telegramChatId) {
        this.telegramChatId = telegramChatId;
    }

    public String getTelegramUsername() {
        return telegramUsername == null ? "" : telegramUsername;
    }

    public void setTelegramUsername(String telegramUsername) {
        this.telegramUsername = telegramUsername == null ? "" : telegramUsername;
    }

    public boolean isLinked() {
        return telegramChatId != 0L;
    }

    public TypeStats statsFor(String typeName) {
        return byType.computeIfAbsent(typeName, k -> new TypeStats());
    }

    public int getTotalGames() {
        int total = 0;
        for (TypeStats s : byType.values()) {
            total += s.games;
        }
        return total;
    }

    public int getTotalWins() {
        int total = 0;
        for (TypeStats s : byType.values()) {
            total += s.wins;
        }
        return total;
    }

    public int getTotalPoints() {
        int total = 0;
        for (TypeStats s : byType.values()) {
            total += s.points;
        }
        return total;
    }

    public Map<String, TypeStats> getByType() {
        return byType;
    }
}
