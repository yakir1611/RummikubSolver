package com.example.rummikubsolver;

import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;

import static com.example.rummikubsolver.Tile.Color.BLACK;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class GreedySolverTest {

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

    // a tile that's higher than everything on the board run should get appended, not prepended
    @Test
    public void appendsTileThatExtendsTheTopOfARun() {
        Board b = board(set(t(5, BLACK), t(6, BLACK), t(7, BLACK)));
        Hand h = hand(t(8, BLACK));

        assertTrue(new GreedySolver().makeMove(b, h));

        RummiSet run = b.getSets().get(0);
        assertEquals(4, run.getSize());
        assertEquals(8, run.getTiles().get(run.getSize() - 1).getValue());
        assertTrue(h.isEmpty());
    }

    // a tile that's lower than everything on the board run should get prepended, not appended
    @Test
    public void prependsTileThatExtendsTheBottomOfARun() {
        Board b = board(set(t(5, BLACK), t(6, BLACK), t(7, BLACK)));
        Hand h = hand(t(4, BLACK));

        assertTrue(new GreedySolver().makeMove(b, h));

        RummiSet run = b.getSets().get(0);
        assertEquals(4, run.getSize());
        assertEquals(4, run.getTiles().get(0).getValue());
        assertTrue(h.isEmpty());
    }

    // the spare joker in [5,6,Joker] can go either way - a hand tile below the
    // real tiles must still land at the start, not just wherever addTile() defaults to
    @Test
    public void prependsTileBelowARunWithASpareJoker() {
        Board b = board(set(t(5, BLACK), t(6, BLACK), joker()));
        Hand h = hand(t(3, BLACK));

        assertTrue(new GreedySolver().makeMove(b, h));

        RummiSet run = b.getSets().get(0);
        assertEquals(4, run.getSize());
        assertEquals(3, run.getTiles().get(0).getValue());
        assertTrue(h.isEmpty());
    }

    // same board, but a hand tile above the real tiles must land at the end
    @Test
    public void appendsTileAboveARunWithASpareJoker() {
        Board b = board(set(t(5, BLACK), t(6, BLACK), joker()));
        Hand h = hand(t(8, BLACK));

        assertTrue(new GreedySolver().makeMove(b, h));

        RummiSet run = b.getSets().get(0);
        assertEquals(4, run.getSize());
        assertEquals(8, run.getTiles().get(run.getSize() - 1).getValue());
        assertTrue(h.isEmpty());
    }
}
