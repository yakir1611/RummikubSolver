package com.example.rummikubsolver.vision;

import com.example.rummikubsolver.RummiSet;
import com.example.rummikubsolver.Tile;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Tests for BoardAssembler. Mostly about geometry: does it group the right
 * tiles into the right chains, and does it handle the imageAspect correction
 * properly (that one bit us hard, see testPortraitAspect_doesNotMergeRows).
 *
 * All boxes below use a "realistic-ish" tile ratio (width 0.1, height 0.14 in
 * normalized coords) just so the numbers aren't totally arbitrary. Tiles in
 * a row are placed edge-to-edge (center distance == width), which lands
 * comfortably under NEIGHBOR_DISTANCE_FACTOR (1.3) without being a corner case.
 */
public class BoardAssemblerTest {

    private static final float TILE_W = 0.1f;
    private static final float TILE_H = 0.14f;
    private static final float CONF_HIGH = 0.9f; // above LOW_CONFIDENCE_THRESHOLD so tiles don't get flagged

    private int idCounter;

    @Before
    public void resetIdCounter() {
        idCounter = 0;
    }

    // ---- builders ----

    private DetectedTile tile(Integer number, Tile.Color color, float x, float y, DetectedTile.Source source) {
        String id = "t" + (idCounter++);
        return new DetectedTile(id, number, color, false, CONF_HIGH, new BoundingBox(x, y, TILE_W, TILE_H), source);
    }

    private DetectedTile board(int number, Tile.Color color, float x, float y) {
        return tile(number, color, x, y, DetectedTile.Source.BOARD);
    }

    private DetectedTile hand(int number, Tile.Color color, float x, float y) {
        return tile(number, color, x, y, DetectedTile.Source.HAND);
    }

    // ---- tests ----

    @Test
    public void testHorizontalRun_formsOneValidSet() {
        // 3 blue tiles in a row, touching, consecutive values -> one clean run
        List<DetectedTile> detections = new ArrayList<>();
        detections.add(board(4, Tile.Color.BLUE, 0.1f, 0.3f));
        detections.add(board(5, Tile.Color.BLUE, 0.2f, 0.3f));
        detections.add(board(6, Tile.Color.BLUE, 0.3f, 0.3f));

        BoardAssembler.DetectionResult result = BoardAssembler.assemble(detections, 1.0);

        assertTrue(result.isBoardValid());
        assertEquals(1, result.board.getSets().size());
        assertTrue(result.board.getSets().get(0).isValid());
        assertTrue("shouldn't have any warnings for a clean read", result.warnings.isEmpty());
    }

    @Test
    public void testPortraitAspect_doesNotMergeRows() {
        // THE important test. Portrait photo (imageAspect < 1) means y needs to be
        // divided by imageAspect before comparing to x, otherwise vertical gaps look
        // squashed and two separate rows can get chained together into garbage.
        //
        // Here the row gap (dy=0.10 in normalized y) is small enough that WITHOUT the
        // /imageAspect correction it would read as within NEIGHBOR_DISTANCE_FACTOR and
        // wrongly merge the two rows. With the correction (dividing by 0.5625) the gap
        // gets stretched out past the threshold, so the rows stay separate. That's the
        // whole point of this test - it fails if someone "simplifies" the aspect math away.
        double imageAspect = 0.5625; // 9:16 portrait

        List<DetectedTile> detections = new ArrayList<>();
        // top row: blue 1,2,3
        detections.add(board(1, Tile.Color.BLUE, 0.1f, 0.1f));
        detections.add(board(2, Tile.Color.BLUE, 0.2f, 0.1f));
        detections.add(board(3, Tile.Color.BLUE, 0.3f, 0.1f));
        // bottom row: red 4,5,6, shifted down by 0.10 in y
        detections.add(board(4, Tile.Color.RED, 0.1f, 0.2f));
        detections.add(board(5, Tile.Color.RED, 0.2f, 0.2f));
        detections.add(board(6, Tile.Color.RED, 0.3f, 0.2f));

        BoardAssembler.DetectionResult result = BoardAssembler.assemble(detections, imageAspect);

        assertEquals(2, result.board.getSets().size());
        for (RummiSet set : result.board.getSets()) {
            assertTrue(set.isValid());
        }
    }

    @Test
    public void testDiagonalSet_isRecognizedAsChain() {
        // same run as test 1 but walking diagonally instead of straight across.
        // each step moves by (+0.06, +0.06) so the chain is perfectly straight
        // (angle deviation 0), just not horizontal. imageAspect=1.0 so x/y are
        // directly comparable, no correction needed to reason about this one.
        List<DetectedTile> detections = new ArrayList<>();
        detections.add(board(7, Tile.Color.BLUE, 0.10f, 0.10f));
        detections.add(board(8, Tile.Color.BLUE, 0.16f, 0.16f));
        detections.add(board(9, Tile.Color.BLUE, 0.22f, 0.22f));

        BoardAssembler.DetectionResult result = BoardAssembler.assemble(detections, 1.0);

        assertEquals(1, result.board.getSets().size());
        assertTrue(result.board.getSets().get(0).isValid());
    }

    @Test
    public void testTwoGluedSets_areSplit() {
        // one long row of 6 tiles: RED 1,2,3 directly touching BLUE 1,2,3.
        // geometrically it's a single chain (nothing separates them physically),
        // but as a set it's neither a group nor a run -> should get split back
        // into the two legal runs it's actually made of.
        List<DetectedTile> detections = new ArrayList<>();
        detections.add(board(1, Tile.Color.RED, 0.1f, 0.3f));
        detections.add(board(2, Tile.Color.RED, 0.2f, 0.3f));
        detections.add(board(3, Tile.Color.RED, 0.3f, 0.3f));
        detections.add(board(1, Tile.Color.BLUE, 0.4f, 0.3f));
        detections.add(board(2, Tile.Color.BLUE, 0.5f, 0.3f));
        detections.add(board(3, Tile.Color.BLUE, 0.6f, 0.3f));

        BoardAssembler.DetectionResult result = BoardAssembler.assemble(detections, 1.0);

        assertEquals(2, result.board.getSets().size());
        for (RummiSet set : result.board.getSets()) {
            assertTrue(set.isValid());
        }

        boolean sawSplitWarning = false;
        for (BoardAssembler.Warning w : result.warnings) {
            if (w.type == BoardAssembler.Warning.Type.SPLIT_APPLIED) sawSplitWarning = true;
        }
        assertTrue("expected a SPLIT_APPLIED warning", sawSplitWarning);
    }

    @Test
    public void testTooSmallChain_producesBlockingWarning() {
        // just 2 tiles on the board, touching each other -> one chain of size 2,
        // under MIN_SET_SIZE(3). Not a real set, should block the board.
        List<DetectedTile> detections = new ArrayList<>();
        detections.add(board(5, Tile.Color.BLUE, 0.1f, 0.1f));
        detections.add(board(6, Tile.Color.BLUE, 0.2f, 0.1f));

        BoardAssembler.DetectionResult result = BoardAssembler.assemble(detections, 1.0);

        boolean sawTooSmall = false;
        for (BoardAssembler.Warning w : result.warnings) {
            if (w.type == BoardAssembler.Warning.Type.TOO_SMALL) sawTooSmall = true;
        }
        assertTrue("expected a TOO_SMALL warning", sawTooSmall);
        assertFalse(result.isBoardValid());
    }

    @Test
    public void testHandTiles_goToHandNotBoard() {
        // hand tiles skip all the geometry stuff entirely, positions here are irrelevant
        List<DetectedTile> detections = new ArrayList<>();
        detections.add(hand(1, Tile.Color.RED, 0.1f, 0.1f));
        detections.add(hand(5, Tile.Color.BLUE, 0.5f, 0.5f));
        detections.add(hand(10, Tile.Color.YELLOW, 0.9f, 0.9f));

        BoardAssembler.DetectionResult result = BoardAssembler.assemble(detections, 1.0);

        assertEquals(3, result.hand.getSize());
        assertTrue(result.board.getSets().isEmpty());
    }
}
