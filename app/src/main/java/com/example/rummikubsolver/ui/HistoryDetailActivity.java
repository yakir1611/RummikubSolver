package com.example.rummikubsolver.ui;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.util.Base64;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.example.rummikubsolver.R;
import com.example.rummikubsolver.RummiSet;
import com.example.rummikubsolver.Tile;
import com.example.rummikubsolver.vision.TileCodeFormat;

import java.util.ArrayList;
import java.util.List;

/**
 * Shows one saved history entry in full: the board photo taken that turn (if exist) and the same four tile-rendered
 * sections SolutionActivity showed when it was saved - board/hand,
 * before/after - reconstructed from the tile codes the server stored via
 * BoardRenderer, the exact same helper SolutionActivity itself uses. Not
 * re-solved, just redrawn - this screen is display-only.
 */
public class HistoryDetailActivity extends AppCompatActivity {

    public static final String EXTRA_GAME_NUMBER = "game_number";
    public static final String EXTRA_DATE = "date";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_history_detail);
        // Bind every view field to its id in the layout.
        TextView textGameTitle = findViewById(R.id.textGameTitle);
        TextView textGameDate = findViewById(R.id.textGameDate);
        TextView labelPhoto = findViewById(R.id.labelPhoto);
        ImageView imageBoardPhoto = findViewById(R.id.imageBoardPhoto);
        LinearLayout boardBeforeContainer = findViewById(R.id.boardBeforeContainer);
        LinearLayout handBeforeContainer = findViewById(R.id.handBeforeContainer);
        LinearLayout boardAfterContainer = findViewById(R.id.boardAfterContainer);
        LinearLayout handRemainingContainer = findViewById(R.id.handRemainingContainer);

        int gameNumber = getIntent().getIntExtra(EXTRA_GAME_NUMBER, 0);
        String date = getIntent().getStringExtra(EXTRA_DATE);
        textGameTitle.setText(getString(R.string.history_game_title, gameNumber));
        textGameDate.setText(date);
        // Handle the optional legacy board photo (decode + display, or hide if absent).
        bindBoardPhoto(TurnSession.get().getHistoryBoardImage(), labelPhoto, imageBoardPhoto);
        // Rebuild and render the board state before the move.
        bindBoardSection(TurnSession.get().getHistoryBoardBefore(), boardBeforeContainer);
        // Rebuild and render the hand state before the move.
        bindHandSection(TurnSession.get().getHistoryHandBefore(), handBeforeContainer);
        // Rebuild and render the board state after the move.
        bindBoardSection(TurnSession.get().getHistoryBoardAfter(), boardAfterContainer);
        // Rebuild and render the hand tiles remaining after the move.
        bindHandSection(TurnSession.get().getHistoryHandRemaining(), handRemainingContainer);
    }

    /** Only present on entries saved before the photo was dropped from history. */
    // Decodes and shows the legacy board photo, or hides the photo section entirely if there isn't one.
    private void bindBoardPhoto(String boardImage, TextView label, ImageView image) {
        if (boardImage == null) {
            label.setVisibility(View.GONE);
            image.setVisibility(View.GONE);
            return;
        }
        // Decode the base64 text back into the original raw JPEG bytes.
        byte[] bytes = Base64.decode(boardImage, Base64.NO_WRAP);
        // Decode those raw bytes into an actual displayable Bitmap.
        Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        if (bitmap == null) {
            label.setVisibility(View.GONE);
            image.setVisibility(View.GONE);
            return;
        }
        image.setImageBitmap(bitmap);
    }

    /** Reconstructs RummiSets from tile codes and renders them via BoardRenderer. */
    private void bindBoardSection(List<List<String>> setCodes, LinearLayout container) {
        if (setCodes == null) {
            BoardRenderer.drawSets(this, container, null);
            return;
        }
        int[] nextId = {0};
        // Will hold one RummiSet per saved set of codes.
        List<RummiSet> sets = new ArrayList<>(setCodes.size());
        // Walk through every saved set (list of tile codes).
        for (List<String> codes : setCodes) {
            // Will hold the reconstructed Tile objects for this one set.
            List<Tile> tiles = new ArrayList<>(codes.size());
            // Walk through every tile code inside this set.
            for (String code : codes) {
                // Parse the code (e.g. "R7") into a real Tile, assigning it the next free id.
                tiles.add(TileCodeFormat.fromCode(code, nextId[0]++));
            }
            // Wrap the finished tile list as one RummiSet and add it to the result.
            sets.add(new RummiSet(tiles));
        }
        // Draw the fully reconstructed sets using the same renderer SolutionActivity uses.
        BoardRenderer.drawSets(this, container, sets);
    }

    /** Reconstructs Tiles from tile codes and renders them via BoardRenderer. */
    private void bindHandSection(List<String> tileCodes, LinearLayout container) {
        if (tileCodes == null) {
            BoardRenderer.drawHand(this, container, null);
            return;
        }
        int[] nextId = {0};
        // Will hold the reconstructed Tile objects for this hand.
        List<Tile> tiles = new ArrayList<>(tileCodes.size());
        // Walk through every saved tile code.
        for (String code : tileCodes) {
            // Parse the code into a real Tile, assigning it the next free id.
            tiles.add(TileCodeFormat.fromCode(code, nextId[0]++));
        }
        // Draw the reconstructed flat tile list using the same renderer as a live hand.
        BoardRenderer.drawHand(this, container, tiles);
    }
}
