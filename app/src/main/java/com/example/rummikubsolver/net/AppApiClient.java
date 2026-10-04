package com.example.rummikubsolver.net;

import android.os.Handler;
import android.os.Looper;

import androidx.annotation.Nullable;

import com.example.rummikubsolver.BuildConfig;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Talks to our own Node/Express server (auth + history).
 */
public class AppApiClient {

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final OkHttpClient client = new OkHttpClient();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // Whichever base URL (USB or WiFi) last succeeded - tried first on the next call.
    private static volatile String preferredBaseUrl = BuildConfig.APP_SERVER_URL_USB;

    // Returns the other candidate base URL, so a failed USB attempt falls back to WiFi (and vice versa).
    private String otherBaseUrl(String current) {
        return current.equals(BuildConfig.APP_SERVER_URL_USB)
                ? BuildConfig.APP_SERVER_URL_WIFI
                : BuildConfig.APP_SERVER_URL_USB;
    }

    //Builds a Request for a given base URL - lets one call site be retried against a different base URL.
    private interface RequestFactory {
        Request build(String baseUrl);
    }

    // Sends a request via the last known-good base URL, retrying once against the other URL on failure.
    private void executeWithFallback(RequestFactory factory, Callback finalCallback) {
        attempt(factory, finalCallback, preferredBaseUrl, true);
    }

    /**
     * Tries one base URL. On failure, retries once against the other base URL if canFallback
     * is true; otherwise reports the failure. On success, remembers this URL as preferred.
     */
    private void attempt(RequestFactory factory, Callback finalCallback, String tryUrl, boolean canFallback) {
        client.newCall(factory.build(tryUrl)).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                if (canFallback) {
                    attempt(factory, finalCallback, otherBaseUrl(tryUrl), false);
                } else {
                    finalCallback.onFailure(call, e);
                }
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                preferredBaseUrl = tryUrl;
                finalCallback.onResponse(call, response);
            }
        });
    }

    /** What a successful register/login call hands back. */
    public static class AuthResult {
        public final String token;
        public final String userId;
        public final String username;

        // Builds an AuthResult straight from the server's {token, userId, username} response.
        AuthResult(String token, String userId, String username) {
            this.token = token;
            this.userId = userId;
            this.username = username;
        }
    }

    /**
     * One saved turn, as the server returns it.
     */
    public static class HistoryEntryDto {
        public final String id;
        public final String name;
        public final long timestamp;
        public final int tilesPlayed;
        @Nullable public final String boardImage;
        @Nullable public final List<List<String>> boardBefore;
        @Nullable public final List<String> handBefore;
        @Nullable public final List<List<String>> boardAfter;
        @Nullable public final List<String> handRemaining;

        // Builds a HistoryEntryDto from the fields already parsed out of the server's JSON.
        HistoryEntryDto(String id, String name, long timestamp, int tilesPlayed, @Nullable String boardImage,
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
    }

    /** One game, as the server returns it. Mirrors HistoryStore.Game. */
    public static class GameDto {
        public final String id;
        public final String name;
        public final long timestamp;

        // Builds a GameDto from the fields already parsed out of the server's JSON.
        GameDto(String id, String name, long timestamp) {
            this.id = id;
            this.name = name;
            this.timestamp = timestamp;
        }
    }

    /** Callback for register()/login() results. */
    public interface AuthCallback {
        void onSuccess(AuthResult result);
        // message is sent by the server
        void onFailure(String message);
    }

    // Callback for calls that only confirm success/failure, with no data to return (save/rename).
    public interface HistorySaveCallback {
        void onSuccess();
        void onFailure(String message);
    }

    // Callback for calls that return a list of saved history entries.
    public interface HistoryListCallback {
        void onSuccess(List<HistoryEntryDto> entries);
        void onFailure(String message);
    }

    // Callback for createGame()'s result.
    public interface GameCreateCallback {
        void onSuccess(GameDto game);
        void onFailure(String message);
    }

    // Callback for calls that return a list of games.
    public interface GameListCallback {
        void onSuccess(List<GameDto> games);
        void onFailure(String message);
    }

    /** POST /api/auth/register { username, password } -> a fresh auth token, logging the user in right away. */
    public void register(String username, String password, AuthCallback callback) {
        JSONObject body = new JSONObject();
        try {
            body.put("username", username);
            body.put("password", password);
        } catch (JSONException e) {
            callback.onFailure("שגיאה פנימית בבניית הבקשה");
            return;
        }
        postJson("api/auth/register", body, null, new SimpleJsonCallback() {
            @Override
            void onSuccess(JSONObject json) {
                deliverAuthSuccess(json, callback);
            }

            @Override
            void onError(String message) {
                mainHandler.post(() -> callback.onFailure(message));
            }
        });
    }

    /** POST /api/auth/login { username, password } -> a fresh auth token if the credentials match. */
    public void login(String username, String password, AuthCallback callback) {
        JSONObject body = new JSONObject();
        try {
            body.put("username", username);
            body.put("password", password);
        } catch (JSONException e) {
            callback.onFailure("שגיאה פנימית בבניית הבקשה");
            return;
        }
        postJson("api/auth/login", body, null, new SimpleJsonCallback() {
            @Override
            void onSuccess(JSONObject json) {
                deliverAuthSuccess(json, callback);
            }

            @Override
            void onError(String message) {
                mainHandler.post(() -> callback.onFailure(message));
            }
        });
    }

    /** Shared by register()/login() - parses {token, userId, username} and delivers it on the main thread. */
    private void deliverAuthSuccess(JSONObject json, AuthCallback callback) {
        try {
            AuthResult result = new AuthResult(
                    json.getString("token"),
                    json.getString("userId"),
                    json.getString("username"));
            mainHandler.post(() -> callback.onSuccess(result));
        } catch (JSONException e) {
            mainHandler.post(() -> callback.onFailure("תשובת שרת לא תקינה"));
        }
    }

    /**
     * @param token the JWT from a previous login/register - saveHistory and
     *              getHistory both require this, per requireAuth on the server
     * @param name display name for this entry - the caller defaults this to
     *             the current date+time (see SolutionActivity), renameable
     *             later via renameHistoryEntry()
     * @param boardBefore the board going into the solve, as an outer array of
     *                    sets, each set an array of tile codes (see
     *                    TileCodeFormat), or null
     * @param handBefore the full hand going into the solve, flat tile-code array, or null
     * @param boardAfter the solver's resulting board, same shape as boardBefore, or null
     * @param handRemaining hand tiles left after the move, flat tile-code array, or null
     * @param gameId the game this turn belongs to (see TurnSession.getCurrentGameId()), or null
     */
    public void saveHistoryEntry(String token, String name, int tilesPlayed,
                                  @Nullable List<List<String>> boardBefore,
                                  @Nullable List<String> handBefore,
                                  @Nullable List<List<String>> boardAfter,
                                  @Nullable List<String> handRemaining,
                                  @Nullable String gameId,
                                  HistorySaveCallback callback) {
        JSONObject body = new JSONObject();
        try {
            body.put("name", name);
            body.put("tilesPlayed", tilesPlayed);
            putSetLists(body, "boardBefore", boardBefore);
            putStringList(body, "handBefore", handBefore);
            putSetLists(body, "boardAfter", boardAfter);
            putStringList(body, "handRemaining", handRemaining);
            if (gameId != null) body.put("gameId", gameId);
        } catch (JSONException e) {
            callback.onFailure("שגיאה פנימית בבניית הבקשה");
            return;
        }
        postJson("api/history", body, token, new SimpleJsonCallback() {
            @Override
            void onSuccess(JSONObject json) {
                mainHandler.post(callback::onSuccess);
            }

            @Override
            void onError(String message) {
                mainHandler.post(() -> callback.onFailure(message));
            }
        });
    }

    /** PATCH /api/history/:id { name } - renames an already-saved entry. */
    public void renameHistoryEntry(String token, String entryId, String newName, HistorySaveCallback callback) {
        JSONObject body = new JSONObject();
        try {
            body.put("name", newName);
        } catch (JSONException e) {
            callback.onFailure("שגיאה פנימית בבניית הבקשה");
            return;
        }
        patchJson("api/history/" + entryId, body, token, new SimpleJsonCallback() {
            @Override
            void onSuccess(JSONObject json) {
                mainHandler.post(callback::onSuccess);
            }

            @Override
            void onError(String message) {
                mainHandler.post(() -> callback.onFailure(message));
            }
        });
    }

    /** POST /api/games  { name } -> the created game. */
    public void createGame(String token, String name, GameCreateCallback callback) {
        JSONObject body = new JSONObject();
        try {
            body.put("name", name);
        } catch (JSONException e) {
            callback.onFailure("שגיאה פנימית בבניית הבקשה");
            return;
        }
        postJson("api/games", body, token, new SimpleJsonCallback() {
            @Override
            void onSuccess(JSONObject json) {
                try {
                    GameDto game = parseGame(json);
                    mainHandler.post(() -> callback.onSuccess(game));
                } catch (JSONException | java.time.format.DateTimeParseException e) {
                    mainHandler.post(() -> callback.onFailure("תשובת שרת לא תקינה"));
                }
            }

            @Override
            void onError(String message) {
                mainHandler.post(() -> callback.onFailure(message));
            }
        });
    }

    /** PATCH /api/games/:id  { name } -> renames a game. */
    public void renameGame(String token, String gameId, String newName, HistorySaveCallback callback) {
        JSONObject body = new JSONObject();
        try {
            body.put("name", newName);
        } catch (JSONException e) {
            callback.onFailure("שגיאה פנימית בבניית הבקשה");
            return;
        }
        patchJson("api/games/" + gameId, body, token, new SimpleJsonCallback() {
            @Override
            void onSuccess(JSONObject json) {
                mainHandler.post(callback::onSuccess);
            }

            @Override
            void onError(String message) {
                mainHandler.post(() -> callback.onFailure(message));
            }
        });
    }

    /** GET /api/games -> this user's games, newest first. */
    public void getGames(String token, GameListCallback callback) {
        executeWithFallback(base -> new Request.Builder()
                .url(base + "api/games")
                .header("Authorization", "Bearer " + token)
                .get()
                .build(), new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                mainHandler.post(() -> callback.onFailure(networkErrorMessage(e)));
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                String bodyText = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) {
                    mainHandler.post(() -> callback.onFailure(extractServerError(bodyText, response.code())));
                    return;
                }
                try {
                    JSONArray arr = new JSONArray(bodyText);
                    List<GameDto> games = new ArrayList<>();
                    for (int i = 0; i < arr.length(); i++) {
                        games.add(parseGame(arr.getJSONObject(i)));
                    }
                    mainHandler.post(() -> callback.onSuccess(games));
                } catch (JSONException | java.time.format.DateTimeParseException e) {
                    mainHandler.post(() -> callback.onFailure("תשובת שרת לא תקינה"));
                }
            }
        });
    }

    /** GET /api/games/:id/history -> the turns saved inside this game, newest first. */
    public void getGameHistory(String token, String gameId, HistoryListCallback callback) {
        executeWithFallback(base -> new Request.Builder()
                .url(base + "api/games/" + gameId + "/history")
                .header("Authorization", "Bearer " + token)
                .get()
                .build(), new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                mainHandler.post(() -> callback.onFailure(networkErrorMessage(e)));
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                String bodyText = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) {
                    mainHandler.post(() -> callback.onFailure(extractServerError(bodyText, response.code())));
                    return;
                }
                try {
                    List<HistoryEntryDto> entries = parseHistoryEntries(bodyText);
                    mainHandler.post(() -> callback.onSuccess(entries));
                } catch (JSONException | java.time.format.DateTimeParseException e) {
                    mainHandler.post(() -> callback.onFailure("תשובת שרת לא תקינה"));
                }
            }
        });
    }

    /** Same {_id, name, timestamp} shape parseGame() reads - factored out since
     *  createGame and getGames both parse it. */
    private GameDto parseGame(JSONObject o) throws JSONException {
        long ts = java.time.Instant.parse(o.getString("timestamp")).toEpochMilli();
        String name = o.has("name") && !o.isNull("name") ? o.getString("name") : null;
        return new GameDto(o.getString("_id"), name, ts);
    }

    /** GET /api/history -> this user's saved turns, newest first. */
    public void getHistory(String token, HistoryListCallback callback) {
        executeWithFallback(base -> new Request.Builder()
                .url(base + "api/history")
                .header("Authorization", "Bearer " + token)
                .get()
                .build(), new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                mainHandler.post(() -> callback.onFailure(networkErrorMessage(e)));
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                String bodyText = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) {
                    mainHandler.post(() -> callback.onFailure(extractServerError(bodyText, response.code())));
                    return;
                }
                try {
                    List<HistoryEntryDto> entries = parseHistoryEntries(bodyText);
                    mainHandler.post(() -> callback.onSuccess(entries));
                } catch (JSONException | java.time.format.DateTimeParseException e) {
                    mainHandler.post(() -> callback.onFailure("תשובת שרת לא תקינה"));
                }
            }
        });
    }

    /** Parses a JSON array of history entries - same response shape GET
     *  /api/history and GET /api/games/:id/history both return, so both
     *  getHistory() and getGameHistory() share this. */
    private List<HistoryEntryDto> parseHistoryEntries(String bodyText) throws JSONException {
        JSONArray arr = new JSONArray(bodyText);
        List<HistoryEntryDto> entries = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            long ts = java.time.Instant.parse(o.getString("timestamp")).toEpochMilli();
            String id = o.getString("_id");
            String name = o.has("name") && !o.isNull("name") ? o.getString("name") : null;

            String boardImage = o.has("boardImage") && !o.isNull("boardImage")
                    ? o.getString("boardImage") : null;

            List<List<String>> boardBefore = parseSetLists(o, "boardBefore");
            List<String> handBefore = parseStringList(o, "handBefore");
            List<List<String>> boardAfter = parseSetLists(o, "boardAfter");
            List<String> handRemaining = parseStringList(o, "handRemaining");

            entries.add(new HistoryEntryDto(id, name, ts, o.getInt("tilesPlayed"),
                    boardImage, boardBefore, handBefore, boardAfter, handRemaining));
        }
        return entries;
    }

    /** Puts an outer array of sets (each an array of tile codes) under key, if non-null. */
    private void putSetLists(JSONObject body, String key, @Nullable List<List<String>> setLists) throws JSONException {
        if (setLists == null) return;
        JSONArray setsArray = new JSONArray();
        for (List<String> set : setLists) {
            setsArray.put(new JSONArray(set));
        }
        body.put(key, setsArray);
    }

    /** Puts a flat array of tile codes under key, if non-null. */
    private void putStringList(JSONObject body, String key, @Nullable List<String> strings) throws JSONException {
        if (strings == null) return;
        body.put(key, new JSONArray(strings));
    }

    /** Reverse of putSetLists() - null if the field is absent/JSON null. */
    @Nullable
    private List<List<String>> parseSetLists(JSONObject o, String key) throws JSONException {
        if (!o.has(key) || o.isNull(key)) return null;
        JSONArray setsArray = o.getJSONArray(key);
        List<List<String>> setLists = new ArrayList<>();
        for (int s = 0; s < setsArray.length(); s++) {
            JSONArray setArray = setsArray.getJSONArray(s);
            List<String> set = new ArrayList<>();
            for (int j = 0; j < setArray.length(); j++) {
                set.add(setArray.getString(j));
            }
            setLists.add(set);
        }
        return setLists;
    }

    /** Reverse of putStringList() - null if the field is absent/JSON null. */
    @Nullable
    private List<String> parseStringList(JSONObject o, String key) throws JSONException {
        if (!o.has(key) || o.isNull(key)) return null;
        JSONArray array = o.getJSONArray(key);
        List<String> strings = new ArrayList<>();
        for (int j = 0; j < array.length(); j++) {
            strings.add(array.getString(j));
        }
        return strings;
    }

    /** Internal helper so register/login/saveHistoryEntry don't each repeat
     *  the same "build request, enqueue, read body, check status" dance. */
    private abstract class SimpleJsonCallback {
        abstract void onSuccess(JSONObject json);
        abstract void onError(String message);
    }

    /** Shared POST helper: builds the request via executeWithFallback(), then
     *  parses the JSON response and reports success/failure through callback. */
    private void postJson(String path, JSONObject body, @Nullable String token, SimpleJsonCallback callback) {
        executeWithFallback(base -> {
            Request.Builder builder = new Request.Builder()
                    .url(base + path)
                    .post(RequestBody.create(body.toString(), JSON));
            if (token != null) {
                builder.header("Authorization", "Bearer " + token);
            }
            return builder.build();
        }, new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                callback.onError(networkErrorMessage(e));
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                String bodyText = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) {
                    callback.onError(extractServerError(bodyText, response.code()));
                    return;
                }
                try {
                    callback.onSuccess(new JSONObject(bodyText));
                } catch (JSONException e) {
                    callback.onError("תשובת שרת לא תקינה");
                }
            }
        });
    }

    /** Same shape as postJson(), but PATCH - used by renameHistoryEntry(). */
    private void patchJson(String path, JSONObject body, @Nullable String token, SimpleJsonCallback callback) {
        executeWithFallback(base -> {
            Request.Builder builder = new Request.Builder()
                    .url(base + path)
                    .patch(RequestBody.create(body.toString(), JSON));
            if (token != null) {
                builder.header("Authorization", "Bearer " + token);
            }
            return builder.build();
        }, new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                callback.onError(networkErrorMessage(e));
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                String bodyText = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) {
                    callback.onError(extractServerError(bodyText, response.code()));
                    return;
                }
                try {
                    callback.onSuccess(new JSONObject(bodyText));
                } catch (JSONException e) {
                    callback.onError("תשובת שרת לא תקינה");
                }
            }
        });
    }

    /** Pulls the {"error": "..."} message our Express error responses always
     *  send, falling back to a generic message if the body isn't JSON (e.g.
     *  the server is down and something else answered on that port/host). */
    private String extractServerError(String bodyText, int statusCode) {
        try {
            JSONObject json = new JSONObject(bodyText);
            if (json.has("error")) return json.getString("error");
        } catch (JSONException ignored) {
            // fall through to the generic message below
        }
        return "השרת החזיר שגיאה (" + statusCode + ")";
    }

    /** User-facing message for OkHttp-level failures (couldn't connect at all). */
    private String networkErrorMessage(IOException e) {
        return "לא ניתן להתחבר לשרת. ודא שהשרת פועל ושכתובת ה-IP נכונה";
    }
}
