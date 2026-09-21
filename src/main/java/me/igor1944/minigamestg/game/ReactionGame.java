package me.igor1944.minigamestg.game;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import me.igor1944.minigamestg.MiniGamesTGPlugin;
import me.igor1944.minigamestg.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

/**
 * Игра «Реакция»: через случайную паузу в чат выводится слово,
 * побеждает первый участник, введший его точно в чат.
 */
public class ReactionGame extends Game {

    private final Random random = new Random();
    private final List<BukkitTask> tasks = new ArrayList<>();
    private volatile boolean awaitingWord;
    private volatile String currentWord;

    public ReactionGame(int id, String name, Player creator) {
        super(id, GameType.REACTION, name, creator);
    }

    @Override
    public int getMinPlayers() {
        return 2;
    }

    @Override
    public int getMaxPlayers() {
        return 50;
    }

    public boolean isAwaitingWord() {
        return awaitingWord;
    }

    public String getCurrentWord() {
        return currentWord;
    }

    public void clearAwaiting() {
        this.awaitingWord = false;
        this.currentWord = null;
    }

    @Override
    public void onStart(MiniGamesTGPlugin plugin, GameManager manager) {
        manager.broadcastToGame(this, Msg.prefixed("&eИгра &f«Реакция» &eначалась! "
                + "Когда появится слово — первым напиши его в чат!"));

        int min = plugin.getConfig().getInt("game.reaction.min-word-delay-ticks", 40);
        int max = Math.max(min, plugin.getConfig().getInt("game.reaction.max-word-delay-ticks", 160));
        int delay = min + random.nextInt(max - min + 1);

        tasks.add(Bukkit.getScheduler().runTaskLater(plugin, () -> {
            List<String> words = plugin.getConfig().getStringList("game.reaction.words");
            if (words.isEmpty()) {
                words = new ArrayList<>();
                words.add("майнкрафт");
            }
            currentWord = words.get(random.nextInt(words.size()));
            awaitingWord = true;
            manager.broadcastToGame(this, Msg.prefixed("&a§lСЛОВО: &f§l" + currentWord));
        }, delay));

        long timeoutTicks = Math.max(5, plugin.getConfig().getInt("game.reaction.answer-timeout-seconds", 30)) * 20L;
        tasks.add(Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (awaitingWord) {
                manager.finishNoWinner(ReactionGame.this);
            }
        }, delay + timeoutTicks));
    }

    @Override
    public void onCleanup() {
        for (BukkitTask task : tasks) {
            if (task != null) {
                task.cancel();
            }
        }
        tasks.clear();
        clearAwaiting();
    }
}
