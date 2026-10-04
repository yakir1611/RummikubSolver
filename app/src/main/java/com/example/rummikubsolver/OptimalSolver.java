package com.example.rummikubsolver;

import java.util.ArrayList;
import java.util.List;

/**
 * finds the best move for one turn that plays the most tiles from the hand
 * it searches with backtracking and counts tiles by amount not by object
 * it starts from the GreedySolver as a floor and drops paths that cannot beat the best
 * run on a background thread it is not thread safe
 */
public class OptimalSolver {
    /**
     * the most nodes the search may explore
     * if it goes over this solve stops and returns the best move it found so far
     * and sets searchCompleted to false
     */
    public static final long DEFAULT_NODE_CAP = 2000000;

    // Outcome of an optimal move search.
    public static class Result {
        // The complete final board
        public final List<RummiSet> newBoardSets;
        // the hand tiles that were played
        public final List<Tile> playedHandTiles;
        // true if the board tiles can form a valid layout false if bad detection or impossible
        public final boolean feasible;
        // True if the search finished completely (its the optimal)
        // else its stopped because of the node budget.
        public final boolean searchCompleted;
        // Number of search nodes explored
        public final long nodesExplored;

        Result(List<RummiSet> newBoardSets, List<Tile> playedHandTiles,
               boolean feasible, boolean searchCompleted, long nodesExplored) {
            this.newBoardSets = newBoardSets;
            this.playedHandTiles = playedHandTiles;
            this.feasible = feasible;
            this.searchCompleted = searchCompleted;
            this.nodesExplored = nodesExplored;
        }

        // helper for the UI to show optimal move or best move found when the search was capped
        public boolean isProvablyOptimal() {
            return feasible && searchCompleted;
        }
    }

    // the node cap this solver uses
    private final long nodeCap;
    // make a solver with your own node cap higher is more accurate lower is faster
    public OptimalSolver(long nodeCap) {
        this.nodeCap = nodeCap;
    }
    // Creates a solver using the default search budget of 2,000,000 nodes.
    public OptimalSolver() {
        this(DEFAULT_NODE_CAP);
    }

    // search state we reset all of it at the start of each solve()
    private int[][] total;          // [color][value 1..13] copies still in the pool
    private int[][] boardTiles;       // board copies still unconsumed (must reach 0)
    private int totalJokers;       // hand and bord
    private int boardJokers;
    private int handUsed;           // hand tiles consumed into sets so far
    private int optionalRemaining;  // hand tiles neither consumed nor skipped yet
    private long nodes;
    private boolean capped;        // True if the search hit the node budget.
    private int bestHandUsed;      // Max number of tiles played from hand that found so far
    private List<RummiSet> bestBoard; // Holds the winning board configuration (the layout of sets).
    private List<Tile> bestPlayed; // List of tiles played from the hand in the best solution.
    private List<PotentialSet> currentPath; // Tracks the stack of sets chosen in the current backtracking branch.

    // Physical tiles per cell, board tiles first, for reconstructing a recorded solution.
    private List<Tile>[][] cellTiles;
    private List<Tile> jokerTiles;   // Stores all physical jokers (board & hands)
    // original board copies per cell value that never changes
    private int[][] boardCount;
    // original board jokers value that never changes
    private int boardJokerCount;

    /**
     * runs the full solve for one turn
     * first build the pools then seed a floor with the GreedySolver then run the search
     * if nothing valid was found return the original board with feasible false
     * otherwise return the best board and the hand tiles it played
     */
    public Result solve(Board board, Hand hand) {
        initState(board, hand);             // build the pools and reset the state
        seedWithGreedy(board, hand);        // get a lower bound from the GreedySolver
        // run the search from the start. -1 means there is no previous cell
        // Long.MIN_VALUE is the smallest long value so nothing is below it which makes it a no key floor
        search(-1, Long.MIN_VALUE);

        if (bestHandUsed < 0) {             // return the board as is if nothing valid was found
            return new Result(deepCopySets(board.getSets()), new ArrayList<>(),
                    false, !capped, nodes);
        }
        // return the best board and the tiles we played
        return new Result(bestBoard, bestPlayed, true, !capped, nodes);
    }

    @SuppressWarnings("unchecked")
    private void initState(Board board, Hand hand) {
        total = new int[4][14];        // all real tile copies by color and value
        boardTiles = new int[4][14];   // how many of them came from the board
        boardCount = new int[4][14];   // the same board count kept frozen for rebuilding
        cellTiles = new List[4][14];   // the real tile objects for each cell
        for (int c = 0; c < 4; c++) {
            for (int v = 1; v <= 13; v++) {
                cellTiles[c][v] = new ArrayList<>(); // start each cell with an empty list
            }
        }
        jokerTiles = new ArrayList<>();  // all the joker objects
        totalJokers = 0;
        boardJokers = 0;
        boardJokerCount = 0;

        for (RummiSet s : board.getSets()) {
            for (Tile t : s.getTiles()) {
                addToPool(t, true);    // add every board tile to the pool marked as board
            }
        }
        for (Tile t : hand.getTiles()) {
            addToPool(t, false);       // add every hand tile to the pool marked as hand
        }

        handUsed = 0;
        optionalRemaining = hand.getSize();  // all hand tiles are still optional
        nodes = 0;                           // node counter starts at zero
        capped = false;                      // we did not hit the node cap yet
        bestHandUsed = -1;
        bestBoard = null;
        bestPlayed = null;
        currentPath = new ArrayList<>(); // the search stack starts empty
    }

    private void addToPool(Tile t, boolean fromBoard) {
        if (t.isJoker()) {
            jokerTiles.add(t);
            totalJokers++;
            if (fromBoard) {
                boardJokers++;
                boardJokerCount++;
            }
            return;
        }
        int v = t.getValue();
        // reject a bad tile before it reaches the pool
        if (v < 1 || v > 13 || t.getColor() == null) {
            throw new IllegalArgumentException("Invalid tile: " + t);
        }
        int c = t.getColor().ordinal();     // turn the color into a number from 0 to 3
        cellTiles[c][v].add(t);
        total[c][v]++;
        if (fromBoard) {
            boardTiles[c][v]++;
            boardCount[c][v]++;
        }
    }

    /**
     * runs the GreedySolver on copies
     * if it made a valid board we use its result as the first lower bound and the fallback best
     */
    private void seedWithGreedy(Board board, Hand hand) {
        // work on copies so we do not touch the real board and hand
        Board gb = new Board(board);
        Hand gh = new Hand(hand);
        new GreedySolver().makeMove(gb, gh);
        if (!gb.isValid()) {    // if greedy did not make a valid board skip the seed
            return;
        }
        List<Tile> played = new ArrayList<>(hand.getTiles());
        for (Tile t : gh.getTiles()) {
            played.remove(t);   // the tiles greedy played are the ones no longer in its hand
        }
        // save the greedy move as the first best solution
        bestHandUsed = played.size();
        bestBoard = deepCopySets(gb.getSets());
        bestPlayed = played;
    }

    /**
     * the backtracking search
     */

    /**
     * prevCell is the anchor cell of the set the caller just applied or -1
     * prevKey is that set shape key
     * when we anchor again on the same cell duplicate copies we only try candidates with key >= prevKey
     * so an unordered pair of sets is explored in exactly one order
     */
    private void search(int prevCell, long prevKey) {
        if (capped) {
            return;
        }
        // count this node and stop if we passed the budget
        nodes++;
        if (nodes > nodeCap) {
            capped = true;
            return;
        }
        // branch and bound even playing all the remaining hand tiles cannot beat the best so stop
        if (handUsed + optionalRemaining <= bestHandUsed) {
            return;
        }
        // the lowest real tile still left in the pool
        int cell = findAnchor();
        if (cell < 0) {
            // all real tiles are placed a leftover board joker means this branch is illegal
            if (boardJokers == 0 && handUsed > bestHandUsed) {
                record();
            }
            return;
        }

        int c = cell & 3;            // unpack the cell into its color
        int v = cell >> 2;           // and its value
        // minKey is the lowest set key we allow at this cell
        long minKey;
        if (cell == prevCell) { // still the same cell so there is a duplicate tile
            minKey = prevKey; // the next set must be equal or higher to avoid a repeat order
        } else {
            minKey = Long.MIN_VALUE; // a new cell so any key is allowed
        }
        // all the sets that can cover this cell
        List<PotentialSet> cands = generatePotentialSets(c, v);
        for (PotentialSet cd : cands) {
            if (cd.key < minKey) {     // skip a set that would repeat an order we already explored
                continue;
            } // take this set then recurse then undo it
            int fromBoardMask = apply(cd);
            currentPath.add(cd);
            search(cell, cd.key);
            currentPath.remove(currentPath.size() - 1);
            undo(cd, fromBoardMask);
            if (capped) {
                return;
            }
        }

        // skip branch we leave the rest of this tile in the hand instead of covering it
        // this order also stops counting the same board as cover then skip and skip then cover
        if (boardTiles[c][v] == 0) {
            int k = total[c][v];        // how many copies are left all from the hand now
            total[c][v] = 0;
            optionalRemaining -= k;     // they are no longer waiting for a decision
            search(-1, Long.MIN_VALUE);     // go on to the next tile
            total[c][v] = k;                         // undo put the copies back
            optionalRemaining += k;
        }
    }

    /**
     * the lowest real tile still left it goes by value first then by color
     * returns -1 if none are left
     */
    private int findAnchor() {
        for (int v = 1; v <= 13; v++) {
            for (int c = 0; c < 4; c++) {
                if (total[c][v] > 0) {
                    return (v << 2) | c;
                }
            }
        }
        return -1;
    }

    /**
     * building the possible sets
     */

    /**
     * one set shape that sits on a single cell
     * a RUN is kept as counts realMask is which real values are used and runEnd is the top value
     * and bottomJokers is how many jokers sit below the anchor
     * the exact joker spots are worked out later when we rebuild the set
     */
    private static final class PotentialSet {
        final boolean isRun;
        final int color;        // run color (groups use colorMask instead)
        final int value;        // run anchor value / group value
        final int realMask;     // runs: bit w set = real tile of value w is used
        final int runEnd;
        final int bottomJokers;
        final int colorMask;    // groups: colors present, anchor included
        final int jokers;       // total jokers consumed
        // key is one number that stands for the shape of the set
        // for a RUN it is realMask pushed up by 6 then runEnd pushed up by 2 then the jokers
        // for a GROUP it is a marker at bit 40 then the colors then the jokers
        // the bit 40 marker keeps every GROUP key above every RUN key so the two never collide
        final long key;
        final int handCost;     // hand tiles this set consumes right now (ordering only)

        PotentialSet(boolean isRun, int color, int value, int realMask, int runEnd,
                  int bottomJokers, int colorMask, int jokers, long key, int handCost) {
            this.isRun = isRun;
            this.color = color;
            this.value = value;
            this.realMask = realMask;
            this.runEnd = runEnd;
            this.bottomJokers = bottomJokers;
            this.colorMask = colorMask;
            this.jokers = jokers;
            this.key = key;
            this.handCost = handCost;
        }
    }

    /**
     * makes every RUN and GROUP that can sit on this cell
     * ordered so the sets that play more hand tiles come first
     */
    private List<PotentialSet> generatePotentialSets(int c, int v) {
        List<PotentialSet> out = new ArrayList<>();
        walkRun(c, v, v + 1, 1 << v, 0, 1, out);
        genGroups(c, v, out);
        // more hand tiles first so we cut faster and the key just breaks ties
        out.sort((a, b) -> a.handCost != b.handCost
                ? Integer.compare(b.handCost, a.handCost)
                : Long.compare(a.key, b.key));
        return out;
    }

    /**
     * lists every RUN that starts at the anchor cell
     * it walks up one value at a time and picks a real tile or a joker for each next value
     * and adds a candidate at every legal stop of length 3 or more
     * jokers below the anchor are only tried once the walk is blocked at 13
     * because a spare joker below or above gives the same set and allowing both would duplicate it
     */
    private void walkRun(int c, int anchor, int w, int realMask, int jokersUsed, int len,
                         List<PotentialSet> out) {
        if (len >= 3) {                 // long enough so save this RUN as a candidate
            out.add(runPotentialSet(c, anchor, realMask, w - 1, 0, jokersUsed));
        }
        if (w > 13) {                   // blocked at the top now try spare jokers below the anchor
            for (int j = 1; jokersUsed + j <= totalJokers && anchor - j >= 1; j++) {
                if (len + j >= 3) {
                    out.add(runPotentialSet(c, anchor, realMask, 13, j, jokersUsed + j));
                }
            }
            return;
        }
        if (total[c][w] > 0) {          // use the real tile at this value
            walkRun(c, anchor, w + 1, realMask | (1 << w), jokersUsed, len + 1, out);
        }
        if (jokersUsed < totalJokers) { // or use a joker for this value
            walkRun(c, anchor, w + 1, realMask, jokersUsed + 1, len + 1, out);
        }
    }

    /**
     * builds one RUN candidate and works out its handCost and key
     * handCost is how many tiles it must take from the hand and key is a small id of the shape
     */
    private PotentialSet runPotentialSet(int c, int anchor, int realMask, int runEnd,
                                         int bottomJokers, int jokers) {
        // count the handCost by starting with the jokers from the hand
        // and then the real tiles are added
        int handCost = Math.max(0, jokers - boardJokers);
        for (int w = anchor; w <= runEnd; w++) {
            // a real value with no board copy also comes from the hand
            if ((realMask >> w & 1) != 0 && boardTiles[c][w] == 0) {
                handCost++;
            }

        } // build the RUN key realMask high then runEnd then jokers
        long key = ((long) realMask << 6) | (runEnd << 2) | bottomJokers;
        return new PotentialSet(true, c, anchor, realMask, runEnd, bottomJokers, 0,
                jokers, key, handCost);
    }

    /**
     * lists every GROUP at value v that includes the anchor color c
     * for each color set it also tries adding jokers up to size 4 and works out handCost and key
     */
    private void genGroups(int c, int v, List<PotentialSet> out) {
        for (int mask = 0; mask < 16; mask++) { // every subset of the four colors from 0000 to 1111
            if ((mask & (1 << c)) == 0) {         // must include the anchor color
                continue;
            }

            boolean available = true;
            for (int c2 = 0; c2 < 4 && available; c2++) {
                // a color in the set has no real tile of this value
                if (c2 != c && (mask & (1 << c2)) != 0 && total[c2][v] == 0) {
                    available = false;
                }
            }
            if (!available) {
                continue;
            }

            int size = Integer.bitCount(mask);  // how many real colors are in this group
            for (int j = 0; j <= totalJokers && size + j <= 4; j++) {
                if (size + j < 3) {
                    continue;
                }

                // count the handCost by starting with the jokers from the hand
                // and then the real tiles are added
                int handCost = Math.max(0, j - boardJokers);
                for (int c2 = 0; c2 < 4; c2++) {
                    // a color with no board copy comes from the hand
                    if ((mask & (1 << c2)) != 0 && boardTiles[c2][v] == 0) {
                        handCost++;
                    }
                }

                // build the GROUP key marker at bit 40 then colors then jokers
                long key = (1L << 40) | ((long) mask << 2) | j;
                out.add(new PotentialSet(false, c, v, 0, 0, 0,
                        mask, j, key, handCost));
            }
        }
    }

    /**
     * applying and undoing a candidate
     */

    /**
     * consumes the tiles of this candidate board copies before hand copies
     * returns bitmask that marks for each tile whether board copy was taken so undo can put it back
     */
    private int apply(PotentialSet cd) {
        int fromBoardMask = 0;
        int i = 0;
        if (cd.isRun) {
            // a RUN takes its real values
            for (int w = cd.value; w <= cd.runEnd; w++) {
                if ((cd.realMask >> w & 1) != 0) {
                    if (consumeCell(cd.color, w)) {
                        fromBoardMask = fromBoardMask | (1 << i);
                    }
                    i++;
                }
            }
        } else {
            // a GROUP takes one tile per color in the set
            for (int c2 = 0; c2 < 4; c2++) {
                if ((cd.colorMask >> c2 & 1) != 0) {
                    if (consumeCell(c2, cd.value)) {
                        fromBoardMask = fromBoardMask | (1 << i);
                    }
                    i++;
                }
            }
        }
        for (int k = 0; k < cd.jokers; k++) {
            // then the jokers it uses
            if (consumeJoker()) {
                fromBoardMask = fromBoardMask | (1 << i);
            }
            i++;
        }
        return fromBoardMask;
    }

    /**
     * puts back everything apply took using the bitmask to know if each tile was a board or a hand copy
     */
    private void undo(PotentialSet cd, int fromBoardMask) {
        int i = 0;
        if (cd.isRun) {
            // give back the RUN real values
            for (int w = cd.value; w <= cd.runEnd; w++) {
                if ((cd.realMask >> w & 1) != 0) {
                    restoreCell(cd.color, w, (fromBoardMask >> i & 1) != 0);
                    i++;
                }
            }
        } else {
            // give back the GROUP colors
            for (int c2 = 0; c2 < 4; c2++) {
                if ((cd.colorMask >> c2 & 1) != 0) {
                    restoreCell(c2, cd.value, (fromBoardMask >> i & 1) != 0);
                    i++;
                }
            }
        }
        for (int k = 0; k < cd.jokers; k++) {
            totalJokers++;
            if ((fromBoardMask >> i & 1) != 0) {
                // this joker came from the board
                boardJokers++;
            } else {
                // this joker came from the hand
                handUsed--;
                optionalRemaining++;
            }
            i++;
        }
    }

    /**
     * takes one copy of this tile from the pool board copy first
     * returns true for a board copy and false for a hand copy
     */
    private boolean consumeCell(int c, int v) {
        total[c][v]--;
        if (boardTiles[c][v] > 0) {
            boardTiles[c][v]--;
            return true;
        }// no board copy left so it came from the hand
        handUsed++;
        optionalRemaining--;
        return false;
    }

    /**
     * takes one joker from the pool board joker first
     * returns true for a board joker and false for a hand joker
     */
    private boolean consumeJoker() {
        totalJokers--;
        if (boardJokers > 0) {
            boardJokers--;
            return true;
        }// no board joker left so it came from the hand
        handUsed++;
        optionalRemaining--;
        return false;
    }

    /**
     * puts one copy of this tile back to the board or to the hand
     */
    private void restoreCell(int c, int v, boolean wasBoard) {
        total[c][v]++;
        if (wasBoard) {
            boardTiles[c][v]++;
        } else {
            handUsed--;
            optionalRemaining++;
        }
    }

    /**
     * turning a solution back into real sets and tiles
     */

    /**
     * called when we found a new best
     * it turns the chosen shapes in currentPath into real sets and tiles and saves bestBoard and bestPlayed
     */
    private void record() {
        bestHandUsed = handUsed;
        // cursors so repeated pops of a cell take different real tiles
        int[][] popCount = new int[4][14];
        int[] jokerCursor = new int[1];
        List<RummiSet> sets = new ArrayList<>();
        List<Tile> played = new ArrayList<>();

        for (PotentialSet cd : currentPath) {
            List<Tile> ts = new ArrayList<>();
            if (cd.isRun) {
                // include the joker slots below the anchor
                for (int w = cd.value - cd.bottomJokers; w <= cd.runEnd; w++) {
                    if (w >= cd.value && (cd.realMask >> w & 1) != 0) {
                        ts.add(popTile(cd.color, w, popCount, played));   // a real value here
                    } else {
                        ts.add(popJoker(jokerCursor, played));          // otherwise a joker
                    }
                }
            } else { // GROUP
                for (int c2 = 0; c2 < 4; c2++) {
                    if ((cd.colorMask >> c2 & 1) != 0) {
                        // one real tile per color in the GROUP
                        ts.add(popTile(c2, cd.value, popCount, played));
                    }
                } // add all the joker's this group use
                for (int k = 0; k < cd.jokers; k++) {
                    ts.add(popJoker(jokerCursor, played));
                }
            }
            // build the real set from these tiles
            sets.add(new RummiSet(ts));
        }
        // save the winning board and the played tiles
        bestBoard = sets;
        bestPlayed = played;
    }
    /**
     * pops the next real tile for this cell board tiles come before hand tiles
     */
    private Tile popTile(int c, int v, int[][] popCount, List<Tile> played) {
        int idx = popCount[c][v];
        popCount[c][v]++;
        Tile t = cellTiles[c][v].get(idx);
        if (idx >= boardCount[c][v]) {   // past the board tiles so this one is played from the hand
            played.add(t);
        }
        return t;
    }

    /**
     * pops the next joker board jokers come before hand jokers
     */
    private Tile popJoker(int[] jokerCursor, List<Tile> played) {
        int idx = jokerCursor[0];
        jokerCursor[0]++;
        Tile t = jokerTiles.get(idx);
        if (idx >= boardJokerCount) {    // past the board jokers so this one is played from the hand
            played.add(t);
        }
        return t;
    }

    /**
     * makes an independent copy of a list of sets
     */
    private static List<RummiSet> deepCopySets(List<RummiSet> sets) {
        List<RummiSet> out = new ArrayList<>();
        for (RummiSet s : sets) {
            out.add(new RummiSet(s));
        }
        return out;
    }
}
