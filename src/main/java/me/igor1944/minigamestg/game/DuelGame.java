package me.igor1944.minigamestg.game;

import java.util.ArrayList;
import java.util.List;
import me.igor1944.minigamestg.MiniGamesTGPlugin;
import me.igor1944.minigamestg.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

/**
 * Дуэль 1 на 1. Победитель определяется автоматически:
 * убийство соперника, смерть или выход игрока с сервера.
 */
public class DuelGame extends Game {

    private final List<BukkitTask> tasks = new ArrayList<>();

    public DuelGame(int id, String name, Player creator) {
        super(id, GameType.DUEL, name, creator);
    }

    @Override
    public int getMinPlayers() {
        return 2;
    }

    @Override
    public int getMaxPlayers() {
        return 2;
    }

    @Override
    public void onStart(MiniGamesTGPlugin plugin, GameManager manager) {
        int countdown = Math.max(0, plugin.getConfig().getInt("game.duel-countdown-seconds", 3));

        // Подготавливаем бойцов: полное здоровье и сытость.
        for (java.util.UUID uuid : getPlayers()) {
            Player p = Bukkit.getPlayer(uuid);
            if (p == null) {
                continue;
            }
            AttributeInstance maxHealth = p.getAttribute(Attribute.GENERIC_MAX_HEALTH);
            if (maxHealth != null) {
                p.setHealth(maxHealth.getValue());
            }
            p.setFoodLevel(20);
            p.setFireTicks(0);
        }

        if (countdown > 0) {
            manager.broadcastToGame(this, Msg.prefixed("&cДуэль начинается! Отсчёт: &f" + countdown + " сек."));
            for (int i = countdown; i >= 1; i--) {
                final int n = i;
                tasks.add(Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    for (java.util.UUID uuid : getPlayers()) {
                        Player p = Bukkit.getPlayer(uuid);
                        if (p != null) {
                            p.sendTitle(Msg.color("&e" + n), Msg.color("&7Приготовься!"), 0, 25, 5);
                        }
                    }
                }, (long) (countdown - n) * 20L));
            }
            tasks.add(Bukkit.getScheduler().runTaskLater(plugin, () -> {
                for (java.util.UUID uuid : getPlayers()) {
                    Player p = Bukkit.getPlayer(uuid);
                    if (p != null) {
                        p.sendTitle(Msg.color("&cБОЙ!"), "", 0, 30, 10);
                        p.sendMessage(Msg.prefixed("&cДуэль &f— побеждает последний выживший!"));
                    }
                }
            }, (long) countdown * 20L));
        } else {
            manager.broadcastToGame(this, Msg.prefixed("&cБОЙ! Побеждает последний выживший!"));
        }
    }

    @Override
    public void onCleanup() {
        for (BukkitTask task : tasks) {
            if (task != null) {
                task.cancel();
            }
        }
        tasks.clear();
    }
}
