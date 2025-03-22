package com.example.music_chair;

import android.Manifest;
import android.content.Intent;
import android.database.Cursor;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.provider.MediaStore;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.Button;
import android.widget.TextView;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import android.content.pm.PackageManager;
import android.content.res.AssetFileDescriptor;
import android.hardware.Camera;

import java.io.IOException;
import java.util.Random;

public class MainActivity extends AppCompatActivity {
    private SurfaceView surfaceView;
    private MediaPlayer mediaPlayer;
    private Uri selectedMusic;
    private Handler handler;
    private Random random;
    private boolean isPlaying = false;
    private final int CAMERA_REQUEST_CODE = 100;
    private final int STORAGE_PERMISSION_CODE = 101;
    private TextView tvSelectedMusic;
    private static final String TAG = "MusicChair";
    private Camera camera;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        surfaceView = findViewById(R.id.surfaceView);
        tvSelectedMusic = findViewById(R.id.tvSelectedMusic);
        Button btnSelectMusic = findViewById(R.id.btnSelectMusic);
        Button btnStart = findViewById(R.id.btnStart);

        handler = new Handler();
        random = new Random();
        mediaPlayer = new MediaPlayer();

        // Set error listener for MediaPlayer
        mediaPlayer.setOnErrorListener((mp, what, extra) -> {
            String errorMsg = "MediaPlayer Error: " + what + ", " + extra;
            Log.e(TAG, errorMsg);
            tvSelectedMusic.setText(errorMsg);
            isPlaying = false;
            return false;
        });

        requestPermissions();

        btnSelectMusic.setOnClickListener(view -> selectMusic());

        btnStart.setOnClickListener(view -> {
            if (selectedMusic != null) {
                startMusicWithRandomStops();
            } else {
                tvSelectedMusic.setText("Please select music first");
            }
        });
    }

    private void requestPermissions() {
        // First request storage permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{Manifest.permission.READ_EXTERNAL_STORAGE},
                    STORAGE_PERMISSION_CODE
            );
        } else {
            // If storage is already granted, request camera
            requestCameraPermission();
        }
    }

    private void requestCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA},
                    CAMERA_REQUEST_CODE);
        } else {
            setupCamera();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == STORAGE_PERMISSION_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // Storage permission granted - now request camera
                requestCameraPermission();
            } else {
                Log.d(TAG, "Storage permission denied");
            }
        } else if (requestCode == CAMERA_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                setupCamera(); // Start camera if permission granted
            } else {
                Log.d(TAG, "Camera permission denied");
            }
        }
    }

    private void setupCamera() {
        surfaceView.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(@NonNull SurfaceHolder holder) {
                openFrontCamera(holder);
            }

            @Override
            public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {
                // If surface changes (e.g., rotation), restart the camera preview
                if (camera != null) {
                    try {
                        camera.stopPreview();

                        // Set optimal preview size
                        Camera.Parameters parameters = camera.getParameters();
                        Camera.Size previewSize = getBestPreviewSize(width, height, parameters);
                        if (previewSize != null) {
                            parameters.setPreviewSize(previewSize.width, previewSize.height);
                            camera.setParameters(parameters);
                        }

                        camera.setPreviewDisplay(holder);
                        camera.startPreview();
                    } catch (Exception e) {
                        Log.e(TAG, "Error restarting camera preview: " + e.getMessage());
                    }
                }
            }

            @Override
            public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
                releaseCamera();
            }
        });
    }

    private Camera.Size getBestPreviewSize(int width, int height, Camera.Parameters parameters) {
        Camera.Size result = null;

        for (Camera.Size size : parameters.getSupportedPreviewSizes()) {
            if (size.width <= width && size.height <= height) {
                if (result == null) {
                    result = size;
                } else {
                    int resultArea = result.width * result.height;
                    int newArea = size.width * size.height;

                    if (newArea > resultArea) {
                        result = size;
                    }
                }
            }
        }

        return result;
    }

    private void openFrontCamera(SurfaceHolder holder) {
        releaseCamera(); // Make sure to release camera first if it exists

        try {
            // Find front camera
            int cameraId = -1;
            int numberOfCameras = Camera.getNumberOfCameras();
            for (int i = 0; i < numberOfCameras; i++) {
                Camera.CameraInfo info = new Camera.CameraInfo();
                Camera.getCameraInfo(i, info);
                if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                    cameraId = i;
                    break;
                }
            }

            if (cameraId == -1) {
                // No front camera found, try to open default camera
                Log.w(TAG, "No front camera found, using default camera");
                camera = Camera.open();
            } else {
                camera = Camera.open(cameraId);
                Log.d(TAG, "Front camera opened");
            }

            // Set display orientation
            Camera.CameraInfo info = new Camera.CameraInfo();
            Camera.getCameraInfo(cameraId != -1 ? cameraId : 0, info);
            int rotation = getWindowManager().getDefaultDisplay().getRotation();
            int degrees = 0;
            switch (rotation) {
                case 0: degrees = 0; break;
                case 1: degrees = 90; break;
                case 2: degrees = 180; break;
                case 3: degrees = 270; break;
            }

            int result;
            if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                result = (info.orientation + degrees) % 360;
                result = (360 - result) % 360;  // compensate for front camera mirror
            } else {
                result = (info.orientation - degrees + 360) % 360;
            }
            camera.setDisplayOrientation(result);

            camera.setPreviewDisplay(holder);
            camera.startPreview();
        } catch (Exception e) {
            Log.e(TAG, "Error setting up camera: " + e.getMessage(), e);
        }
    }

    private void releaseCamera() {
        if (camera != null) {
            camera.stopPreview();
            camera.release();
            camera = null;
        }
    }

    private void selectMusic() {
        // Use ACTION_GET_CONTENT instead for better compatibility
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("audio/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        musicPickerLauncher.launch(intent);
    }

    private final ActivityResultLauncher<Intent> musicPickerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    selectedMusic = result.getData().getData();
                    if (selectedMusic != null) {
                        // Log the URI
                        Log.d(TAG, "Selected music URI: " + selectedMusic.toString());

                        // Check if the URI is accessible
                        try {
                            String mimeType = getContentResolver().getType(selectedMusic);
                            Log.d(TAG, "MIME type: " + mimeType);
                            String musicTitle = getMusicTitle(selectedMusic);
                            tvSelectedMusic.setText("Selected: " + musicTitle);

                            // Make sure we can access the file
                            try (AssetFileDescriptor afd = getContentResolver().openAssetFileDescriptor(selectedMusic, "r")) {
                                if (afd == null) {
                                    tvSelectedMusic.setText("Cannot access the selected file");
                                }
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "Error accessing URI", e);
                            tvSelectedMusic.setText("Error accessing selected file: " + e.getMessage());
                        }
                    } else {
                        tvSelectedMusic.setText("Invalid selection");
                    }
                }
            });

    private String getMusicTitle(Uri uri) {
        // First try using ContentResolver
        String[] projection = {MediaStore.Audio.Media.TITLE};
        try (Cursor cursor = getContentResolver().query(uri, projection, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int titleIndex = cursor.getColumnIndex(MediaStore.Audio.Media.TITLE);
                if (titleIndex != -1) {
                    return cursor.getString(titleIndex);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting music title from ContentResolver", e);
        }

        // Try to get the display name
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int displayNameIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME);
                if (displayNameIndex != -1) {
                    return cursor.getString(displayNameIndex);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting display name", e);
        }

        // Try another approach if the first one fails
        String path = uri.getPath();
        if (path != null) {
            String[] segments = path.split("/");
            if (segments.length > 0) {
                return segments[segments.length - 1];
            }
        }

        return "Unknown";
    }

    private void startMusicWithRandomStops() {
        if (isPlaying) return;

        try {
            mediaPlayer.reset();

            // Log the URI we're trying to play
            Log.d(TAG, "Attempting to play: " + selectedMusic.toString());

            // Get a file descriptor from content resolver
            try (AssetFileDescriptor afd = getContentResolver().openAssetFileDescriptor(selectedMusic, "r")) {
                if (afd != null) {
                    mediaPlayer.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
                } else {
                    throw new IOException("Could not open file descriptor");
                }
            }

            // Use prepareAsync with a completion listener
            mediaPlayer.setOnPreparedListener(mp -> {
                isPlaying = true;
                mediaPlayer.start();
                tvSelectedMusic.setText("Playing: " + getMusicTitle(selectedMusic));
                scheduleRandomStops();
            });

            mediaPlayer.prepareAsync();
            tvSelectedMusic.setText("Preparing: " + getMusicTitle(selectedMusic));
        } catch (IOException e) {
            String errorMsg = "Error: " + e.getMessage();
            Log.e(TAG, errorMsg, e);
            tvSelectedMusic.setText(errorMsg);
            isPlaying = false;
        } catch (Exception e) {
            String errorMsg = "Unexpected error: " + e.getMessage();
            Log.e(TAG, errorMsg, e);
            tvSelectedMusic.setText(errorMsg);
            isPlaying = false;
        }
    }

    private void scheduleRandomStops() {
        int delay = (random.nextInt(21) + 10) * 1000; // Random time between 10-30 sec
        handler.postDelayed(() -> {
            if (mediaPlayer != null && mediaPlayer.isPlaying()) {
                mediaPlayer.pause();
                tvSelectedMusic.setText("Paused! Find a chair!");
                handler.postDelayed(() -> {
                    if (mediaPlayer != null) {
                        mediaPlayer.start();
                        tvSelectedMusic.setText("Playing: " + getMusicTitle(selectedMusic));
                        scheduleRandomStops();
                    }
                }, 2000); // Resume after 2 sec
            }
        }, delay);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            setupCamera();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mediaPlayer != null && mediaPlayer.isPlaying()) {
            mediaPlayer.pause();
            isPlaying = false;
        }
        handler.removeCallbacksAndMessages(null);
        releaseCamera();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mediaPlayer != null) {
            mediaPlayer.release();
            mediaPlayer = null;
        }
        handler.removeCallbacksAndMessages(null);
        releaseCamera();
    }
}