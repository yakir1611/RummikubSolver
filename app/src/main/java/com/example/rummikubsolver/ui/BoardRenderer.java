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
 * Renders a list of RummiSets into a container the same way everywhere it's
 * needed - originally lived only in SolutionActivity, factored out here once
 * HistoryDetailActivity needed the exact same rendering for a reconstructed
 * (from history) solution.
 */
final class BoardRenderer {

    private BoardRenderer() {}

    static void drawSets(Context context, LinearLayout container, List<RummiSet> sets) {
        container.removeAllViews();
        if (sets == null) return;

        LayoutInflater inflater = LayoutInflater.from(context);
        for (int i = 0; i < sets.size(); i++) {
            RummiSet set = sets.get(i);
            View block = inflater.inflate(R.layout.item_set_block, container, false);
            TextView label = block.findViewById(R.id.textSetLabel);
            GridLayout grid = block.findViewById(R.id.setTiles);

            label.setText(context.getString(R.string.review_set_label, i + 1));
            grid.setColumnCount(Math.max(1, set.getSize()));

            List<Tile> displayOrder = new ArrayList<>(sortedForDisplay(set.getTiles()));
            Collections.reverse(displayOrder);
            for (Tile t : displayOrder) {
                TileView tv = new TileView(context);
                tv.bind(t);
                grid.addView(tv);
            }
            container.addView(block);
        }
    }

    /**
     * Renders a flat list of tiles (a hand, not a set) as one block - reuses
     * item_set_block.xml for the same tile grid/background look as drawSets(),
     * but hides the "Set N" label since a hand isn't a numbered set.
     */
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
     * based purely on the tiles' own values; doesn't attempt to recover what
     * the solver actually assigned each joker internally (not recoverable in
     * general - e.g. {10, Joker, Joker} alone is ambiguous between 8-9-10,
     * 9-10-11 and 10-11-12). A group (no gaps at all) sends its joker(s) to
     * the start, same as any other no-gap case - no special group detection.
     */
    private static List<Tile> sortedForDisplay(List<Tile> tiles) {
        List<Tile> numbered = new ArrayList<>();
        List<Tile> jokers = new ArrayList<>();
        for (Tile t : tiles) {
            if (t.isJoker()) jokers.add(t); else numbered.add(t);
        }
        numbered.sort(Comparator.comparingInt(Tile::getValue));

        List<Tile> result = new ArrayList<>(numbered);
        for (Tile joker : jokers) {
            insertJoker(result, joker);
        }
        return result;
    }

    /** Places one joker into result - see sortedForDisplay() for the rule. */
    private static void insertJoker(List<Tile> result, Tile joker) {
        int insertAt = -1;
        for (int i = 0; i < result.size() - 1; i++) {
            Tile a = result.get(i);
            if (a.isJoker()) continue;

            int j = i + 1;
            int jokersBetween = 0;
            while (j < result.size() && result.get(j).isJoker()) {
                jokersBetween++;
                j++;
            }
            if (j >= result.size()) break; // a is the last non-joker tile, nothing after it

            Tile b = result.get(j);
            int gapCapacity = b.getValue() - a.getValue() - 1;
            if (gapCapacity > jokersBetween) {
                insertAt = j; // right after any jokers already filling this gap
                break;
            }
        }

        if (insertAt >= 0) {
            result.add(insertAt, joker);
            return;
        }

        // no gap with room left - start/end fallback based on the lowest value present
        Tile lowest = firstNumbered(result);
        if (lowest != null && lowest.getValue() != 1) {
            result.add(0, joker);
        } else {
            result.add(joker);
        }
    }

    private static Tile firstNumbered(List<Tile> tiles) {
        for (Tile t : tiles) {
            if (!t.isJoker()) return t;
        }
        return null;
    }
}
