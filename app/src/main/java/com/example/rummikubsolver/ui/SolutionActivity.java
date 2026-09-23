package com.example.rummikubsolver.ui;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.example.rummikubsolver.Board;
import com.example.rummikubsolver.Hand;
import com.example.rummikubsolver.OptimalSolver;
import com.example.rummikubsolver.R;
import com.example.rummikubsolver.RummiSet;
import com.example.rummikubsolver.Tile;
import com.example.rummikubsolver.vision.TileCodeFormat;
import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Runs the solver on the reviewed game state and shows the recommended move
 * as four tile-rendered sections - board/hand, before/after. No board photo
 * anywhere on this screen; the captured photo only ever lived on the earlier
 * capture/review steps and is never encoded or displayed here.
 *
 * As soon as a real move is found, it's saved to history automatically -
 * there's no manual "Save to History" button anymore (see saveToHistory()).
 *
 * The search can take a while (branch and bound with a 2M node cap), so it runs
 * on a background thread with a loading overlay. Anything that touches views
 * comes back through the main-thread Handler.
 */
public class SolutionActivity extends AppCompatActivity {

    private LinearLayout beforeBoardContainer, beforeHandContainer;
    private LinearLayout afterBoardContainer, remainingHandContainer;
    private TextView textPlayedCount, textRemaining;
    private View loadingOverlay;
    private MaterialButton btnNewTurn, btnHome;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_solution);

        beforeBoardContainer = findViewById(R.id.beforeBoardContainer);
        beforeHandContainer = findViewById(R.id.beforeHandContainer);
        afterBoardContainer = findViewById(R.id.afterBoardContainer);
        remainingHandContainer = findViewById(R.id.remainingHandContainer);
        textPlayedCount = findViewById(R.id.textPlayedCount);
        textRemaining = findViewById(R.id.textRemaining);
        loadingOverlay = findViewById(R.id.loadingOverlay);
        btnNewTurn = findViewById(R.id.btnNewTurn);
        btnHome = findViewById(R.id.btnHome);

        btnNewTurn.setOnClickListener(v -> {
            TurnSession.get().startNewTurn();
            Intent i = new Intent(this, CaptureActivity.class);
            i.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(i);
            finish();
        });

        btnHome.setOnClickListener(v -> {
            // clears everything above HomeActivity in the back stack, so
            // pressing back from Home doesn't walk through the whole turn
            // again - same idea as btnNewTurn above, just going further back
            Intent i = new Intent(this, HomeActivity.class);
            i.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            finish();
        });

        solve();
    }

    private void solve() {
        if (TurnSession.get().getDetections().isEmpty()) {
            Toast.makeText(this, "אין מצב משחק לחישוב", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        // built straight from the current boardSetIndex groupings - no geometry
        // re-run, so this matches exactly what the review screen showed
        TurnSession.GameState state = TurnSession.get().buildCurrentGameState();

        // the two "before" sections don't need the solver - draw them right away
        BoardRenderer.drawSets(this, beforeBoardContainer, state.board.getSets());
        BoardRenderer.drawHand(this, beforeHandContainer, state.hand.getTiles());

        worker.execute(() -> {
            // copies, so the solver can't mutate what we're already showing
            Board board = new Board(state.board);
            Hand hand = new Hand(state.hand);

            OptimalSolver.Result result = new OptimalSolver().solve(board, hand);

            main.post(() -> {
                loadingOverlay.setVisibility(View.GONE);
                showResult(result, state);
            });
        });
    }

    private void showResult(OptimalSolver.Result result, TurnSession.GameState stateBefore) {
        int played = result.playedHandTiles == null ? 0 : result.playedHandTiles.size();

        // same tiles, minus whatever the solver played - the exact Tile
        // instances in playedHandTiles come from stateBefore.hand's own list
        // (the solver never clones tiles), so equals()-by-id removal is exact
        List<Tile> remaining = new ArrayList<>(stateBefore.hand.getTiles());
        if (result.playedHandTiles != null) {
            remaining.removeAll(result.playedHandTiles);
        }

        if (!result.feasible || played == 0) {
            textPlayedCount.setText(R.string.solution_none);
            textRemaining.setText(getString(R.string.solution_remaining, stateBefore.hand.getSize()));
        } else {
            textPlayedCount.setText(getString(R.string.solution_tiles_played, played));
            textRemaining.setText(getString(R.string.solution_remaining, remaining.size()));
        }

        BoardRenderer.drawSets(this, afterBoardContainer, result.newBoardSets);
        BoardRenderer.drawHand(this, remainingHandContainer, remaining);

        // auto-save: what used to be the "Save to History" button's click
        // handler now fires on its own, under the exact condition that used
        // to leave that button enabled - a real, feasible move was found
        if (result.feasible && played > 0) {
            saveToHistory(result, stateBefore, remaining);
        }
    }

    /**
     * Saves the just-computed solution to history - all four sections the
     * screen just rendered (board/hand, before/after), in the same tile-code
     * format BoardRenderer was fed. No board photo involved.
     *
     * Default name is "תור מספר N", N being the running count of turns saved
     * in this game so far (see TurnSession.getNextTurnNumber()) - not a
     * timestamp, since HistoryStore.Entry already carries one.
     *
     * The game itself is created lazily: if this is the first turn (no
     * currentGameId yet), the game is created on the server right here,
     * using the name HomeActivity remembered, and only then is the entry
     * saved under it. A user who never gets a feasible move never creates a
     * game at all - see TurnSession.startNewGame().
     */
    private void saveToHistory(OptimalSolver.Result result, TurnSession.GameState stateBefore, List<Tile> handRemaining) {
        String name = getString(R.string.solution_turn_default_name, TurnSession.get().getNextTurnNumber());

        List<List<String>> boardBefore = toSetCodes(stateBefore.board.getSets());
        List<String> handBefore = TileCodeFormat.toCodes(stateBefore.hand.getTiles());
        List<List<String>> boardAfter = toSetCodes(result.newBoardSets);
        List<String> handRemainingCodes = TileCodeFormat.toCodes(handRemaining);

        String gameId = TurnSession.get().getCurrentGameId();
        if (gameId != null) {
            saveEntry(gameId, name, result, boardBefore, handBefore, boardAfter, handRemainingCodes);
            return;
        }

        HistoryStore.get().createGame(TurnSession.get().getPendingGameName(), new HistoryStore.GameCreateCallback() {
            @Override
            public void onCreated(String newGameId) {
                TurnSession.get().setCurrentGameId(newGameId);
                saveEntry(newGameId, name, result, boardBefore, handBefore, boardAfter, handRemainingCodes);
            }

            @Override
            public void onError(String message) {
                Toast.makeText(SolutionActivity.this, message, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void saveEntry(String gameId, String name, OptimalSolver.Result result,
                            List<List<String>> boardBefore, List<String> handBefore,
                            List<List<String>> boardAfter, List<String> handRemainingCodes) {
        HistoryStore.get().save(name, result.playedHandTiles.size(),
                boardBefore, handBefore, boardAfter, handRemainingCodes,
                gameId,
                new HistoryStore.SaveCallback() {
                    @Override
                    public void onSaved() {
                        TurnSession.get().incrementTurnCount();
                        Toast.makeText(SolutionActivity.this, "נשמר בהיסטוריה", Toast.LENGTH_SHORT).show();
                    }

                    @Override
                    public void onError(String message) {
                        Toast.makeText(SolutionActivity.this, message, Toast.LENGTH_SHORT).show();
                    }
                });
    }

    private List<List<String>> toSetCodes(List<RummiSet> sets) {
        List<List<String>> codes = new ArrayList<>();
        for (RummiSet set : sets) {
            codes.add(TileCodeFormat.toCodes(set.getTiles()));
        }
        return codes;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        worker.shutdownNow(); // don't leak the thread if the user backs out mid-search
    }
}
