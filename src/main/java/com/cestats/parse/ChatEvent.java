package com.cestats.parse;

import com.cestats.model.Side;
import com.cestats.model.StatLine;
import com.cestats.model.Winner;

import java.util.List;

/** Every chat line we understand, as a closed set. */
public sealed interface ChatEvent {

    record Kill(String killer, Side killerSide, String weapon, String victim, Side victimSide)
            implements ChatEvent {
    }

    /** A death with no killer — fall damage or the void. */
    record Death(String victim) implements ChatEvent {
    }

    record RoundEnd(Side winner, String reason) implements ChatEvent {
    }

    record Bomb(String site) implements ChatEvent {
    }

    record Result(Winner winner) implements ChatEvent {
    }

    record Stats(List<StatLine> players) implements ChatEvent {
    }

    /** Joined a lobby / queue / room: anything buffered belongs to a previous match. */
    record ContextReset(String why) implements ChatEvent {
    }
}
