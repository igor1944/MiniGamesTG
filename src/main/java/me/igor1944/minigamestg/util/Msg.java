package me.igor1944.minigamestg.util;

import org.bukkit.ChatColor;

/**
 * Утилита для цветных сообщений с префиксом плагина.
 */
public final class Msg {

    private static String prefix = "&8[&bMiniGamesTG&8] ";

    private Msg() {
    }

    public static void setPrefix(String newPrefix) {
        prefix = newPrefix == null ? "" : newPrefix;
    }

    /** Переводит &-коды в цвета. */
    public static String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text == null ? "" : text);
    }

    /** Сообщение с цветовыми кодами и префиксом плагина. */
    public static String prefixed(String text) {
        return color(prefix) + color(text);
    }
}
