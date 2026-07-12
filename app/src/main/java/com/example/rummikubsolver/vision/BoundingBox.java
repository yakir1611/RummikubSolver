package com.example.rummikubsolver.vision;

/**
 * Represents the location of a detected tile within the source image.
 * Coordinates are normalized (0.0 - 1.0) relative to image width/height,
 * so they remain valid regardless of the actual image resolution.
 * (x, y) is the top-left corner of the box.
 */
public class BoundingBox {
    public final float x;
    public final float y;
    public final float width;
    public final float height;

    public BoundingBox(float x, float y, float width, float height) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
    }

    /** Returns a new BoundingBox representing this one moved/resized by the given deltas.
     *  Used when the user drags the box to correct a detection's position. */
    public BoundingBox adjustedBy(float dx, float dy, float dWidth, float dHeight) {
        return new BoundingBox(x + dx, y + dy, width + dWidth, height + dHeight);
    }

    @Override
    public String toString() {
        return String.format("BoundingBox[x=%.3f, y=%.3f, w=%.3f, h=%.3f]", x, y, width, height);
    }
}
