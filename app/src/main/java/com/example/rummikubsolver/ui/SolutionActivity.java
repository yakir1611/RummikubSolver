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
 * as four tile-rendered sections - board/hand, before/after.
 * As soon as a real move is found, it's saved to history automatically.
 * The search can take a while, so it runs
 * on a background thread with a loading overlay. Anything that touches views
 * comes back through the main-thread Handler.
 */
public class SolutionActivity extends AppCompatActivity {

    private LinearLayout beforeBoardContainer, beforeHandContainer;
    private LinearLayout afterBoardContainer, remainingHandContainer;
    private TextView textPlayedCount, textRemaining;
    private View loadingOverlay;
    private MaterialButton btnNewTurn, btnHome;
    // Single background thread the solver search runs on, so it never blocks the UI.
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    // Handler bound to the main thread's message loop, used to post UI updates back from the worker.
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_solution);
        // Bind every view field to its id in the layout.
        beforeBoardContainer = findViewById(R.id.beforeBoardContainer);
        beforeHandContainer = findViewById(R.id.beforeHandContainer);
        afterBoardContainer = findViewById(R.id.afterBoardContainer);
        remainingHandContainer = findViewById(R.id.remainingHandContainer);
        textPlayedCount = findViewById(R.id.textPlayedCount);
        textRemaining = findViewById(R.id.textRemaining);
        loadingOverlay = findViewById(R.id.loadingOverlay);
        btnNewTurn = findViewById(R.id.btnNewTurn);
        btnHome = findViewById(R.id.btnHome);
        // Tapping "new turn" starts a fresh turn and goes back to capture.
        btnNewTurn.setOnClickListener(v -> {
            TurnSession.get().startNewTurn();
            Intent i = new Intent(this, CaptureActivity.class);
            i.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(i);
            // Close this screen so back doesn't return to a stale solution.
            finish();
        });
        // Tapping "home" returns to the home screen, clearing the whole turn's stack.
        btnHome.setOnClickListener(v -> {
            // clears everything above HomeActivity in the back stack, so
            // pressing back from Home doesn't walk through the whole turn
            // again - same idea as btnNewTurn above, just going further back
            Intent i = new Intent(this, HomeActivity.class);
            i.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            finish();
        });
        // Kick off the solve process as soon as the screen opens.
        solve();
    }
    // Builds the current game state, draws the "before" sections immediately, then runs the solver in the background.
    private void solve() {
        if (TurnSession.get().getDetections().isEmpty()) {
            Toast.makeText(this, "אין מצב משחק לחישוב", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        // built straight from the current boardSetIndex groupings - no geometry
        // re-run, so this matches exactly what the review screen showed
        // Build the Board/Hand state from the already-grouped and already-validated detections.
        TurnSession.GameState state = TurnSession.get().buildCurrentGameState();

        // the two "before" sections don't need the solver - draw them right away
        BoardRenderer.drawSets(this, beforeBoardContainer, state.board.getSets());
        BoardRenderer.drawHand(this, beforeHandContainer, state.hand.getTiles());
        // Run the actual solving work on the background worker thread.
        worker.execute(() -> {
            // copies, so the solver can't mutate what we're already showing
            Board board = new Board(state.board);
            Hand hand = new Hand(state.hand);
            // Run the backtracking solver on the copies.
            OptimalSolver.Result result = new OptimalSolver().solve(board, hand);
            // Hop back onto the main thread to update the UI with the result.
            main.post(() -> {
                loadingOverlay.setVisibility(View.GONE);
                showResult(result, state);
            });
        });
    }
    // Renders the solver's result into the "after" sections and triggers auto-save if a real move was found.
    private void showResult(OptimalSolver.Result result, TurnSession.GameState stateBefore) {
        // How many hand tiles were actually played, or 0 if none.
        int played = result.playedHandTiles == null ? 0 : result.playedHandTiles.size();

        // same tiles, minus whatever the solver played - the exact Tile
        // instances in playedHandTiles come from stateBefore.hand's own list
        // (the solver never clones tiles), so equals()-by-id removal is exact
        List<Tile> remaining = new ArrayList<>(stateBefore.hand.getTiles());
        // Remove exactly the tiles the solver played, leaving what's left in hand.
        if (result.playedHandTiles != null) {
            remaining.removeAll(result.playedHandTiles);
        }
        // No feasible move or nothing was actually played.
        if (!result.feasible || played == 0) {
            textPlayedCount.setText(R.string.solution_none);
            textRemaining.setText(getString(R.string.solution_remaining, stateBefore.hand.getSize()));
        } else {
            textPlayedCount.setText(getString(R.string.solution_tiles_played, played));
            textRemaining.setText(getString(R.string.solution_remaining, remaining.size()));

            if (remaining.isEmpty()) {
                Toast.makeText(this, R.string.solution_hand_emptied, Toast.LENGTH_LONG).show();
            }
        }
        // Draw the "after" board using the solver's resulting sets.
        BoardRenderer.drawSets(this, afterBoardContainer, result.newBoardSets);
        // Draw the remaining hand tiles.
        BoardRenderer.drawHand(this, remainingHandContainer, remaining);

        // Only auto-save when there's an actual, feasible move with tiles played.
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
     */
    // Converts the solved turn's state into tile codes and saves it (creating the game first, if needed).
    private void saveToHistory(OptimalSolver.Result result, TurnSession.GameState stateBefore, List<Tile> handRemaining) {
        String name = getString(R.string.solution_turn_default_name, TurnSession.get().getNextTurnNumber());
        // Convert the "before" board sets into saved tile-code format.
        List<List<String>> boardBefore = toSetCodes(stateBefore.board.getSets());
        // Convert the "before" hand into saved tile-code format.
        List<String> handBefore = TileCodeFormat.toCodes(stateBefore.hand.getTiles());
        // Convert the "after" board sets into saved tile-code format.
        List<List<String>> boardAfter = toSetCodes(result.newBoardSets);
        // Convert the remaining hand into saved tile-code format.
        List<String> handRemainingCodes = TileCodeFormat.toCodes(handRemaining);
        // Check if this game already has a server-side id.
        String gameId = TurnSession.get().getCurrentGameId();
        // Game already exists - just save this entry under it.
        if (gameId != null) {
            saveEntry(gameId, name, result, boardBefore, handBefore, boardAfter, handRemainingCodes);
            return;
        }
        // First turn in this game - create the game on the server first.
        HistoryStore.get().createGame(TurnSession.get().getPendingGameName(), new HistoryStore.GameCreateCallback() {
            @Override
            public void onCreated(String newGameId) {
                TurnSession.get().setCurrentGameId(newGameId);
                // Now save this entry under the newly created game.
                saveEntry(newGameId, name, result, boardBefore, handBefore, boardAfter, handRemainingCodes);
            }

            @Override
            public void onError(String message) {
                Toast.makeText(SolutionActivity.this, message, Toast.LENGTH_SHORT).show();
            }
        });
    }
    // Sends the actual save-entry request to the history store.
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
    // Converts a list of RummiSets into the nested tile-code format used for saving.
    private List<List<String>> toSetCodes(List<RummiSet> sets) {
        List<List<String>> codes = new ArrayList<>();
        for (RummiSet set : sets) {
            codes.add(TileCodeFormat.toCodes(set.getTiles()));
        }
        return codes;
    }
    // Standard Activity cleanup - runs when the screen is being destroyed.
    @Override
    protected void onDestroy() {
        super.onDestroy();
        worker.shutdownNow(); // don't leak the thread if the user backs out mid-search
    }
}
