package com.example.rummikubsolver.ui;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.rummikubsolver.R;

import java.util.List;

/**
 * Saved games, newest first. this screen lists the games themselves;
 * tapping one opens GameHistoryActivity for the turns inside it.
 */
public class HistoryActivity extends AppCompatActivity {

    // The list widget showing all saved games.
    private RecyclerView recycler;
    private TextView empty;
    private ProgressBar progress;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_history);
        // Bind the view fields to their ids in the layout.
        recycler = findViewById(R.id.recyclerHistory);
        empty = findViewById(R.id.textEmpty);
        progress = findViewById(R.id.progressHistory);
        // Tell the RecyclerView to lay its rows out as a simple vertical list.
        recycler.setLayoutManager(new LinearLayoutManager(this));
        // Kick off loading the user's saved games.
        loadGames();
    }

    // Fetches all of the user's games and updates the UI once they arrive.
    private void loadGames() {
        showLoading();
        // Ask the shared history store for the full games list.
        HistoryStore.get().loadGames(new HistoryStore.GameLoadCallback() {
            @Override
            public void onLoaded(List<HistoryStore.Game> games) {
                if (games.isEmpty()) {
                    showMessage(getString(R.string.history_empty));
                    return;
                }
                progress.setVisibility(View.GONE);
                empty.setVisibility(View.GONE);
                recycler.setVisibility(View.VISIBLE);
                recycler.setAdapter(new Adapter(games));
            }

            @Override
            public void onError(String message) {
                // reusing textEmpty for the error message rather than adding a
                // third view - "no games yet" and "couldn't load games" are
                // both "nothing to show in the list" from the UI's perspective
                showMessage(message);
            }
        });
    }

    // Puts the screen into its "loading" visual state.
    private void showLoading() {
        progress.setVisibility(View.VISIBLE);
        empty.setVisibility(View.GONE);
        recycler.setVisibility(View.GONE);
    }
    // Puts the screen into a "message instead of list" state (used for both empty and error cases).
    private void showMessage(String text) {
        progress.setVisibility(View.GONE);
        recycler.setVisibility(View.GONE);
        empty.setText(text);
        empty.setVisibility(View.VISIBLE);
    }

    /** Plain text-input dialog to rename one game; reloads the list on success. */
    private void showRenameDialog(HistoryStore.Game game) {
        // Plain text field that will hold the new name.
        EditText input = new EditText(this);
        input.setText(game.name);
        if (game.name != null) input.setSelection(game.name.length());

        new AlertDialog.Builder(this)
                .setTitle(R.string.history_rename_title)
                .setView(input)
                // Save button - runs when the user confirms the rename.
                .setPositiveButton(R.string.editor_save, (dialog, which) -> {
                    String newName = input.getText() == null ? "" : input.getText().toString().trim();
                    if (TextUtils.isEmpty(newName)) return;
                    // Ask the store to persist the new name for this game's id.
                    HistoryStore.get().renameGame(game.id, newName, new HistoryStore.SaveCallback() {
                        @Override
                        public void onSaved() {
                            loadGames(); // refresh so the new name shows immediately
                        }

                        @Override
                        public void onError(String message) {
                            Toast.makeText(HistoryActivity.this, message, Toast.LENGTH_SHORT).show();
                        }
                    });
                })
                .setNegativeButton(R.string.editor_cancel, null)
                .show();
    }

    // RecyclerView adapter that turns the list of games into rows on screen.
    private class Adapter extends RecyclerView.Adapter<Adapter.Holder> {
        // The games this adapter is currently displaying.
        private final List<HistoryStore.Game> items;

        Adapter(List<HistoryStore.Game> items) {
            this.items = items;
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_history, parent, false);
            return new Holder(v);
        }
        // Fills one already-created row view with this position's data.
        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            HistoryStore.Game g = items.get(position);
            // server returns games newest-first, so the oldest (last in the
            // list) is Game 1, ascending regardless of display order - used
            // as a fallback title for a game saved with no name
            int gameNumber = items.size() - position;

            holder.name.setText(g.name != null ? g.name : getString(R.string.history_game_fallback, gameNumber));
            holder.date.setText(g.formattedDate());
            // no per-turn count shown here - just the name/date, same info a
            // single saved entry used to show before games existed
            holder.sub.setVisibility(View.GONE);
            // Tapping the rename icon opens the rename dialog for this game.
            holder.renameButton.setOnClickListener(v -> showRenameDialog(g));
            // Tapping the row opens the turns list for this specific game.
            holder.itemView.setOnClickListener(v -> {
                // Build the Intent to open GameHistoryActivity.
                Intent intent = new Intent(HistoryActivity.this, GameHistoryActivity.class);
                intent.putExtra(GameHistoryActivity.EXTRA_GAME_ID, g.id);
                intent.putExtra(GameHistoryActivity.EXTRA_GAME_NAME,
                        g.name != null ? g.name : getString(R.string.history_game_fallback, gameNumber));
                // Actually navigate to the game's turns list.
                startActivity(intent);
            });
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        class Holder extends RecyclerView.ViewHolder {
            final TextView name, date, sub;
            final View renameButton;

            Holder(@NonNull View itemView) {
                super(itemView);
                name = itemView.findViewById(R.id.textHistoryName);
                date = itemView.findViewById(R.id.textHistoryDate);
                sub = itemView.findViewById(R.id.textHistorySub);
                renameButton = itemView.findViewById(R.id.btnRenameEntry);
            }
        }
    }
}
