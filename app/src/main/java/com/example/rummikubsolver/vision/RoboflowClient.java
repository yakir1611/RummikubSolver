package com.example.rummikubsolver.vision;

import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;

import androidx.annotation.NonNull;

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

// talks to Roboflow to get tiles detections
public class RoboflowClient {

    private static final String URL =
            "https://serverless.roboflow.com/yakirs-workspace-74eqq/workflows/my-first-project-rklmj";

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
            body.put("api_key", BuildConfig.ROBOFLOW_API_KEY);
            // Roboflow expects the image nested inside an "inputs" object, not as a top level field
            // this is the exact structure their workflow API requires, not a choice made here
            JSONObject inputs = new JSONObject();
            inputs.put("image", base64Image);
            body.put("inputs", inputs);
        } catch (JSONException e) {
            listener.onFailure(e);
            return;
        }

        RequestBody requestBody = RequestBody.create(body.toString(), JSON);
        Request request = new Request.Builder()
                .url(URL)
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
                            new IOException("Roboflow returned " + response.code())));
                    return;
                }

                String responseBody = response.body() != null ? response.body().string() : "";
                try {
                    List<DetectionParser.RawDetection> raw = convertResponseToDetections (responseBody);
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

    // parses the Roboflow response into RawDetection objects with proper normalization
    private List<DetectionParser.RawDetection> convertResponseToDetections (String responseBody) throws JSONException {
        List<DetectionParser.RawDetection> results = new ArrayList<>();

        JSONObject root = new JSONObject(responseBody);
        JSONArray outputs = root.getJSONArray("outputs");
        JSONObject firstOutput = outputs.getJSONObject(0);

        // get image dimensions so we can normalize coordinates
        JSONObject imageMetadata = firstOutput.getJSONObject("predictions").getJSONObject("image");
        float imageWidth = (float) imageMetadata.getDouble("width");
        float imageHeight = (float) imageMetadata.getDouble("height");

        // get the predictions array
        JSONArray predictions = firstOutput.getJSONObject("predictions").getJSONArray("predictions");

        for (int i = 0; i < predictions.length(); i++) {
            JSONObject p = predictions.getJSONObject(i);
            String label = p.getString("class");
            float confidence = (float) p.getDouble("confidence");
            // Roboflow gives us center (x, y) in pixel coordinates, plus width/height
            float centerX = (float) p.getDouble("x");
            float centerY = (float) p.getDouble("y");
            float pixelWidth = (float) p.getDouble("width");
            float pixelHeight = (float) p.getDouble("height");

            // convert center to top-left corner (Roboflow uses YOLO format = center)
            float cornerPixelX = centerX - pixelWidth / 2.0f;
            float cornerPixelY = centerY - pixelHeight / 2.0f;

            // normalize to 0.0-1.0 range
            float normalizedX = cornerPixelX / imageWidth;
            float normalizedY = cornerPixelY / imageHeight;
            float normalizedWidth = pixelWidth / imageWidth;
            float normalizedHeight = pixelHeight / imageHeight;

            // clamp to valid range (sometimes edge detections can go slightly out)
            normalizedX = Math.max(0.0f, Math.min(1.0f, normalizedX));
            normalizedY = Math.max(0.0f, Math.min(1.0f, normalizedY));
            normalizedWidth = Math.max(0.0f, Math.min(1.0f, normalizedWidth));
            normalizedHeight = Math.max(0.0f, Math.min(1.0f, normalizedHeight));

            BoundingBox box = new BoundingBox(
                    normalizedX,
                    normalizedY,
                    normalizedWidth,
                    normalizedHeight
            );

            results.add(new DetectionParser.RawDetection(label, confidence, box));
        }

        return results;
    }
}