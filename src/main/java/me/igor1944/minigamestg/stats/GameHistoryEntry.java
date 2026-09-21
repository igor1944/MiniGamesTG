package me.igor1944.minigamestg.stats;

import java.util.ArrayList;
import java.util.List;

/**
 * Запись об одной сыгранной игре (для истории и «последних игр»).
 */
public class GameHistoryEntry {

    private long timestamp;
    private int gameId;
    private String type;
    private String name;
    private final List<GameResult> results = new ArrayList<>();

    public GameHistoryEntry() {
        // Для Gson.
    }

    public GameHistoryEntry(long timestamp, int gameId, String type, String name, List<GameResult> results) {
        this.timestamp = timestamp;
        this.gameId = gameId;
        this.type = type;
        this.name = name;
        this.results.addAll(results);
    }

    public long getTimestamp() {
        return timestamp;
    }

    public int getGameId() {
        return gameId;
    }

    public String getType() {
        return type;
    }

    public String getName() {
        return name;
    }

    public List<GameResult> getResults() {
        return results;
    }

    /** Результат конкретного игрока в этой игре или null. */
    public GameResult resultOf(java.util.UUID uuid) {
        for (GameResult r : results) {
            if (r.getUuid().equals(uuid)) {
                return r;
            }
        }
        return null;
    }
}
