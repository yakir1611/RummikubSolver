package com.example.rummikubsolver.vision;

/**
 * Location of a detected tile in the source image.
 * Coordinates are normalized (0.0 - 1.0). (x, y) is the top-left corner.
 * rotation is in degrees, clockwise, around the box center (0 = straight tile);
 * used by the CV pipeline to deskew tilted tiles before recognition.
 */
public class BoundingBox {
    public final float x;
    public final float y;
    public final float width;
    public final float height;
    public final float rotation;

    /** Straight (non-rotated) box. */
    public BoundingBox(float x, float y, float width, float height) {
        this(x, y, width, height, 0.0f);
    }

    /** Box with a rotation angle (for tilted tiles). */
    public BoundingBox(float x, float y, float width, float height, float rotation) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.rotation = rotation;
    }

    /** Returns a new box moved/resized by the given deltas. Rotation is preserved. */
    public BoundingBox adjustedBy(float dx, float dy, float dWidth, float dHeight) {
        return new BoundingBox(x + dx, y + dy, width + dWidth, height + dHeight, rotation);
    }

    /** Returns a new box with the same position/size but a different rotation. */
    public BoundingBox withRotation(float newRotation) {
        return new BoundingBox(x, y, width, height, newRotation);
    }

    @Override
    public String toString() {
        if (rotation == 0.0f) {
            return String.format("BoundingBox[x=%.3f, y=%.3f, w=%.3f, h=%.3f]", x, y, width, height);
        }
        return String.format("BoundingBox[x=%.3f, y=%.3f, w=%.3f, h=%.3f, rot=%.1f°]",
                x, y, width, height, rotation);
    }
}