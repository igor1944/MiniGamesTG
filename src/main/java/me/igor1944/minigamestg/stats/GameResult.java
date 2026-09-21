package me.igor1944.minigamestg.stats;

import java.util.UUID;

/**
 * Результат одного участника игры.
 * place: 1 — победитель, 2/3 — призовые места, 0 — просто участник.
 */
public class GameResult {

    private final int place;
    private final UUID uuid;
    private final String name;

    public GameResult(int place, UUID uuid, String name) {
        this.place = place;
        this.uuid = uuid;
        this.name = name;
    }

    public int getPlace() {
        return place;
    }

    public UUID getUuid() {
        return uuid;
    }

    public String getName() {
        return name;
    }

    public boolean isWinner() {
        return place == 1;
    }

    /** Очки за результат: победа = 3, участие = 1. */
    public int getPoints() {
        return isWinner() ? 3 : 1;
    }
}
