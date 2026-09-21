package me.igor1944.minigamestg.game;

import java.util.Locale;

/** Тип мини-игры. */
public enum GameType {

    DUEL("Дуэль"),
    REACTION("Реакция"),
    CUSTOM("Кастомная");

    private final String display;

    GameType(String display) {
        this.display = display;
    }

    /** Название типа для показа игрокам. */
    public String getDisplay() {
        return display;
    }

    /** Распознаёт тип по строке (англ. и рус. варианты). */
    public static GameType fromString(String input) {
        if (input == null) {
            return null;
        }
        String s = input.trim().toLowerCase(Locale.ROOT);
        switch (s) {
            case "duel":
            case "дуэль":
            case "дуель":
                return DUEL;
            case "reaction":
            case "реакция":
            case "клик":
                return REACTION;
            case "custom":
            case "кастом":
            case "кастомная":
                return CUSTOM;
            default:
                break;
        }
        for (GameType type : values()) {
            if (type.name().equalsIgnoreCase(s)) {
                return type;
            }
        }
        return null;
    }
}
