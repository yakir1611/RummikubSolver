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
 * Shows one saved history entry in full: the board photo taken that turn (if
 * this is an old entry that still has one) and the same four tile-rendered
 * sections SolutionActivity showed when it was saved - board/hand,
 * before/after - reconstructed from the tile codes the server stored via
 * BoardRenderer, the exact same helper SolutionActivity itself uses. Not
 * re-solved, just redrawn - this screen is display-only.
 *
 * Data comes through TurnSession's transient history* fields, not Intent
 * extras - same reasoning as boardPhoto/handPhoto: a full-res photo plus a
 * whole board's worth of tiles can exceed Intent's ~1MB limit. Only the small
 * bits (game number, date) travel as extras.
 */
public class HistoryDetailActivity extends AppCompatActivity {

    public static final String EXTRA_GAME_NUMBER = "game_number";
    public static final String EXTRA_DATE = "date";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_history_detail);

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

        bindBoardPhoto(TurnSession.get().getHistoryBoardImage(), labelPhoto, imageBoardPhoto);

        bindBoardSection(TurnSession.get().getHistoryBoardBefore(), boardBeforeContainer);
        bindHandSection(TurnSession.get().getHistoryHandBefore(), handBeforeContainer);
        bindBoardSection(TurnSession.get().getHistoryBoardAfter(), boardAfterContainer);
        bindHandSection(TurnSession.get().getHistoryHandRemaining(), handRemainingContainer);
    }

    /** Only present on entries saved before the photo was dropped from history. */
    private void bindBoardPhoto(String boardImage, TextView label, ImageView image) {
        if (boardImage == null) {
            label.setVisibility(View.GONE);
            image.setVisibility(View.GONE);
            return;
        }
        byte[] bytes = Base64.decode(boardImage, Base64.NO_WRAP);
        Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        if (bitmap == null) {
            label.setVisibility(View.GONE);
            image.setVisibility(View.GONE);
            return;
        }
        image.setImageBitmap(bitmap);
    }

    /** Reconstructs RummiSets from tile codes and renders them via BoardRenderer - same helper, same look as SolutionActivity. */
    private void bindBoardSection(List<List<String>> setCodes, LinearLayout container) {
        if (setCodes == null) {
            BoardRenderer.drawSets(this, container, null);
            return;
        }
        int[] nextId = {0};
        List<RummiSet> sets = new ArrayList<>(setCodes.size());
        for (List<String> codes : setCodes) {
            List<Tile> tiles = new ArrayList<>(codes.size());
            for (String code : codes) {
                tiles.add(TileCodeFormat.fromCode(code, nextId[0]++));
            }
            sets.add(new RummiSet(tiles));
        }
        BoardRenderer.drawSets(this, container, sets);
    }

    /** Reconstructs Tiles from tile codes and renders them via BoardRenderer - same helper, same look as SolutionActivity. */
    private void bindHandSection(List<String> tileCodes, LinearLayout container) {
        if (tileCodes == null) {
            BoardRenderer.drawHand(this, container, null);
            return;
        }
        int[] nextId = {0};
        List<Tile> tiles = new ArrayList<>(tileCodes.size());
        for (String code : tileCodes) {
            tiles.add(TileCodeFormat.fromCode(code, nextId[0]++));
        }
        BoardRenderer.drawHand(this, container, tiles);
    }
}
