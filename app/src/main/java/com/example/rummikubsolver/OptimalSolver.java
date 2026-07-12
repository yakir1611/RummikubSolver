package com.example.rummikubsolver;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Exact solver for a single Rummikub turn.
 *
 * Finds the move that plays the maximum possible number of tiles from the hand, allowing
 * the existing board sets to be fully dismantled and rearranged, under the hard constraint
 * that every tile currently on the board ends up in some valid set (board tiles can never
 * return to the hand). The {@link GreedySolver} remains available as a fallback and is
 * also run first internally to seed the branch-and-bound lower bound.
 *
 * Algorithm: backtracking over a count-normalized pool (tiles of equal color/value are
 * interchangeable; the physical Tile objects are mapped back only when a solution is
 * recorded). Each step anchors on the lowest remaining tile and enumerates every valid
 * set in which that tile is the lowest real tile (runs walking upward from it, groups at
 * its value), plus a "leave it in hand" branch for optional copies. Board copies of a
 * tile are always consumed before hand copies, and a branch-and-bound cut discards any
 * state that cannot beat the best solution found so far.
 *
 * Call {@link #solve} from a background thread. A single instance is not thread-safe;
 * use one instance per concurrent search.
 */
public class OptimalSolver {

    /**
     * Node budget for the exhaustive search. If exceeded, {@link #solve} stops and returns
     * the best solution found so far, flagged via {@link Result#searchCompleted} = false.
     * Tune this to trade worst-case latency against the guarantee of optimality.
     */
    public static final long DEFAULT_NODE_CAP = 2_000_000L;

    /** Outcome of an optimal-move search. */
    public static class Result {
        /** The complete final board: every original board tile plus the played hand tiles. */
        public final List<RummiSet> newBoardSets;
        /** The exact hand Tile objects that were played (empty if no tile can be played). */
        public final List<Tile> playedHandTiles;
        /**
         * False only if the board tiles themselves cannot form any valid arrangement
         * (possible with mis-detected boards); newBoardSets then echoes the input board.
         */
        public final boolean feasible;
        /**
         * True: the search ran to completion, so the move is provably optimal.
         * False: the node cap was reached, so this is only the best move found.
         */
        public final boolean searchCompleted;
        /** Number of search nodes explored (diagnostics/tuning). */
        public final long nodesExplored;

        Result(List<RummiSet> newBoardSets, List<Tile> playedHandTiles,
               boolean feasible, boolean searchCompleted, long nodesExplored) {
            this.newBoardSets = newBoardSets;
            this.playedHandTiles = playedHandTiles;
            this.feasible = feasible;
            this.searchCompleted = searchCompleted;
            this.nodesExplored = nodesExplored;
        }

        /** Convenience for the UI: "Optimal move" vs "Best move found (search capped)". */
        public boolean isProvablyOptimal() {
            return feasible && searchCompleted;
        }
    }

    private final long nodeCap;

    public OptimalSolver() {
        this(DEFAULT_NODE_CAP);
    }

    public OptimalSolver(long nodeCap) {
        this.nodeCap = nodeCap;
    }

    // ---- Search state (reset per solve() call) ----

    private int[][] total;          // [color][value 1..13] copies still in the pool
    private int[][] mand;           // board copies still unconsumed (must reach 0)
    private int totalJokers;
    private int mandJokers;
    private int handUsed;           // hand tiles consumed into sets so far
    private int optionalRemaining;  // hand tiles neither consumed nor skipped yet
    private long nodes;
    private boolean capped;

    private int bestHandUsed;
    private List<RummiSet> bestSets;
    private List<Tile> bestPlayed;
    private Deque<Candidate> chosen;

    // Physical tiles per cell, board tiles first, for reconstructing a recorded solution.
    private List<Tile>[][] cellTiles;
    private List<Tile> jokerTiles;
    private int[][] boardCount;     // initial board copies per cell
    private int boardJokerCount;

    public Result solve(Board board, Hand hand) {
        initState(board, hand);
        seedWithGreedy(board, hand);
        search(-1, Long.MIN_VALUE);

        if (bestHandUsed < 0) {
            return new Result(deepCopySets(board.getSets()), new ArrayList<>(),
                    false, !capped, nodes);
        }
        return new Result(bestSets, bestPlayed, true, !capped, nodes);
    }

    @SuppressWarnings("unchecked")
    private void initState(Board board, Hand hand) {
        total = new int[4][14];
        mand = new int[4][14];
        boardCount = new int[4][14];
        cellTiles = new List[4][14];
        for (int c = 0; c < 4; c++) {
            for (int v = 1; v <= 13; v++) {
                cellTiles[c][v] = new ArrayList<>();
            }
        }
        jokerTiles = new ArrayList<>();
        totalJokers = 0;
        mandJokers = 0;
        boardJokerCount = 0;

        for (RummiSet s : board.getSets()) {
            for (Tile t : s.getTiles()) {
                addToPool(t, true);
            }
        }
        for (Tile t : hand.getTiles()) {
            addToPool(t, false);
        }

        handUsed = 0;
        optionalRemaining = hand.getSize();
        nodes = 0;
        capped = false;
        bestHandUsed = -1;
        bestSets = null;
        bestPlayed = null;
        chosen = new ArrayDeque<>();
    }

    private void addToPool(Tile t, boolean fromBoard) {
        if (t.isJoker()) {
            jokerTiles.add(t);
            totalJokers++;
            if (fromBoard) {
                mandJokers++;
                boardJokerCount++;
            }
            return;
        }
        int v = t.getValue();
        if (v < 1 || v > 13 || t.getColor() == null) {
            throw new IllegalArgumentException("Invalid tile: " + t);
        }
        int c = t.getColor().ordinal();
        cellTiles[c][v].add(t);
        total[c][v]++;
        if (fromBoard) {
            mand[c][v]++;
            boardCount[c][v]++;
        }
    }

    /**
     * Runs the existing greedy solver on copies and, if it produced a valid board, uses
     * its move as the initial branch-and-bound lower bound (and fallback best solution).
     */
    private void seedWithGreedy(Board board, Hand hand) {
        Board gb = new Board(board);
        Hand gh = new Hand(hand);
        new GreedySolver().makeMove(gb, gh);
        if (!gb.isValid()) {
            return;
        }
        List<Tile> played = new ArrayList<>(hand.getTiles());
        for (Tile t : gh.getTiles()) {
            played.remove(t);
        }
        bestHandUsed = played.size();
        bestSets = deepCopySets(gb.getSets());
        bestPlayed = played;
    }

    // ---- Core backtracking ----

    /**
     * @param prevCell the anchor cell of the set applied by the direct caller, or -1.
     * @param prevKey  that set's shape key; when this node re-anchors on the same cell
     *                 (duplicate copies), only candidates with key >= prevKey are tried,
     *                 so an unordered pair of sets is explored in exactly one order.
     */
    private void search(int prevCell, long prevKey) {
        if (capped) {
            return;
        }
        if (++nodes > nodeCap) {
            capped = true;
            return;
        }
        // Branch and bound: even playing every remaining hand tile cannot beat the best.
        if (handUsed + optionalRemaining <= bestHandUsed) {
            return;
        }

        int cell = findAnchor();
        if (cell < 0) {
            // All real tiles resolved; a board joker left over means this branch is illegal.
            if (mandJokers == 0 && handUsed > bestHandUsed) {
                record();
            }
            return;
        }

        int c = cell & 3;
        int v = cell >> 2;
        long minKey = (cell == prevCell) ? prevKey : Long.MIN_VALUE;

        List<Candidate> cands = generateCandidates(c, v);
        for (Candidate cd : cands) {
            if (cd.key < minKey) {
                continue;
            }
            int boardBits = apply(cd);
            chosen.addLast(cd);
            search(cell, cd.key);
            chosen.removeLast();
            undo(cd, boardBits);
            if (capped) {
                return;
            }
        }

        // Skip branch: leave the remaining copies of this tile in hand. Only legal once all
        // board copies are consumed, which also dedupes "cover then skip" vs "skip then cover".
        if (mand[c][v] == 0) {
            int k = total[c][v];
            total[c][v] = 0;
            optionalRemaining -= k;
            search(-1, Long.MIN_VALUE);
            total[c][v] = k;
            optionalRemaining += k;
        }
    }

    /** Lowest remaining real tile (value-major, then color order), or -1 if none. */
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

    // ---- Candidate enumeration ----

    /**
     * A set shape anchored at one cell. Runs are stored as a multiset: the real values
     * used (realMask), the top occupied value (runEnd) and jokers below the anchor
     * (bottomJokers); the concrete joker positions are resolved at reconstruction.
     */
    private static final class Candidate {
        final boolean isRun;
        final int color;        // run color (groups use colorMask instead)
        final int value;        // run anchor value / group value
        final int realMask;     // runs: bit w set = real tile of value w is used
        final int runEnd;
        final int bottomJokers;
        final int colorMask;    // groups: colors present, anchor included
        final int jokers;       // total jokers consumed
        final long key;         // canonical shape key (unique per shape at an anchor)
        final int handCost;     // hand tiles this set consumes right now (ordering only)

        Candidate(boolean isRun, int color, int value, int realMask, int runEnd,
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

    private List<Candidate> generateCandidates(int c, int v) {
        List<Candidate> out = new ArrayList<>();
        walkRun(c, v, v + 1, 1 << v, 0, 1, out);
        genGroups(c, v, out);
        // Try sets that play more hand tiles first, so good solutions (and thus tight
        // bounds) are found early; key as a deterministic tiebreaker.
        out.sort((a, b) -> a.handCost != b.handCost
                ? Integer.compare(b.handCost, a.handCost)
                : Long.compare(a.key, b.key));
        return out;
    }

    /**
     * Enumerates all runs anchored at (c, anchor): walk upward choosing a real tile or a
     * joker for each next value, emitting a candidate at every legal stop. Jokers below
     * the anchor are only considered once the walk is blocked at 13 — placing a spare
     * joker below or above yields the same multiset, so allowing both would duplicate.
     */
    private void walkRun(int c, int anchor, int w, int realMask, int jokersUsed, int len,
                         List<Candidate> out) {
        if (len >= 3) {
            out.add(runCandidate(c, anchor, realMask, w - 1, 0, jokersUsed));
        }
        if (w > 13) {
            for (int j = 1; jokersUsed + j <= totalJokers && anchor - j >= 1; j++) {
                if (len + j >= 3) {
                    out.add(runCandidate(c, anchor, realMask, 13, j, jokersUsed + j));
                }
            }
            return;
        }
        if (total[c][w] > 0) {
            walkRun(c, anchor, w + 1, realMask | (1 << w), jokersUsed, len + 1, out);
        }
        if (jokersUsed < totalJokers) {
            walkRun(c, anchor, w + 1, realMask, jokersUsed + 1, len + 1, out);
        }
    }

    private Candidate runCandidate(int c, int anchor, int realMask, int runEnd,
                                   int bottomJokers, int jokers) {
        int handCost = Math.max(0, jokers - mandJokers);
        for (int w = anchor; w <= runEnd; w++) {
            if ((realMask >> w & 1) != 0 && mand[c][w] == 0) {
                handCost++;
            }
        }
        long key = ((long) realMask << 6) | (runEnd << 2) | bottomJokers;
        return new Candidate(true, c, anchor, realMask, runEnd, bottomJokers, 0,
                jokers, key, handCost);
    }

    /** Enumerates all groups at value v that include the anchor color c. */
    private void genGroups(int c, int v, List<Candidate> out) {
        for (int mask = 0; mask < 16; mask++) {
            if ((mask & (1 << c)) == 0) {
                continue;
            }
            boolean available = true;
            for (int c2 = 0; c2 < 4 && available; c2++) {
                if (c2 != c && (mask & (1 << c2)) != 0 && total[c2][v] == 0) {
                    available = false;
                }
            }
            if (!available) {
                continue;
            }
            int size = Integer.bitCount(mask);
            for (int j = 0; j <= totalJokers && size + j <= 4; j++) {
                if (size + j < 3) {
                    continue;
                }
                int handCost = Math.max(0, j - mandJokers);
                for (int c2 = 0; c2 < 4; c2++) {
                    if ((mask & (1 << c2)) != 0 && mand[c2][v] == 0) {
                        handCost++;
                    }
                }
                long key = (1L << 40) | ((long) mask << 2) | j;
                out.add(new Candidate(false, c, v, 0, 0, 0, mask, j, key, handCost));
            }
        }
    }

    // ---- Applying / undoing a candidate ----

    /** Consumes the candidate's tiles (board copies first). Returns a bitmask recording,
     *  per consumption in iteration order, whether a board copy was taken (for undo). */
    private int apply(Candidate cd) {
        int bits = 0;
        int i = 0;
        if (cd.isRun) {
            for (int w = cd.value; w <= cd.runEnd; w++) {
                if ((cd.realMask >> w & 1) != 0) {
                    if (consumeCell(cd.color, w)) {
                        bits |= 1 << i;
                    }
                    i++;
                }
            }
        } else {
            for (int c2 = 0; c2 < 4; c2++) {
                if ((cd.colorMask >> c2 & 1) != 0) {
                    if (consumeCell(c2, cd.value)) {
                        bits |= 1 << i;
                    }
                    i++;
                }
            }
        }
        for (int k = 0; k < cd.jokers; k++) {
            if (consumeJoker()) {
                bits |= 1 << i;
            }
            i++;
        }
        return bits;
    }

    private void undo(Candidate cd, int bits) {
        int i = 0;
        if (cd.isRun) {
            for (int w = cd.value; w <= cd.runEnd; w++) {
                if ((cd.realMask >> w & 1) != 0) {
                    restoreCell(cd.color, w, (bits >> i++ & 1) != 0);
                }
            }
        } else {
            for (int c2 = 0; c2 < 4; c2++) {
                if ((cd.colorMask >> c2 & 1) != 0) {
                    restoreCell(c2, cd.value, (bits >> i++ & 1) != 0);
                }
            }
        }
        for (int k = 0; k < cd.jokers; k++) {
            totalJokers++;
            if ((bits >> i++ & 1) != 0) {
                mandJokers++;
            } else {
                handUsed--;
                optionalRemaining++;
            }
        }
    }

    /** @return true if a board copy was consumed, false if a hand copy. */
    private boolean consumeCell(int c, int v) {
        total[c][v]--;
        if (mand[c][v] > 0) {
            mand[c][v]--;
            return true;
        }
        handUsed++;
        optionalRemaining--;
        return false;
    }

    private boolean consumeJoker() {
        totalJokers--;
        if (mandJokers > 0) {
            mandJokers--;
            return true;
        }
        handUsed++;
        optionalRemaining--;
        return false;
    }

    private void restoreCell(int c, int v, boolean wasBoard) {
        total[c][v]++;
        if (wasBoard) {
            mand[c][v]++;
        } else {
            handUsed--;
            optionalRemaining++;
        }
    }

    // ---- Solution reconstruction ----

    /** Turns the current stack of chosen candidates into concrete sets and played tiles. */
    private void record() {
        bestHandUsed = handUsed;
        int[][] cursor = new int[4][14];
        int[] jokerCursor = new int[1];
        List<RummiSet> sets = new ArrayList<>();
        List<Tile> played = new ArrayList<>();

        for (Candidate cd : chosen) {
            List<Tile> ts = new ArrayList<>();
            if (cd.isRun) {
                for (int w = cd.value - cd.bottomJokers; w <= cd.runEnd; w++) {
                    if (w >= cd.value && (cd.realMask >> w & 1) != 0) {
                        ts.add(popTile(cd.color, w, cursor, played));
                    } else {
                        ts.add(popJoker(jokerCursor, played));
                    }
                }
            } else {
                for (int c2 = 0; c2 < 4; c2++) {
                    if ((cd.colorMask >> c2 & 1) != 0) {
                        ts.add(popTile(c2, cd.value, cursor, played));
                    }
                }
                for (int k = 0; k < cd.jokers; k++) {
                    ts.add(popJoker(jokerCursor, played));
                }
            }
            sets.add(new RummiSet(ts));
        }
        bestSets = sets;
        bestPlayed = played;
    }

    private Tile popTile(int c, int v, int[][] cursor, List<Tile> played) {
        int idx = cursor[c][v]++;
        Tile t = cellTiles[c][v].get(idx);
        if (idx >= boardCount[c][v]) {
            played.add(t);
        }
        return t;
    }

    private Tile popJoker(int[] jokerCursor, List<Tile> played) {
        int idx = jokerCursor[0]++;
        Tile t = jokerTiles.get(idx);
        if (idx >= boardJokerCount) {
            played.add(t);
        }
        return t;
    }

    private static List<RummiSet> deepCopySets(List<RummiSet> sets) {
        List<RummiSet> out = new ArrayList<>();
        for (RummiSet s : sets) {
            out.add(new RummiSet(s));
        }
        return out;
    }
}
