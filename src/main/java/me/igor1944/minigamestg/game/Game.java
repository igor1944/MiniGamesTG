package me.igor1944.minigamestg.game;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import org.bukkit.entity.Player;

/**
 * Базовый класс мини-игры: хранит участников, состояние и время.
 * Логика конкретного типа — в наследниках.
 */
public abstract class Game {

    private final int id;
    private final GameType type;
    private final String name;
    private final UUID creator;
    private final String creatorName;
    private final LinkedHashSet<UUID> players = new LinkedHashSet<>();
    private volatile GameState state = GameState.LOBBY;
    private final long createdAt = System.currentTimeMillis();
    private long startedAt;

    protected Game(int id, GameType type, String name, Player creator) {
        this.id = id;
        this.type = type;
        this.name = name;
        this.creator = creator.getUniqueId();
        this.creatorName = creator.getName();
    }

    /** Минимум участников для старта. */
    public abstract int getMinPlayers();

    /** Максимум участников. */
    public abstract int getMaxPlayers();

    /** Вызывается при старте игры (уже в состоянии RUNNING). */
    public abstract void onStart(me.igor1944.minigamestg.MiniGamesTGPlugin plugin, GameManager manager);

    /** Отмена таймеров/задач при завершении или отмене игры. */
    public abstract void onCleanup();

    public int getId() {
        return id;
    }

    public GameType getType() {
        return type;
    }

    public String getName() {
        return name;
    }

    /** Имя с номером, напр.: Дуэль #3. */
    public String getDisplayName() {
        return name + " #" + id;
    }

    public UUID getCreator() {
        return creator;
    }

    public String getCreatorName() {
        return creatorName;
    }

    public GameState getState() {
        return state;
    }

    public void setState(GameState state) {
        this.state = state;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public long getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(long startedAt) {
        this.startedAt = startedAt;
    }

    public Set<UUID> getPlayers() {
        return Collections.unmodifiableSet(players);
    }

    public boolean isParticipant(UUID uuid) {
        return players.contains(uuid);
    }

    public boolean addPlayer(UUID uuid) {
        return players.add(uuid);
    }

    public boolean removePlayer(UUID uuid) {
        return players.remove(uuid);
    }

    public int getPlayerCount() {
        return players.size();
    }

    public boolean isFull() {
        return players.size() >= getMaxPlayers();
    }
}
