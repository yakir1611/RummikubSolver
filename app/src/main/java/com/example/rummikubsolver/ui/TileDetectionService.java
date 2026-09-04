package com.example.rummikubsolver.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;

import com.example.rummikubsolver.vision.DetectedTile;
import com.example.rummikubsolver.vision.DetectionClient;
import com.example.rummikubsolver.vision.DetectionParser;

import java.util.List;

/**
 * Thin layer between the UI and DetectionClient.
 *
 * Why it exists: the screens shouldn't care whether detection comes from the
 * cloud API, a local model, or anything else. They call detect() and get
 * DetectedTiles back on the main thread.
 *
 * Flow: DetectionClient fetches RawDetections from the model, DetectionParser
 * turns them into DetectedTiles (and stamps HAND/BOARD via the source arg),
 * and this class guarantees the callback lands on the main thread.
 */
public class TileDetectionService {

    public interface Callback {
        void onSuccess(List<DetectedTile> tiles);
        void onError(String message);
    }

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());

    public TileDetectionService(Context context) {
        this.context = context.getApplicationContext();
    }

    /**
     * Runs detection on one photo.
     *
     * @param source tells the parser whether these tiles are HAND or BOARD -
     *               BoardAssembler splits on exactly this field
     */
    public void detect(Bitmap photo, DetectedTile.Source source, Callback callback) {
        if (photo == null) {
            main.post(() -> callback.onError("אין תמונה לניתוח"));
            return;
        }

        // DetectionClient talks to the network and hands us RawDetections.
        // it already hops to the main thread, but we wrap our own main.post
        // anyway so this service guarantees a main-thread callback on its own,
        // no matter what the client does internally
        new DetectionClient().detectTiles(photo, new DetectionClient.DetectionListener() {
            @Override
            public void onSuccess(List<DetectionParser.RawDetection> detections) {
                // source (HAND/BOARD) gets injected here - the parser is the
                // only layer that knows which photo this was
                List<DetectedTile> tiles = new DetectionParser().parse(detections, source);
                main.post(() -> callback.onSuccess(tiles));
            }

            @Override
            public void onFailure(Exception e) {
                main.post(() -> callback.onError(e.getMessage()));
            }
        });
    }
}
