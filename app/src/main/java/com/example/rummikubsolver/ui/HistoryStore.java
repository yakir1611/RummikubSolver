package com.example.rummikubsolver.ui;

import androidx.annotation.Nullable;

import com.example.rummikubsolver.net.AppApiClient;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Repository for saved turn history.
 * This file acts as a mediator between AppApiClient and all the files that calls a history act,
 * after getting a response from AppApiClient it converts the response to a response that is usable by the android screens.
 */
public class HistoryStore {

    private static HistoryStore instance;

    // The networking client used for every server call this store makes.
    private final AppApiClient api = new AppApiClient();

    // Represents one saved turn (a single history entry) in the app's own UI-facing shape.
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
        // Formats this entry's raw timestamp into a readable Hebrew-locale date/time string.
        public String formattedDate() {
            SimpleDateFormat f = new SimpleDateFormat("dd.MM.yyyy, HH:mm", new Locale("he"));
            return f.format(new Date(timestamp));
        }
    }

    /** One game - a container of turns. Mirrors AppApiClient.GameDto. */
    public static class Game {
        public final String id;
        public final String name;
        public final long timestamp;

        Game(String id, String name, long timestamp) {
            this.id = id;
            this.name = name;
            this.timestamp = timestamp;
        }

        public String formattedDate() {
            SimpleDateFormat f = new SimpleDateFormat("dd.MM.yyyy, HH:mm", new Locale("he"));
            return f.format(new Date(timestamp));
        }
    }
    // Callback contract for loading a list of history entries.
    public interface LoadCallback {
        void onLoaded(List<Entry> entries);
        void onError(String message);
    }
    // Callback contract for any save/rename operation that doesn't return data.
    public interface SaveCallback {
        void onSaved();
        void onError(String message);
    }
    // Callback contract for loading a list of games.
    public interface GameLoadCallback {
        void onLoaded(List<Game> games);
        void onError(String message);
    }

    // Callback contract for creating a new game.
    public interface GameCreateCallback {
        void onCreated(String gameId);
        void onError(String message);
    }
    // Private constructor - forces all access through the singleton get() method below.
    private HistoryStore() {}
    // Returns the single shared HistoryStore instance, creating it on first use.
    public static synchronized HistoryStore get() {
        if (instance == null) instance = new HistoryStore();
        return instance;
    }

    /**
     * @param name defaults to the current date+time, renameable later via rename() below.
     * @param boardBefore/handBefore/boardAfter/handRemaining the same four
     *             sections the Solution screen rendered via BoardRenderer,
     *             saved verbatim in tile-code format (see TileCodeFormat)
     */
    // Saves a new history entry (one solved turn) to the server.
    public void save(String name, int tilesPlayed,
                      @Nullable List<List<String>> boardBefore, @Nullable List<String> handBefore,
                      @Nullable List<List<String>> boardAfter, @Nullable List<String> handRemaining,
                      @Nullable String gameId,
                      SaveCallback callback) {
        // Grab the current user's auth token from the shared session.
        String token = TurnSession.get().getAuthToken();
        if (token == null) {
            callback.onError("יש להתחבר כדי לשמור היסטוריה");
            return;
        }
        // Delegate the actual HTTP call to AppApiClient, passing the token and all entry data.
        api.saveHistoryEntry(token, name, tilesPlayed, boardBefore, handBefore, boardAfter, handRemaining, gameId,
                new AppApiClient.HistorySaveCallback() {
                    @Override
                    public void onSuccess() { callback.onSaved(); }

                    @Override
                    public void onFailure(String message) { callback.onError(message); }
                });
    }
    // Loads the user's full history (all entries, across all games).
    public void load(LoadCallback callback) {
        // Grab the current user's auth token.
        String token = TurnSession.get().getAuthToken();
        if (token == null) {
            callback.onLoaded(new ArrayList<>());
            return;
        }
        // Ask AppApiClient to fetch the full history list from the server.
        api.getHistory(token, new AppApiClient.HistoryListCallback() {
            @Override
            public void onSuccess(List<AppApiClient.HistoryEntryDto> dtos) {
                List<Entry> entries = new ArrayList<>(dtos.size());
                for (AppApiClient.HistoryEntryDto d : dtos) {
                    entries.add(new Entry(d.id, d.name, d.timestamp, d.tilesPlayed, d.boardImage,
                            d.boardBefore, d.handBefore, d.boardAfter, d.handRemaining));
                }
                // server already sorts newest-first (see historyController.js),
                // so no re-sort needed here.
                callback.onLoaded(entries);
            }

            @Override
            public void onFailure(String message) { callback.onError(message); }
        });
    }

    /** Renames an already-saved entry. Server enforces that it belongs to this user. */
    // Renames a single history entry by its id.
    public void rename(String entryId, String newName, SaveCallback callback) {
        // Grab the current user's auth token.
        String token = TurnSession.get().getAuthToken();
        if (token == null) {
            callback.onError("יש להתחבר כדי לשנות שם");
            return;
        }
        // Delegate the rename call to AppApiClient.
        api.renameHistoryEntry(token, entryId, newName, new AppApiClient.HistorySaveCallback() {
            @Override
            public void onSuccess() { callback.onSaved(); }

            @Override
            public void onFailure(String message) { callback.onError(message); }
        });
    }

    /** Creates a new game on the server. */
    public void createGame(String name, GameCreateCallback callback) {
        // Grab the current user's auth token.
        String token = TurnSession.get().getAuthToken();
        if (token == null) {
            callback.onError("יש להתחבר כדי להתחיל משחק חדש");
            return;
        }
        // Delegate the create-game call to AppApiClient.
        api.createGame(token, name, new AppApiClient.GameCreateCallback() {
            @Override
            public void onSuccess(AppApiClient.GameDto game) { callback.onCreated(game.id); }

            @Override
            public void onFailure(String message) { callback.onError(message); }
        });
    }
    // Loads the full list of the user's saved games.
    public void loadGames(GameLoadCallback callback) {
        // Grab the current user's auth token.
        String token = TurnSession.get().getAuthToken();
        if (token == null) {
            callback.onLoaded(new ArrayList<>());
            return;
        }
        // Ask AppApiClient to fetch the games list from the server.
        api.getGames(token, new AppApiClient.GameListCallback() {
            @Override
            public void onSuccess(List<AppApiClient.GameDto> dtos) {
                List<Game> games = new ArrayList<>(dtos.size());
                for (AppApiClient.GameDto d : dtos) {
                    games.add(new Game(d.id, d.name, d.timestamp));
                }
                callback.onLoaded(games);
            }

            @Override
            public void onFailure(String message) { callback.onError(message); }
        });
    }

    /** Renames an already-saved game. Server enforces that it belongs to this user. */
    public void renameGame(String gameId, String newName, SaveCallback callback) {
        // Grab the current user's auth token.
        String token = TurnSession.get().getAuthToken();
        if (token == null) {
            callback.onError("יש להתחבר כדי לשנות שם");
            return;
        }
        // Delegate the rename call to AppApiClient.
        api.renameGame(token, gameId, newName, new AppApiClient.HistorySaveCallback() {
            @Override
            public void onSuccess() { callback.onSaved(); }

            @Override
            public void onFailure(String message) { callback.onError(message); }
        });
    }

    /** The turns saved inside one game. */
    // Loads only the history entries that belong to one specific game.
    public void loadGameHistory(String gameId, LoadCallback callback) {
        // Grab the current user's auth token.
        String token = TurnSession.get().getAuthToken();
        if (token == null) {
            callback.onLoaded(new ArrayList<>());
            return;
        }
        // Ask AppApiClient to fetch just this game's history entries.
        api.getGameHistory(token, gameId, new AppApiClient.HistoryListCallback() {
            @Override
            public void onSuccess(List<AppApiClient.HistoryEntryDto> dtos) {
                List<Entry> entries = new ArrayList<>(dtos.size());
                for (AppApiClient.HistoryEntryDto d : dtos) {
                    entries.add(new Entry(d.id, d.name, d.timestamp, d.tilesPlayed, d.boardImage,
                            d.boardBefore, d.handBefore, d.boardAfter, d.handRemaining));
                }
                callback.onLoaded(entries);
            }

            @Override
            public void onFailure(String message) { callback.onError(message); }
        });
    }
}
