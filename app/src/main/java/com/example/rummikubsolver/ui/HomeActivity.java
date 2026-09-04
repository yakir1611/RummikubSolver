package com.example.rummikubsolver.ui;

import android.content.Intent;
import android.os.Bundle;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.example.rummikubsolver.R;
import com.google.android.material.card.MaterialCardView;

/** Two big choices: start a turn, or look at saved ones. */
public class HomeActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_home);

        TextView greeting = findViewById(R.id.textGreeting);
        greeting.setText(getString(R.string.home_greeting, TurnSession.get().getUsername()));

        MaterialCardView cardNewGame = findViewById(R.id.cardNewGame);
        MaterialCardView cardHistory = findViewById(R.id.cardHistory);

        cardNewGame.setOnClickListener(v -> {
            TurnSession.get().startNewTurn(); // clear whatever the last turn left
            startActivity(new Intent(this, CaptureActivity.class));
        });

        cardHistory.setOnClickListener(v ->
                startActivity(new Intent(this, HistoryActivity.class)));
    }
}
