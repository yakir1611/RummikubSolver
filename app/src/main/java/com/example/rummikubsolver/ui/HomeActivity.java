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

/** Two big choices: start a turn, or look at saved ones. */
public class HomeActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_home);

        TextView greeting = findViewById(R.id.textGreeting);
        greeting.setText(getString(R.string.home_greeting, TurnSession.get().getUsername()));

        MaterialCardView cardNewGame = findViewById(R.id.cardNewGame);
        MaterialCardView cardContinueGame = findViewById(R.id.cardContinueGame);
        MaterialCardView cardHistory = findViewById(R.id.cardHistory);

        cardNewGame.setOnClickListener(v -> startNewGame());

        cardContinueGame.setOnClickListener(v -> continueLastGame());

        cardHistory.setOnClickListener(v ->
                startActivity(new Intent(this, HistoryActivity.class)));
    }

    /**
     * Just remembers a default name and moves on to the capture screen - no
     * server call here. The game itself is only created once the first turn
     * is actually saved (see SolutionActivity.saveToHistory / TurnSession.
     * setCurrentGameId()), so backing out before finishing a turn never
     * leaves an empty game behind.
     */
    private void startNewGame() {
        String name = new SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(new Date());
        TurnSession.get().startNewGame(name); // also clears whatever the last turn left
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
        HistoryStore.get().loadGames(new HistoryStore.GameLoadCallback() {
            @Override
            public void onLoaded(List<HistoryStore.Game> games) {
                if (games.isEmpty()) {
                    Toast.makeText(HomeActivity.this, R.string.home_no_previous_game, Toast.LENGTH_SHORT).show();
                    return;
                }
                HistoryStore.Game lastGame = games.get(0);
                HistoryStore.get().loadGameHistory(lastGame.id, new HistoryStore.LoadCallback() {
                    @Override
                    public void onLoaded(List<HistoryStore.Entry> entries) {
                        TurnSession.get().resumeGame(lastGame.id, entries.size());
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
}
