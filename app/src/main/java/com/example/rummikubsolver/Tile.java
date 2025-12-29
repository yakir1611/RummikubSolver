package com.example.rummikubsolver;

import java.util.Objects;

public class Tile {
    public enum Color {
        RED, BLUE, BLACK, YELLOW
    }
    private final int id;
    private final int value; // 1 to 13, 0 if Joker
    private final Color color;
    private final boolean isJoker;

    // Constructor for regular tiles
    public Tile(int id, int value, Color color) {
        this.id = id;
        this.value = value;
        this.color = color;
        this.isJoker = false;
    }

    // Constructor for Joker
    public Tile(int id) {
        this.id = id;
        this.value = 0;
        this.color = null;
        this.isJoker = true;
    }
    public int getId() {
        return id;
    }
    public int getValue() {
        return value;
    }
    public Color getColor() {
        return color;
    }
    public boolean isJoker() {
        return isJoker;
    }

    @Override
    public String toString() {
        if (isJoker) return "Joker";
        return value + " " + color;
    }

    // Standard equality check based on unique ID
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Tile tile = (Tile) o;
        return id == tile.id;
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}