package com.example.rummikubsolver.vision;

import com.example.rummikubsolver.Tile;

/**
 * Represents a single tile as detected by the Computer Vision pipeline,
 * BEFORE it is confirmed as accurate.
 *
 * Unlike {@link Tile} (which always represents a known, correct tile used by
 * the game logic / Solver), a DetectedTile may be incomplete or wrong:
 * the CV model might be unsure of the number, the color, or whether the
 * tile is a joker. That uncertainty is exactly what the manual-correction
 * screen (RUM-5 in our board) needs to work with.
 *
 * Typical flow:
 *   1. CV pipeline produces a List<DetectedTile> from a photo.
 *   2. UI shows them overlaid on the image; low-confidence ones are flagged
 *      (see needsManualReview()).
 *   3. User confirms or corrects each one (number/color/joker/box position).
 *   4. Once verified, each DetectedTile is converted to a clean Tile via
 *      toTile(), and from that point on the existing Board/Hand/Solver code
 *      works exactly as it already does today - it never needs to know
 *      DetectedTile exists.
 */
public class DetectedTile {

    /** Confidence below this value is automatically flagged for manual review. */
    public static final float LOW_CONFIDENCE_THRESHOLD = 0.75f;

    public enum Source {
        HAND,   // detected on the player's personal hand
        BOARD   // detected among the sets on the table
    }
    private final String detectionId; // stable id, survives corrections (e.g. UUID or "img3_tile07")
    private Integer number;           // 1-13, or null if joker / not recognized
    private Tile.Color color;         // null if joker / not recognized
    private boolean isJoker;
    private float confidence;         // 0.0 - 1.0, overall detection confidence
    private BoundingBox boundingBox;
    private Source source;
    private Integer boardSetIndex;    // if source == BOARD: which set this tile currently belongs to
                                       // (index into the reconstructed Board); null if not yet grouped
    private boolean userVerified;     // true once the user has confirmed or corrected this tile

    public DetectedTile(String detectionId, Integer number, Tile.Color color, boolean isJoker,
                         float confidence, BoundingBox boundingBox, Source source) {
        this.detectionId = detectionId;
        this.number = number;
        this.color = color;
        this.isJoker = isJoker;
        this.confidence = confidence;
        this.boundingBox = boundingBox;
        this.source = source;
        this.userVerified = false;
    }


    public String getDetectionId() { return detectionId; }
    public Integer getNumber() { return number; }
    public Tile.Color getColor() { return color; }
    public boolean isJoker() { return isJoker; }
    public float getConfidence() { return confidence; }
    public BoundingBox getBoundingBox() { return boundingBox; }
    public Source getSource() { return source; }
    public Integer getBoardSetIndex() { return boardSetIndex; }
    public boolean isUserVerified() { return userVerified; }

    // Manual correction
    public void correctNumber(int number) {
        this.number = number;
        this.userVerified = true;
    }
    public void correctColor(Tile.Color color) {
        this.color = color;
        this.userVerified = true;
    }
    public void correctJokerFlag(boolean isJoker) {
        this.isJoker = isJoker;
        if (isJoker) {
            this.number = null;
            this.color = null;
        }
        this.userVerified = true;
    }
    public void correctBoundingBox(BoundingBox newBox) {
        this.boundingBox = newBox;
        this.userVerified = true;
    }
    public void confirmAsIs() {
        this.userVerified = true;
    }

    /**
     * Whether the UI should flag this tile for the user to look at,
     * either because the model wasn't confident, or because required
     * fields are simply missing.
     */
    public boolean needsManualReview() {
        if (userVerified) return false;
        if (confidence < LOW_CONFIDENCE_THRESHOLD) return true;
        if (!isJoker && (number == null || color == null)) return true;
        return false;
    }

    /**
     * Converts this DetectedTile into a clean, logical Tile for use by the
     * existing game model (Board / Hand / Solver / RummiSet).
     *
     * @param tileId a unique id to assign to the resulting Tile (see Tile's constructors)
     * @throws IllegalStateException if the tile is not yet complete/verified enough to convert
     */
    public Tile toTile(int tileId) {
        if (isJoker) {
            return new Tile(tileId);    // Constructor for Joker needs only id
        }
        if (number == null || color == null) {
            throw new IllegalStateException(
                    "Cannot convert an incomplete DetectedTile (id=" + detectionId + ") to a Tile. "
                    + "Number and color must be set (or isJoker must be true) first.");
        }
        return new Tile(tileId, number, color);
    }

    @Override
    public String toString() {
        String value = isJoker ? "Joker" : (number + " " + color);
        return String.format("DetectedTile[%s, conf=%.2f, %s, verified=%b]",
                value, confidence, source, userVerified);
    }
}
