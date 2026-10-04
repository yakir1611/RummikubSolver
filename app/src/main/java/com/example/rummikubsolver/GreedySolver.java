package com.example.rummikubsolver;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class GreedySolver {
    /**
     * main entry point for the solver, tries to make a move in this priority order
     * 1. play full sets straight from the hand
     * 2. steal a tile from the board from an end or the middle to finish a pair from the hand
     * 3. add one tile to a set that is already on the board
     */
    public boolean makeMove(Board board, Hand hand) {
        boolean madeProgress = false;
        // 1. Try to play complete sets from the hand
        while (playNewSetFromHand(board, hand)) {
            madeProgress = true;
        }
        // 2. Try to steal a tile from the board to complete a pair
        while (playSmartPairTheft(board, hand)) {
            madeProgress = true;
        }
        // 3. Try to add single tiles to existing sets
        while (addSingleTileToExistingSet(board, hand)) {
            madeProgress = true;
        }
        return madeProgress;
    }


    /**
     * step 1 check for RUNS first then GROUPS
     */
    private boolean playNewSetFromHand(Board board, Hand hand) {
        // first priority RUNS
        if (findAndPlayRun(board, hand)) return true;
        // second priority GROUPS
        return findAndPlayGroup(board, hand);
    }

    /**
     * scans the hand to find and play valid RUN sets a RUN is the same color with values in a row
     */
    private boolean findAndPlayRun(Board board, Hand hand) {
        // a joker has no real color getColor() returns null so the color sort below crashes on it
        // we remove it here first to avoid the crash
        // building a RUN with a joker is a separate ticket
        List<Tile> tiles = new ArrayList<>();
        for (Tile t : hand.getTiles()) {
            if (!t.isJoker()) tiles.add(t);
        }

        // Sort first by Color, then by Value to easily find runs
        tiles.sort((t1, t2) -> {
            int colorCmp = t1.getColor().compareTo(t2.getColor());
            if (colorCmp != 0) return colorCmp;
            return Integer.compare(t1.getValue(), t2.getValue());
        });

        for (int i = 0; i < tiles.size(); i++) {
            Tile startTile = tiles.get(i);

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
     * scans the hand to find and play valid GROUP sets with the same value with different colors
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

    /**
     * step 2 smart pair theft GROUPS and RUNS need different handling
     */

    /**
     * goes over all board sets to find a tile we can steal to complete a pair in the hand
     * a GROUP lets us take any tile and RUN lets us take an end or split a valid middle
     */
    private boolean playSmartPairTheft(Board board, Hand hand) {
        // Create a copy to allow modification of the board during iteration
        List<RummiSet> setsCopy = new ArrayList<>(board.getSets());
        for (RummiSet boardSet : setsCopy) {
            // for a GROUP any tile can be stolen if the set that stays is still valid
            if (boardSet.getSetType() == RummiSet.SetType.GROUP) {
                if (boardSet.getSize() > 3) {
                    // Try to steal from ANY index
                    for (int i = 0; i < boardSet.getSize(); i++) {
                        if (tryToMatchAndExecute(board, hand, boardSet, i)) return true;
                    }
                }
            }

            // for a RUN only an end or a valid middle split can be stolen
            else {
                // steal from an end this needs a RUN bigger than 3
                if (boardSet.getSize() > 3) {
                    // Try first tile
                    if (tryToMatchAndExecute(board, hand, boardSet, 0)) return true;
                    // Try last tile
                    if (tryToMatchAndExecute(board, hand, boardSet, boardSet.getSize() - 1)) return true;
                }

                // split in the middle this needs a RUN of 7 or more
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
     * checks if a tile on the board matches a pair in the hand
     * if it does it makes the move by a simple removal or a split
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
                // Skip pairs that clearly don't match
                if (!isPotentiallyValidPair(t1, t2)) continue;

                List<Tile> potentialNewSetTiles = new ArrayList<>();
                potentialNewSetTiles.add(stolenTile);
                potentialNewSetTiles.add(t1);
                potentialNewSetTiles.add(t2);

                // Sort the tiles before validating to ensure correct order
                potentialNewSetTiles.sort((tile1, tile2) -> {
                    int colorCmp = tile1.getColor().compareTo(tile2.getColor());
                    if (colorCmp != 0) return colorCmp;
                    return Integer.compare(tile1.getValue(), tile2.getValue());
                });

                RummiSet newSet = new RummiSet(potentialNewSetTiles);
                if (newSet.isValid()) { // EXECUTION
                    boolean isGroup = (sourceSet.getSetType() == RummiSet.SetType.GROUP);
                    boolean isEdge = (tileIndex == 0 || tileIndex == sourceSet.getSize() - 1);

                    // a GROUP or the edge of a RUN so just remove the tile
                    if (isGroup || isEdge) {
                        sourceSet.getTiles().remove(tileIndex);
                        board.addSet(newSet);

                    } else {
                        // middle of a RUN so split it into left right and the new set
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


    /**
     * step 3 scans the hand for single tiles that can be appended to existing sets on the board.
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
                if (set.getSetType() == RummiSet.SetType.RUN &&
                        tileMatchesPossibleAddition(set, tile)) {
                    // Figure out which side it goes on by comparing to the real tiles already there
                    int lowestValue = Integer.MAX_VALUE;
                    for (Tile t : set.getTiles()) {
                        if (!t.isJoker()) lowestValue = Math.min(lowestValue, t.getValue());
                    }
                    if (tile.getValue() < lowestValue) {
                        set.addTile(0, tile);
                    } else {
                        set.addTile(tile);
                    }
                    hand.removeTile(tile);
                    return true;
                }
            }
        }
        return false;
    }

    /**
     *  helper functions
     */
    // adds a new set to the board and removes the used tiles from the hand
    private void placeNewSet(Board board, Hand hand, RummiSet newSet, List<Tile> tilesToRemove) {
        board.addSet(newSet);
        for (Tile t : tilesToRemove) {
            hand.removeTile(t);
        }
    }

    // checks if a specific color exists in a list of tiles (used for Groups)
    private boolean hasColor(List<Tile> list, Tile.Color color) {
        for (Tile t : list) {
            if (t.getColor() == color) return true;
        }
        return false;
    }

    // checks if this tile's value+color shows up anywhere in the run's possible additions
    private boolean tileMatchesPossibleAddition(RummiSet set, Tile tile) {
        for (RummiSet.PossibleAddition addition : set.getPossibleRunAdditions()) {
            if (addition.getValue() == tile.getValue() && addition.getColor() == tile.getColor()) {
                return true;
            }
        }
        return false;
    }

    // quick check if two tiles could even form a set together, so we skip obviously invalid pairs
    private boolean isPotentiallyValidPair(Tile t1, Tile t2) {
        // could be a GROUP same value and different color
        if (t1.getValue() == t2.getValue() && t1.getColor() != t2.getColor()) return true;

        // could be a RUN same color and a gap of 1 or 2
        if (t1.getColor() == t2.getColor()) {
            int diff = Math.abs(t1.getValue() - t2.getValue());
            if (diff == 1 || diff == 2) return true;
        }
        return false;
    }
}