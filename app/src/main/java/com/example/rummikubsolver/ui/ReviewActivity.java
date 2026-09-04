package com.example.rummikubsolver.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.example.rummikubsolver.R;
import com.example.rummikubsolver.RummiSet;
import com.example.rummikubsolver.Tile;
import com.example.rummikubsolver.vision.DetectedTile;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Shows what the CV pipeline saw, grouped into sets, and lets the user fix
 * anything that came out wrong.
 *
 * Set membership lives on DetectedTile.boardSetIndex, stamped once by
 * TurnSession.applyInitialBoardGrouping() when the screen first opens. From
 * then on this screen never touches BoardAssembler again - editing a tile's
 * number/color/joker only re-checks the validity of whatever group it's
 * already in, it doesn't re-run the geometry. (Moving a tile between groups
 * or creating a new one is a later step; for now boardSetIndex only ever
 * changes via that one initial pass.)
 *
 * The Solve button is only enabled when every board tile is in a valid
 * group and none are left unassigned.
 */
public class ReviewActivity extends AppCompatActivity {

    private LinearLayout boardContainer, warningsContainer, statusBanner;
    private GridLayout handContainer;
    private TextView textSummary, textStatus;
    private MaterialButton btnSolve;
    private FloatingActionButton fabAddTile;

    // tiles inside a group that failed isValid(), so we can outline them in red
    private final Set<String> invalidGroupTileIds = new HashSet<>();
    private boolean boardValid;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_review);

        boardContainer = findViewById(R.id.boardContainer);
        handContainer = findViewById(R.id.handContainer);
        warningsContainer = findViewById(R.id.warningsContainer);
        statusBanner = findViewById(R.id.statusBanner);
        textSummary = findViewById(R.id.textSummary);
        textStatus = findViewById(R.id.textStatus);
        btnSolve = findViewById(R.id.btnSolve);
        fabAddTile = findViewById(R.id.fabAddTile);

        // one-time geometric pass - after this, set membership lives on the tiles
        TurnSession.get().applyInitialBoardGrouping();

        btnSolve.setOnClickListener(v ->
                startActivity(new Intent(this, SolutionActivity.class)));
        fabAddTile.setOnClickListener(v -> addNewTile());

        refresh();
    }

    /** Re-checks validity of the current groups and redraws everything. No geometry re-run. */
    private void refresh() {
        Map<Integer, List<DetectedTile>> bySetIndex = new TreeMap<>();
        List<DetectedTile> unassigned = new ArrayList<>();
        for (DetectedTile d : TurnSession.get().getDetections()) {
            if (d.getSource() != DetectedTile.Source.BOARD) continue;
            Integer idx = d.getBoardSetIndex();
            if (idx == null) unassigned.add(d);
            else bySetIndex.computeIfAbsent(idx, k -> new ArrayList<>()).add(d);
        }

        invalidGroupTileIds.clear();
        List<ReviewWarning> warnings = new ArrayList<>();

        for (List<DetectedTile> group : bySetIndex.values()) {
            if (!isGroupValid(group)) {
                for (DetectedTile d : group) invalidGroupTileIds.add(d.getDetectionId());
                warnings.add(new ReviewWarning(true,
                        "יש קבוצה של " + group.size() + " אבנים שלא מרכיבה סט חוקי. "
                                + "בדקו שהמספרים והצבעים זוהו נכון."));
            }
        }
        if (!unassigned.isEmpty()) {
            warnings.add(new ReviewWarning(true,
                    "נמצאו " + unassigned.size() + " אבנים שלא משתייכות לאף סט. "
                            + "אולי הן רחוקות מדי בתמונה, או שהן שייכות לסט שכן."));
        }
        int lowConfidenceCount = countLowConfidence();
        if (lowConfidenceCount > 0) {
            warnings.add(new ReviewWarning(false,
                    lowConfidenceCount + " אבנים זוהו בביטחון נמוך. כדאי לוודא שהן נכונות."));
        }

        boardValid = invalidGroupTileIds.isEmpty() && unassigned.isEmpty();

        drawSummary();
        drawWarnings(warnings);
        drawBoard(bySetIndex, unassigned);
        drawHand();
        updateSolveButton();
    }

    private int countLowConfidence() {
        int count = 0;
        for (DetectedTile d : TurnSession.get().getDetections()) {
            if (d.needsManualReview()) count++;
        }
        return count;
    }

    /**
     * A group is valid if every tile in it converts cleanly (guards the
     * toTile() edge case where a tile ends up with no number, no color, and
     * isn't a joker - e.g. after toggling the joker switch on then off in
     * the editor without picking a value) and the resulting RummiSet is legal.
     */
    private boolean isGroupValid(List<DetectedTile> group) {
        List<Tile> tiles = new ArrayList<>(group.size());
        int fakeId = 0;
        for (DetectedTile d : group) {
            if (!convertible(d)) return false; // toTile() would throw - treat the group as invalid instead
            tiles.add(d.toTile(fakeId++));
        }
        return new RummiSet(tiles).isValid();
    }

    private boolean convertible(DetectedTile d) {
        return d.isJoker() || (d.getNumber() != null && d.getColor() != null);
    }

    private void drawSummary() {
        int boardCount = 0, handCount = 0;
        for (DetectedTile t : TurnSession.get().getDetections()) {
            if (t.getSource() == DetectedTile.Source.HAND) handCount++;
            else boardCount++;
        }
        textSummary.setText(getString(R.string.review_summary, boardCount, handCount));
    }

    private void drawWarnings(List<ReviewWarning> warnings) {
        warningsContainer.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(this);

        for (ReviewWarning w : warnings) {
            View row = inflater.inflate(R.layout.item_warning, warningsContainer, false);
            TextView title = row.findViewById(R.id.textWarningTitle);
            TextView message = row.findViewById(R.id.textWarningMessage);
            View stripe = row.findViewById(R.id.warningStripe);

            row.setBackgroundResource(w.blocking
                    ? R.drawable.bg_warning_blocking
                    : R.drawable.bg_warning_info);
            stripe.setBackgroundColor(ContextCompat.getColor(this,
                    w.blocking ? R.color.warn_blocking : R.color.warn_info));
            title.setText(w.blocking
                    ? R.string.warning_blocking_prefix
                    : R.string.warning_info_prefix);
            title.setTextColor(ContextCompat.getColor(this,
                    w.blocking ? R.color.warn_blocking : R.color.text_primary));
            message.setText(w.message);

            warningsContainer.addView(row);
        }
    }

    private void drawBoard(Map<Integer, List<DetectedTile>> bySetIndex, List<DetectedTile> unassigned) {
        boardContainer.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(this);

        int label = 1;
        for (List<DetectedTile> group : bySetIndex.values()) {
            View block = inflater.inflate(R.layout.item_set_block, boardContainer, false);
            TextView setLabel = block.findViewById(R.id.textSetLabel);
            GridLayout grid = block.findViewById(R.id.setTiles);

            setLabel.setText(getString(R.string.review_set_label, label++));
            grid.setColumnCount(Math.max(1, group.size()));

            List<DetectedTile> displayOrder = new ArrayList<>(sortedForDisplay(group));
            Collections.reverse(displayOrder);
            for (DetectedTile d : displayOrder) {
                TileView tv = new TileView(this);
                tv.bind(d);
                if (invalidGroupTileIds.contains(d.getDetectionId()) || d.needsManualReview()) {
                    tv.setState(TileView.State.WARNING);
                }
                tv.setOnClickListener(v -> openEditor(d));
                grid.addView(tv);
            }

            // whole-block red outline when the group itself is an invalid set -
            // same drawable drawUnassigned() already uses for its own block
            boolean groupInvalid = false;
            for (DetectedTile d : group) {
                if (invalidGroupTileIds.contains(d.getDetectionId())) {
                    groupInvalid = true;
                    break;
                }
            }
            if (groupInvalid) {
                block.setBackgroundResource(R.drawable.bg_set_container_invalid);
            }

            boardContainer.addView(block);
        }

        drawUnassigned(unassigned);
    }

    /** Tiles with no boardSetIndex - still need to be visible and editable. */
    private void drawUnassigned(List<DetectedTile> unassigned) {
        if (unassigned.isEmpty()) return;

        View block = LayoutInflater.from(this).inflate(R.layout.item_set_block, boardContainer, false);
        block.setBackgroundResource(R.drawable.bg_set_container_invalid);
        ((TextView) block.findViewById(R.id.textSetLabel)).setText(R.string.review_unassigned);
        GridLayout row = block.findViewById(R.id.setTiles);
        row.setColumnCount(6);

        List<DetectedTile> displayOrder = new ArrayList<>(sortedForDisplay(unassigned));
        Collections.reverse(displayOrder);
        for (DetectedTile d : displayOrder) {
            TileView tv = new TileView(this);
            tv.bind(d);
            tv.setState(TileView.State.WARNING);
            tv.setOnClickListener(v -> openEditor(d));
            row.addView(tv);
        }
        boardContainer.addView(block);
    }

    /**
     * Numbered tiles ascending, jokers/nulls left in place at the end - per
     * the review-screen ordering rule (don't try to position jokers by value).
     */
    private List<DetectedTile> sortedForDisplay(List<DetectedTile> group) {
        List<DetectedTile> numbered = new ArrayList<>();
        List<DetectedTile> rest = new ArrayList<>();
        for (DetectedTile d : group) {
            if (d.getNumber() != null) numbered.add(d);
            else rest.add(d);
        }
        numbered.sort(Comparator.comparingInt(DetectedTile::getNumber));

        List<DetectedTile> result = new ArrayList<>(numbered);
        result.addAll(rest);
        return result;
    }

    private void drawHand() {
        handContainer.removeAllViews();
        handContainer.setColumnCount(6);

        for (DetectedTile d : TurnSession.get().getDetections()) {
            if (d.getSource() != DetectedTile.Source.HAND) continue;
            TileView tv = new TileView(this);
            tv.bind(d);
            if (d.needsManualReview()) tv.setState(TileView.State.WARNING);
            tv.setOnClickListener(v -> openEditor(d));
            handContainer.addView(tv);
        }
    }

    private void openEditor(DetectedTile tile) {
        List<Integer> usedIndices = TurnSession.get().getUsedBoardSetIndices();
        TileEditorDialog.show(this, tile, usedIndices, new TileEditorDialog.Listener() {
            @Override
            public void onSaved() {
                refresh(); // re-check validity of whatever group this tile is already in
            }

            @Override
            public void onDeleted() {
                TurnSession.get().getDetections().remove(tile);
                refresh();
            }

            @Override
            public void onCancelled() {
                // existing tile - it was never mutated before Save, nothing to discard
            }
        });
    }

    /** Adds a brand-new default board tile and immediately opens the editor on it. */
    private void addNewTile() {
        DetectedTile newTile = TurnSession.get().addManualBoardTile();
        List<Integer> usedIndices = TurnSession.get().getUsedBoardSetIndices();
        TileEditorDialog.show(this, newTile, usedIndices, new TileEditorDialog.Listener() {
            @Override
            public void onSaved() {
                refresh();
            }

            @Override
            public void onDeleted() {
                discardNewTile(newTile);
            }

            @Override
            public void onCancelled() {
                // cancelling a brand-new tile discards it - don't leave a stray default behind
                discardNewTile(newTile);
            }
        });
    }

    private void discardNewTile(DetectedTile tile) {
        TurnSession.get().getDetections().remove(tile);
        refresh();
    }

    private void updateSolveButton() {
        btnSolve.setEnabled(boardValid);
        btnSolve.setText(boardValid ? R.string.review_solve : R.string.review_solve_blocked);

        statusBanner.setBackgroundResource(boardValid
                ? R.drawable.bg_set_container
                : R.drawable.bg_warning_blocking);
        textStatus.setText(boardValid ? R.string.review_all_good : R.string.review_needs_fix);
        textStatus.setTextColor(ContextCompat.getColor(this,
                boardValid ? R.color.warn_ok : R.color.warn_blocking));
    }

    /** One row in the warnings list. Blocking ones keep Solve disabled, info ones don't. */
    private static final class ReviewWarning {
        final boolean blocking;
        final String message;

        ReviewWarning(boolean blocking, String message) {
            this.blocking = blocking;
            this.message = message;
        }
    }
}
