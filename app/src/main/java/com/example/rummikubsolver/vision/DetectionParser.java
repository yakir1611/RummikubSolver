package com.example.rummikubsolver.vision;

import com.example.rummikubsolver.Tile;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns raw detections from the CV model into DetectedTile objects.
 * The model gives us, per detection, a class label like "B2", "R13" or "Joker",
 * plus a confidence and a bounding box. This class knows how to read
 * that label (letter = color, digits = number) and build a DetectedTile from it.
 *
 * It does NOT decide HAND vs BOARD - the caller passes that in, since it depends
 * on how the photo was taken (separate hand/board photos, user choice, etc).
 */
public class DetectionParser {

    /** One raw detection coming out of the model, before we turn it into a DetectedTile. */
    public static class RawDetection {
        public final String label;      // e.g. "B2", "R13", "Joker"
        public final float confidence;  // 0.0 - 1.0
        public final BoundingBox box;

        public RawDetection(String label, float confidence, BoundingBox box) {
            this.label = label;
            this.confidence = confidence;
            this.box = box;
        }
    }

    /**
     * Converts a list of raw detections into DetectedTiles.
     *
     * @param detections raw output from the model
     * @param source     whether these tiles came from the hand or the board
     */
    public List<DetectedTile> parse(List<RawDetection> detections, DetectedTile.Source source) {
        List<DetectedTile> result = new ArrayList<>();
        int counter = 0;
        for (RawDetection d : detections) {
            String id = source + "-" + (counter++);  // simple unique id, e.g. "HAND-0"
            result.add(parseOne(d, source, id));
        }
        return result;
    }

    /** Turns a single raw detection into a DetectedTile. */
    private DetectedTile parseOne(RawDetection d, DetectedTile.Source source, String id) {
        String label = d.label.trim();

        // Joker: no number, no color.
        if (label.equalsIgnoreCase("Joker")) {
            return new DetectedTile(id, null, null, true, d.confidence, d.box, source);
        }

        // Normal tile: first char is the color letter, the rest is the number.
        Tile.Color color = letterToColor(label.charAt(0));
        Integer number = parseNumber(label.substring(1));

        return new DetectedTile(id, number, color, false, d.confidence, d.box, source);
    }

    /** Maps the model's color letter to our Tile.Color. Returns null if unknown. */
    private Tile.Color letterToColor(char c) {
        switch (Character.toUpperCase(c)) {
            case 'B': return Tile.Color.BLUE;
            case 'R': return Tile.Color.RED;
            case 'K': return Tile.Color.BLACK;   // K = blacK
            case 'Y': return Tile.Color.YELLOW;
            default:  return null;               // unknown letter -> let manual review catch it
        }
    }

    /** Parses the numeric part; returns null if it's not a valid 1-13. */
    private Integer parseNumber(String s) {
        try {
            int n = Integer.parseInt(s.trim());
            if (n >= 1 && n <= 13) return n;
            return null;   // out of range -> treated as "not recognized", flagged for review
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
