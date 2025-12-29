package com.example.rummikubsolver;
import java.util.ArrayList;
import java.util.List;

public class Board {
    private final List<RummiSet> sets;

    // Default constructor
    public Board() {
        this.sets = new ArrayList<>();
    }

    // Copy constructor (Deep Copy)
    public Board(Board other) {
        this.sets = new ArrayList<>();
        for (RummiSet set : other.sets) {
            this.sets.add(new RummiSet(set)); // Use RummiSet's copy constructor
        }
    }

    public void addSet(RummiSet set) {
        sets.add(set);
    }
    public boolean removeSet(RummiSet set) {
        return sets.remove(set);
    }
    public List<RummiSet> getSets() {
        return sets;
    }
    public int getSetsCount() {
        return sets.size();
    }


    // Check if the ENTIRE board is valid (all sets are valid)
    public boolean isValid() {
        for (RummiSet set : sets) {
            if (!set.isValid()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("Board containing ").append(sets.size()).append(" sets:\n");
        for (int i = 0; i < sets.size(); i++) {
            sb.append(i + 1).append(". ").append(sets.get(i)).append("\n");
        }
        return sb.toString();
    }
}