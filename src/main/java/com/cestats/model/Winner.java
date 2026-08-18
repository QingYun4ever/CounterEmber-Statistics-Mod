package com.cestats.model;

/** Match outcome. Separate from {@link Side} because a match can also be drawn or unknown. */
public enum Winner {
    CT,
    T,
    DRAW,
    UNKNOWN;

    public static Winner ofSide(Side side) {
        return side == Side.CT ? CT : T;
    }

    public boolean matches(Side side) {
        return (this == CT && side == Side.CT) || (this == T && side == Side.T);
    }
}
