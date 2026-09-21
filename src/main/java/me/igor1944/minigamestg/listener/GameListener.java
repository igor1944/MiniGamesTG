package me.igor1944.minigamestg.listener;

import me.igor1944.minigamestg.MiniGamesTGPlugin;
import me.igor1944.minigamestg.game.GameManager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

/**
 * Маршрутизирует игровые события сервера в GameManager:
 * чат — для «Реакции», смерти/выходы — для дуэлей.
 */
public class GameListener implements Listener {

    private final GameManager games;

    public GameListener(MiniGamesTGPlugin plugin) {
        this.games = plugin.getGames();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    @SuppressWarnings("deprecation")
    public void onChat(AsyncPlayerChatEvent event) {
        games.handleChat(event.getPlayer(), event.getMessage());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        games.handleDeath(event.getEntity());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        games.handleQuit(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        // Обновляем ник в статистике (на случай смены имени).
        games.getPlugin().getStats().touchPlayer(event.getPlayer());
        // Возвращаем на исходную позицию, если игрок вышел посреди дуэли.
        games.handleJoinReturn(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        // Проигравший дуэль возрождается на своей исходной позиции.
        games.handleRespawn(event);
    }
}
