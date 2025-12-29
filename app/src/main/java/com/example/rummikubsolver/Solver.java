package com.example.rummikubsolver;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
//Solver.java
public class Solver {

    /**
     * Main entry point for the solver.
     * Tries to make a move in the following priority order:
     * 1. Play full sets directly from hand.
     * 2. Steal a tile from the board (from ends or middle) to complete a pair from hand.
     * 3. Add a single tile to an existing set on the board.
     */
    public boolean makeMove(Board board, Hand hand) {
        boolean madeProgress = false;

        // Step 1: Try to play complete sets from the hand
        while (playNewSetFromHand(board, hand)) {
            madeProgress = true;
        }

        // Step 2: Try to steal a tile from the board to complete a pair
        while (playSmartPairTheft(board, hand)) {
            madeProgress = true;
        }

        // Step 3: Try to add single tiles to existing sets
        while (addSingleTileToExistingSet(board, hand)) {
            madeProgress = true;
        }

        return madeProgress;
    }

    // =================================================================
    // Step 1: Play Full Sets from Hand
    // =================================================================

    /**
     * Orchestrates Step 1: Checks for Runs first, then Groups.
     */
    private boolean playNewSetFromHand(Board board, Hand hand) {
        // Priority 1: Runs (e.g., 3, 4, 5 of same color)
        if (findAndPlayRun(board, hand)) return true;

        // Priority 2: Groups (e.g., 7, 7, 7 of different colors)
        if (findAndPlayGroup(board, hand)) return true;

        return false;
    }

    /**
     * Scans the hand to find and play valid Run sets (Same color, consecutive values).
     */
    private boolean findAndPlayRun(Board board, Hand hand) {
        List<Tile> tiles = new ArrayList<>(hand.getTiles());

        // Sort first by Color, then by Value to easily find runs
        tiles.sort((t1, t2) -> {
            int colorCmp = t1.getColor().compareTo(t2.getColor());
            if (colorCmp != 0) return colorCmp;
            return Integer.compare(t1.getValue(), t2.getValue());
        });

        for (int i = 0; i < tiles.size(); i++) {
            Tile startTile = tiles.get(i);
            if (startTile.isJoker()) continue;

            List<Tile> potentialRun = new ArrayList<>();
            potentialRun.add(startTile);

            int nextNeededValue = startTile.getValue() + 1;
            Tile.Color runColor = startTile.getColor();

            for (int j = i + 1; j < tiles.size(); j++) {
                Tile current = tiles.get(j);
                if (current.getColor() != runColor) break;

                if (current.getValue() == nextNeededValue) {
                    potentialRun.add(current);
                    nextNeededValue++;
                }
            }

            if (potentialRun.size() >= 3) {
                RummiSet newSet = new RummiSet(potentialRun);
                if (newSet.isValid()) {
                    placeNewSet(board, hand, newSet, potentialRun);
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Scans the hand to find and play valid Group sets (Same value, different colors).
     */
    private boolean findAndPlayGroup(Board board, Hand hand) {
        List<Tile> tiles = new ArrayList<>(hand.getTiles());

        // Sort by Value only to group same numbers together
        tiles.sort(Comparator.comparingInt(Tile::getValue));

        for (int i = 0; i < tiles.size(); i++) {
            Tile t1 = tiles.get(i);
            if (t1.isJoker()) continue;

            List<Tile> potentialGroup = new ArrayList<>();
            potentialGroup.add(t1);

            for (int j = i + 1; j < tiles.size(); j++) {
                Tile t2 = tiles.get(j);
                // Ensure same value but different colors
                if (t2.getValue() == t1.getValue() && !hasColor(potentialGroup, t2.getColor())) {
                    potentialGroup.add(t2);
                }
            }

            if (potentialGroup.size() >= 3) {
                RummiSet newSet = new RummiSet(potentialGroup);
                if (newSet.isValid()) {
                    placeNewSet(board, hand, newSet, potentialGroup);
                    return true;
                }
            }
        }
        return false;
    }

    // =================================================================
    // Step 2: Smart Pair Theft (Handles Groups & Runs differently)
    // =================================================================

    /**
     * Iterates over all board sets to find a tile that can be stolen to complete a pair in hand.
     * Handles specific logic for Groups (any tile) and Runs (ends or valid middle split).
     */
    private boolean playSmartPairTheft(Board board, Hand hand) {
        // Create a copy to allow modification of the board during iteration
        List<RummiSet> setsCopy = new ArrayList<>(board.getSets());

        for (RummiSet boardSet : setsCopy) {

            // CASE A: GROUP (Any tile can be stolen if the remaining set is valid)
            if (boardSet.getSetType() == RummiSet.SetType.GROUP) {
                if (boardSet.getSize() > 3) {
                    // Try to steal from ANY index
                    for (int i = 0; i < boardSet.getSize(); i++) {
                        if (tryToMatchAndExecute(board, hand, boardSet, i)) return true;
                    }
                }
            }

            // CASE B: RUN (Only Ends or specific Middle Split allowed)
            else {
                // 1. Check Ends (Standard Steal) - Requires Size > 3
                if (boardSet.getSize() > 3) {
                    // Try first tile
                    if (tryToMatchAndExecute(board, hand, boardSet, 0)) return true;
                    // Try last tile
                    if (tryToMatchAndExecute(board, hand, boardSet, boardSet.getSize() - 1)) return true;
                }

                // 2. Check Middle Split - Requires Size >= 7
                if (boardSet.getSize() >= 7) {
                    // Try splitting in the middle (leaving 3 tiles on each side)
                    for (int i = 3; i <= boardSet.getSize() - 4; i++) {
                        if (tryToMatchAndExecute(board, hand, boardSet, i)) return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Core Logic: Checks if a specific tile on the board matches a pair in hand.
     * If valid, it executes the move (either simple removal or complex split).
     */
    private boolean tryToMatchAndExecute(Board board, Hand hand, RummiSet sourceSet, int tileIndex) {
        Tile stolenTile = sourceSet.getTiles().get(tileIndex);
        if (stolenTile.isJoker()) return false;

        List<Tile> handTiles = hand.getTiles();

        // Search for a matching pair in hand
        for (int i = 0; i < handTiles.size(); i++) {
            for (int j = i + 1; j < handTiles.size(); j++) {
                Tile t1 = handTiles.get(i);
                Tile t2 = handTiles.get(j);

                // Optimization: Skip pairs that clearly don't match
                if (!isPotentiallyValidPair(t1, t2)) continue;

                List<Tile> potentialNewSetTiles = new ArrayList<>();
                potentialNewSetTiles.add(stolenTile);
                potentialNewSetTiles.add(t1);
                potentialNewSetTiles.add(t2);

                // CRITICAL: Sort the tiles before validating to ensure correct order
                potentialNewSetTiles.sort((tile1, tile2) -> {
                    int colorCmp = tile1.getColor().compareTo(tile2.getColor());
                    if (colorCmp != 0) return colorCmp;
                    return Integer.compare(tile1.getValue(), tile2.getValue());
                });

                RummiSet newSet = new RummiSet(potentialNewSetTiles);
                if (newSet.isValid()) {
                    // EXECUTION
                    boolean isGroup = (sourceSet.getSetType() == RummiSet.SetType.GROUP);
                    boolean isEdge = (tileIndex == 0 || tileIndex == sourceSet.getSize() - 1);

                    // If it's a Group, OR it's the edge of a Run -> Simple Removal
                    if (isGroup || isEdge) {
                        sourceSet.getTiles().remove(tileIndex);
                        board.addSet(newSet);

                    } else {
                        // It must be a Run Middle Split: Break into Left, Right, and New
                        List<Tile> originalTiles = sourceSet.getTiles();

                        RummiSet leftSet = new RummiSet(new ArrayList<>(originalTiles.subList(0, tileIndex)));
                        RummiSet rightSet = new RummiSet(new ArrayList<>(originalTiles.subList(tileIndex + 1, originalTiles.size())));

                        // Verify split validity
                        if (!leftSet.isValid() || !rightSet.isValid()) return false;

                        board.removeSet(sourceSet);
                        board.addSet(leftSet);
                        board.addSet(rightSet);
                        board.addSet(newSet);
                    }

                    // Remove used tiles from hand
                    hand.removeTile(t1);
                    hand.removeTile(t2);
                    return true;
                }
            }
        }
        return false;
    }

    // =================================================================
    // Step 3: Add Single Tiles
    // =================================================================

    /**
     * Scans the hand for single tiles that can be appended to existing sets on the board.
     */
    private boolean addSingleTileToExistingSet(Board board, Hand hand) {
        for (RummiSet set : board.getSets()) {
            // Use copy of tiles to avoid modification errors
            for (Tile tile : new ArrayList<>(hand.getTiles())) {

                // Adding to a Group
                if (set.getSetType() == RummiSet.SetType.GROUP) {
                    if (set.getGroupMissingColors().contains(tile.getColor())
                            && tile.getValue() == set.getTiles().get(0).getValue()) {
                        set.addTile(tile);
                        hand.removeTile(tile);
                        return true;
                    }
                }

                // Adding to a Run
                if (set.getSetType() == RummiSet.SetType.RUN) {
                    Tile.Color runColor = set.getRunColor();
                    if (runColor != null && tile.getColor() == runColor) {
                        // Check if it fits at the end
                        if (tile.getValue() == set.getRunNextValue()) {
                            set.addTile(tile);
                            hand.removeTile(tile);
                            return true;
                        }
                        // Check if it fits at the start
                        if (tile.getValue() == set.getRunPrecedingValue()) {
                            set.addTile(0, tile);
                            hand.removeTile(tile);
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    // =================================================================
    // Helper Functions
    // =================================================================

    /**
     * Helper: Adds a new set to the board and removes the used tiles from the hand.
     */
    private void placeNewSet(Board board, Hand hand, RummiSet newSet, List<Tile> tilesToRemove) {
        board.addSet(newSet);
        for (Tile t : tilesToRemove) {
            hand.removeTile(t);
        }
    }

    /**
     * Helper: Checks if a specific color exists in a list of tiles (used for Groups).
     */
    private boolean hasColor(List<Tile> list, Tile.Color color) {
        for (Tile t : list) {
            if (t.getColor() == color) return true;
        }
        return false;
    }

    /**
     * Optimization: Quickly checks if two tiles have any potential to form a set together.
     * Prevents creating unnecessary objects for obviously invalid pairs.
     */
    private boolean isPotentiallyValidPair(Tile t1, Tile t2) {
        // Potential Group (Same value, different color)
        if (t1.getValue() == t2.getValue() && t1.getColor() != t2.getColor()) return true;

        // Potential Run (Same color, difference of 1 or 2)
        if (t1.getColor() == t2.getColor()) {
            int diff = Math.abs(t1.getValue() - t2.getValue());
            if (diff == 1 || diff == 2) return true;
        }
        return false;
    }
}