package com.example.rummikubsolver;

import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.example.rummikubsolver.Tile.Color.BLACK;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RummiSetTest {

    private int nextId;

    @Before
    public void resetIds() {
        nextId = 1;
    }

    private Tile t(int value, Tile.Color color) {
        return new Tile(nextId++, value, color);
    }

    private Tile joker() {
        return new Tile(nextId++);
    }

    private RummiSet set(Tile... tiles) {
        return new RummiSet(Arrays.asList(tiles));
    }

    // pulls out just the values so tests can compare against a plain set of ints
    private Set<Integer> values(List<RummiSet.PossibleAddition> additions) {
        Set<Integer> out = new HashSet<>();
        for (RummiSet.PossibleAddition a : additions) {
            out.add(a.getValue());
        }
        return out;
    }

    private Set<Integer> setOf(Integer... values) {
        return new HashSet<>(Arrays.asList(values));
    }

    // a full run (1-13) already uses every value - an extra joker has nowhere to go
    @Test
    public void fullRunWithExtraJokerIsInvalid() {
        Tile[] tiles = new Tile[14];
        for (int v = 1; v <= 13; v++) {
            tiles[v - 1] = t(v, BLACK);
        }
        tiles[13] = joker();
        assertFalse(set(tiles).isValid());
    }

    // joker has room to extend the run below (4-5-6-7) or above (5-6-7-8)
    @Test
    public void shortRunWithJokerAtTheEdgeIsValid() {
        assertTrue(set(t(5, BLACK), t(6, BLACK), t(7, BLACK), joker()).isValid());
    }

    // joker fills in below 12 (11-12-13), not stuck with nowhere to go
    @Test
    public void runNearTheTopWithJokerIsValid() {
        assertTrue(set(t(12, BLACK), t(13, BLACK), joker()).isValid());
    }

    // plain run, no jokers involved - behaviour must stay exactly as before
    @Test
    public void plainRunWithoutJokersIsStillValid() {
        assertTrue(set(t(4, BLACK), t(5, BLACK), t(6, BLACK)).isValid());
    }

    // no jokers at all - only the two direct neighbours are possible
    @Test
    public void possibleAdditionsWithNoJokers() {
        RummiSet run = set(t(4, BLACK), t(5, BLACK), t(6, BLACK));
        assertEquals(setOf(3, 7), values(run.getPossibleRunAdditions()));
    }

    // both jokers are plugging the 5,6 hole, nothing left over to slide to an edge -
    // this was the actual bug: they used to get counted as free just because
    // Joker.getValue() == 0 sorts before everything
    @Test
    public void jokersFillingInternalGapLeaveNothingSpare() {
        RummiSet run = set(t(3, BLACK), t(4, BLACK), joker(), joker(), t(7, BLACK));
        assertEquals(setOf(2, 8), values(run.getPossibleRunAdditions()));
    }

    // one spare joker can sit below OR above - both splits count, so 4 values come out
    @Test
    public void oneSpareJokerGivesBothSplits() {
        RummiSet run = set(t(5, BLACK), t(6, BLACK), joker());
        assertEquals(setOf(3, 4, 7, 8), values(run.getPossibleRunAdditions()));
    }

    // two spare jokers, three ways to split them between the ends (0/1/2 below)
    @Test
    public void twoSpareJokersGiveEveryLegalSplit() {
        RummiSet run = set(t(7, BLACK), t(8, BLACK), joker(), joker());
        assertEquals(setOf(4, 5, 6, 9, 10, 11), values(run.getPossibleRunAdditions()));
    }

    // close enough to 13 that pushing both spares above is illegal - one split
    // is cut off, but the others still work
    @Test
    public void runNearTheTopBlocksOneSplit() {
        RummiSet run = set(t(10, BLACK), t(11, BLACK), t(12, BLACK), joker(), joker());
        assertEquals(setOf(7, 8, 13), values(run.getPossibleRunAdditions()));
    }

    // close enough to 1 that pushing both spares below is illegal - one split
    // is cut off, but the others still work
    @Test
    public void runNearTheBottomBlocksOneSplit() {
        RummiSet run = set(joker(), joker(), t(2, BLACK), t(3, BLACK), t(4, BLACK));
        assertEquals(setOf(1, 6, 7), values(run.getPossibleRunAdditions()));
    }
}
