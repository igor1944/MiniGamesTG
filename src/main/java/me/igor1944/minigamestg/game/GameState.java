package me.igor1944.minigamestg.game;

/** Состояние мини-игры. */
public enum GameState {
    /** Лобби: набор игроков. */
    LOBBY,
    /** Игра идёт. */
    RUNNING,
    /** Игра завершена, результаты записаны. */
    FINISHED,
    /** Игра отменена. */
    CANCELLED
}
