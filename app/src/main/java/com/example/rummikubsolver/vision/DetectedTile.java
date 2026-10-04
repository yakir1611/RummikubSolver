package com.example.rummikubsolver.vision;

import com.example.rummikubsolver.Tile;

/**
 * a tile that the CV found in the photo but we havent confirmed yet
 *
 * unlike Tile which is always a correct tile used by the solver a DetectedTile
 * can be wrong or missing info the model might not be sure about the number
 * the color or if its a joker thats what the manual correction screen RUM-5 is for
 *
 * flow
 *   1 CV makes a List of DetectedTile from the photo
 *   2 UI shows them on the image and flags the unsure ones see needsManualReview
 *   3 user confirms or fixes each one number color joker or box
 *   4 when its verified we turn it into a clean Tile with toTile and from there
 *     the old Board Hand and solver code works like always it never knows DetectedTile existed
 */
public class DetectedTile {

    //Confidence below this value is automatically flagged for manual review
    public static final float LOW_CONFIDENCE_THRESHOLD = 0.75f;

    public enum Source {
        HAND,
        BOARD
    }
    private final String detectionId; // stable id
    private Integer number;           // 1-13, or null if joker or not recognized
    private Tile.Color color;         // null if joker or not recognized
    private boolean isJoker;
    private float confidence;         // 0.0 - 1.0
    private BoundingBox boundingBox;
    private Source source;
    // only for BOARD tiles which set this tile is in right now
    // its the index in the rebuilt Board and its null if not grouped yet
    private Integer boardSetIndex; // Integer able to be NULL
    private boolean userVerified;  // true once the user has confirmed or corrected this tile

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


    // just bookkeeping which rebuilt Board set this tile is in right now
    // unlike the correctXxx methods above this doesnt mean the user verified the tile
    // so it leaves userVerified alone on purpose
    public void setBoardSetIndex(Integer boardSetIndex) { this.boardSetIndex = boardSetIndex; }

    /**
     * Manual correction
     */
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
     * should the UI flag this tile for the user to check
     * either the model wasn't sure or some needed fields are just missing
     */
    public boolean needsManualReview() {
        if (userVerified) return false;
        if (confidence < LOW_CONFIDENCE_THRESHOLD) return true;
        if (!isJoker && (number == null || color == null)) return true;
        return false;
    }

    /**
     * turns this DetectedTile into a clean Tile that the game model can use
     * tileId is a unique id for the new tile,throws if the tile isn't complete enough to convert yet
     */
    public Tile toTile(int tileId) {
        if (isJoker) {
            return new Tile(tileId);    // Constructor for Joker needs only id
        }
        if (number == null || color == null) {
            throw new IllegalStateException(
                    "Cannot convert an incomplete DetectedTile (id=" + detectionId + ") to a Tile.");
        }
        return new Tile(tileId, number, color);
    }
}
