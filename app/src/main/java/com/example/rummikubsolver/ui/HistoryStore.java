package com.example.rummikubsolver.ui;

import androidx.annotation.Nullable;

import com.example.rummikubsolver.net.AppApiClient;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Repository for saved turn history - now backed by the Node/Mongo server
 * instead of SharedPreferences (the class comment used to promise exactly
 * this swap; this is that swap).
 *
 * The one thing that had to change beyond "call the server instead": SharedPreferences
 * reads are synchronous, a network call isn't. load()/save() used to return/act
 * immediately; now they take a callback and the screens (HistoryActivity,
 * SolutionActivity) show a loading state while waiting.
 */
public class HistoryStore {

    private static HistoryStore instance;

    private final AppApiClient api = new AppApiClient();

    public static class Entry {
        public final String id;
        public final String name;
        public final long timestamp;
        public final int tilesPlayed;
        // kept for entries saved before the photo was dropped from history
        // (see SolutionActivity) - a new entry never has one
        @Nullable public final String boardImage;
        // the four sections the Solution screen showed - see BoardRenderer
        @Nullable public final List<List<String>> boardBefore;
        @Nullable public final List<String> handBefore;
        @Nullable public final List<List<String>> boardAfter;
        @Nullable public final List<String> handRemaining;

        Entry(String id, String name, long timestamp, int tilesPlayed, @Nullable String boardImage,
              @Nullable List<List<String>> boardBefore, @Nullable List<String> handBefore,
              @Nullable List<List<String>> boardAfter, @Nullable List<String> handRemaining) {
            this.id = id;
            this.name = name;
            this.timestamp = timestamp;
            this.tilesPlayed = tilesPlayed;
            this.boardImage = boardImage;
            this.boardBefore = boardBefore;
            this.handBefore = handBefore;
            this.boardAfter = boardAfter;
            this.handRemaining = handRemaining;
        }

        public String formattedDate() {
            SimpleDateFormat f = new SimpleDateFormat("dd.MM.yyyy, HH:mm", new Locale("he"));
            return f.format(new Date(timestamp));
        }
    }

    public interface LoadCallback {
        void onLoaded(List<Entry> entries);
        void onError(String message);
    }

    public interface SaveCallback {
        void onSaved();
        void onError(String message);
    }

    private HistoryStore() {}

    public static synchronized HistoryStore get() {
        if (instance == null) instance = new HistoryStore();
        return instance;
    }

    /**
     * @param name defaults to the current date+time (see SolutionActivity) at
     *             save time - renameable later via rename() below
     * @param boardBefore/handBefore/boardAfter/handRemaining the same four
     *             sections the Solution screen rendered via BoardRenderer,
     *             saved verbatim in tile-code format (see TileCodeFormat)
     */
    public void save(String name, int tilesPlayed,
                      @Nullable List<List<String>> boardBefore, @Nullable List<String> handBefore,
                      @Nullable List<List<String>> boardAfter, @Nullable List<String> handRemaining,
                      SaveCallback callback) {
        String token = TurnSession.get().getAuthToken();
        if (token == null) {
            // guest mode, or somehow got here logged out - nothing to attach
            // this entry to server-side, so fail clearly instead of silently
            // dropping it (which is what the old SharedPreferences version
            // would never have done - it always had *somewhere* local to write)
            callback.onError("יש להתחבר כדי לשמור היסטוריה");
            return;
        }
        api.saveHistoryEntry(token, name, tilesPlayed, boardBefore, handBefore, boardAfter, handRemaining,
                new AppApiClient.HistorySaveCallback() {
                    @Override
                    public void onSuccess() { callback.onSaved(); }

                    @Override
                    public void onFailure(String message) { callback.onError(message); }
                });
    }

    public void load(LoadCallback callback) {
        String token = TurnSession.get().getAuthToken();
        if (token == null) {
            // a guest never saved anything server-side either - empty list,
            // not an error, so HistoryActivity just shows "no history" same
            // as a logged-in user with a genuinely empty history
            callback.onLoaded(new ArrayList<>());
            return;
        }
        api.getHistory(token, new AppApiClient.HistoryListCallback() {
            @Override
            public void onSuccess(List<AppApiClient.HistoryEntryDto> dtos) {
                List<Entry> entries = new ArrayList<>(dtos.size());
                for (AppApiClient.HistoryEntryDto d : dtos) {
                    entries.add(new Entry(d.id, d.name, d.timestamp, d.tilesPlayed, d.boardImage,
                            d.boardBefore, d.handBefore, d.boardAfter, d.handRemaining));
                }
                // server already sorts newest-first (see historyController.js),
                // no re-sort needed here like the old SharedPreferences version had to do
                callback.onLoaded(entries);
            }

            @Override
            public void onFailure(String message) { callback.onError(message); }
        });
    }

    /** Renames an already-saved entry. Server enforces that it belongs to this user. */
    public void rename(String entryId, String newName, SaveCallback callback) {
        String token = TurnSession.get().getAuthToken();
        if (token == null) {
            callback.onError("יש להתחבר כדי לשנות שם");
            return;
        }
        api.renameHistoryEntry(token, entryId, newName, new AppApiClient.HistorySaveCallback() {
            @Override
            public void onSuccess() { callback.onSaved(); }

            @Override
            public void onFailure(String message) { callback.onError(message); }
        });
    }
}
