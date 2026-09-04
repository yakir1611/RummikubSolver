package com.example.rummikubsolver.ui;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.RadioGroup;

import androidx.appcompat.app.AlertDialog;

import com.example.rummikubsolver.R;
import com.example.rummikubsolver.Tile;
import com.example.rummikubsolver.vision.DetectedTile;
import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Lets the user fix one tile: number, color, joker flag, board-set
 * assignment (board tiles only), or delete it entirely.
 *
 * The correct* setters also flip userVerified - so a tile the user touched
 * stops being flagged for review even if the model's confidence was low.
 * setBoardSetIndex is plain bookkeeping and doesn't touch userVerified (see
 * DetectedTile) - it's not a correction to the tile's identity.
 *
 * Number and joker share one picker (tap btnNumber -> AlertDialog.setItems,
 * 1-13 then "Joker" as the 14th item). This used to be a 13-button GridLayout
 * plus a separate joker switch; the grid's fixed 44dp boxes silently clipped
 * the digits to nothing (OutlinedButton's default style bakes in 24dp of
 * horizontal padding per side). A plain AlertDialog list sidesteps that whole
 * class of bug since Android's own list-item layout does the sizing, not us.
 *
 * Board-set assignment works the same way (tap btnSet -> AlertDialog.setItems)
 * and is hidden entirely for hand tiles, which have no board-set concept. The
 * list is built from usedBoardSetIndices and labeled by POSITION in that list
 * ("סט 1", "סט 2"...), not by the raw boardSetIndex value - ReviewActivity
 * labels its blocks by iteration position too, so this keeps the numbers in
 * sync once indices get sparse (after "+ סט חדש" or a set getting emptied
 * out). "לא משויך" and "+ סט חדש" are the last two items.
 */
final class TileEditorDialog {

    interface Listener {
        void onSaved();
        void onDeleted();
        /** Dialog closed without Save or Delete (Cancel button, back press, or tap outside). */
        void onCancelled();
    }

    private TileEditorDialog() {}

    /**
     * @param usedBoardSetIndices distinct board-set indices currently in use
     *                            (ascending) - see TurnSession.getUsedBoardSetIndices()
     */
    static void show(Context context, DetectedTile tile, List<Integer> usedBoardSetIndices,
                      Listener listener) {
        View view = LayoutInflater.from(context).inflate(R.layout.dialog_edit_tile, null);

        RadioGroup groupColor = view.findViewById(R.id.groupColor);
        MaterialButton btnNumber = view.findViewById(R.id.btnNumber);
        View setPickerSection = view.findViewById(R.id.setPickerSection);
        MaterialButton btnSet = view.findViewById(R.id.btnSet);
        MaterialButton btnDelete = view.findViewById(R.id.btnDeleteTile);

        // working copy - only written back to the tile if the user hits save
        final Tile.Color[] pickedColor = {tile.getColor()};
        final Integer[] pickedNumber = {tile.getNumber()};
        final boolean[] pickedJoker = {tile.isJoker()};
        final Integer[] pickedSetIndex = {tile.getBoardSetIndex()};
        // set true by Save/Delete before they dismiss, so the dismiss listener below
        // can tell "closed without saving" apart from "closed because we saved/deleted"
        final boolean[] handled = {false};

        // --- color ---
        if (pickedColor[0] != null) {
            groupColor.check(radioIdFor(pickedColor[0]));
        }
        groupColor.setOnCheckedChangeListener((g, checkedId) -> {
            pickedColor[0] = colorForRadio(checkedId);
            pickedJoker[0] = false;
            updateNumberLabel(btnNumber, pickedNumber[0], pickedJoker[0]);
        });

        // --- number / joker - one picker list for both ---
        updateNumberLabel(btnNumber, pickedNumber[0], pickedJoker[0]);
        btnNumber.setOnClickListener(v -> {
            String[] items = new String[14];
            for (int n = 1; n <= 13; n++) items[n - 1] = String.valueOf(n);
            items[13] = context.getString(R.string.editor_joker);

            new AlertDialog.Builder(context)
                    .setTitle(R.string.editor_number)
                    .setItems(items, (d, which) -> {
                        if (which == 13) {
                            pickedJoker[0] = true;
                            pickedNumber[0] = null;
                            pickedColor[0] = null;
                            groupColor.clearCheck();
                        } else {
                            pickedNumber[0] = which + 1;
                            pickedJoker[0] = false;
                        }
                        updateNumberLabel(btnNumber, pickedNumber[0], pickedJoker[0]);
                    })
                    .show();
        });

        // --- board set assignment - board tiles only, hand tiles hide the whole section ---
        if (tile.getSource() == DetectedTile.Source.BOARD) {
            updateSetLabel(context, btnSet, usedBoardSetIndices, pickedSetIndex[0]);
            btnSet.setOnClickListener(v -> {
                int unassignedPos = usedBoardSetIndices.size();
                int newSetPos = usedBoardSetIndices.size() + 1;

                List<String> items = new ArrayList<>();
                for (int p = 0; p < usedBoardSetIndices.size(); p++) {
                    items.add(context.getString(R.string.review_set_label, p + 1));
                }
                items.add(context.getString(R.string.editor_set_unassigned));
                items.add(context.getString(R.string.editor_set_new));

                new AlertDialog.Builder(context)
                        .setTitle(R.string.editor_set_label)
                        .setItems(items.toArray(new String[0]), (d, which) -> {
                            if (which == unassignedPos) {
                                pickedSetIndex[0] = null;
                            } else if (which == newSetPos) {
                                pickedSetIndex[0] = usedBoardSetIndices.isEmpty()
                                        ? 0 : Collections.max(usedBoardSetIndices) + 1;
                            } else {
                                pickedSetIndex[0] = usedBoardSetIndices.get(which);
                            }
                            updateSetLabel(context, btnSet, usedBoardSetIndices, pickedSetIndex[0]);
                        })
                        .show();
            });
        } else {
            setPickerSection.setVisibility(View.GONE);
        }

        AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle(R.string.editor_title)
                .setView(view)
                .setPositiveButton(R.string.editor_save, (d, w) -> {
                    handled[0] = true;
                    apply(tile, pickedJoker[0], pickedNumber[0], pickedColor[0], pickedSetIndex[0]);
                    listener.onSaved();
                })
                .setNegativeButton(R.string.editor_cancel, null)
                .create();

        // fires for every dismissal path (Cancel button, back press, tap outside, and
        // also after the programmatic dismiss() calls from Save/Delete below) - handled[0]
        // is what tells those apart from a real cancel
        dialog.setOnDismissListener(d -> {
            if (!handled[0]) listener.onCancelled();
        });

        btnDelete.setOnClickListener(v -> {
            handled[0] = true;
            dialog.dismiss();
            listener.onDeleted();
        });

        dialog.show();
    }

    /** Shows the picked number, "ג'וקר" for a joker, or the placeholder if nothing's picked yet. */
    private static void updateNumberLabel(MaterialButton btnNumber, Integer number, boolean joker) {
        if (joker) {
            btnNumber.setText(R.string.editor_joker);
        } else if (number != null) {
            btnNumber.setText(String.valueOf(number));
        } else {
            btnNumber.setText(R.string.editor_number);
        }
    }

    /**
     * Shows the current set assignment as "סט N" (N = position in
     * usedBoardSetIndices, same numbering ReviewActivity renders), or
     * "לא משויך" if unassigned. A just-picked "+ סט חדש" value won't be in
     * usedBoardSetIndices yet (that list is a snapshot from when the dialog
     * opened) - it's always the newest/highest index, so it always lands at
     * the last position, same as it will once saved and re-grouped.
     */
    private static void updateSetLabel(Context context, MaterialButton btnSet,
                                        List<Integer> usedBoardSetIndices, Integer setIndex) {
        if (setIndex == null) {
            btnSet.setText(R.string.editor_set_unassigned);
            return;
        }
        int position = usedBoardSetIndices.indexOf(setIndex);
        int displayPosition = position >= 0 ? position : usedBoardSetIndices.size();
        btnSet.setText(context.getString(R.string.review_set_label, displayPosition + 1));
    }

    /** Writes the picked values back through the correct* setters. */
    private static void apply(DetectedTile tile, boolean joker, Integer number, Tile.Color color,
                               Integer setIndex) {
        tile.setBoardSetIndex(setIndex);
        if (joker) {
            tile.correctJokerFlag(true); // also nulls number and color
            return;
        }
        // clear a stale joker flag first, otherwise number/color get ignored
        if (tile.isJoker()) tile.correctJokerFlag(false);
        if (number != null) tile.correctNumber(number);
        if (color != null) tile.correctColor(color);
        tile.confirmAsIs(); // user looked at it, stop flagging it
    }

    private static int radioIdFor(Tile.Color c) {
        switch (c) {
            case RED:    return R.id.radioRed;
            case BLUE:   return R.id.radioBlue;
            case BLACK:  return R.id.radioBlack;
            case YELLOW: return R.id.radioYellow;
            default:     return -1;
        }
    }

    private static Tile.Color colorForRadio(int id) {
        if (id == R.id.radioRed)    return Tile.Color.RED;
        if (id == R.id.radioBlue)   return Tile.Color.BLUE;
        if (id == R.id.radioBlack)  return Tile.Color.BLACK;
        if (id == R.id.radioYellow) return Tile.Color.YELLOW;
        return null;
    }
}
