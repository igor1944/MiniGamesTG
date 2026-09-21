package me.igor1944.minigamestg;

import me.igor1944.minigamestg.arena.ArenaManager;
import me.igor1944.minigamestg.command.MgCommand;
import me.igor1944.minigamestg.game.GameManager;
import me.igor1944.minigamestg.listener.GameListener;
import me.igor1944.minigamestg.stats.StatsManager;
import me.igor1944.minigamestg.telegram.TelegramBotService;
import me.igor1944.minigamestg.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * MiniGamesTG — мини-игры (дуэль, реакция, кастомные) с интеграцией Telegram:
 * уведомления о результатах, личная статистика, периодический топ игроков.
 */
public class MiniGamesTGPlugin extends JavaPlugin {

    private StatsManager statsManager;
    private GameManager gameManager;
    private ArenaManager arenaManager;
    private TelegramBotService telegramBot;
    private BukkitTask statsBroadcastTask;
    private BukkitTask autoSaveTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        Msg.setPrefix(getConfig().getString("messages.prefix", "&8[&bMiniGamesTG&8] "));

        statsManager = new StatsManager(this);
        statsManager.load();

        arenaManager = new ArenaManager(this);
        arenaManager.load();

        gameManager = new GameManager(this);
        gameManager.startCleanupTask();

        MgCommand mgCommand = new MgCommand(this);
        PluginCommand command = getCommand("mg");
        if (command == null) {
            getLogger().severe("Команда /mg не найдена в plugin.yml — плагин не сможет работать!");
        } else {
            command.setExecutor(mgCommand);
            command.setTabCompleter(mgCommand);
        }
        Bukkit.getPluginManager().registerEvents(new GameListener(this), this);

        startTelegram();
        scheduleTasks();

        getLogger().info("MiniGamesTG включён. Telegram-бот: "
                + (telegramBot != null && telegramBot.isRunning() ? "активен" : "отключён"));
    }

    @Override
    public void onDisable() {
        if (statsBroadcastTask != null) {
            statsBroadcastTask.cancel();
        }
        if (autoSaveTask != null) {
            autoSaveTask.cancel();
        }
        if (telegramBot != null) {
            telegramBot.stop();
        }
        if (gameManager != null) {
            gameManager.shutdown();
        }
        if (statsManager != null) {
            statsManager.saveNow();
        }
        getLogger().info("MiniGamesTG выключен, статистика сохранена.");
    }

    /** Перезагрузка конфигурации (по /mg reload). */
    public void reloadPlugin() {
        reloadConfig();
        Msg.setPrefix(getConfig().getString("messages.prefix", "&8[&bMiniGamesTG&8] "));
        if (arenaManager != null) {
            arenaManager.load();
        }
        startTelegram();
        scheduleTasks();
    }

    /** (Пере)запускает Telegram-бота по настройкам из config.yml. */
    private void startTelegram() {
        if (telegramBot == null) {
            telegramBot = new TelegramBotService(this);
        }
        boolean enabled = getConfig().getBoolean("telegram.enabled", false);
        String token = getConfig().getString("telegram.bot-token", "");
        if (!enabled || token == null || token.trim().isEmpty() || token.contains("ВСТАВЬ")) {
            telegramBot.stop();
            getLogger().info("Telegram отключён или не задан токен — плагин работает без Telegram.");
            return;
        }
        telegramBot.start(token.trim());
    }

    /** Периодические задачи: рассылка статистики и автосохранение. */
    private void scheduleTasks() {
        if (statsBroadcastTask != null) {
            statsBroadcastTask.cancel();
            statsBroadcastTask = null;
        }
        long intervalMinutes = Math.max(1, getConfig().getLong("telegram.stats-interval-minutes", 60));
        long intervalTicks = intervalMinutes * 60L * 20L;
        statsBroadcastTask = Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
            try {
                if (telegramBot != null) {
                    telegramBot.sendPeriodicStats();
                }
            } catch (RuntimeException e) {
                getLogger().warning("Ошибка периодической рассылки статистики: " + e.getMessage());
            }
        }, intervalTicks, intervalTicks);

        if (autoSaveTask != null) {
            autoSaveTask.cancel();
        }
        autoSaveTask = Bukkit.getScheduler().runTaskTimerAsynchronously(this,
                () -> statsManager.saveAsync(), 6000L, 6000L); // каждые 5 минут
    }

    // ---------- доступ к подсистемам ----------

    public StatsManager getStats() {
        return statsManager;
    }

    public GameManager getGames() {
        return gameManager;
    }

    public ArenaManager getArenas() {
        return arenaManager;
    }

    public TelegramBotService getTelegram() {
        return telegramBot;
    }
}
