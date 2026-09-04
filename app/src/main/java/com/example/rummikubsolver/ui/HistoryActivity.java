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
 * Saved solutions, newest first.
 *
 * load() is a network call now (HistoryStore -> AppApiClient -> the Node
 * server), so this can't just fetch-and-bind in onCreate() like it used to
 * with SharedPreferences - there's a moment where we have nothing to show
 * yet, hence progressHistory.
 */
public class HistoryActivity extends AppCompatActivity {

    private RecyclerView recycler;
    private TextView empty;
    private ProgressBar progress;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_history);

        recycler = findViewById(R.id.recyclerHistory);
        empty = findViewById(R.id.textEmpty);
        progress = findViewById(R.id.progressHistory);
        recycler.setLayoutManager(new LinearLayoutManager(this));

        loadHistory();
    }

    private void loadHistory() {
        showLoading();
        HistoryStore.get().load(new HistoryStore.LoadCallback() {
            @Override
            public void onLoaded(List<HistoryStore.Entry> entries) {
                if (entries.isEmpty()) {
                    showMessage(getString(R.string.history_empty));
                    return;
                }
                progress.setVisibility(View.GONE);
                empty.setVisibility(View.GONE);
                recycler.setVisibility(View.VISIBLE);
                recycler.setAdapter(new Adapter(entries));
            }

            @Override
            public void onError(String message) {
                // reusing textEmpty for the error message rather than adding a
                // third view - "no history yet" and "couldn't load history"
                // are both "nothing to show in the list" from the UI's perspective
                showMessage(message);
            }
        });
    }

    private void showLoading() {
        progress.setVisibility(View.VISIBLE);
        empty.setVisibility(View.GONE);
        recycler.setVisibility(View.GONE);
    }

    private void showMessage(String text) {
        progress.setVisibility(View.GONE);
        recycler.setVisibility(View.GONE);
        empty.setText(text);
        empty.setVisibility(View.VISIBLE);
    }

    /** Plain text-input dialog to rename one entry; reloads the list on success. */
    private void showRenameDialog(HistoryStore.Entry entry) {
        EditText input = new EditText(this);
        input.setText(entry.name);
        if (entry.name != null) input.setSelection(entry.name.length());

        new AlertDialog.Builder(this)
                .setTitle(R.string.history_rename_title)
                .setView(input)
                .setPositiveButton(R.string.editor_save, (dialog, which) -> {
                    String newName = input.getText() == null ? "" : input.getText().toString().trim();
                    if (TextUtils.isEmpty(newName)) return;
                    HistoryStore.get().rename(entry.id, newName, new HistoryStore.SaveCallback() {
                        @Override
                        public void onSaved() {
                            loadHistory(); // refresh so the new name shows immediately
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

    private class Adapter extends RecyclerView.Adapter<Adapter.Holder> {

        private final List<HistoryStore.Entry> items;

        Adapter(List<HistoryStore.Entry> items) {
            this.items = items;
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_history, parent, false);
            return new Holder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            HistoryStore.Entry e = items.get(position);
            // server returns entries newest-first, so the oldest (last in the
            // list) is Game 1, ascending regardless of display order - used
            // as a fallback title for entries saved before "name" existed
            int gameNumber = items.size() - position;

            holder.name.setText(e.name != null ? e.name : getString(R.string.history_game_title, gameNumber));
            holder.date.setText(e.formattedDate());
            holder.sub.setText(getString(R.string.history_entry_sub, e.tilesPlayed));

            holder.renameButton.setOnClickListener(v -> showRenameDialog(e));

            boolean hasDetail = e.boardImage != null
                    || (e.boardAfter != null && !e.boardAfter.isEmpty());
            if (hasDetail) {
                holder.itemView.setOnClickListener(v -> {
                    TurnSession.get().setHistoryBoardImage(e.boardImage);
                    TurnSession.get().setHistoryBoardBefore(e.boardBefore);
                    TurnSession.get().setHistoryHandBefore(e.handBefore);
                    TurnSession.get().setHistoryBoardAfter(e.boardAfter);
                    TurnSession.get().setHistoryHandRemaining(e.handRemaining);

                    Intent intent = new Intent(HistoryActivity.this, HistoryDetailActivity.class);
                    intent.putExtra(HistoryDetailActivity.EXTRA_GAME_NUMBER, gameNumber);
                    intent.putExtra(HistoryDetailActivity.EXTRA_DATE, e.formattedDate());
                    startActivity(intent);
                });
            } else {
                holder.itemView.setOnClickListener(null);
                holder.itemView.setClickable(false);
            }
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
