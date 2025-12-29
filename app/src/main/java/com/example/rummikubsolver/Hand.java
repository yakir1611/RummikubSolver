package com.example.rummikubsolver;
import java.util.ArrayList;
import java.util.List;
public class Hand {
    private final List<Tile> tiles;

    // Default constructor (empty hand)
    public Hand() {
        this.tiles = new ArrayList<>();
    }

    // Copy constructor (deep copy for the solver)
    public Hand(Hand other) {
        this.tiles = new ArrayList<>(other.tiles);
    }


    public void addTile(Tile tile) {
        tiles.add(tile);
    }
    public boolean removeTile(Tile tile) {
        return tiles.remove(tile);
    }
    public List<Tile> getTiles() {
        return tiles;
    }
    public int getSize() {
        return tiles.size();
    }
    public boolean isEmpty() {
        return tiles.isEmpty();
    }

    @Override
    public String toString() {
        return "Hand: " + tiles.toString();
    }
}