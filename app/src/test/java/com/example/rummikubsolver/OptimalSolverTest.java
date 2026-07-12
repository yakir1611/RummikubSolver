package com.example.rummikubsolver;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.example.rummikubsolver.Tile.Color.BLACK;
import static com.example.rummikubsolver.Tile.Color.BLUE;
import static com.example.rummikubsolver.Tile.Color.RED;
import static com.example.rummikubsolver.Tile.Color.YELLOW;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class OptimalSolverTest {

    private int nextId;

    @Before
    public void resetIds() {
        nextId = 1;
    }

    // ---- Builders ----

    private Tile t(int value, Tile.Color color) {
        return new Tile(nextId++, value, color);
    }

    private Tile joker() {
        return new Tile(nextId++);
    }

    private RummiSet set(Tile... tiles) {
        return new RummiSet(Arrays.asList(tiles));
    }

    private Board board(RummiSet... sets) {
        Board b = new Board();
        for (RummiSet s : sets) {
            b.addSet(s);
        }
        return b;
    }

    private Hand hand(Tile... tiles) {
        Hand h = new Hand();
        for (Tile tile : tiles) {
            h.addTile(tile);
        }
        return h;
    }

    // ---- Invariant helpers ----

    private static Set<Integer> ids(Iterable<Tile> tiles) {
        Set<Integer> out = new HashSet<>();
        for (Tile t : tiles) {
            out.add(t.getId());
        }
        return out;
    }

    private static List<Tile> allTiles(List<RummiSet> sets) {
        List<Tile> out = new ArrayList<>();
        for (RummiSet s : sets) {
            out.addAll(s.getTiles());
        }
        return out;
    }

    /** Structural invariants every feasible result must satisfy. */
    private void assertInvariants(Board boardBefore, Hand handBefore, OptimalSolver.Result r) {
        for (RummiSet s : r.newBoardSets) {
            assertTrue("Invalid set in result: " + s, s.isValid());
        }
        List<Tile> finalTiles = allTiles(r.newBoardSets);
        Set<Integer> finalIds = ids(finalTiles);
        assertEquals("A tile was used twice", finalTiles.size(), finalIds.size());

        Set<Integer> boardIdsBefore = ids(allTiles(boardBefore.getSets()));
        Set<Integer> handIdsBefore = ids(handBefore.getTiles());
        Set<Integer> playedIds = ids(r.playedHandTiles);

        assertTrue("A board tile went missing from the final board",
                finalIds.containsAll(boardIdsBefore));
        assertTrue("A played tile did not come from the hand",
                handIdsBefore.containsAll(playedIds));
        Set<Integer> expected = new HashSet<>(boardIdsBefore);
        expected.addAll(playedIds);
        assertEquals("Final board must be exactly board tiles + played tiles",
                expected, finalIds);
    }

    private OptimalSolver.Result solveAndCheck(Board b, Hand h) {
        OptimalSolver.Result r = new OptimalSolver().solve(b, h);
        assertTrue("Expected a feasible result", r.feasible);
        assertInvariants(b, h, r);
        return r;
    }

    /** Runs the greedy solver on copies and returns how many tiles it played. */
    private int greedyPlays(Board b, Hand h) {
        Board gb = new Board(b);
        Hand gh = new Hand(h);
        new GreedySolver().makeMove(gb, gh);
        return h.getSize() - gh.getSize();
    }

    // ---- Scenario 1: plays a full run from hand ----

    @Test
    public void playsRunFromHandOntoEmptyBoard() {
        Board b = board();
        Hand h = hand(t(3, RED), t(4, RED), t(5, RED), t(9, BLUE));

        OptimalSolver.Result r = solveAndCheck(b, h);

        assertEquals(3, r.playedHandTiles.size());
        assertTrue(r.searchCompleted);
        assertTrue(r.isProvablyOptimal());
    }

    // ---- Scenario 2: greedy fails, optimal rearranges the board ----

    @Test
    public void greedyFailsButOptimalRearranges() {
        // Board: group of four 5s + run R6-R7-R8. Hand: R4.
        // Optimal move: steal R5 from the group (still valid with 3 tiles) and
        // rebuild the run as R4-R5-R6-R7-R8. Greedy cannot see this.
        Board b = board(
                set(t(5, RED), t(5, BLUE), t(5, BLACK), t(5, YELLOW)),
                set(t(6, RED), t(7, RED), t(8, RED)));
        Hand h = hand(t(4, RED));

        assertEquals("Greedy should be unable to play R4", 0, greedyPlays(b, h));

        OptimalSolver.Result r = solveAndCheck(b, h);
        assertEquals(1, r.playedHandTiles.size());
        assertEquals(4, r.playedHandTiles.get(0).getValue());
        assertTrue(r.isProvablyOptimal());
    }

    // ---- Scenario 3: board joker is re-used, never stranded ----

    @Test
    public void boardJokerIsReusedNeverStranded() {
        // Board: R11 R12 Joker. Hand: R10, R13. The only way to play both hand tiles
        // is the 5-run Joker(9) R10 R11 R12 R13 — the board joker must stay in play.
        Tile jk = joker();
        Board b = board(set(t(11, RED), t(12, RED), jk));
        Hand h = hand(t(10, RED), t(13, RED));

        OptimalSolver.Result r = solveAndCheck(b, h);

        assertEquals(2, r.playedHandTiles.size());
        assertTrue("Board joker must remain on the board",
                ids(allTiles(r.newBoardSets)).contains(jk.getId()));
        assertTrue(r.isProvablyOptimal());
    }

    // ---- Scenario 4: full-board rearrangement, beats greedy ----

    @Test
    public void fullBoardRearrangementBeatsGreedy() {
        // Board: runs R10-R12 and B10-B12. Hand: Y10 Y11 Y12 K10 K11.
        // Greedy plays the yellow run (3 tiles). Optimal dismantles BOTH board runs
        // into groups [10 x4] [11 x4] [12 x3] and plays all 5 hand tiles.
        Board b = board(
                set(t(10, RED), t(11, RED), t(12, RED)),
                set(t(10, BLUE), t(11, BLUE), t(12, BLUE)));
        Hand h = hand(t(10, YELLOW), t(11, YELLOW), t(12, YELLOW),
                t(10, BLACK), t(11, BLACK));

        assertEquals("Greedy is expected to find only the yellow run",
                3, greedyPlays(b, h));

        OptimalSolver.Result r = solveAndCheck(b, h);
        assertEquals(5, r.playedHandTiles.size());
        assertTrue(r.isProvablyOptimal());
    }

    // ---- Scenario 5: no move possible ----

    @Test
    public void noMovePossibleReturnsZeroPlayed() {
        Board b = board(set(t(1, RED), t(2, RED), t(3, RED)));
        Hand h = hand(t(13, BLACK), t(5, YELLOW));

        OptimalSolver.Result r = solveAndCheck(b, h);

        assertEquals(0, r.playedHandTiles.size());
        assertTrue(r.isProvablyOptimal());
    }

    // ---- Scenario 6: duplicate tile in hand, only one copy playable ----

    @Test
    public void duplicateTileInHandOnlyOneCopyPlayable() {
        // Board: group R5 B5 K5. Hand: second R5 + Y5. Y5 completes the group of four,
        // but the duplicate R5 can never join (no group holds two reds) and must stay.
        Board b = board(set(t(5, RED), t(5, BLUE), t(5, BLACK)));
        Hand h = hand(t(5, RED), t(5, YELLOW));

        OptimalSolver.Result r = solveAndCheck(b, h);

        assertEquals(1, r.playedHandTiles.size());
        assertEquals(YELLOW, r.playedHandTiles.get(0).getColor());
        assertTrue(r.isProvablyOptimal());
    }

    // ---- Scenario 7: node cap reached — best-so-far returned and flagged ----

    @Test
    public void nodeCapReturnsBestFoundAndFlagsIncomplete() {
        Board b = board(
                set(t(5, RED), t(5, BLUE), t(5, BLACK), t(5, YELLOW)),
                set(t(6, RED), t(7, RED), t(8, RED)));
        Hand h = hand(t(4, RED));

        OptimalSolver.Result r = new OptimalSolver(1).solve(b, h);

        assertFalse("Search must report that it was capped", r.searchCompleted);
        assertFalse(r.isProvablyOptimal());
        assertTrue("Greedy seed must still provide a feasible fallback", r.feasible);
        assertInvariants(b, h, r);
        // With a 1-node budget the search cannot beat the greedy seed (0 tiles).
        assertEquals(0, r.playedHandTiles.size());
    }
}
