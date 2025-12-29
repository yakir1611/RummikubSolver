package com.example.rummikubsolver;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class RummiSet {
    private final List<Tile> tiles;

    // Default constructor (empty set)
    public RummiSet() {
        this.tiles = new ArrayList<>();
    }
    // Safe constructor - handles null input
    public RummiSet(List<Tile> initialTiles) {
        if (initialTiles != null) {
            this.tiles = new ArrayList<>(initialTiles);
        } else {
            this.tiles = new ArrayList<>();
        }
    }
    // Copy constructor (deep copy)
    public RummiSet(RummiSet other) {
        this.tiles = new ArrayList<>(other.tiles);
    }


    public void addTile(Tile tile) {
        tiles.add(tile);
    }
    // Add a tile at a specific position (0 = start)
    public void addTile(int index, Tile tile) {
        if (index >= 0 && index <= tiles.size()) {
            tiles.add(index, tile);
        }
    }
    public List<Tile> getTiles() {
        return tiles;
    }
    public int getSize() {
        return tiles.size();
    }
    // Remove a specific tile object
    public boolean removeTile(Tile tile) {
        return tiles.remove(tile);
    }
    // Remove a tile at a specific index
    public Tile removeTileAt(int index) {
        if (index >= 0 && index < tiles.size()) {
            return tiles.remove(index);
        }
        return null;
    }


    // The main function to check if this specific set is valid
    public boolean isValid() {
        if (tiles == null || tiles.size() < 3) {
            return false;
        }
        // Work on a copy to avoid changing the original order
        List<Tile> sortedTiles = new ArrayList<>(tiles);
        if (isGroup(sortedTiles)) {
            return true;
        }
        return isRun(sortedTiles);
    }

    // Check for "Group": Same value, different colors
    private boolean isGroup(List<Tile> checkList) {
        if (tiles.size() > 4) {
            return false;
        }
        int targetValue = -1;
        List<Tile.Color> colorsSeen = new ArrayList<>();

        for (Tile tile : checkList) {
            if (tile.isJoker()) continue;

            if (targetValue == -1) {
                targetValue = tile.getValue();
            } else if (tile.getValue() != targetValue) {
                return false; // Numbers are not identical
            }

            if (colorsSeen.contains(tile.getColor())) {
                return false; // Duplicate color
            }
            colorsSeen.add(tile.getColor());
        }
        return true;
    }

    // Check for "Run": Same color, consecutive numbers (Gap Logic)
    private boolean isRun(List<Tile> checkList) {
        List<Tile> numbersOnly = new ArrayList<>();
        int jokerCount = 0;
        Tile.Color targetColor = null;

        // 1. Separate jokers and check color consistency
        for (Tile tile : checkList) {
            if (tile.isJoker()) {
                jokerCount++;
            } else {
                numbersOnly.add(tile);
                if (targetColor == null) {
                    targetColor = tile.getColor();
                } else if (targetColor != tile.getColor()) {
                    return false; // Mixed colors
                }
            }
        }

        // Sort the numbers (e.g., 3, 5, 6)
        numbersOnly.sort(Comparator.comparingInt(Tile::getValue));

        // Start checking from the first real number we have
        int expectedValue = numbersOnly.get(0).getValue();

        for (Tile tile : numbersOnly) {
            int currentValue = tile.getValue();
            // Loop: Fill gaps with jokers
            while (expectedValue < currentValue) {
                if (jokerCount > 0) {
                    jokerCount--;    // Use a joker
                    expectedValue++; // Advance the expectation
                } else {
                    return false;    // Missing number and no jokers left
                }
            }
            // Check if we have the expected number
            if (currentValue == expectedValue) {
                expectedValue++;
            } else {
                return false;
            }
        }
        return true;
    }


    // Helpers for the Solver
    public enum SetType {
        GROUP, RUN, INVALID
    }

    // Determine if this is a Group or a Run
    public SetType getSetType() {
        if (!isValid()) return SetType.INVALID;

        List<Tile> copy = new ArrayList<>(tiles);
        if (isGroup(copy)) return SetType.GROUP;
        return SetType.RUN;
    }

    // For Groups: returns which colors are missing (needed to complete the group)
    public List<Tile.Color> getGroupMissingColors() {
        if (getSetType() != SetType.GROUP) return new ArrayList<>();

        List<Tile.Color> missing = new ArrayList<>();
        // Add all possible colors
        missing.add(Tile.Color.RED);
        missing.add(Tile.Color.BLUE);
        missing.add(Tile.Color.BLACK);
        missing.add(Tile.Color.YELLOW);

        // Remove colors that are already present
        for (Tile t : tiles) {
            if (!t.isJoker()) {
                missing.remove(t.getColor());
            }
        }
        return missing;
    }

    // For Runs: returns the next logical number needed to extend the run
    // Example: [3, 4, Joker] -> The logical sequence is 3,4,5 -> so it returns 6.
    public int getRunNextValue() {
        if (getSetType() != SetType.RUN) return -1;
        List<Tile> sorted = new ArrayList<>(tiles);
        sorted.sort(Comparator.comparingInt(Tile::getValue));

        int lowestReal = -1;
        int jokersBefore = 0; // Count jokers that sorted to the start (value 0)

        for (Tile t : sorted) {
            if (t.isJoker()) {
                jokersBefore++;
            } else {
                lowestReal = t.getValue();
                break; // Found the first real number
            }
        }
        // Calculate where the run logically starts and ends
        int logicalStart = lowestReal - jokersBefore;
        int logicalEnd = logicalStart + tiles.size() - 1;

        if (logicalEnd >= 13) return -1; // Cannot extend beyond 13
        return logicalEnd + 1;
    }

    // Returns the number needed at the START of the run
    // Example: [4, 5, 6] -> logicalStart is 4 -> returns 3.
    public int getRunPrecedingValue() {
        if (getSetType() != SetType.RUN) return -1;

        List<Tile> sorted = new ArrayList<>(tiles);
        sorted.sort(Comparator.comparingInt(Tile::getValue));

        int lowestReal = -1;
        int jokersBefore = 0;

        // Find the first real number and count jokers acting as smaller numbers
        for (Tile t : sorted) {
            if (t.isJoker()) {
                jokersBefore++;
            } else {
                lowestReal = t.getValue();
                break;
            }
        }

        // Calculate the logical start of the run
        // Example: Joker, 4, 5. Lowest=4, Jokers=1. Logical Start = 3.
        int logicalStart = lowestReal - jokersBefore;

        // Check boundary: We cannot go below 1
        if (logicalStart <= 1) return -1;

        return logicalStart - 1; // Return the value needed to extend backwards
    }

    // Helper to get the run's color (returns null if it's a group or invalid)
    public Tile.Color getRunColor() {
        if (getSetType() != SetType.RUN) return null;
        for (Tile t : tiles) {
            if (!t.isJoker()) return t.getColor();
        }
        return null;
    }

    @Override
    public String toString() {
        return tiles.toString();
    }
}