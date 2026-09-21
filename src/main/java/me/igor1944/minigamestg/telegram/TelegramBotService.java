package me.igor1944.minigamestg.telegram;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import me.igor1944.minigamestg.MiniGamesTGPlugin;
import me.igor1944.minigamestg.game.Game;
import me.igor1944.minigamestg.stats.GameHistoryEntry;
import me.igor1944.minigamestg.stats.GameResult;
import me.igor1944.minigamestg.stats.PlayerRecord;
import org.bukkit.entity.Player;

/**
 * Telegram-бот на длинном опросе (long polling) без внешних библиотек.
 *
 * Функции:
 *  - привязка Telegram-аккаунта к игроку по одноразовому коду (/start в Telegram,
 *    /mg tg <код> в игре);
 *  - личные уведомления о результатах игр;
 *  - команды /mystats, /last, /top, /chatid, /help;
 *  - периодическая рассылка общей статистики и топа в заданный чат.
 */
public class TelegramBotService {

    /** Код привязки живёт 10 минут. */
    private static final long LINK_CODE_TTL_MILLIS = 10 * 60_000L;
    /** Таймаут long polling у Telegram (сек). */
    private static final int POLL_TIMEOUT_SECONDS = 25;

    private final MiniGamesTGPlugin plugin;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final Map<String, PendingLink> pendingLinks = new ConcurrentHashMap<>();
    private final Object sendLock = new Object();

    private volatile boolean running;
    private volatile Thread worker;
    private String token;
    private String baseUrl;
    private long offset;

    /** Ожидающая привязка: код -> чат, который его запросил. */
    private static class PendingLink {
        final long chatId;
        final String username;
        final long expiresAt;

        PendingLink(long chatId, String username, long expiresAt) {
            this.chatId = chatId;
            this.username = username;
            this.expiresAt = expiresAt;
        }
    }

    public TelegramBotService(MiniGamesTGPlugin plugin) {
        this.plugin = plugin;
    }

    // ---------- жизненный цикл ----------

    public synchronized void start(String token) {
        stop();
        this.token = token;
        this.baseUrl = "https://api.telegram.org/bot" + token + "/";
        this.offset = 0L;
        this.running = true;

        worker = new Thread(this::pollLoop, "MiniGamesTG-Telegram");
        worker.setDaemon(true);
        worker.start();
        plugin.getLogger().info("Telegram-бот запущен (long polling).");
    }

    public synchronized void stop() {
        running = false;
        if (worker != null) {
            worker.interrupt();
            try {
                worker.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            worker = null;
        }
        pendingLinks.clear();
    }

    public boolean isRunning() {
        return running && worker != null && worker.isAlive();
    }

    /** Пропускаем старые сообщения, накопившиеся, пока сервер был выключен. */
    private void skipBacklog() {
        try {
            JsonObject response = apiGet("getUpdates?timeout=0&offset=-1");
            if (response != null && response.get("ok").getAsBoolean()) {
                JsonArray result = response.getAsJsonArray("result");
                if (result.size() > 0) {
                    JsonObject last = result.get(result.size() - 1).getAsJsonObject();
                    offset = last.get("update_id").getAsLong() + 1;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            plugin.getLogger().warning("Telegram: не удалось пропустить старые сообщения: " + e.getMessage());
        }
    }

    private void pollLoop() {
        skipBacklog();
        while (running) {
            try {
                JsonObject response = apiGet("getUpdates?offset=" + offset + "&timeout=" + POLL_TIMEOUT_SECONDS);
                if (response == null) {
                    sleepQuietly(5000);
                    continue;
                }
                if (!response.get("ok").getAsBoolean()) {
                    plugin.getLogger().warning("Telegram API вернул ошибку: " + response);
                    sleepQuietly(10_000);
                    continue;
                }
                for (JsonElement element : response.getAsJsonArray("result")) {
                    JsonObject update = element.getAsJsonObject();
                    offset = update.get("update_id").getAsLong() + 1;
                    try {
                        handleUpdate(update);
                    } catch (RuntimeException e) {
                        plugin.getLogger().warning("Telegram: ошибка обработки сообщения: " + e.getMessage());
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                plugin.getLogger().warning("Telegram: сбой опроса (" + e.getMessage() + "), повтор через 5 сек.");
                sleepQuietly(5000);
            }
        }
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---------- обработка команд бота ----------

    private void handleUpdate(JsonObject update) {
        if (!update.has("message")) {
            return;
        }
        JsonObject message = update.getAsJsonObject("message");
        if (!message.has("text")) {
            return;
        }
        String text = message.get("text").getAsString().trim();
        JsonObject chat = message.getAsJsonObject("chat");
        long chatId = chat.get("id").getAsLong();
        String chatType = chat.has("type") ? chat.get("type").getAsString() : "private";
        String firstName = "";
        String username = "";
        if (message.has("from")) {
            JsonObject from = message.getAsJsonObject("from");
            if (from.has("first_name")) {
                firstName = from.get("first_name").getAsString();
            }
            if (from.has("username")) {
                username = from.get("username").getAsString();
            }
        }

        if (!text.startsWith("/")) {
            return;
        }
        String command = text.split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        int at = command.indexOf('@');
        if (at > 0) {
            command = command.substring(0, at); // /top@MyBot -> /top
        }

        switch (command) {
            case "/start":
                handleStart(chatId, chatType, firstName, username);
                break;
            case "/mystats":
                handleMyStats(chatId);
                break;
            case "/last":
                handleLast(chatId);
                break;
            case "/top":
                handleTop(chatId);
                break;
            case "/chatid":
                sendMessage(chatId, "\uD83D\uDCAC ID этого чата: <code>" + chatId + "</code>\n"
                        + "Впишите его в config.yml -> telegram.report-chat-id, чтобы сюда приходила "
                        + "периодическая статистика и топ игроков.");
                break;
            case "/help":
                sendMessage(chatId, helpText());
                break;
            default:
                break;
        }
    }

    private void handleStart(long chatId, String chatType, String firstName, String username) {
        if (!"private".equals(chatType)) {
            sendMessage(chatId, "\uD83D\uDC4B Привет! Я бот сервера MiniGamesTG.\n"
                    "Чтобы привязать аккаунт, напишите мне в личные сообщения: /start\n"
                    "ID этого чата (для report-chat-id): <code>" + chatId + "</code>");
            return;
        }
        PlayerRecord record = plugin.getStats().findByChatId(chatId);
        String code = createLinkCode(chatId, username);
        StringBuilder sb = new StringBuilder();
        sb.append("\uD83D\uDC4B Привет, ").append(escape(firstName.isEmpty() ? "игрок" : firstName)).append("!\n\n");
        sb.append("Я бот сервера <b>MiniGamesTG</b>. Привяжи аккаунт, чтобы получать результаты игр, "
                + "следить за своей статистикой и участвовать в топе.\n\n");
        sb.append("\uD83D\uDD11 Твой код привязки: <b>").append(code).append("</b>\n");
        sb.append("Введи в игре: <code>/mg tg ").append(code).append("</code>\n");
        sb.append("(код действует 10 минут)\n\n");
        if (record != null) {
            sb.append("✅ Этот чат уже привязан к игроку <b>").append(escape(record.getName())).append("</b>\n\n");
        }
        sb.append(helpText());
        sendMessage(chatId, sb.toString());
    }

    private void handleMyStats(long chatId) {
        PlayerRecord record = plugin.getStats().findByChatId(chatId);
        if (record == null) {
            sendNotLinked(chatId);
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("\uD83D\uDCCA Статистика игрока <b>").append(escape(record.getName())).append("</b>\n");
        sb.append("Всего игр: <b>").append(record.getTotalGames()).append("</b>");
        sb.append(" | Побед: <b>").append(record.getTotalWins()).append("</b>");
        sb.append(" | Очки: <b>").append(record.getTotalPoints()).append("</b>");
        if (record.getTotalGames() > 0) {
            int winrate = Math.round(100f * record.getTotalWins() / record.getTotalGames());
            sb.append(" | Винрейт: <b>").append(winrate).append("%</b>");
        }
        sb.append("\n");
        if (!record.getByType().isEmpty()) {
            sb.append("\nПо типам игр:\n");
            Map<String, PlayerRecord.TypeStats> sorted = new TreeMap<>(record.getByType());
            for (Map.Entry<String, PlayerRecord.TypeStats> e : sorted.entrySet()) {
                me.igor1944.minigamestg.game.GameType type =
                        me.igor1944.minigamestg.game.GameType.fromString(e.getKey());
                String display = type != null ? type.getDisplay() : e.getKey();
                PlayerRecord.TypeStats s = e.getValue();
                sb.append("• ").append(display).append(": ").append(s.games).append(" игр, ")
                        .append(s.wins).append(" побед, ").append(s.points).append(" очков\n");
            }
        }
        sendMessage(chatId, sb.toString());
    }

    private void handleLast(long chatId) {
        PlayerRecord record = plugin.getStats().findByChatId(chatId);
        if (record == null) {
            sendNotLinked(chatId);
            return;
        }
        List<GameHistoryEntry> entries = plugin.getStats().historyOf(record.getUuid(), 5);
        if (entries.isEmpty()) {
            sendMessage(chatId, "\uD83D\uDCC2 Пока нет сыгранных игр. Самое время начать! /mg create");
            return;
        }
        SimpleDateFormat fmt = new SimpleDateFormat("dd.MM HH:mm");
        StringBuilder sb = new StringBuilder();
        sb.append("\uD83D\uDCDC Последние игры <b>").append(escape(record.getName())).append("</b>:\n\n");
        for (GameHistoryEntry entry : entries) {
            GameResult mine = entry.resultOf(record.getUuid());
            String mark = mine != null && mine.isWinner() ? "\uD83C\uDFC6 победа" : "◻️ участие";
            if (mine != null && mine.getPlace() > 1) {
                mark = "\uD83C\uDFC5 " + mine.getPlace() + " место";
            }
            sb.append("• ").append(entry.getName()).append(" #").append(entry.getGameId())
                    .append(" — ").append(mark)
                    .append(" (").append(fmt.format(new Date(entry.getTimestamp()))).append(")\n");
        }
        sendMessage(chatId, sb.toString());
    }

    private void handleTop(long chatId) {
        sendMessage(chatId, buildTopMessage(10));
    }

    private void sendNotLinked(long chatId) {
        sendMessage(chatId, "❌ Этот чат ещё не привязан к игроку.\n"
                + "Напишите /start, получите код и введите в игре: /mg tg <код>");
    }

    private String helpText() {
        return "\uD83D\uDCE6 Команды бота:\n"
                + "/start — привязать аккаунт\n"
                + "/mystats — моя статистика\n"
                + "/last — мои последние игры\n"
                + "/top — топ игроков сервера\n"
                + "/chatid — ID этого чата\n"
                + "/help — эта справка";
    }

    // ---------- привязка аккаунта ----------

    /** Создаёт одноразовый 6-значный код для чата. Вызывается из потока бота. */
    public String createLinkCode(long chatId, String username) {
        cleanupExpiredLinks();
        String code;
        java.util.concurrent.ThreadLocalRandom rnd = java.util.concurrent.ThreadLocalRandom.current();
        do {
            code = String.format("%06d", rnd.nextInt(1_000_000));
        } while (pendingLinks.containsKey(code));
        pendingLinks.put(code, new PendingLink(chatId, username,
                System.currentTimeMillis() + LINK_CODE_TTL_MILLIS));
        return code;
    }

    /**
     * Завершает привязку: игрок вводит код в Minecraft-команде /mg tg <код>.
     * @return true, если код верный и привязка выполнена.
     */
    public boolean completeLink(String code, Player player) {
        PendingLink pending = pendingLinks.remove(code);
        if (pending == null || pending.expiresAt < System.currentTimeMillis()) {
            return false;
        }
        boolean ok = plugin.getStats().link(player.getUniqueId(), player.getName(),
                pending.chatId, pending.username);
        if (ok) {
            sendMessage(pending.chatId, "✅ Аккаунт успешно привязан к игроку <b>"
                    + escape(player.getName()) + "</b>!\n"
                    + "Теперь вам будут приходить результаты ваших игр. Команды: /mystats /last /top");
        }
        return ok;
    }

    private void cleanupExpiredLinks() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, PendingLink>> it = pendingLinks.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue().expiresAt < now) {
                it.remove();
            }
        }
    }

    // ---------- исходящие уведомления ----------

    /** Личные уведомления участникам о результате игры + опционально итог в общий чат. */
    public void notifyGameFinished(Game game, List<GameResult> results) {
        if (!isRunning()) {
            return;
        }
        String summary = buildGameSummary(game, results);
        if (plugin.getConfig().getBoolean("telegram.notify-players", true)) {
            for (GameResult r : results) {
                PlayerRecord record = plugin.getStats().getRecord(r.getUuid());
                if (record == null || !record.isLinked()) {
                    continue;
                }
                String personalResult;
                if (r.isWinner()) {
                    personalResult = "\uD83C\uDFC6 <b>ПОБЕДА!</b>";
                } else if (r.getPlace() > 1) {
                    personalResult = "\uD83C\uDFC5 Место: <b>" + r.getPlace() + "</b>";
                } else {
                    personalResult = "Участие (без места)";
                }
                String text = summary + "\n\nТвой результат: " + personalResult
                        + "\n\uD83D\uDCCA Всего игр: " + record.getTotalGames()
                        + " | Побед: " + record.getTotalWins()
                        + " | Очки: " + record.getTotalPoints();
                sendMessage(record.getTelegramChatId(), text);
            }
        }
        if (plugin.getConfig().getBoolean("telegram.post-results-to-report-chat", false)) {
            String reportChat = getReportChatId();
            if (!reportChat.isEmpty()) {
                sendMessage(reportChat, summary);
            }
        }
    }

    /** Периодическая рассылка: общая статистика и топ в report-chat-id. */
    public void sendPeriodicStats() {
        if (!isRunning()) {
            return;
        }
        String reportChat = getReportChatId();
        if (reportChat.isEmpty()) {
            return;
        }
        long totalGames = plugin.getStats().getTotalGames();
        if (totalGames <= 0) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("\uD83D\uDCC8 <b>Общая статистика сервера</b>\n");
        sb.append("Всего игр сыграно: <b>").append(totalGames).append("</b>\n\n");
        sb.append(buildTopMessage(10));
        sendMessage(reportChat, sb.toString());
    }

    /** Формирует текст топа игроков. */
    private String buildTopMessage(int limit) {
        List<PlayerRecord> top = plugin.getStats().top(limit, null);
        StringBuilder sb = new StringBuilder();
        sb.append("\uD83C\uDFC6 <b>Топ игроков MiniGamesTG</b>\n");
        if (top.isEmpty()) {
            sb.append("Пока пусто — сыграйте первую игру!");
            return sb.toString();
        }
        String[] medals = {"\uD83E\uDD47", "\uD83E\uDD48", "\uD83E\uDD49"};
        int i = 0;
        for (PlayerRecord r : top) {
            i++;
            String place = i <= 3 ? medals[i - 1] : (i + ".");
            sb.append(place).append(" ").append(escape(r.getName()))
                    .append(" — побед: ").append(r.getTotalWins())
                    .append(", очки: ").append(r.getTotalPoints())
                    .append(" (всего игр: ").append(r.getTotalGames()).append(")\n");
        }
        return sb.toString();
    }

    /** Текст итогов игры (для личных уведомлений и общего чата). */
    private String buildGameSummary(Game game, List<GameResult> results) {
        StringBuilder sb = new StringBuilder();
        sb.append("\uD83C\uDFC1 Игра <b>").append(escape(game.getDisplayName())).append("</b> (")
                .append(game.getType().getDisplay()).append(") завершена!\n");
        String[] medals = {"\uD83E\uDD47", "\uD83E\uDD48", "\uD83E\uDD49"};
        boolean hasPlaces = results.stream().anyMatch(r -> r.getPlace() > 0);
        for (GameResult r : results) {
            if (r.getPlace() >= 1 && r.getPlace() <= 3) {
                sb.append(medals[r.getPlace() - 1]).append(" ").append(escape(r.getName())).append("\n");
            }
        }
        if (!hasPlaces) {
            sb.append("Без победителя — никто не успел.\n");
            sb.append("Участников: ").append(results.size()).append("\n");
        }
        return sb.toString();
    }

    private String getReportChatId() {
        String id = plugin.getConfig().getString("telegram.report-chat-id", "");
        return id == null ? "" : id.trim();
    }

    // ---------- HTTP ----------

    /** Отправка сообщения. Потокобезопасно, ошибки только логируются. */
    public void sendMessage(long chatId, String text) {
        sendMessage(String.valueOf(chatId), text);
    }

    /** Отправка сообщения (chat_id строкой — бывает отрицательным для групп). */
    public void sendMessage(String chatId, String text) {
        if (chatId == null || chatId.isEmpty() || token == null) {
            return;
        }
        synchronized (sendLock) {
            try {
                String body = "chat_id=" + URLEncoder.encode(chatId, StandardCharsets.UTF_8)
                        + "&text=" + URLEncoder.encode(text, StandardCharsets.UTF_8)
                        + "&parse_mode=HTML&disable_web_page_preview=true";
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "sendMessage"))
                        .timeout(Duration.ofSeconds(15))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) {
                    plugin.getLogger().warning("Telegram sendMessage -> HTTP " + response.statusCode()
                            + ": " + abbreviate(response.body()));
                }
            } catch (IOException e) {
                plugin.getLogger().warning("Telegram: не удалось отправить сообщение: " + e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException e) {
                plugin.getLogger().warning("Telegram: ошибка отправки: " + e.getMessage());
            }
        }
    }

    /** GET-запрос к Bot API, возвращает распарсенный JSON или null. */
    private JsonObject apiGet(String methodAndQuery) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + methodAndQuery))
                .timeout(Duration.ofSeconds(POLL_TIMEOUT_SECONDS + 15))
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 409) {
            plugin.getLogger().warning("Telegram: конфликт getUpdates — где-то ещё запущен этот же бот!");
        }
        if (response.statusCode() != 200) {
            plugin.getLogger().warning("Telegram " + methodAndQuery.split("\\?")[0]
                    + " -> HTTP " + response.statusCode() + ": " + abbreviate(response.body()));
            return null;
        }
        try {
            return JsonParser.parseString(response.body()).getAsJsonObject();
        } catch (RuntimeException e) {
            plugin.getLogger().warning("Telegram: нечитаемый ответ API: " + e.getMessage());
            return null;
        }
    }

    // ---------- утилиты ----------

    private static String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= 200 ? s : s.substring(0, 200) + "...";
    }

    /** Экранирование спецсимволов HTML для parse_mode=HTML. */
    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** Для отладки: список активных кодов привязки (количество). */
    public int getPendingLinkCount() {
        cleanupExpiredLinks();
        return pendingLinks.size();
    }

    /** Для будущих расширений. */
    public UUID findLinkedPlayer(long chatId) {
        PlayerRecord record = plugin.getStats().findByChatId(chatId);
        return record == null ? null : record.getUuid();
    }
}
