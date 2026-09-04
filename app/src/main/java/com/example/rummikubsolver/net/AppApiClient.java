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
 * Talks to our own Node/Express server (auth + history) - NOT Roboflow,
 * that's RoboflowClient's job. Same shape on purpose: OkHttp, async
 * enqueue(), callback hops back to the main thread. Two separate classes
 * because they talk to two unrelated services with unrelated request/response
 * formats; merging them would just make one class that knows about both.
 */
public class AppApiClient {

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final OkHttpClient client = new OkHttpClient();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // BuildConfig.APP_SERVER_URL always ends with "/" (see build.gradle.kts
    // default) - so endpoint paths below are appended without a leading "/"
    private final String baseUrl = BuildConfig.APP_SERVER_URL;

    /** What a successful register/login call hands back. */
    public static class AuthResult {
        public final String token;
        public final String userId;
        public final String username;

        AuthResult(String token, String userId, String username) {
            this.token = token;
            this.userId = userId;
            this.username = username;
        }
    }

    /**
     * One saved turn, as the server returns it. Mirrors HistoryStore.Entry.
     * boardBefore/boardAfter are each an outer array of sets, each set an
     * array of tile codes; handBefore/handRemaining are flat tile-code
     * arrays - the exact same shapes SolutionActivity builds to feed
     * BoardRenderer, saved verbatim (see TileCodeFormat).
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

    public interface AuthCallback {
        void onSuccess(AuthResult result);
        /** message is already a user-facing Hebrew string - the server sends
         *  one (e.g. "שם המשתמש כבר תפוס"), we fall back to a generic one
         *  only for network-level failures the server never got to answer. */
        void onFailure(String message);
    }

    public interface HistorySaveCallback {
        void onSuccess();
        void onFailure(String message);
    }

    public interface HistoryListCallback {
        void onSuccess(List<HistoryEntryDto> entries);
        void onFailure(String message);
    }

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
     */
    public void saveHistoryEntry(String token, String name, int tilesPlayed,
                                  @Nullable List<List<String>> boardBefore,
                                  @Nullable List<String> handBefore,
                                  @Nullable List<List<String>> boardAfter,
                                  @Nullable List<String> handRemaining,
                                  HistorySaveCallback callback) {
        JSONObject body = new JSONObject();
        try {
            body.put("name", name);
            body.put("tilesPlayed", tilesPlayed);
            putSetLists(body, "boardBefore", boardBefore);
            putStringList(body, "handBefore", handBefore);
            putSetLists(body, "boardAfter", boardAfter);
            putStringList(body, "handRemaining", handRemaining);
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

    public void getHistory(String token, HistoryListCallback callback) {
        Request request = new Request.Builder()
                .url(baseUrl + "api/history")
                .header("Authorization", "Bearer " + token)
                .get()
                .build();

        client.newCall(request).enqueue(new Callback() {
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
                    List<HistoryEntryDto> entries = new ArrayList<>();
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject o = arr.getJSONObject(i);
                        // Mongo stores timestamp as an ISO date string; Date.parse
                        // via new Date().getTime() equivalent here is manual since
                        // org.json has no date parsing - java.util handles ISO-8601 fine
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
                    mainHandler.post(() -> callback.onSuccess(entries));
                } catch (JSONException | java.time.format.DateTimeParseException e) {
                    mainHandler.post(() -> callback.onFailure("תשובת שרת לא תקינה"));
                }
            }
        });
    }

    // ---- shared plumbing ----

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

    private void postJson(String path, JSONObject body, @Nullable String token, SimpleJsonCallback callback) {
        Request.Builder builder = new Request.Builder()
                .url(baseUrl + path)
                .post(RequestBody.create(body.toString(), JSON));
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        Request request = builder.build();

        client.newCall(request).enqueue(new Callback() {
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
        Request.Builder builder = new Request.Builder()
                .url(baseUrl + path)
                .patch(RequestBody.create(body.toString(), JSON));
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        Request request = builder.build();

        client.newCall(request).enqueue(new Callback() {
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

    private String networkErrorMessage(IOException e) {
        // most common case for students testing locally: forgot to start the
        // server, or used the wrong APP_SERVER_URL - worth a clearer hint
        // than OkHttp's raw "Failed to connect" message
        return "לא ניתן להתחבר לשרת. ודא שהשרת פועל ושכתובת ה-IP נכונה";
    }
}
