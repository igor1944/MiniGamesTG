package me.igor1944.minigamestg.game;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import me.igor1944.minigamestg.MiniGamesTGPlugin;
import me.igor1944.minigamestg.arena.Arena;
import me.igor1944.minigamestg.stats.GameResult;
import me.igor1944.minigamestg.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

/**
 * Управляет всеми мини-играми: создание, лобби, старт,
 * определение результатов, запись статистики и уведомления.
 */
public class GameManager {

    private final MiniGamesTGPlugin plugin;
    private final Map<Integer, Game> games = new LinkedHashMap<>();
    private int nextId = 1;
    private BukkitTask cleanupTask;

    /**
     * Куда вернуть игроков после дуэли (UUID -> исходная позиция).
     * Заполняется при телепортации на арену / сведении игроков,
     * очищается при возврате (сразу, при респавне или при повторном входе).
     */
    private final Map<UUID, Location> returnLocations = new HashMap<>();

    public GameManager(MiniGamesTGPlugin plugin) {
        this.plugin = plugin;
    }

    public MiniGamesTGPlugin getPlugin() {
        return plugin;
    }

    /** Периодическая чистка просроченных лобби. */
    public void startCleanupTask() {
        cleanupTask = Bukkit.getScheduler().runTaskTimer(plugin, this::cleanupLobbies, 1200L, 1200L);
    }

    // ---------- создание / лобби ----------

    /**
     * Создаёт игру и помещает создателя в лобби.
     * @return сообщение об ошибке или null при успехе.
     */
    public String createGame(Player creator, GameType type, String name) {
        if (getGameOf(creator.getUniqueId()) != null) {
            return "&cВы уже участвуете в другой игре. Сначала покиньте её: /mg leave";
        }
        if (name == null || name.isBlank()) {
            name = type.getDisplay();
        }
        if (name.length() > 32) {
            name = name.substring(0, 32);
        }
        Game game;
        int id = nextId++;
        switch (type) {
            case DUEL:
                game = new DuelGame(id, name, creator);
                break;
            case REACTION:
                game = new ReactionGame(id, name, creator);
                break;
            default:
                game = new CustomGame(id, name, creator);
                break;
        }
        game.addPlayer(creator.getUniqueId());
        games.put(id, game);

        broadcastToGame(game, Msg.prefixed("&aСоздано лобби &f«" + game.getDisplayName() + "» &7("
                + type.getDisplay() + ", " + game.getMinPlayers() + "–" + game.getMaxPlayers()
                + " игроков). Игроки могут присоединиться: &f/mg join " + id));
        plugin.getLogger().info(creator.getName() + " создал игру " + game.getDisplayName()
                + " (" + type.name() + ")");
        return null;
    }

    /**
     * Добавляет игрока в лобби.
     * @return сообщение об ошибке или null при успехе.
     */
    public String joinGame(Player player, int gameId) {
        Game game = games.get(gameId);
        if (game == null) {
            return "&cИгра с номером &f" + gameId + " &cне найдена. Список: /mg list";
        }
        return doJoin(player, game);
    }

    /** Входит в самое свежее открытое лобби. */
    public String joinLatest(Player player) {
        List<Game> lobbies = getLobbies();
        if (lobbies.isEmpty()) {
            return "&cСейчас нет открытых лобби. Создайте свою игру: /mg create <тип>";
        }
        return doJoin(player, lobbies.get(lobbies.size() - 1));
    }

    private String doJoin(Player player, Game game) {
        if (getGameOf(player.getUniqueId()) != null) {
            return "&cВы уже участвуете в другой игре. Покиньте её: /mg leave";
        }
        if (game.getState() != GameState.LOBBY) {
            return "&cИгра &f«" + game.getDisplayName() + "» &cуже началась или завершена.";
        }
        if (game.isFull()) {
            return "&cЛобби &f«" + game.getDisplayName() + "» &cуже заполнено.";
        }
        game.addPlayer(player.getUniqueId());
        broadcastToGame(game, Msg.prefixed("&e" + player.getName() + " &7присоединился к лобби &f«"
                + game.getDisplayName() + "» &8(" + game.getPlayerCount() + "/" + game.getMaxPlayers() + ")"));
        return null;
    }

    /** Выход игрока из лобби (из начатой игры выйти нельзя — только отмена). */
    public String leaveGame(Player player) {
        Game game = getGameOf(player.getUniqueId());
        if (game == null) {
            return "&cВы не участвуете ни в одной игре.";
        }
        if (game.getState() == GameState.RUNNING) {
            return "&cИгра уже идёт — выход только через конец игры или отмену (/mg cancel).";
        }
        removeFromLobby(game, player.getUniqueId(), player.getName());
        return null;
    }

    private void removeFromLobby(Game game, UUID uuid, String name) {
        game.removePlayer(uuid);
        if (game.getPlayers().isEmpty()) {
            games.remove(game.getId());
            plugin.getLogger().info("Лобби " + game.getDisplayName() + " закрыто (нет игроков).");
        } else {
            broadcastToGame(game, Msg.prefixed("&7" + name + " покинул лобби &f«" + game.getDisplayName()
                    + "» &8(" + game.getPlayerCount() + "/" + game.getMaxPlayers() + ")"));
        }
    }

    // ---------- старт / отмена / завершение ----------

    /** Запускает игру. sender — создатель лобби или админ. */
    public String startGame(Player sender) {
        Game game = getGameOf(sender.getUniqueId());
        if (game == null) {
            return "&cВы не находитесь в лобби. Создайте игру: /mg create <тип>";
        }
        return startGame(sender, game);
    }

    /** Принудительный старт по номеру (для админов). */
    public String startGame(Player sender, int gameId) {
        Game game = games.get(gameId);
        if (game == null) {
            return "&cИгра с номером &f" + gameId + " &cне найдена.";
        }
        return startGame(sender, game);
    }

    private String startGame(Player sender, Game game) {
        boolean isCreator = game.getCreator().equals(sender.getUniqueId());
        if (!isCreator && !sender.hasPermission("minigamestg.admin")) {
            return "&cЗапустить игру может только её создатель (&f" + game.getCreatorName() + "&c).";
        }
        if (game.getState() != GameState.LOBBY) {
            return "&cЭта игра уже запущена.";
        }
        if (game.getPlayerCount() < game.getMinPlayers()) {
            return "&cНедостаточно игроков: нужно минимум &f" + game.getMinPlayers()
                    + "&c, сейчас &f" + game.getPlayerCount() + "&c. Пригласите друзей: /mg join " + game.getId();
        }
        game.setState(GameState.RUNNING);
        game.setStartedAt(System.currentTimeMillis());
        game.onStart(plugin, this);
        plugin.getLogger().info("Игра " + game.getDisplayName() + " началась. Участники: "
                + String.join(", ", participantNames(game)));
        return null;
    }

    /** Отменяет игру (лобби или запущенную). */
    public String cancelGame(Player sender) {
        Game game = getGameOf(sender.getUniqueId());
        if (game == null) {
            return "&cВы не находитесь в игре.";
        }
        boolean isCreator = game.getCreator().equals(sender.getUniqueId());
        if (!isCreator && !sender.hasPermission("minigamestg.admin")) {
            return "&cОтменить игру может только её создатель или администратор.";
        }
        doCancel(game, "Игра отменена" + (isCreator ? " организатором." : " администратором."));
        return null;
    }

    /** Отмена конкретной игры (используется также при чистке лобби). */
    public void doCancel(Game game, String reason) {
        game.setState(GameState.CANCELLED);
        game.onCleanup();
        games.remove(game.getId());
        returnPlayersHome(game);
        broadcastToGame(game, Msg.prefixed("&7" + reason + " &8(«" + game.getDisplayName() + "»)"));
    }

    /**
     * Завершает кастомную игру вручную: places — ники участников в порядке мест.
     */
    public String endCustomGame(Player sender, List<String> placeNames) {
        Game game = getGameOf(sender.getUniqueId());
        if (game == null) {
            return "&cВы не находитесь в игре.";
        }
        boolean isCreator = game.getCreator().equals(sender.getUniqueId());
        if (!isCreator && !sender.hasPermission("minigamestg.admin")) {
            return "&cЗавершить игру может только её создатель или администратор.";
        }
        if (game.getType() != GameType.CUSTOM) {
            return "&cИгры типа «" + game.getType().getDisplay() + "» завершаются автоматически.";
        }
        if (game.getState() != GameState.RUNNING) {
            return "&cИгра ещё не запущена. Старт: /mg start";
        }
        if (placeNames.isEmpty()) {
            return "&cУкажите победителя: /mg end <ник> [2 место] [3 место]";
        }
        List<UUID> places = new ArrayList<>();
        for (String rawName : placeNames) {
            UUID uuid = findParticipant(game, rawName);
            if (uuid == null) {
                return "&cИгрок &f" + rawName + " &cне участвует в этой игре. Участники: &f"
                        + String.join(", ", participantNames(game));
            }
            if (places.contains(uuid)) {
                return "&cИгрок &f" + rawName + " &cуказан дважды.";
            }
            places.add(uuid);
        }
        finishGame(game, places);
        return null;
    }

    /** Ищет участника игры по нику (регистронезависимо, допускает начало ника). */
    public UUID findParticipant(Game game, String name) {
        String needle = name.toLowerCase(java.util.Locale.ROOT);
        UUID prefixMatch = null;
        for (UUID uuid : game.getPlayers()) {
            String known = nameOf(uuid);
            if (known == null) {
                continue;
            }
            String lower = known.toLowerCase(java.util.Locale.ROOT);
            if (lower.equals(needle)) {
                return uuid;
            }
            if (lower.startsWith(needle)) {
                prefixMatch = uuid;
            }
        }
        return prefixMatch;
    }

    /**
     * Основной метод завершения: фиксирует места, обновляет статистику,
     * рассылает уведомления в игру и в Telegram.
     *
     * @param placesInOrder UUID участников по местам (1-й элемент — победитель);
     *                      остальные участники получают «участие».
     */
    public void finishGame(Game game, List<UUID> placesInOrder) {
        if (game.getState() == GameState.FINISHED || game.getState() == GameState.CANCELLED) {
            return;
        }
        game.setState(GameState.FINISHED);
        game.onCleanup();
        games.remove(game.getId());

        List<GameResult> results = new ArrayList<>();
        int place = 1;
        for (UUID uuid : placesInOrder) {
            if (game.isParticipant(uuid)) {
                results.add(new GameResult(place++, uuid, nameOf(uuid)));
            }
        }
        for (UUID uuid : game.getPlayers()) {
            boolean placed = false;
            for (GameResult r : results) {
                if (r.getUuid().equals(uuid)) {
                    placed = true;
                    break;
                }
            }
            if (!placed) {
                results.add(new GameResult(0, uuid, nameOf(uuid)));
            }
        }

        plugin.getStats().recordGame(game, results);

        // Краткий анонс в чат и консоль.
        GameResult winner = results.isEmpty() ? null : results.get(0);
        List<String> summary = buildSummary(game, results);
        for (UUID uuid : game.getPlayers()) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) {
                for (String line : summary) {
                    p.sendMessage(line);
                }
            }
        }
        plugin.getLogger().info("Игра " + game.getDisplayName() + " завершена. "
                + (winner != null && winner.isWinner() ? "Победитель: " + winner.getName() : "Без победителя."));

        if (plugin.getConfig().getBoolean("game.announce-results", true) && winner != null && winner.isWinner()) {
            Bukkit.broadcastMessage(Msg.prefixed("&6🏁 «" + game.getDisplayName() + "»: победил &f&l"
                    + winner.getName() + "&6!"));
        }

        // Титулы и звуки.
        for (GameResult r : results) {
            Player p = Bukkit.getPlayer(r.getUuid());
            if (p == null) {
                continue;
            }
            if (r.isWinner()) {
                p.sendTitle(Msg.color("&6&lПОБЕДА!"), Msg.color("&e«" + game.getDisplayName() + "»"), 10, 60, 20);
                p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f);
            } else {
                p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.2f);
            }
        }

        // Возвращаем дуэлянтов на исходные позиции (мёртвые — на респавне).
        returnPlayersHome(game);

        // Уведомления в Telegram.
        if (plugin.getTelegram() != null) {
            plugin.getTelegram().notifyGameFinished(game, results);
        }
    }

    /** Завершение без победителя (например, никто не успел ввести слово). */
    public void finishNoWinner(Game game) {
        if (game.getState() != GameState.RUNNING) {
            return;
        }
        broadcastToGame(game, Msg.prefixed("&7Время вышло — победителя нет. Игра засчитана всем как участие."));
        finishGame(game, Collections.emptyList());
    }

    /** Подробный итог игры для чата участников. */
    private List<String> buildSummary(Game game, List<GameResult> results) {
        List<String> lines = new ArrayList<>();
        lines.add(Msg.color("&8&m----------&r &6🏁 «" + game.getDisplayName() + "» &8&m----------"));
        for (GameResult r : results) {
            String medal;
            switch (r.getPlace()) {
                case 1: medal = "&6🥇 1 место"; break;
                case 2: medal = "&f🥈 2 место"; break;
                case 3: medal = "&c🥉 3 место"; break;
                default: medal = "&7• участие"; break;
            }
            lines.add(Msg.color(" " + medal + "&r &8– &f" + r.getName()));
        }
        lines.add(Msg.color("&7Очки: &f+3 &7за победу, &f+1 &7за участие. Статистика: &f/mg stats"));
        lines.add(Msg.color("&8&m-----------------------------------"));
        return lines;
    }

    // ---------- телепортация (дуэли) ----------

    /**
     * Дуэль: сводит бойцов вместе. Приоритет — случайная подготовленная арена
     * (/mg arena ...); если арен нет и включено game.duel.teleport-players-together —
     * телепортирует второго игрока на вычисленную точку перед первым.
     * Исходные позиции запоминаются для возврата после боя.
     */
    public void teleportDuelists(Game game) {
        List<UUID> ids = new ArrayList<>(game.getPlayers());
        if (ids.size() < 2) {
            return;
        }
        Player a = Bukkit.getPlayer(ids.get(0));
        Player b = Bukkit.getPlayer(ids.get(1));
        if (a == null || b == null) {
            return;
        }
        Arena arena = plugin.getArenas().getRandomComplete();
        if (arena != null) {
            saveReturnLocation(a);
            saveReturnLocation(b);
            a.teleport(arena.getPos1());
            b.teleport(arena.getPos2());
            broadcastToGame(game, Msg.prefixed("&7Дуэлянты телепортированы на арену &f" + arena.getName() + "&7."));
            return;
        }
        if (!plugin.getConfig().getBoolean("game.duel.teleport-players-together", true)) {
            return; // телепортация отключена — дерутся, где стоят
        }
        saveReturnLocation(b);
        Location spot = computeFacingSpot(a.getLocation(), 5.0);
        b.teleport(spot);
        broadcastToGame(game, Msg.prefixed("&7Игроки сведены друг к другу. После боя вас вернёт назад."));
    }

    private void saveReturnLocation(Player p) {
        returnLocations.putIfAbsent(p.getUniqueId(), p.getLocation().clone());
    }

    /**
     * Точка в `distance` блоках по направлению взгляда, с безопасной высотой
     * (по поверхности, если перепад небольшой) и взглядом назад на игрока.
     */
    private Location computeFacingSpot(Location base, double distance) {
        Vector dir = base.getDirection().clone();
        dir.setY(0);
        if (dir.lengthSquared() < 1.0E-4) {
            dir = new Vector(1, 0, 0);
        }
        dir.normalize().multiply(distance);
        Location target = base.clone().add(dir);
        World world = base.getWorld();
        if (world != null) {
            int groundY = world.getHighestBlockYAt(target.getBlockX(), target.getBlockZ()) + 1;
            if (Math.abs(groundY - base.getY()) <= 8) {
                target.setY(groundY);
            }
        }
        target.setPitch(0f);
        double dx = base.getX() - target.getX();
        double dz = base.getZ() - target.getZ();
        target.setYaw((float) Math.toDegrees(Math.atan2(-dx, dz)));
        return target;
    }

    /** Возвращает участников игры на сохранённые позиции (см. teleportDuelists). */
    private void returnPlayersHome(Game game) {
        if (returnLocations.isEmpty()) {
            return;
        }
        if (!plugin.getConfig().getBoolean("game.duel.teleport-back-after", true)) {
            returnLocations.keySet().removeAll(game.getPlayers());
            return;
        }
        for (UUID uuid : game.getPlayers()) {
            Location loc = returnLocations.get(uuid);
            if (loc == null) {
                continue;
            }
            Player p = Bukkit.getPlayer(uuid);
            if (p != null && p.isOnline()) {
                if (p.isDead()) {
                    continue; // вернём через PlayerRespawnEvent
                }
                p.teleport(loc);
                p.sendMessage(Msg.prefixed("&7Вы возвращены на своё место."));
                returnLocations.remove(uuid);
            }
            // оффлайн-игроков вернём при следующем входе (handleJoinReturn)
        }
    }

    /** Проигравший дуэль возрождается там, где был до боя. */
    public void handleRespawn(PlayerRespawnEvent event) {
        Location loc = returnLocations.remove(event.getPlayer().getUniqueId());
        if (loc != null) {
            event.setRespawnLocation(loc);
        }
    }

    /** Игрок, вышедший с сервера посреди дуэли, возвращается на своё место при входе. */
    public void handleJoinReturn(Player player) {
        Location loc = returnLocations.remove(player.getUniqueId());
        if (loc == null) {
            return;
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) {
                player.teleport(loc);
                player.sendMessage(Msg.prefixed("&7Вы возвращены на место, где были до дуэли."));
            }
        }, 10L);
    }

    // ---------- события из слушателя ----------

    /** Чат: проверка ответа в игре «Реакция». Вызывается из асинхронного события. */
    public void handleChat(Player player, String message) {
        Game game = getGameOf(player.getUniqueId());
        if (!(game instanceof ReactionGame) || game.getState() != GameState.RUNNING) {
            return;
        }
        ReactionGame reaction = (ReactionGame) game;
        if (!reaction.isAwaitingWord() || reaction.getCurrentWord() == null) {
            return;
        }
        if (!message.trim().equalsIgnoreCase(reaction.getCurrentWord().trim())) {
            return;
        }
        reaction.clearAwaiting();
        // Завершение игры — в основном потоке сервера.
        Bukkit.getScheduler().runTask(plugin, () ->
                finishGame(game, Collections.singletonList(player.getUniqueId())));
    }

    /** Смерть в дуэли: побеждает соперник. */
    public void handleDeath(Player victim) {
        Game game = getGameOf(victim.getUniqueId());
        if (!(game instanceof DuelGame) || game.getState() != GameState.RUNNING) {
            return;
        }
        UUID winnerUuid = null;
        for (UUID uuid : game.getPlayers()) {
            if (!uuid.equals(victim.getUniqueId())) {
                winnerUuid = uuid;
                break;
            }
        }
        if (winnerUuid == null) {
            finishNoWinner(game);
            return;
        }
        List<UUID> places = new ArrayList<>();
        places.add(winnerUuid);
        places.add(victim.getUniqueId());
        finishGame(game, places);
    }

    /** Выход игрока с сервера: техпоражение в дуэли, выход из лобби. */
    public void handleQuit(Player player) {
        Game game = getGameOf(player.getUniqueId());
        if (game == null) {
            return;
        }
        if (game.getState() == GameState.LOBBY) {
            removeFromLobby(game, player.getUniqueId(), player.getName());
            return;
        }
        if (game.getState() != GameState.RUNNING) {
            return;
        }
        if (game instanceof DuelGame) {
            UUID winnerUuid = null;
            for (UUID uuid : game.getPlayers()) {
                if (!uuid.equals(player.getUniqueId())) {
                    winnerUuid = uuid;
                    break;
                }
            }
            if (winnerUuid != null) {
                List<UUID> places = new ArrayList<>();
                places.add(winnerUuid);
                places.add(player.getUniqueId());
                finishGame(game, places);
            } else {
                finishNoWinner(game);
            }
        } else {
            // В остальных играх выбывший просто исключается из участников.
            game.removePlayer(player.getUniqueId());
            broadcastToGame(game, Msg.prefixed("&7" + player.getName() + " вышел с сервера и выбыл из игры."));
        }
    }

    // ---------- сервисные функции ----------

    /** Чистит лобби, простаивающие дольше lobby-expire-minutes. */
    public void cleanupLobbies() {
        long maxIdle = Math.max(1, plugin.getConfig().getInt("game.lobby-expire-minutes", 10)) * 60_000L;
        long now = System.currentTimeMillis();
        List<Game> expired = new ArrayList<>();
        for (Game game : games.values()) {
            if (game.getState() == GameState.LOBBY && now - game.getCreatedAt() > maxIdle) {
                expired.add(game);
            }
        }
        for (Game game : expired) {
            doCancel(game, "Лобби закрыто: истекло время ожидания.");
        }
    }

    /** Отменяет все активные игры (при выключении плагина). */
    public void shutdown() {
        if (cleanupTask != null) {
            cleanupTask.cancel();
            cleanupTask = null;
        }
        List<Game> copy = new ArrayList<>(games.values());
        for (Game game : copy) {
            try {
                doCancel(game, "Игра остановлена: перезагрузка сервера.");
            } catch (RuntimeException e) {
                plugin.getLogger().warning("Ошибка при отмене игры: " + e.getMessage());
            }
        }
        games.clear();
    }

    // ---------- поиск ----------

    public Game getGame(int id) {
        return games.get(id);
    }

    /** Игра, в которой состоит игрок (любое состояние), или null. */
    public Game getGameOf(UUID uuid) {
        for (Game game : games.values()) {
            if (game.isParticipant(uuid)) {
                return game;
            }
        }
        return null;
    }

    public Collection<Game> getGames() {
        return games.values();
    }

    /** Открытые лобби в порядке создания. */
    public List<Game> getLobbies() {
        List<Game> lobbies = new ArrayList<>();
        for (Game game : games.values()) {
            if (game.getState() == GameState.LOBBY) {
                lobbies.add(game);
            }
        }
        return lobbies;
    }

    // ---------- вывод ----------

    public void broadcastToGame(Game game, String message) {
        for (UUID uuid : game.getPlayers()) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) {
                p.sendMessage(message);
            }
        }
    }

    /** Ник игрока по UUID (из онлайна или сохранённой статистики). */
    public String nameOf(UUID uuid) {
        Player online = Bukkit.getPlayer(uuid);
        if (online != null) {
            return online.getName();
        }
        me.igor1944.minigamestg.stats.PlayerRecord record = plugin.getStats().getRecord(uuid);
        if (record != null) {
            return record.getName();
        }
        org.bukkit.OfflinePlayer offline = Bukkit.getOfflinePlayer(uuid);
        String name = offline.getName();
        return name != null ? name : uuid.toString().substring(0, 8);
    }

    /** Список ников участников игры. */
    public List<String> participantNames(Game game) {
        List<String> names = new ArrayList<>();
        for (UUID uuid : game.getPlayers()) {
            names.add(nameOf(uuid));
        }
        return names;
    }
}
