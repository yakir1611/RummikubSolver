package com.example.rummikubsolver.vision;

import com.example.rummikubsolver.Tile;

import java.util.ArrayList;
import java.util.List;

/**
 * turns raw detections from the CV model into DetectedTile objects
 * for each detection the model gives us a class label like R13 or Joker plus a confidence
 * and a bounding box this class knows how to read that label the letter is the color
 * and the digits are the number and build a DetectedTile from it
 * it does not decide HAND vs BOARD the caller passes that in
 * because it depends on how the photo was taken like separate hand and board photos
 */
public class DetectionParser {
    /**
     * One raw detection coming out of the model, before we turn it into a DetectedTile.
     */
    public static class RawDetection {
        public final String label;      // "B2", "Joker"
        public final float confidence;  // 0.0 - 1.0
        public final BoundingBox box;

        public RawDetection(String label, float confidence, BoundingBox box) {
            this.label = label;
            this.confidence = confidence;
            this.box = box;
        }
    }

    /**
     * turns a list of raw detections from the model into list of  DetectedTiles
     * source is whether these tiles came from the hand or the board
     */
    public List<DetectedTile> parse(List<RawDetection> detections, DetectedTile.Source source) {
        List<DetectedTile> result = new ArrayList<>();
        int counter = 0;
        for (RawDetection d : detections) {
            String id = source + "-" + (counter++);  // simple unique id like "HAND-0"
            result.add(parseOne(d, source, id));
        }
        return result;
    }

    /**
     * Turns a single raw detection into a DetectedTile.
     */
    private DetectedTile parseOne(RawDetection d, DetectedTile.Source source, String id) {
        String label = d.label.trim();

        // Joker have no number and no color.
        if (label.equalsIgnoreCase("Joker")) {
            return new DetectedTile(id, null, null, true, d.confidence, d.box, source);
        }

        // normal tile the first char is the color letter and the rest is the number
        Tile.Color color = letterToColor(label.charAt(0));
        Integer number = parseNumber(label.substring(1)); // Integer able to be NULL

        return new DetectedTile(id, number, color, false, d.confidence, d.box, source);
    }

    /**
     * maps the models color letter to our Tile.Color and returns null if its unknown
     */
    private Tile.Color letterToColor(char c) {
        switch (Character.toUpperCase(c)) {
            case 'B': return Tile.Color.BLUE;
            case 'R': return Tile.Color.RED;
            case 'K': return Tile.Color.BLACK;   // K = blacK
            case 'Y': return Tile.Color.YELLOW;
            default:  return null;
        }
    }

    /**
     * parses the number part and returns null if its not a valid 1 to 13
     */
    private Integer parseNumber(String s) {
        try {
            int n = Integer.parseInt(s.trim());
            if (n >= 1 && n <= 13) return n;
            return null;   // out of range so flagged for review
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
