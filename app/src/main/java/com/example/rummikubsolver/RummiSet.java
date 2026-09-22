package com.example.rummikubsolver;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

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

        // Leftover jokers didn't fill an internal gap - they can still be
        // valid if there's room to extend the run at either end (e.g.
        // [5,6,7]+Joker can become [4,5,6,7] or [5,6,7,8]), but not if the
        // run is already 1..13 with nowhere left to put them.
        if (jokerCount > 0) {
            int lowestValue = numbersOnly.get(0).getValue();
            int highestValue = numbersOnly.get(numbersOnly.size() - 1).getValue();
            int spaceBelow = lowestValue - 1;
            int spaceAbove = 13 - highestValue;
            if (jokerCount > spaceBelow + spaceAbove) {
                return false;
            }
        }
        return true;
    }


    // Helpers for the GreedySolver
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

    // One tile (value + color) that could legally be tacked onto a Run, at either end.
    public static class PossibleAddition {
        private final int value;
        private final Tile.Color color;

        public PossibleAddition(int value, Tile.Color color) {
            this.value = value;
            this.color = color;
        }

        public int getValue() { return value; }
        public Tile.Color getColor() { return color; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof PossibleAddition)) return false;
            PossibleAddition that = (PossibleAddition) o;
            return value == that.value && color == that.color;
        }

        @Override
        public int hashCode() {
            return Objects.hash(value, color);
        }

        @Override
        public String toString() {
            return value + " " + color;
        }
    }

    // For Runs: every tile that could legally be added at either end.
    // A free joker (one not stuck plugging an internal gap) can end up on
    // either side, and each possible split gives a different pair of values.
    // So just loop over every legal split and grab them all.
    // Example: [5, 6, Joker] -> joker's free, splits are 0-below/1-below,
    // giving {4,7} and {3,8} -> together {3,4,7,8}.
    public List<PossibleAddition> getPossibleRunAdditions() {
        List<PossibleAddition> result = new ArrayList<>();
        if (getSetType() != SetType.RUN) return result;

        List<Tile> sorted = new ArrayList<>(tiles);
        sorted.sort(Comparator.comparingInt(Tile::getValue));

        List<Tile> numbersOnly = new ArrayList<>();
        int jokerCount = 0;
        for (Tile t : sorted) {
            if (t.isJoker()) {
                jokerCount++;
            } else {
                numbersOnly.add(t); // sorted already puts these in ascending order
            }
        }

        // same gap walk as isRun(), just to count how many jokers are actually
        // stuck plugging holes between real numbers
        int expected = numbersOnly.get(0).getValue();
        int jokersUsedForGaps = 0;
        for (Tile t : numbersOnly) {
            while (expected < t.getValue()) {
                jokersUsedForGaps++;
                expected++;
            }
            expected++;
        }

        int lowestReal = numbersOnly.get(0).getValue();
        int highestReal = numbersOnly.get(numbersOnly.size() - 1).getValue();
        int spareJokers = jokerCount - jokersUsedForGaps;
        Tile.Color runColor = numbersOnly.get(0).getColor();

        int spaceBelow = lowestReal - 1;
        int spaceAbove = 13 - highestReal;

        // try every way to split the spare jokers between the two ends.
        // still has to stay inside 1..13 on both sides, same overall bound
        // isRun() already checks
        int minBelow = Math.max(0, spareJokers - spaceAbove);
        int maxBelow = Math.min(spareJokers, spaceBelow);
        for (int jokersBelow = minBelow; jokersBelow <= maxBelow; jokersBelow++) {
            int jokersAbove = spareJokers - jokersBelow;
            int logicalStart = lowestReal - jokersBelow;
            int logicalEnd = highestReal + jokersAbove;

            if (logicalStart - 1 >= 1) {
                addIfNew(result, new PossibleAddition(logicalStart - 1, runColor));
            }
            if (logicalEnd + 1 <= 13) {
                addIfNew(result, new PossibleAddition(logicalEnd + 1, runColor));
            }
        }
        return result;
    }

    private void addIfNew(List<PossibleAddition> list, PossibleAddition candidate) {
        if (!list.contains(candidate)) list.add(candidate);
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