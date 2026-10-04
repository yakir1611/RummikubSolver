package com.example.rummikubsolver.ui;

import android.content.Context;
import android.graphics.Color;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.GridLayout;

import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatTextView;
import androidx.core.content.ContextCompat;

import com.example.rummikubsolver.R;
import com.example.rummikubsolver.Tile;
import com.example.rummikubsolver.vision.DetectedTile;

/**
 * Draws a single Rummikub tile - ivory background, number in the tile's color.
 * Used everywhere: review screen, solution screen, history.
 *
 * States it can show:
 *  - normal
 *  - selected (user tapped it)
 *  - warning (belongs to a set that failed validation)
 *
 *  Every time a tile is needed  to be painted it uses this class
 *  (when the tile is definite we use bind(TILE tile), in solutionActivity and HistoryDetailActivity)
 *  and when the tile need to be checked like in ReviewActivity we use bind(DetectedTile dt).
 */
public class TileView extends AppCompatTextView {
    // Three possible visual states this view can render.
    public enum State { NORMAL, SELECTED, WARNING }

    private static final int TILE_W_DP = 40;
    private static final int TILE_H_DP = 54;

    public TileView(Context context) {
        this(context, null);
    }

    public TileView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }
    // One-time visual setup applied to every TileView instance.
    private void init() {
        setGravity(Gravity.CENTER);
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        setTypeface(getTypeface(), android.graphics.Typeface.BOLD);
        setBackgroundResource(R.drawable.bg_tile);
        int m = dp(3);
        // tiles sit right next to each other like on a real table
        setPadding(0, 0, 0, 0);
        GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
        lp.width = dp(TILE_W_DP);
        lp.height = dp(TILE_H_DP);
        lp.setMargins(m, m, m, m);
        // Apply the layout params to this view.
        setLayoutParams(lp);
    }

    /** Shows a confirmed tile from the game model. */
    public void bind(Tile tile) {
        // Jokers are drawn as a star instead of a number.
        if (tile.isJoker()) {
            setText("★");
            setTextColor(ContextCompat.getColor(getContext(), R.color.brand_accent));
        } else {
            // Regular tile: show its numeric value as text.
            setText(String.valueOf(tile.getValue()));
            // Color the number according to the tile's Rummikub color.
            setTextColor(colorFor(tile.getColor()));
        }
    }

    /**
     * Shows a tile straight from the CV pipeline. Unlike a Tile, this one may be
     * missing its number or color - we show "?" so the user knows to fix it.
     */
    public void bind(DetectedTile dt) {
        if (dt.isJoker()) {
            setText("★");
            setTextColor(ContextCompat.getColor(getContext(), R.color.brand_accent));
            return;
        }
        Integer n = dt.getNumber();
        setText(n == null ? "?" : String.valueOf(n));
        setTextColor(dt.getColor() == null
                ? ContextCompat.getColor(getContext(), R.color.text_secondary)
                : colorFor(dt.getColor()));
    }
    // Switches the tile's background drawable based on its current display state.
    public void setState(State state) {
        switch (state) {
            case SELECTED:
                setBackgroundResource(R.drawable.bg_tile_selected);
                break;
            case WARNING:
                setBackgroundResource(R.drawable.bg_tile_warning);
                break;
            default:
                setBackgroundResource(R.drawable.bg_tile);
        }
    }
    // Maps a Rummikub tile color to its actual Android color resource.
    private int colorFor(Tile.Color c) {
        if (c == null) return Color.DKGRAY;
        switch (c) {
            case RED:    return ContextCompat.getColor(getContext(), R.color.tile_red);
            case BLUE:   return ContextCompat.getColor(getContext(), R.color.tile_blue);
            case BLACK:  return ContextCompat.getColor(getContext(), R.color.tile_black);
            case YELLOW: return ContextCompat.getColor(getContext(), R.color.tile_yellow);
            default:     return Color.DKGRAY;
        }
    }
    // Converts a dp value to actual pixels based on this device's screen density.
    private int dp(int value) {
        float d = getResources().getDisplayMetrics().density;
        return Math.round(value * d);
    }
}
