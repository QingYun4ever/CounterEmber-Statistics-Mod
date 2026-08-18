package com.cestats.model;

/** Which side of the bomb scenario a player is on. The server names them in Chinese. */
public enum Side {
    CT,
    T;

    public static final String TEAM_CT = "反恐精英";
    public static final String TEAM_T = "恐怖分子";

    public static Side fromTeam(String team) {
        return TEAM_CT.equals(team) ? CT : T;
    }

    public Side other() {
        return this == CT ? T : CT;
    }

    public String teamName() {
        return this == CT ? TEAM_CT : TEAM_T;
    }
}
