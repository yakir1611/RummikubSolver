package com.example.rummikubsolver.vision;

import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;

import com.example.rummikubsolver.BuildConfig;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

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

// talks to our own tile-detection server.
//This file does the HTTP request with the pictures to the python server (after checking that the connection is valid).
public class DetectionClient {

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    // The actual HTTP client used for every call, built once per instance.
    private final OkHttpClient client = new OkHttpClient.Builder()
            // Max time to wait while establishing the TCP connection.
            .connectTimeout(60, TimeUnit.SECONDS)
            // Max time to wait while sending the request body (the base64 image can be large).
            .writeTimeout(60, TimeUnit.SECONDS)
            // Max time to wait while waiting for/reading the server's response.
            .readTimeout(60, TimeUnit.SECONDS)
            // Finalize and construct the configured client.
            .build();

    // hops back to main thread so the listener can safely touch UI
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    // Shared across instances: remembers which server URL (USB/WiFi) worked last time.
    private static volatile String preferredBaseUrl = BuildConfig.DETECTION_SERVER_URL_USB;
    // Given the current base URL, returns the other configured option to try next.
    private String otherBaseUrl(String current) {
        return current.equals(BuildConfig.DETECTION_SERVER_URL_USB)
                ? BuildConfig.DETECTION_SERVER_URL_WIFI
                : BuildConfig.DETECTION_SERVER_URL_USB;
    }
    // Small functional interface - builds a Request once a base URL is chosen.
    private interface RequestFactory {
        Request build(String baseUrl);
    }
    // Entry point for making a call that can fall back to the other server URL on failure.
    private void executeWithFallback(RequestFactory factory, Callback finalCallback) {
        // Start with whichever URL last succeeded, allowing one fallback attempt.
        attempt(factory, finalCallback, preferredBaseUrl, true);
    }
    // Tries one URL; if it fails and fallback is still allowed, retries with the other URL.
    private void attempt(RequestFactory factory, Callback finalCallback, String tryUrl, boolean canFallback) {
        // Build the request for this URL and enqueue it asynchronously.
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
                // Remember this URL as the one that worked, so next time we try it first.
                preferredBaseUrl = tryUrl;
                finalCallback.onResponse(call, response);
            }
        });
    }
    // Public callback interface used by detectTiles() to report results.
    public interface DetectionListener {
        // Called with the parsed raw detections on success.
        void onSuccess(List<DetectionParser.RawDetection> detections);
        void onFailure(Exception e);
    }
    // Sends one photo to the detection server and reports the result via the listener.
    public void detectTiles(Bitmap bitmap, DetectionListener listener) {
        // Encode the bitmap into a base64 JPEG string for transport.
        String base64Image = bitmapToBase64(bitmap);
        // JSON object that will become the HTTP request body.
        JSONObject body = new JSONObject();
        try {
            // Put the base64 image under the single "image" key the server expects.
            body.put("image", base64Image);
        } catch (JSONException e) {
            listener.onFailure(e);
            return;
        }
        // Issue the request (with automatic USB/WiFi fallback) and handle the eventual result.
        executeWithFallback(base -> new Request.Builder()
                // Target URL is whichever base URL is being tried.
                .url(base)
                // POST the JSON body built above.
                .post(RequestBody.create(body.toString(), JSON))
                // Finalize the request object.
                .build(), new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                mainHandler.post(() -> listener.onFailure(e));
            }
            // Called once a response is received from the server.
            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (!response.isSuccessful()) {
                    mainHandler.post(() -> listener.onFailure(
                            new IOException("Detection server returned " + response.code())));
                    return;
                }
                // Read the response body as a string, defaulting to empty if there is none.
                String responseBody = response.body() != null ? response.body().string() : "";
                try {
                    // Parse the server's JSON into a list of raw detection objects.
                    List<DetectionParser.RawDetection> raw = convertResponseToDetections(responseBody);
                    // Deliver the successful result back on the main thread.
                    mainHandler.post(() -> listener.onSuccess(raw));
                } catch (JSONException e) {
                    mainHandler.post(() -> listener.onFailure(e));
                }
            }
        });
    }
    // Converts a Bitmap into a base64-encoded JPEG string, ready to embed in JSON.
    private String bitmapToBase64(Bitmap bitmap) {
        // Buffer to hold the compressed JPEG bytes.
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream);
        byte[] bytes = stream.toByteArray();
        return Base64.encodeToString(bytes, Base64.NO_WRAP);
    }
    // Parses the server's JSON body into a list of raw, unprocessed detections.
    private List<DetectionParser.RawDetection> convertResponseToDetections(String responseBody) throws JSONException {
        List<DetectionParser.RawDetection> results = new ArrayList<>();
        // Parse the whole response body as a JSON object.
        JSONObject root = new JSONObject(responseBody);
        // Extract the "predictions" array holding one entry per detected tile.
        JSONArray predictions = root.getJSONArray("predictions");
        // Walk every prediction entry in the array.
        for (int i = 0; i < predictions.length(); i++) {
            // The current prediction as a JSON object.
            JSONObject p = predictions.getJSONObject(i);
            // The detected class label (e.g. a number/color/joker code).
            String label = p.getString("class");
            // Confidence score for this detection, as a float.
            float confidence = (float) p.getDouble("confidence");
            //x and y represents the top left corner of the tile and width and height are the width and height of the tile in the picture.
            float x = (float) p.getDouble("x");
            float y = (float) p.getDouble("y");
            float width = (float) p.getDouble("width");
            float height = (float) p.getDouble("height");

            // clamp to valid range (sometimes edge detections can go slightly out)
            //clamp into 0-1 range, we use 0-1 because instead of saying the amount of pixels in the picture we say the percentage.
            x = Math.max(0.0f, Math.min(1.0f, x));
            y = Math.max(0.0f, Math.min(1.0f, y));
            width = Math.max(0.0f, Math.min(1.0f, width));
            height = Math.max(0.0f, Math.min(1.0f, height));
            // Build the bounding box from the (now clamped) normalized values.
            BoundingBox box = new BoundingBox(x, y, width, height);
            // Add a new raw detection combining label, confidence and box.
            results.add(new DetectionParser.RawDetection(label, confidence, box));
        }
        // Return every parsed detection from this response.
        return results;
    }
}
