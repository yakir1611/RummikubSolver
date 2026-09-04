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
 * 'step' field. Simpler than two near-identical Activities, and the back button
 * naturally takes you from the hand step back to the board step.
 *
 * After both photos are in, we send them to the model one after the other and
 * collect the DetectedTiles into the TurnSession, then move on to review.
 */
public class CaptureActivity extends AppCompatActivity {

    private enum Step { BOARD, HAND }

    private Step step = Step.BOARD;

    private ImageView imagePreview;
    private TextView textPlaceholder, textStep, textHint, textLoading;
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

        imagePreview = findViewById(R.id.imagePreview);
        textPlaceholder = findViewById(R.id.textPlaceholder);
        textStep = findViewById(R.id.textStep);
        textHint = findViewById(R.id.textHint);
        textLoading = findViewById(R.id.textLoading);
        btnCamera = findViewById(R.id.btnCamera);
        btnGallery = findViewById(R.id.btnGallery);
        btnNext = findViewById(R.id.btnNext);
        loadingOverlay = findViewById(R.id.loadingOverlay);

        registerLaunchers();

        btnCamera.setOnClickListener(v -> askCameraThenShoot());
        btnGallery.setOnClickListener(v -> galleryLauncher.launch("image/*"));
        btnNext.setOnClickListener(v -> onNext());

        showStep(Step.BOARD);
    }

    private void registerLaunchers() {
        // TakePicturePreview gives a small thumbnail bitmap - plenty for the model
        // and it avoids the FileProvider + full-res-file dance entirely.
        cameraLauncher = registerForActivityResult(
                new ActivityResultContracts.TakePicturePreview(),
                bitmap -> {
                    if (bitmap != null) setPhoto(bitmap);
                });

        galleryLauncher = registerForActivityResult(
                new ActivityResultContracts.GetContent(),
                uri -> {
                    if (uri != null) setPhoto(loadBitmap(uri));
                });

        permissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                granted -> {
                    if (granted) cameraLauncher.launch(null);
                    else Toast.makeText(this, R.string.capture_permission_needed,
                            Toast.LENGTH_LONG).show();
                });
    }

    private void askCameraThenShoot() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            cameraLauncher.launch(null);
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA);
        }
    }

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

    private void setPhoto(Bitmap bmp) {
        if (bmp == null) return;
        currentPhoto = bmp;
        imagePreview.setImageBitmap(bmp);
        textPlaceholder.setVisibility(View.GONE);
        btnNext.setEnabled(true);
        btnCamera.setText(R.string.capture_retake);
    }

    private void showStep(Step s) {
        step = s;
        currentPhoto = null;
        imagePreview.setImageDrawable(null);
        textPlaceholder.setVisibility(View.VISIBLE);
        btnNext.setEnabled(false);
        btnCamera.setText(R.string.capture_take_photo);

        if (s == Step.BOARD) {
            textStep.setText(R.string.capture_step_board);
            textHint.setText(R.string.capture_hint_board);
            btnNext.setText(R.string.capture_next);
        } else {
            textStep.setText(R.string.capture_step_hand);
            textHint.setText(R.string.capture_hint_hand);
            btnNext.setText(R.string.capture_analyze);
        }
    }

    private void onNext() {
        if (currentPhoto == null) return;

        if (step == Step.BOARD) {
            TurnSession.get().setBoardPhoto(currentPhoto);
            showStep(Step.HAND);
        } else {
            TurnSession.get().setHandPhoto(currentPhoto);
            runDetection();
        }
    }

    /**
     * Sends both photos to the model. The board comes back first, then we chain
     * the hand request - the client is async, so nesting the callbacks keeps the
     * order without blocking the UI thread.
     */
    private void runDetection() {
        setLoading(true);
        TurnSession session = TurnSession.get();
        session.getDetections().clear();

        TileDetectionService service = new TileDetectionService(this);

        service.detect(session.getBoardPhoto(), DetectedTile.Source.BOARD,
                new TileDetectionService.Callback() {
                    @Override
                    public void onSuccess(List<DetectedTile> boardTiles) {
                        session.addDetections(boardTiles);

                        service.detect(session.getHandPhoto(), DetectedTile.Source.HAND,
                                new TileDetectionService.Callback() {
                                    @Override
                                    public void onSuccess(List<DetectedTile> handTiles) {
                                        session.addDetections(handTiles);
                                        setLoading(false);
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

    private void goToReview() {
        startActivity(new Intent(this, ReviewActivity.class));
    }

    private void showError(String message) {
        new AlertDialog.Builder(this)
                .setMessage(getString(R.string.capture_error, message))
                .setPositiveButton(R.string.capture_retry, (d, w) -> runDetection())
                .setNegativeButton(R.string.editor_cancel, null)
                .show();
    }

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
