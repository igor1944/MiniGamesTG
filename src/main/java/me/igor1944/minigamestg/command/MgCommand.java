package me.igor1944.minigamestg.command;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import me.igor1944.minigamestg.MiniGamesTGPlugin;
import me.igor1944.minigamestg.game.Game;
import me.igor1944.minigamestg.game.GameType;
import me.igor1944.minigamestg.stats.GameHistoryEntry;
import me.igor1944.minigamestg.stats.GameResult;
import me.igor1944.minigamestg.stats.PlayerRecord;
import me.igor1944.minigamestg.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

/**
 * Команда /mg: создание и управление мини-играми,
 * статистика, топ, привязка Telegram.
 */
public class MgCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS = Arrays.asList(
            "create", "join", "leave", "start", "end", "cancel", "list",
            "stats", "top", "tg", "reload", "help");

    private final MiniGamesTGPlugin plugin;

    public MgCommand(MiniGamesTGPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);

        // Команды, доступные консоли.
        if (sub.equals("reload")) {
            return handleReload(sender);
        }
        if (!(sender instanceof Player)) {
            sender.sendMessage(Msg.prefixed("&cЭта команда доступна только в игре. Консоли доступно: /mg reload"));
            return true;
        }
        Player player = (Player) sender;

        switch (sub) {
            case "create":
                return handleCreate(player, args);
            case "join":
                return handleJoin(player, args);
            case "leave":
            case "quit":
                return reply(player, plugin.getGames().leaveGame(player));
            case "start":
                return handleStart(player, args);
            case "end":
                return handleEnd(player, args);
            case "cancel":
                return reply(player, plugin.getGames().cancelGame(player));
            case "list":
                return handleList(player);
            case "stats":
                return handleStats(player, args);
            case "top":
                return handleTop(player, args);
            case "tg":
            case "telegram":
            case "link":
                return handleTelegram(player, args);
            case "help":
            default:
                sendHelp(sender);
                return true;
        }
    }

    private boolean reply(Player player, String error) {
        if (error != null) {
            player.sendMessage(Msg.prefixed(error));
        }
        return true;
    }

    // ---------- подкоманды ----------

    private boolean handleCreate(Player player, String[] args) {
        if (!player.hasPermission("minigamestg.create")) {
            player.sendMessage(Msg.prefixed("&cНет прав на создание игр (&fminigamestg.create&c)."));
            return true;
        }
        if (args.length < 2) {
            player.sendMessage(Msg.prefixed("&cИспользование: &f/mg create <duel|reaction|custom> [название]"));
            return true;
        }
        GameType type = GameType.fromString(args[1]);
        if (type == null) {
            player.sendMessage(Msg.prefixed("&cНеизвестный тип &f" + args[1]
                    + "&c. Доступные типы: &fduel&7 (дуэль 1х1)&f, reaction&7 (реакция на слово)&f, custom&7 (ручные результаты)"));
            return true;
        }
        StringBuilder nameBuilder = new StringBuilder();
        for (int i = 2; i < args.length; i++) {
            if (nameBuilder.length() > 0) {
                nameBuilder.append(' ');
            }
            nameBuilder.append(args[i]);
        }
        String name = nameBuilder.length() == 0 ? null : nameBuilder.toString();
        return reply(player, plugin.getGames().createGame(player, type, name));
    }

    private boolean handleJoin(Player player, String[] args) {
        if (args.length >= 2) {
            int id;
            try {
                id = Integer.parseInt(args[1]);
            } catch (NumberFormatException e) {
                player.sendMessage(Msg.prefixed("&cНомер игры должен быть числом. Пример: &f/mg join 3"));
                return true;
            }
            return reply(player, plugin.getGames().joinGame(player, id));
        }
        return reply(player, plugin.getGames().joinLatest(player));
    }

    private boolean handleStart(Player player, String[] args) {
        if (args.length >= 2 && player.hasPermission("minigamestg.admin")) {
            try {
                int id = Integer.parseInt(args[1]);
                return reply(player, plugin.getGames().startGame(player, id));
            } catch (NumberFormatException e) {
                player.sendMessage(Msg.prefixed("&cНомер игры должен быть числом."));
                return true;
            }
        }
        return reply(player, plugin.getGames().startGame(player));
    }

    private boolean handleEnd(Player player, String[] args) {
        List<String> places = new ArrayList<>();
        for (int i = 1; i < args.length && i <= 3; i++) {
            places.add(args[i]);
        }
        return reply(player, plugin.getGames().endCustomGame(player, places));
    }

    private boolean handleList(Player player) {
        List<Game> games = new ArrayList<>(plugin.getGames().getGames());
        if (games.isEmpty()) {
            player.sendMessage(Msg.prefixed("&7Сейчас нет активных игр. Создайте: &f/mg create <тип>"));
            return true;
        }
        player.sendMessage(Msg.color("&8&m-----&r &bАктивные игры &8&m-----"));
        for (Game game : games) {
            String state;
            switch (game.getState()) {
                case LOBBY: state = "&eлобби"; break;
                case RUNNING: state = "&aидёт"; break;
                default: state = "&7завершена"; break;
            }
            player.sendMessage(Msg.color("&f#" + game.getId() + " «" + game.getName() + "» &7("
                    + game.getType().getDisplay() + ") " + state + " &8| &7игроков: &f"
                    + game.getPlayerCount() + " &8| &7создал: &f" + game.getCreatorName()));
        }
        player.sendMessage(Msg.color("&7Присоединиться: &f/mg join <номер>&7, своя игра: &f/mg create <тип>"));
        return true;
    }

    private boolean handleStats(Player player, String[] args) {
        PlayerRecord record;
        String targetName;
        if (args.length >= 2) {
            targetName = args[1];
            OfflinePlayer target = Bukkit.getPlayerExact(targetName);
            if (target != null) {
                record = plugin.getStats().ensureRecord(target.getUniqueId(), target.getName());
            } else {
                // Поиск среди записей по нику (регистронезависимо).
                record = findRecordByName(targetName);
                if (record != null) {
                    targetName = record.getName();
                }
            }
            if (record == null) {
                player.sendMessage(Msg.prefixed("&cИгрок &f" + targetName + " &cне найден в статистике."));
                return true;
            }
        } else {
            targetName = player.getName();
            record = plugin.getStats().ensureRecord(player.getUniqueId(), player.getName());
        }

        player.sendMessage(Msg.color("&8&m-----&r &bСтатистика &f" + targetName + " &8&m-----"));
        player.sendMessage(Msg.color("&7Игр: &f" + record.getTotalGames()
                + " &8| &7Побед: &a" + record.getTotalWins()
                + " &8| &7Очки: &6" + record.getTotalPoints()));
        if (!record.getByType().isEmpty()) {
            for (java.util.Map.Entry<String, PlayerRecord.TypeStats> e : record.getByType().entrySet()) {
                GameType type = GameType.fromString(e.getKey());
                String display = type != null ? type.getDisplay() : e.getKey();
                PlayerRecord.TypeStats s = e.getValue();
                player.sendMessage(Msg.color(" &7• &f" + display + ": &7" + s.games + " игр, &a"
                        + s.wins + " побед, &6" + s.points + " очков"));
            }
        }
        List<GameHistoryEntry> last = plugin.getStats().historyOf(record.getUuid(), 3);
        if (!last.isEmpty()) {
            player.sendMessage(Msg.color("&7Последние игры:"));
            java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("dd.MM HH:mm");
            for (GameHistoryEntry entry : last) {
                GameResult mine = entry.resultOf(record.getUuid());
                String mark = mine != null && mine.isWinner() ? "&aпобеда" : "&7участие";
                if (mine != null && mine.getPlace() > 1) {
                    mark = "&e" + mine.getPlace() + " место";
                }
                player.sendMessage(Msg.color(" &8– &f" + entry.getName() + " #" + entry.getGameId()
                        + " &8(" + mark + "&8, " + fmt.format(new java.util.Date(entry.getTimestamp())) + ")"));
            }
        }
        if (record.isLinked()) {
            player.sendMessage(Msg.color("&7Telegram привязан: &f@" + record.getTelegramUsername()));
        } else {
            player.sendMessage(Msg.color("&8Telegram не привязан. Напишите боту /start и введите &7/mg tg <код>"));
        }
        return true;
    }

    private PlayerRecord findRecordByName(String name) {
        String needle = name.toLowerCase(Locale.ROOT);
        PlayerRecord startsWith = null;
        for (PlayerRecord r : allRecords()) {
            String n = r.getName() == null ? "" : r.getName().toLowerCase(Locale.ROOT);
            if (n.equals(needle)) {
                return r;
            }
            if (n.startsWith(needle)) {
                startsWith = r;
            }
        }
        return startsWith;
    }

    private List<PlayerRecord> allRecords() {
        List<PlayerRecord> list = new ArrayList<>();
        for (PlayerRecord r : plugin.getStats().top(Integer.MAX_VALUE, null)) {
            list.add(r);
        }
        return list;
    }

    private boolean handleTop(Player player, String[] args) {
        GameType type = null;
        if (args.length >= 2) {
            type = GameType.fromString(args[1]);
            if (type == null) {
                player.sendMessage(Msg.prefixed("&cНеизвестный тип: &f" + args[1]
                        + "&c. Типы: duel, reaction, custom"));
                return true;
            }
        }
        List<PlayerRecord> top = plugin.getStats().top(10, type);
        String title = type == null ? "&6&lТОП-10 игроков" : "&6&lТОП-10 (&f" + type.getDisplay() + "&6&l)";
        player.sendMessage(Msg.color("&8&m-----&r " + title + " &8&m-----"));
        if (top.isEmpty()) {
            player.sendMessage(Msg.color("&7Пока никто не сыграл ни одной игры."));
            return true;
        }
        int i = 0;
        for (PlayerRecord r : top) {
            i++;
            String number = (i == 1) ? "&6#1" : (i == 2) ? "&f#2" : (i == 3) ? "&c#3" : "&7#" + i;
            int wins = type == null ? r.getTotalWins() : r.statsFor(type.name()).wins;
            int points = type == null ? r.getTotalPoints() : r.statsFor(type.name()).points;
            int gamesCount = type == null ? r.getTotalGames() : r.statsFor(type.name()).games;
            player.sendMessage(Msg.color(number + " &f" + r.getName() + " &8– &aпобед: " + wins
                    + "&8, &6очки: " + points + " &8(&7игр: " + gamesCount + "&8)"));
        }
        return true;
    }

    private boolean handleTelegram(Player player, String[] args) {
        if (plugin.getTelegram() == null || !plugin.getTelegram().isRunning()) {
            player.sendMessage(Msg.prefixed("&cTelegram-бот сейчас отключён администратором сервера."));
            return true;
        }
        if (args.length < 2) {
            PlayerRecord record = plugin.getStats().getRecord(player.getUniqueId());
            if (record != null && record.isLinked()) {
                player.sendMessage(Msg.prefixed("&aTelegram привязан: &f@" + record.getTelegramUsername()
                        + "&7. Отвязать: &f/mg tg off"));
            } else {
                player.sendMessage(Msg.prefixed("&7Привязка: напишите боту в Telegram &f/start&7, "
                        + "получите код и введите &f/mg tg <код>"));
            }
            return true;
        }
        if (args[1].equalsIgnoreCase("off") || args[1].equalsIgnoreCase("unlink")) {
            if (plugin.getStats().unlink(player.getUniqueId())) {
                player.sendMessage(Msg.prefixed("&7Telegram-аккаунт отвязан."));
            } else {
                player.sendMessage(Msg.prefixed("&cУ вас нет привязанного Telegram-аккаунта."));
            }
            return true;
        }
        String code = args[1].trim();
        if (plugin.getTelegram().completeLink(code, player)) {
            player.sendMessage(Msg.prefixed("&aTelegram успешно привязан! Теперь результаты игр "
                    + "будут приходить вам в личные сообщения бота."));
        } else {
            player.sendMessage(Msg.prefixed("&cНеверный или просроченный код. Получите новый: "
                    + "напишите боту &f/start &cв Telegram."));
        }
        return true;
    }

    private boolean handleReload(CommandSender sender) {
        if (!sender.hasPermission("minigamestg.admin")) {
            sender.sendMessage(Msg.prefixed("&cНет прав (&fminigamestg.admin&c)."));
            return true;
        }
        plugin.reloadPlugin();
        sender.sendMessage(Msg.prefixed("&aКонфигурация перезагружена."));
        return true;
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(Msg.color("&8&m-------&r &bMiniGamesTG &7— команды &8&m-------"));
        sender.sendMessage(Msg.color("&f/mg create <duel|reaction|custom> [название] &8– &7создать игру"));
        sender.sendMessage(Msg.color("&f/mg join [номер] &8– &7войти в лобби"));
        sender.sendMessage(Msg.color("&f/mg leave &8– &7покинуть лобби"));
        sender.sendMessage(Msg.color("&f/mg start &8– &7начать игру (создатель)"));
        sender.sendMessage(Msg.color("&f/mg end <победитель> [2] [3] &8– &7итоги кастомной игры"));
        sender.sendMessage(Msg.color("&f/mg cancel &8– &7отменить игру"));
        sender.sendMessage(Msg.color("&f/mg list &8– &7активные игры"));
        sender.sendMessage(Msg.color("&f/mg stats [ник] &8– &7статистика игрока"));
        sender.sendMessage(Msg.color("&f/mg top [тип] &8– &7топ-10 игроков"));
        sender.sendMessage(Msg.color("&f/mg tg <код|off> &8– &7привязка Telegram"));
        if (sender.hasPermission("minigamestg.admin")) {
            sender.sendMessage(Msg.color("&f/mg reload &8– &7перезагрузить конфиг"));
        }
    }

    // ---------- автодополнение ----------

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return filter(SUBCOMMANDS, args[0]);
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "create":
                if (args.length == 2) {
                    return filter(Arrays.asList("duel", "reaction", "custom"), args[1]);
                }
                return Collections.emptyList();
            case "join":
                if (args.length == 2 && sender instanceof Player) {
                    List<String> ids = new ArrayList<>();
                    for (Game g : plugin.getGames().getLobbies()) {
                        ids.add(String.valueOf(g.getId()));
                    }
                    return filter(ids, args[1]);
                }
                return Collections.emptyList();
            case "end":
                if (sender instanceof Player && args.length >= 2) {
                    Game game = plugin.getGames().getGameOf(((Player) sender).getUniqueId());
                    if (game != null) {
                        return filter(new ArrayList<>(plugin.getGames().participantNames(game)),
                                args[args.length - 1]);
                    }
                }
                return Collections.emptyList();
            case "stats":
            case "top":
                if (args.length == 2) {
                    if (sub.equals("top")) {
                        return filter(Arrays.asList("duel", "reaction", "custom"), args[1]);
                    }
                    List<String> names = new ArrayList<>();
                    for (Player p : Bukkit.getOnlinePlayers()) {
                        names.add(p.getName());
                    }
                    return filter(names, args[1]);
                }
                return Collections.emptyList();
            case "tg":
                if (args.length == 2) {
                    return filter(Collections.singletonList("off"), args[1]);
                }
                return Collections.emptyList();
            default:
                return Collections.emptyList();
        }
    }

    private List<String> filter(List<String> options, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        List<String> result = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(lower)) {
                result.add(option);
            }
        }
        return result;
    }
}
