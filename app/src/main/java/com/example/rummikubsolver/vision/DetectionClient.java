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

// talks to our own tile-detection server (Python + Ultralytics + FastAPI),
// which replaced Roboflow's paid serverless API. Same job, our own model.
public class DetectionClient {

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final OkHttpClient client = new OkHttpClient();
    // hops back to main thread so the listener can safely touch UI
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public interface DetectionListener {
        void onSuccess(List<DetectionParser.RawDetection> detections);
        void onFailure(Exception e);
    }

    public void detectTiles(Bitmap bitmap, DetectionListener listener) {
        String base64Image = bitmapToBase64(bitmap);

        JSONObject body = new JSONObject();
        try {
            // our own server's contract is deliberately simple - just the
            // image, no api_key, no nested "inputs" wrapper (that was
            // Roboflow's workflow-API shape, not something we need anymore)
            body.put("image", base64Image);
        } catch (JSONException e) {
            listener.onFailure(e);
            return;
        }

        RequestBody requestBody = RequestBody.create(body.toString(), JSON);
        Request request = new Request.Builder()
                .url(BuildConfig.DETECTION_SERVER_URL)
                .post(requestBody)
                .build();

        // enqueue = async, runs on OkHttp's own background thread
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                mainHandler.post(() -> listener.onFailure(e));
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (!response.isSuccessful()) {
                    mainHandler.post(() -> listener.onFailure(
                            new IOException("Detection server returned " + response.code())));
                    return;
                }

                String responseBody = response.body() != null ? response.body().string() : "";
                try {
                    List<DetectionParser.RawDetection> raw = convertResponseToDetections(responseBody);
                    mainHandler.post(() -> listener.onSuccess(raw));
                } catch (JSONException e) {
                    mainHandler.post(() -> listener.onFailure(e));
                }
            }
        });
    }

    private String bitmapToBase64(Bitmap bitmap) {
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream);
        byte[] bytes = stream.toByteArray();
        return Base64.encodeToString(bytes, Base64.NO_WRAP);
    }

    // parses our own server's response - already normalized (0-1) and
    // already top-left-corner, so unlike the old Roboflow parsing there is
    // no center-to-corner or pixel-to-normalized math needed here at all
    private List<DetectionParser.RawDetection> convertResponseToDetections(String responseBody) throws JSONException {
        List<DetectionParser.RawDetection> results = new ArrayList<>();

        JSONObject root = new JSONObject(responseBody);
        JSONArray predictions = root.getJSONArray("predictions");

        for (int i = 0; i < predictions.length(); i++) {
            JSONObject p = predictions.getJSONObject(i);
            String label = p.getString("class");
            float confidence = (float) p.getDouble("confidence");
            float x = (float) p.getDouble("x");
            float y = (float) p.getDouble("y");
            float width = (float) p.getDouble("width");
            float height = (float) p.getDouble("height");

            // clamp to valid range (sometimes edge detections can go slightly out)
            x = Math.max(0.0f, Math.min(1.0f, x));
            y = Math.max(0.0f, Math.min(1.0f, y));
            width = Math.max(0.0f, Math.min(1.0f, width));
            height = Math.max(0.0f, Math.min(1.0f, height));

            BoundingBox box = new BoundingBox(x, y, width, height);
            results.add(new DetectionParser.RawDetection(label, confidence, box));
        }

        return results;
    }
}
