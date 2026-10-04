package com.example.rummikubsolver.ui;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.example.rummikubsolver.R;
import com.example.rummikubsolver.RummiSet;
import com.example.rummikubsolver.Tile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * BoardRenderer is a shared, stateless UI helper that draws a list of RummiSets (drawSets)
 * or a flat list of Tiles (drawHand) into a given LinearLayout, as visual tile grids.
 * It has no knowledge of "before/after" or where the data came from — it just renders whatever list it's handed.
 * It exists so that SolutionActivity (showing the solver's suggested move)
 * and HistoryDetailActivity (showing a past turn's saved state) can reuse
 * the exact same rendering logic instead of duplicating it in two activities.
 */
final class BoardRenderer {

    private BoardRenderer() {}

    // Draws every RummiSet in "sets" as its own labeled block inside "container".
    static void drawSets(Context context, LinearLayout container, List<RummiSet> sets) {
        container.removeAllViews();
        if (sets == null) return;
        // Inflater turns an XML layout file into real View objects at runtime.
        LayoutInflater inflater = LayoutInflater.from(context);
        for (int i = 0; i < sets.size(); i++) {
            RummiSet set = sets.get(i);
            // Build one "block" view from the item_set_block.xml layout, attached to container but not added yet.
            View block = inflater.inflate(R.layout.item_set_block, container, false);
            TextView label = block.findViewById(R.id.textSetLabel);
            GridLayout grid = block.findViewById(R.id.setTiles);

            label.setText(context.getString(R.string.review_set_label, i + 1));
            grid.setColumnCount(Math.max(1, set.getSize()));
            // Compute the display order (numeric sort + joker placement) as a fresh mutable copy.
            List<Tile> displayOrder = new ArrayList<>(sortedForDisplay(set.getTiles()));
            Collections.reverse(displayOrder);
            for (Tile t : displayOrder) {
                TileView tv = new TileView(context);
                // Bind the tile's data (value/color/joker) into the widget so it renders correctly.
                tv.bind(t);
                grid.addView(tv);
            }
            // Now that the block is fully built, actually attach it to the container.
            container.addView(block);
        }
    }

    /**
     * Renders a flat list of tiles (a hand, not a set) as one block - reuses
     * item_set_block.xml for the same tile grid/background look as drawSets(),
     * but hides the "Set N" label since a hand isn't a numbered set.
     */
    // Draws a plain list of tiles (the player's hand) using the same visual block as a set.
    static void drawHand(Context context, LinearLayout container, List<Tile> tiles) {
        container.removeAllViews();
        if (tiles == null || tiles.isEmpty()) return; // nothing to show - leave the container empty

        View block = LayoutInflater.from(context).inflate(R.layout.item_set_block, container, false);
        block.findViewById(R.id.textSetLabel).setVisibility(View.GONE);
        GridLayout grid = block.findViewById(R.id.setTiles);
        grid.setColumnCount(Math.max(1, Math.min(tiles.size(), 6)));

        for (Tile t : tiles) {
            TileView tv = new TileView(context);
            tv.bind(t);
            grid.addView(tv);
        }
        container.addView(block);
    }

    /**
     * Sorts non-joker tiles ascending by value, then places each joker: into
     * the first gap that still has room (a value difference > 1 between two
     * adjacent non-joker tiles not yet fully bridged by earlier jokers), or -
     * if there's no gap at all - at the start (if the lowest tile isn't 1) or
     * the end (if it is, since nothing sits below 1). Display-only heuristic
     * based purely on the tiles' own values.
     */
    // Computes a nice-looking display order for one set's tiles (numbers sorted, jokers slotted in).
    private static List<Tile> sortedForDisplay(List<Tile> tiles) {
        List<Tile> numbered = new ArrayList<>();
        List<Tile> jokers = new ArrayList<>();
        for (Tile t : tiles) {
            if (t.isJoker()) jokers.add(t); else numbered.add(t);
        }
        numbered.sort(Comparator.comparingInt(Tile::getValue));
        // Start the result as a copy of the sorted numbered tiles.
        List<Tile> result = new ArrayList<>(numbered);
        // Insert every joker one at a time into the best spot found so far.
        for (Tile joker : jokers) {
            insertJoker(result, joker);
        }
        return result;
    }

    /** Places one joker into result - see sortedForDisplay() for the rule. */
    // Finds the best index in "result" to insert this single joker, and inserts it there.
    private static void insertJoker(List<Tile> result, Tile joker) {
        int insertAt = -1;
        // Scan every adjacent pair of positions, looking for a numeric gap to fill.
        for (int i = 0; i < result.size() - 1; i++) {
            Tile a = result.get(i);
            if (a.isJoker()) continue;
            // j will walk forward from i+1 past any jokers already sitting between "a" and the next number.
            int j = i + 1;
            int jokersBetween = 0;
            // Advance j while it's still pointing at a joker.
            while (j < result.size() && result.get(j).isJoker()) {
                jokersBetween++;
                j++;
            }
            // If we ran off the end of the list, "a" was the last non-joker tile - no gap after it to check.
            if (j >= result.size()) break;
            // The next real (non-joker) tile after all those jokers.
            Tile b = result.get(j);
            // How many numeric slots exist strictly between a and b's values.
            int gapCapacity = b.getValue() - a.getValue() - 1;
            // If there's still room beyond the jokers already placed here, this is our gap.
            if (gapCapacity > jokersBetween) {
                insertAt = j; // right after any jokers already filling this gap
                break;
            }
        }

        if (insertAt >= 0) {
            result.add(insertAt, joker);
            return;
        }

        // no gap with room left - start/end fallback based on the lowest value present (fall back to putting the joker at the start or end).
        Tile lowest = firstNumbered(result);
        if (lowest != null && lowest.getValue() != 1) {
            result.add(0, joker);
        } else {
            // Either everything is jokers, or the lowest number is already 1 (nothing valid below it) - put it at the end.
            result.add(joker);
        }
    }
    // Returns the first non-joker tile in the list, or null if the list is all jokers.
    private static Tile firstNumbered(List<Tile> tiles) {
        for (Tile t : tiles) {
            if (!t.isJoker()) return t;
        }
        return null;
    }
}
