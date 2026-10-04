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
 *
 * This file gets the photos (bitmap) from CaptureActivity and transfer them to DetectionClient,
 * then DetectionClient makes an HTTP request to the python server inorder to find out the tiles
 * in the pictures and after the server respond the tiles transfer to TileDetectionService,
 * and TileDetectionService transfer them to DetectionParser in order to change the servers response to DetectedTile objects,
 * after that the result is transferred to CaptureActivity.
 */
public class TileDetectionService {
    // Callback contract for a detection request.
    public interface Callback {
        void onSuccess(List<DetectedTile> tiles);
        void onError(String message);
    }

    private final Context context;
    // Handler bound to the main thread, used to guarantee callbacks land there.
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
    // Sends one photo for detection and returns the resulting tiles through the callback.
    public void detect(Bitmap photo, DetectedTile.Source source, Callback callback) {
        if (photo == null) {
            main.post(() -> callback.onError("אין תמונה לניתוח"));
            return;
        }

        // DetectionClient talks to the network and hands us RawDetections.
        // it already hops to the main thread, but we wrap our own main.post
        // anyway so this service guarantees a main-thread callback on its own,
        // no matter what the client does internally
        // Create a client and ask it to detect tiles in this photo.
        new DetectionClient().detectTiles(photo, new DetectionClient.DetectionListener() {
            @Override
            public void onSuccess(List<DetectionParser.RawDetection> detections) {
                // source (HAND/BOARD) gets injected here - the parser is the
                // only layer that knows which photo this was
                // Parse the raw detections into DetectedTile objects, tagging them with the given source.
                List<DetectedTile> tiles = new DetectionParser().parse(detections, source);
                // Deliver the result back on the main thread.
                main.post(() -> callback.onSuccess(tiles));
            }

            @Override
            public void onFailure(Exception e) {
                main.post(() -> callback.onError(e.getMessage()));
            }
        });
    }
}
