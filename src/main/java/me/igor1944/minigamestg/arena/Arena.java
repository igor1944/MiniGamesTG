package me.igor1944.minigamestg.arena;

import org.bukkit.Location;

/**
 * Дуэльная арена: две точки спавна (pos1 и pos2).
 * Создаётся админом: /mg arena create &lt;имя&gt;, /mg arena set &lt;имя&gt; &lt;1|2&gt;.
 */
public class Arena {

    private final String name;
    private Location pos1;
    private Location pos2;

    public Arena(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public Location getPos1() {
        return pos1;
    }

    public void setPos1(Location pos1) {
        this.pos1 = pos1;
    }

    public Location getPos2() {
        return pos2;
    }

    public void setPos2(Location pos2) {
        this.pos2 = pos2;
    }

    /** Обе точки спавна заданы — арену можно использовать. */
    public boolean isComplete() {
        return pos1 != null && pos2 != null;
    }
}
