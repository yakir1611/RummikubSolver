# Data Contract: Computer Vision → Game Logic

This document defines the boundary between the CV pipeline (tile detection)
and the existing game logic (`Tile`, `RummiSet`, `Board`, `Hand`, `Solver`).
Both teammates can build against this contract independently.

## Why this exists

The existing model classes (`Tile`, `RummiSet`, `Board`, `Hand`) assume every
tile is **fully known and correct** - a real number, a real color, no
uncertainty. That's the right assumption for the Solver, but it's the wrong
assumption for raw CV output, which is often incomplete or wrong and needs a
manual-correction step before it can be trusted (see RUM-5 on the board).

`DetectedTile` is the missing layer in between.

## The flow

```
Photo → CV pipeline → List<DetectedTile>
                              │
                    UI shows boxes over the image,
                    flags low-confidence tiles
                              │
                    User confirms / corrects each one
                              │
              DetectedTile.toTile()  →  Tile  (existing model)
                              │
              Board / Hand / Solver  (unchanged, works as today)
```

The CV side only ever needs to produce `DetectedTile` objects. The
Solver/game-logic side only ever needs to consume `Tile` objects, exactly as
it does today. `DetectedTile.toTile()` is the single point where one becomes
the other, and it only succeeds once a tile is complete (a real number +
color, or explicitly marked as a joker).

## Files

- `BoundingBox.java` - normalized (0.0-1.0) position of a tile in the source
  image. Normalized coordinates mean the box stays valid regardless of the
  photo's actual resolution.
- `DetectedTile.java` - a tile as seen by the CV pipeline, before
  confirmation. Wraps: id, number (nullable), color (nullable), joker flag,
  confidence score, bounding box, source (RACK or BOARD), and a
  `userVerified` flag.

## Suggested JSON shape (CV service ↔ app)

If the CV model runs as a separate service (local or cloud) rather than
in-process, this is a reasonable shape for the response:

```json
{
  "detections": [
    {
      "detectionId": "img3_tile07",
      "number": 7,
      "color": "BLUE",
      "isJoker": false,
      "confidence": 0.94,
      "boundingBox": { "x": 0.12, "y": 0.34, "width": 0.05, "height": 0.08 },
      "source": "RACK"
    },
    {
      "detectionId": "img3_tile08",
      "number": null,
      "color": null,
      "isJoker": false,
      "confidence": 0.41,
      "boundingBox": { "x": 0.20, "y": 0.34, "width": 0.05, "height": 0.08 },
      "source": "RACK"
    }
  ]
}
```

The second example is a low-confidence detection (`confidence < 0.75`, and
missing `number`/`color`) - `needsManualReview()` would return `true` for
it, so the correction UI should highlight it automatically.

## What this does NOT decide (open questions for later)

- How `boardSetIndex` gets assigned (i.e., which set on the table a detected
  tile belongs to) - that's part of RUM-27 (mapping detected tiles to the
  table structure), not this contract.
- The exact CV approach (OpenCV / ML Kit / custom model) - that's RUM-16.
  This contract is intentionally agnostic to it: whatever approach is used,
  its output just needs to be adapted into `DetectedTile` objects.
