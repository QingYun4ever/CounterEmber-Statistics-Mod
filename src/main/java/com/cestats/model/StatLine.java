package com.cestats.model;

/** One row of the server's end-of-match table. These numbers are authoritative. */
public record StatLine(String name, Side team, boolean mvp, int kills, int deaths, int assists,
                       int adr, int kast, double rating) {
}
