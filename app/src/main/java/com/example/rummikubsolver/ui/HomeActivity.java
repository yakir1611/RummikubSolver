package com.example.rummikubsolver.ui;

import android.content.Intent;
import android.os.Bundle;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.example.rummikubsolver.R;
import com.google.android.material.card.MaterialCardView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** This file is the opening window after logging in, it creates for you four options.
 * the first is to open a new game (by transferring tou to CaptureActivity),
 * the second is to continue the last game you played
 * (by checking the history which game you played last and then continuing him by moving to CaptureActivity),
 * the third option is to look at the history of games you played (by moving yo HistoryActivity),
 * and the last option is to log off. */
public class HomeActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Load this screen's layout.
        setContentView(R.layout.activity_home);

        TextView greeting = findViewById(R.id.textGreeting);
        // Fill in the greeting with the logged-in user's username.
        greeting.setText(getString(R.string.home_greeting, TurnSession.get().getUsername()));
        // Bind the three main choice cards.
        MaterialCardView cardNewGame = findViewById(R.id.cardNewGame);
        MaterialCardView cardContinueGame = findViewById(R.id.cardContinueGame);
        MaterialCardView cardHistory = findViewById(R.id.cardHistory);
        // Tapping this card starts a brand-new game.
        cardNewGame.setOnClickListener(v -> startNewGame());
        // Tapping this card resumes the most recently played game.
        cardContinueGame.setOnClickListener(v -> continueLastGame());
        // Tapping this card opens the saved-games history screen.
        cardHistory.setOnClickListener(v ->
                startActivity(new Intent(this, HistoryActivity.class)));

        TextView btnLogout = findViewById(R.id.btnLogout);
        // Tapping it logs the user out.
        btnLogout.setOnClickListener(v -> logout());
    }

    /**
     * Just remembers a default name and moves on to the capture screen - no
     * server call here. The game itself is only created once the first turn
     * is actually saved (see SolutionActivity.saveToHistory / TurnSession.
     * setCurrentGameId()), so backing out before finishing a turn never
     * leaves an empty game behind.
     */
    private void startNewGame() {
        // Build a default name from the current date and time.
        String name = new SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(new Date());
        // Tell the session a new game is starting.
        TurnSession.get().startNewGame(name);
        // Move on to the board/hand capture screen.
        startActivity(new Intent(HomeActivity.this, CaptureActivity.class));
    }

    /**
     * Finds the most recently created game (server already sorts newest-first,
     * see HistoryStore.loadGames()), loads how many turns it already has so the
     * next one saved continues the numbering instead of restarting at 1 (see
     * TurnSession.resumeGame()), then moves on to the capture screen just like
     * starting a new game does.
     */
    private void continueLastGame() {
        // Ask the store for the user's games (newest-first).
        HistoryStore.get().loadGames(new HistoryStore.GameLoadCallback() {
            @Override
            public void onLoaded(List<HistoryStore.Game> games) {
                if (games.isEmpty()) {
                    Toast.makeText(HomeActivity.this, R.string.home_no_previous_game, Toast.LENGTH_SHORT).show();
                    return;
                }
                // The first game in the (newest-first) list is the most recent one.
                HistoryStore.Game lastGame = games.get(0);
                // Load that game's turns so we know how many have already been played.
                HistoryStore.get().loadGameHistory(lastGame.id, new HistoryStore.LoadCallback() {
                    @Override
                    public void onLoaded(List<HistoryStore.Entry> entries) {
                        // Tell the session to resume this game, continuing the turn numbering from its current size.
                        TurnSession.get().resumeGame(lastGame.id, entries.size());
                        // Move on to the capture screen, same as starting a new game.
                        startActivity(new Intent(HomeActivity.this, CaptureActivity.class));
                    }

                    @Override
                    public void onError(String message) {
                        Toast.makeText(HomeActivity.this, message, Toast.LENGTH_SHORT).show();
                    }
                });
            }

            @Override
            public void onError(String message) {
                Toast.makeText(HomeActivity.this, message, Toast.LENGTH_SHORT).show();
            }
        });
    }
    // Logs the current user out and returns to the login screen.
    private void logout() {
        // Clear the session's stored auth state.
        TurnSession.get().logout();
        // Build an Intent to open the login screen.
        Intent intent = new Intent(this, LoginActivity.class);
        // Clear the whole activity stack so the user can't navigate "back" into the logged-in app.
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        // Navigate to the login screen.
        startActivity(intent);
    }
}
