package com.example.rummikubsolver.ui;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.example.rummikubsolver.R;
import com.example.rummikubsolver.vision.DetectedTile;
import com.google.android.material.button.MaterialButton;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Two-step capture: board photo first, then hand photo.
 *
 * The same Activity handles both steps - it just swaps its texts and keeps a
 * 'step' field.
 *
 * After both photos are in, we send them to the model one after the other and
 * collect the DetectedTiles into the TurnSession, then move on to review.
 */
public class CaptureActivity extends AppCompatActivity {

    // The two phases this single Activity walks through, in order.
    private enum Step { BOARD, HAND }

    private Step step = Step.BOARD;

    private ImageView imagePreview;
    private TextView textPlaceholder, textStep, textTitle, textHint, textLoading;
    private MaterialButton btnCamera, btnGallery, btnNext;
    private View loadingOverlay;

    private Bitmap currentPhoto;

    private ActivityResultLauncher<Void> cameraLauncher;
    private ActivityResultLauncher<String> galleryLauncher;
    private ActivityResultLauncher<String> permissionLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_capture);
        // Bind every view field to its id in the layout.
        imagePreview = findViewById(R.id.imagePreview);
        textPlaceholder = findViewById(R.id.textPlaceholder);
        textStep = findViewById(R.id.textStep);
        textTitle = findViewById(R.id.textTitle);
        textHint = findViewById(R.id.textHint);
        textLoading = findViewById(R.id.textLoading);
        btnCamera = findViewById(R.id.btnCamera);
        btnGallery = findViewById(R.id.btnGallery);
        btnNext = findViewById(R.id.btnNext);
        loadingOverlay = findViewById(R.id.loadingOverlay);

        // Set up the three ActivityResultLaunchers before any button can use them.
        registerLaunchers();
        // Camera button: check/ask permission, then open the camera.
        btnCamera.setOnClickListener(v -> askCameraThenShoot());
        // Gallery button: open the system image picker directly (no special permission needed here).
        btnGallery.setOnClickListener(v -> galleryLauncher.launch("image/*"));
        // Next button: advance to the hand step, or trigger detection if we just finished the hand step.
        btnNext.setOnClickListener(v -> onNext());

        // Initialize the screen's texts/buttons for the first step (board).
        showStep(Step.BOARD);
    }

    // Registers all three ActivityResultLaunchers with their result-handling callbacks.
    private void registerLaunchers() {
        // TakePicturePreview gives a small thumbnail bitmap - plenty for the model
        // and it avoids the FileProvider + full-res-file dance entirely.
        cameraLauncher = registerForActivityResult(
                // Built-in contract: opens the camera app, returns a small preview Bitmap (or null).
                new ActivityResultContracts.TakePicturePreview(),
                // Callback receives the Bitmap once the camera app returns.
                bitmap -> {
                    // Only accept it if the user actually took a photo (didn't cancel).
                    if (bitmap != null) setPhoto(bitmap);
                });

        galleryLauncher = registerForActivityResult(
                new ActivityResultContracts.GetContent(),
                // Callback receives the picked image's Uri (or null if canceled).
                uri -> {
                    // Convert the Uri into an actual Bitmap before using it.
                    if (uri != null) setPhoto(loadBitmap(uri));
                });

        permissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                // Callback receives true/false for whether the user granted the permission.
                granted -> {
                    // If granted, immediately proceed to open the camera.
                    if (granted) cameraLauncher.launch(null);
                    else Toast.makeText(this, R.string.capture_permission_needed,
                            Toast.LENGTH_LONG).show();
                });
    }

    // Opens the camera directly if we already have permission, otherwise asks for it first.
    private void askCameraThenShoot() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            cameraLauncher.launch(null);
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA);
        }
    }

    // Decodes a gallery Uri into a Bitmap, using the API-appropriate method.
    private Bitmap loadBitmap(Uri uri) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                ImageDecoder.Source src = ImageDecoder.createSource(getContentResolver(), uri);
                // software bitmap - we need to read pixels for the base64 encoding
                return ImageDecoder.decodeBitmap(src, (decoder, info, s) ->
                        decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE));
            }
            return MediaStore.Images.Media.getBitmap(getContentResolver(), uri);
        } catch (IOException e) {
            Toast.makeText(this, "לא הצלחנו לפתוח את התמונה", Toast.LENGTH_SHORT).show();
            return null;
        }
    }

    // Called whenever a new photo (camera or gallery) is ready to show as the current candidate.
    private void setPhoto(Bitmap bmp) {
        if (bmp == null) return;
        currentPhoto = bmp;
        // Show it in the preview ImageView.
        imagePreview.setImageBitmap(bmp);
        textPlaceholder.setVisibility(View.GONE);
        btnNext.setEnabled(true);
        btnCamera.setText(R.string.capture_retake);
    }

    // Resets the screen's UI state and texts for the given step (BOARD or HAND).
    private void showStep(Step s) {
        step = s;
        currentPhoto = null;
        imagePreview.setImageDrawable(null);
        textPlaceholder.setVisibility(View.VISIBLE);
        btnNext.setEnabled(false);
        btnCamera.setText(R.string.capture_take_photo);
        // Branch the text content based on which step we just switched to.
        if (s == Step.BOARD) {
            textStep.setText(R.string.capture_step_board);
            textTitle.setText(R.string.capture_title_board);
            textHint.setText(R.string.capture_hint_board);
            btnNext.setText(R.string.capture_next);
        } else {
            textStep.setText(R.string.capture_step_hand);
            textTitle.setText(R.string.capture_title_hand);
            textHint.setText(R.string.capture_hint_hand);
            btnNext.setText(R.string.capture_analyze);
        }
    }
    // Handles a tap on the Next/Analyze button, depending on the current step.
    private void onNext() {
        // Safety check - button should be disabled without a photo, but guard anyway.
        if (currentPhoto == null) return;
        // On the board step, save the board photo and move on to the hand step.
        if (step == Step.BOARD) {
            TurnSession.get().setBoardPhoto(currentPhoto);
            showStep(Step.HAND);
        } else {
            // On the hand step, save the hand photo and kick off detection for both photos.
            TurnSession.get().setHandPhoto(currentPhoto);
            // Start sending both photos to the vision model.
            runDetection();
        }
    }

    /**
     * Sends both photos to the model. The board comes back first, then we chain
     * the hand request - the client is async, so nesting the callbacks keeps the
     * order without blocking the UI thread.
     */
    // Runs the board detection, then (inside its success callback) the hand detection.
    private void runDetection() {
        setLoading(true);
        TurnSession session = TurnSession.get();
        session.getDetections().clear();
        // Create the service wrapper that actually talks to the detection backend.
        TileDetectionService service = new TileDetectionService(this);

        service.detect(session.getBoardPhoto(), DetectedTile.Source.BOARD,
                new TileDetectionService.Callback() {
                    @Override
                    public void onSuccess(List<DetectedTile> boardTiles) {
                        // Store the board's detected tiles into the session
                        session.addDetections(boardTiles);
                        // Now that the board is done, chain the hand detection request.
                        service.detect(session.getHandPhoto(), DetectedTile.Source.HAND,
                                new TileDetectionService.Callback() {
                                    @Override
                                    public void onSuccess(List<DetectedTile> handTiles) {
                                        // Store the hand's detected tiles into the session too.
                                        session.addDetections(handTiles);
                                        setLoading(false);
                                        // Move on to the review screen with all detections collected.
                                        goToReview();
                                    }

                                    @Override
                                    public void onError(String message) {
                                        setLoading(false);
                                        showError(message);
                                    }
                                });
                    }

                    @Override
                    public void onError(String message) {
                        setLoading(false);
                        showError(message);
                    }
                });
    }
    // Navigates to ReviewActivity once both sets of detections are ready.
    private void goToReview() {
        startActivity(new Intent(this, ReviewActivity.class));
    }

    // Displays a dialog explaining the detection failure, with retry/cancel options.
    private void showError(String message) {
        new AlertDialog.Builder(this)
                .setMessage(getString(R.string.capture_error, message))
                .setPositiveButton(R.string.capture_retry, (d, w) -> runDetection())
                .setNegativeButton(R.string.editor_cancel, null)
                .show();
    }

    // Toggles the loading overlay's visibility and refreshes its label text.
    private void setLoading(boolean loading) {
        loadingOverlay.setVisibility(loading ? View.VISIBLE : View.GONE);
        textLoading.setText(R.string.capture_analyzing);
    }

    @Override
    public void onBackPressed() {
        // from the hand step, back goes to the board step instead of leaving
        if (step == Step.HAND) {
            showStep(Step.BOARD);
            return;
        }
        super.onBackPressed();
    }
}
