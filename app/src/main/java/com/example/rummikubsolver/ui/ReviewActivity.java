package com.example.rummikubsolver.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
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
    // Set of detection ids currently flagged as part of an invalid group.
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

        // Run the geometric clustering pass exactly once, stamping each board tile's set index.
        TurnSession.get().applyInitialBoardGrouping();
        // Tapping Solve navigates to the solution screen.
        btnSolve.setOnClickListener(v ->
                startActivity(new Intent(this, SolutionActivity.class)));
        // Tapping the FAB adds and opens the editor for a brand-new tile.
        fabAddTile.setOnClickListener(v -> addNewTile());

        refresh();
    }

    /** Re-checks validity of the current groups and redraws everything. No geometry re-run. */
    private void refresh() {
        // Groups of board tiles, keyed by their set index, kept in ascending order.
        Map<Integer, List<DetectedTile>> bySetIndex = new TreeMap<>();
        // Board tiles that have no set index at all.
        List<DetectedTile> unassigned = new ArrayList<>();
        // Walk through every detection in the session.
        for (DetectedTile d : TurnSession.get().getDetections()) {
            // Skip anything that isn't a board tile (hand tiles are handled separately).
            if (d.getSource() != DetectedTile.Source.BOARD) continue;
            // Read this tile's current set index (maybe null).
            Integer idx = d.getBoardSetIndex();
            // No index - it's unassigned.
            if (idx == null) unassigned.add(d);
            // Otherwise, add it to its group's list (creating the group list on first use).
            else bySetIndex.computeIfAbsent(idx, k -> new ArrayList<>()).add(d);
        }

        invalidGroupTileIds.clear();
        List<ReviewWarning> warnings = new ArrayList<>();
        // Check every group's validity.
        for (List<DetectedTile> group : bySetIndex.values()) {
            // Group failed validity - flag all its tiles and add a blocking warning.
            if (!isGroupValid(group)) {
                // Mark every tile in this invalid group so they get outlined.
                for (DetectedTile d : group) invalidGroupTileIds.add(d.getDetectionId());
                // Add a blocking warning describing the problem.
                warnings.add(new ReviewWarning(true,
                        "יש קבוצה של " + group.size() + " אבנים שלא מרכיבה סט חוקי. "
                                + "בדקו שהמספרים והצבעים זוהו נכון."));
            }
        }
        // Any tiles with no group at all also block solving.
        if (!unassigned.isEmpty()) {
            warnings.add(new ReviewWarning(true,
                    "נמצאו " + unassigned.size() + " אבנים שלא משתייכות לאף סט. "
                            + "אולי הן רחוקות מדי בתמונה, או שהן שייכות לסט שכן."));
        }
        // Count tiles the model flagged as low-confidence, across board and hand.
        int lowConfidenceCount = countLowConfidence();
        // If there are any, add a non-blocking informational warning.
        if (lowConfidenceCount > 0) {
            warnings.add(new ReviewWarning(false,
                    lowConfidenceCount + " אבנים זוהו בביטחון נמוך. כדאי לוודא שהן נכונות."));
        }
        // The board is solvable only if there are no invalid groups and no unassigned tiles.
        boardValid = invalidGroupTileIds.isEmpty() && unassigned.isEmpty();
        // Redraw every part of the screen based on the fresh state.
        drawSummary();
        drawWarnings(warnings);
        drawBoard(bySetIndex, unassigned);
        drawHand();
        updateSolveButton();
    }
    // Counts how many detections (board or hand) are flagged as needing manual review.
    private int countLowConfidence() {
        int count = 0;
        for (DetectedTile d : TurnSession.get().getDetections()) {
            if (d.needsManualReview()) count++;
        }
        return count;
    }

    // Checks whether one group of detected tiles forms a legal RummiSet.
    private boolean isGroupValid(List<DetectedTile> group) {
        List<Tile> tiles = new ArrayList<>(group.size());
        int fakeId = 0;
        for (DetectedTile d : group) {
            if (!convertible(d)) return false;
            tiles.add(d.toTile(fakeId++));
        }
        // Build a RummiSet from the converted tiles and check its own validity rules.
        return new RummiSet(tiles).isValid();
    }
    // Checks whether a detection has enough information to safely convert into a Tile.
    private boolean convertible(DetectedTile d) {
        // Either it's a joker, or it has both a number and a color.
        return d.isJoker() || (d.getNumber() != null && d.getColor() != null);
    }
    // Updates the top summary text with current board/hand tile counts.
    private void drawSummary() {
        int boardCount = 0, handCount = 0;
        for (DetectedTile t : TurnSession.get().getDetections()) {
            if (t.getSource() == DetectedTile.Source.HAND) handCount++;
            else boardCount++;
        }
        textSummary.setText(getString(R.string.review_summary, boardCount, handCount));
    }
    // Rebuilds the warnings list UI from the given warnings.
    private void drawWarnings(List<ReviewWarning> warnings) {
        warningsContainer.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(this);
        // Build one row per warning.
        for (ReviewWarning w : warnings) {
            View row = inflater.inflate(R.layout.item_warning, warningsContainer, false);
            TextView title = row.findViewById(R.id.textWarningTitle);
            TextView message = row.findViewById(R.id.textWarningMessage);
            View stripe = row.findViewById(R.id.warningStripe);
            // Pick the row's background drawable based on whether it blocks solving.
            row.setBackgroundResource(w.blocking
                    ? R.drawable.bg_warning_blocking
                    : R.drawable.bg_warning_info);
            // Color the side stripe to match the warning's severity.
            stripe.setBackgroundColor(ContextCompat.getColor(this,
                    w.blocking ? R.color.warn_blocking : R.color.warn_info));
            // Set the title text to the matching "blocking"/"info" prefix string.
            title.setText(w.blocking
                    ? R.string.warning_blocking_prefix
                    : R.string.warning_info_prefix);
            // Color the title text to match the severity too.
            title.setTextColor(ContextCompat.getColor(this,
                    w.blocking ? R.color.warn_blocking : R.color.text_primary));
            message.setText(w.message);
            // Add the finished row to the warnings container.
            warningsContainer.addView(row);
        }
    }
    // Rebuilds the board section UI: one block per valid/invalid group, plus the unassigned block.
    private void drawBoard(Map<Integer, List<DetectedTile>> bySetIndex, List<DetectedTile> unassigned) {
        boardContainer.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(this);

        int label = 1;
        // Build one block per group, in ascending set-index order.
        for (List<DetectedTile> group : bySetIndex.values()) {
            View block = inflater.inflate(R.layout.item_set_block, boardContainer, false);
            TextView setLabel = block.findViewById(R.id.textSetLabel);
            GridLayout grid = block.findViewById(R.id.setTiles);

            setLabel.setText(getString(R.string.review_set_label, label++));
            grid.setColumnCount(Math.max(1, group.size()));
            // Compute this group's display order (numeric sort, jokers/nulls last).
            List<DetectedTile> displayOrder = new ArrayList<>(sortedForDisplay(group));
            Collections.reverse(displayOrder);
            // Add one TileView per tile in that order.
            for (DetectedTile d : displayOrder) {
                // Create a new tile widget.
                TileView tv = new TileView(this);
                // Bind this detection's data into it.
                tv.bind(d);
                // Flag it visually if it's part of an invalid group, or needs manual review.
                if (invalidGroupTileIds.contains(d.getDetectionId()) || d.needsManualReview()) {
                    tv.setState(TileView.State.WARNING);
                }
                // Tapping a tile opens its editor.
                tv.setOnClickListener(v -> openEditor(d));
                grid.addView(tv);
            }

            // whole-block red outline when the group itself is an invalid set -
            // same drawable drawUnassigned() already uses for its own block.
            // Check whether any tile in this group was flagged invalid.
            boolean groupInvalid = false;
            for (DetectedTile d : group) {
                if (invalidGroupTileIds.contains(d.getDetectionId())) {
                    groupInvalid = true;
                    break;
                }
            }
            // If so, outline the whole block in red.
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
        // Always outline this block in red - unassigned tiles are always a problem.
        block.setBackgroundResource(R.drawable.bg_set_container_invalid);
        ((TextView) block.findViewById(R.id.textSetLabel)).setText(R.string.review_unassigned);
        GridLayout row = block.findViewById(R.id.setTiles);
        row.setColumnCount(6);
        // Compute the display order for the unassigned tiles too.
        List<DetectedTile> displayOrder = new ArrayList<>(sortedForDisplay(unassigned));
        Collections.reverse(displayOrder);
        // Add one TileView per unassigned tile.
        for (DetectedTile d : displayOrder) {
            TileView tv = new TileView(this);
            tv.bind(d);
            tv.setState(TileView.State.WARNING);
            // Tapping a tile opens its editor.
            tv.setOnClickListener(v -> openEditor(d));
            row.addView(tv);
        }
        // Add the finished block to the board container.
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
        // Sort the numbered tiles ascending by their value.
        numbered.sort(Comparator.comparingInt(DetectedTile::getNumber));
        // Build the final order: sorted numbers first, then everything else as-is.
        List<DetectedTile> result = new ArrayList<>(numbered);
        result.addAll(rest);
        return result;
    }
    // Rebuilds the hand section UI from the current detections.
    private void drawHand() {
        handContainer.removeAllViews();
        handContainer.setColumnCount(6);
        // Walk through every detection in the session.
        for (DetectedTile d : TurnSession.get().getDetections()) {
            // Skip anything that isn't a hand tile.
            if (d.getSource() != DetectedTile.Source.HAND) continue;
            TileView tv = new TileView(this);
            tv.bind(d);
            if (d.needsManualReview()) tv.setState(TileView.State.WARNING);
            tv.setOnClickListener(v -> openEditor(d));
            handContainer.addView(tv);
        }
    }
    // Opens the tile editor dialog for an existing detected tile.
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

    /** Asks where the new tile goes (board / hand), then creates it and opens the editor. */
    private void addNewTile() {
        // Let the user choose the destination - the FAB used to always add to the board.
        String[] options = {
                getString(R.string.review_add_to_board),
                getString(R.string.review_add_to_hand)
        };
        new AlertDialog.Builder(this)
                .setTitle(R.string.review_add_where)
                .setItems(options, (d, which) -> {
                    DetectedTile newTile = (which == 0)
                            ? TurnSession.get().addManualBoardTile()
                            : TurnSession.get().addManualHandTile();
                    openEditorForNewTile(newTile);
                })
                .show();
    }

    /** Opens the editor on a just-created tile; cancel/delete discards it so no stray default is left. */
    private void openEditorForNewTile(DetectedTile newTile) {
        // Get the set indices already in use, so the editor can offer valid choices.
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
    // Removes a just-created (and not confirmed) tile and refreshes the screen.
    private void discardNewTile(DetectedTile tile) {
        TurnSession.get().getDetections().remove(tile);
        refresh();
    }
    // Updates the Solve button and status banner based on whether the board is currently valid.
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
