package me.igor1944.minigamestg.game;

import me.igor1944.minigamestg.MiniGamesTGPlugin;
import me.igor1944.minigamestg.util.Msg;
import org.bukkit.entity.Player;

/**
 * Кастомная игра: организатор проводит любую игру
 * (паркур, прятки, лабиринт — что угодно), а плагин ведёт учёт.
 * Результаты вводит вручную организатор: /mg end <победитель> [2 место] [3 место].
 */
public class CustomGame extends Game {

    public CustomGame(int id, String name, Player creator) {
        super(id, GameType.CUSTOM, name, creator);
    }

    @Override
    public int getMinPlayers() {
        return 1;
    }

    @Override
    public int getMaxPlayers() {
        return 100;
    }

    @Override
    public void onStart(MiniGamesTGPlugin plugin, GameManager manager) {
        manager.broadcastToGame(this, Msg.prefixed("&aИгра &f«" + getDisplayName() + "» &aначалась! &7Удачи!"));
    }

    @Override
    public void onCleanup() {
        // Нет задач, нечего отменять.
    }
}
