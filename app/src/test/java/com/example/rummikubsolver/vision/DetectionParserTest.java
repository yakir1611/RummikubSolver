package com.example.rummikubsolver.vision;

import com.example.rummikubsolver.Tile;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Tests for DetectionParser. Just checking that raw "B5"/"R13"/"Joker" style
 * labels get turned into the right DetectedTile fields. Box/confidence values
 * don't matter for parsing so we just reuse the same dummy box everywhere.
 */
public class DetectionParserTest {

    private static final BoundingBox DUMMY_BOX = new BoundingBox(0.1f, 0.1f, 0.1f, 0.14f);
    private static final float DUMMY_CONF = 0.9f;

    private DetectionParser parser;

    @Before
    public void setUp() {
        parser = new DetectionParser();
    }

    // ---- helpers ----

    private DetectionParser.RawDetection raw(String label) {
        return new DetectionParser.RawDetection(label, DUMMY_CONF, DUMMY_BOX);
    }

    private List<DetectionParser.RawDetection> listOf(String... labels) {
        List<DetectionParser.RawDetection> result = new ArrayList<>();
        for (String label : labels) result.add(raw(label));
        return result;
    }

    // ---- tests ----

    @Test
    public void testParsesEachColorLetter() {
        List<DetectedTile> tiles = parser.parse(listOf("B5", "R5", "K5", "Y5"), DetectedTile.Source.BOARD);

        assertEquals(4, tiles.size());
        assertEquals(Tile.Color.BLUE, tiles.get(0).getColor());
        assertEquals(Tile.Color.RED, tiles.get(1).getColor());
        assertEquals(Tile.Color.BLACK, tiles.get(2).getColor());
        assertEquals(Tile.Color.YELLOW, tiles.get(3).getColor());
        for (DetectedTile t : tiles) {
            assertEquals(Integer.valueOf(5), t.getNumber());
        }
    }

    @Test
    public void testParsesTwoDigitNumber() {
        List<DetectedTile> tiles = parser.parse(listOf("R13"), DetectedTile.Source.BOARD);

        DetectedTile t = tiles.get(0);
        assertEquals(Integer.valueOf(13), t.getNumber());
        assertEquals(Tile.Color.RED, t.getColor());
        assertFalse(t.isJoker());
    }

    @Test
    public void testJoker_hasNoNumberOrColor() {
        List<DetectedTile> tiles = parser.parse(listOf("Joker"), DetectedTile.Source.BOARD);

        DetectedTile t = tiles.get(0);
        assertTrue(t.isJoker());
        assertNull(t.getNumber());
        assertNull(t.getColor());
    }

    @Test
    public void testJokerCaseInsensitive() {
        // parseOne uses equalsIgnoreCase, so lowercase should work exactly the same
        List<DetectedTile> tiles = parser.parse(listOf("joker"), DetectedTile.Source.BOARD);

        assertTrue(tiles.get(0).isJoker());
    }

    @Test
    public void testNumberOutOfRange_becomesNull() {
        // 14 and 0 are both out of the 1-13 range, so number should come back null,
        // but the color letter is still fine and should still parse
        List<DetectedTile> tiles = parser.parse(listOf("R14", "B0"), DetectedTile.Source.BOARD);

        assertNull(tiles.get(0).getNumber());
        assertEquals(Tile.Color.RED, tiles.get(0).getColor());

        assertNull(tiles.get(1).getNumber());
        assertEquals(Tile.Color.BLUE, tiles.get(1).getColor());
    }

    @Test
    public void testUnknownColorLetter_becomesNull() {
        // "X" isn't a color the parser knows, so color should be null (left for manual review)
        List<DetectedTile> tiles = parser.parse(listOf("X5"), DetectedTile.Source.BOARD);

        assertNull(tiles.get(0).getColor());
    }

    @Test
    public void testUniqueIdsAndSource() {
        List<DetectionParser.RawDetection> detections = listOf("B1", "B2", "B3");
        List<DetectedTile> tiles = parser.parse(detections, DetectedTile.Source.HAND);

        assertEquals(3, tiles.size());

        Set<String> ids = new HashSet<>();
        for (DetectedTile t : tiles) {
            assertEquals(DetectedTile.Source.HAND, t.getSource());
            ids.add(t.getDetectionId());
        }
        assertEquals("all 3 ids should be distinct", 3, ids.size());
    }

    @Test
    public void testEmptyList_returnsEmpty() {
        List<DetectedTile> tiles = parser.parse(new ArrayList<>(), DetectedTile.Source.BOARD);

        assertTrue(tiles.isEmpty());
    }
}
