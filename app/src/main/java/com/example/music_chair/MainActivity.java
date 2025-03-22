package com.example.music_chair;

import android.Manifest;
import android.content.DialogInterface;
import android.content.Intent;
import android.database.Cursor;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.provider.MediaStore;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
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
    // NEW: MediaPlayer for countdown audio
    private MediaPlayer countdownPlayer;
    private Uri selectedMusic;
    private Handler handler;
    private Random random;
    private boolean isPlaying = false;
    private final int CAMERA_REQUEST_CODE = 100;
    private final int STORAGE_PERMISSION_CODE = 101;
    private TextView tvSelectedMusic;
    private TextView tvChairCount, tvCountdown;
    private static final String TAG = "MusicChair";
    private Camera camera;

    // NEW: variable for number of chairs
    private int chairCount = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        surfaceView = findViewById(R.id.surfaceView);
        tvSelectedMusic = findViewById(R.id.tvSelectedMusic);
        tvChairCount = findViewById(R.id.tvChairCount);
        tvCountdown = findViewById(R.id.tvCountdown);

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
                // Ask user for the number of chairs before starting the game
                showChairInputDialog();
            } else {
                tvSelectedMusic.setText("Please select music first");
            }
        });
    }

    private void requestPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{Manifest.permission.READ_EXTERNAL_STORAGE},
                    STORAGE_PERMISSION_CODE
            );
        } else {
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
                requestCameraPermission();
            } else {
                Log.d(TAG, "Storage permission denied");
            }
        } else if (requestCode == CAMERA_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                setupCamera();
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
                if (camera != null) {
                    try {
                        camera.stopPreview();
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
        releaseCamera();
        try {
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
                Log.w(TAG, "No front camera found, using default camera");
                camera = Camera.open();
            } else {
                camera = Camera.open(cameraId);
                Log.d(TAG, "Front camera opened");
            }

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
                result = (360 - result) % 360;
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
                        Log.d(TAG, "Selected music URI: " + selectedMusic.toString());
                        try {
                            String mimeType = getContentResolver().getType(selectedMusic);
                            Log.d(TAG, "MIME type: " + mimeType);
                            String musicTitle = getMusicTitle(selectedMusic);
                            tvSelectedMusic.setText("Selected: " + musicTitle);
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

        String path = uri.getPath();
        if (path != null) {
            String[] segments = path.split("/");
            if (segments.length > 0) {
                return segments[segments.length - 1];
            }
        }
        return "Unknown";
    }

    // Show dialog to get number of chairs from user
    private void showChairInputDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Enter number of chairs");

        final EditText input = new EditText(this);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        builder.setView(input);

        builder.setPositiveButton("Start", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                String inputText = input.getText().toString().trim();
                if (!inputText.isEmpty()) {
                    chairCount = Integer.parseInt(inputText);
                    tvChairCount.setText("Chairs: " + chairCount);
                    startInitialCountdown();
                }
            }
        });
        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.cancel());
        builder.show();
    }

    // Play countdown voice from res/raw/countdown_voice.mp3
    private void playCountdownAudio() {
        if (countdownPlayer != null) {
            countdownPlayer.release();
        }
        countdownPlayer = MediaPlayer.create(this, R.raw.countdown_voice);
        if (countdownPlayer != null) {
            countdownPlayer.start();
        }
    }

    // Initial 10-second countdown before game starts
    private void startInitialCountdown() {
        // Play countdown audio concurrently with timer
        playCountdownAudio();
        new CountDownTimer(10000, 1100) {
            public void onTick(long millisUntilFinished) {
                tvCountdown.setText("Get Ready: " + (millisUntilFinished / 1100));
            }
            public void onFinish() {
                tvCountdown.setText("");
                startGameMusic();
            }
        }.start();
    }

    // Start playing music and schedule game stops
    private void startGameMusic() {
        if (isPlaying) return;
        try {
            mediaPlayer.reset();
            Log.d(TAG, "Attempting to play: " + selectedMusic.toString());
            try (AssetFileDescriptor afd = getContentResolver().openAssetFileDescriptor(selectedMusic, "r")) {
                if (afd != null) {
                    mediaPlayer.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
                } else {
                    throw new IOException("Could not open file descriptor");
                }
            }
            mediaPlayer.setOnPreparedListener(mp -> {
                isPlaying = true;
                mediaPlayer.start();
                tvSelectedMusic.setText("Playing: " + getMusicTitle(selectedMusic));
                scheduleRandomStopsGame();
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

    // Schedule random stops between 30 and 45 seconds
    private void scheduleRandomStopsGame() {
        int delay = (random.nextInt(16) + 30) * 1100;
        handler.postDelayed(() -> {
            if (mediaPlayer != null && mediaPlayer.isPlaying()) {
                pauseAndProcessStop();
            }
        }, delay);
    }

    // Pause music for 15 seconds then process chair decrement and countdown
    private void pauseAndProcessStop() {
        mediaPlayer.pause();
        tvSelectedMusic.setText("Paused! Find a chair!");
        handler.postDelayed(() -> resumeAfterPause(), 15000);
    }

    // Decrement chairs, check game over, start a 10-second countdown and then resume music
    private void resumeAfterPause() {
        chairCount--;
        tvChairCount.setText("Chairs: " + chairCount);
        if (chairCount <= 0) {
            if (mediaPlayer != null && mediaPlayer.isPlaying()) {
                mediaPlayer.pause();
            }
            isPlaying = false;
            new AlertDialog.Builder(this)
                    .setTitle("Congratulations!")
                    .setMessage("Winner!")
                    .setPositiveButton("OK", (dialog, which) -> dialog.dismiss())
                    .show();
        } else {
            // Play countdown audio for the resume countdown
            playCountdownAudio();
            new CountDownTimer(10000, 1100) {
                public void onTick(long millisUntilFinished) {
                    tvCountdown.setText("Resuming in: " + (millisUntilFinished / 1100));
                }
                public void onFinish() {
                    tvCountdown.setText("");
                    mediaPlayer.start();
                    tvSelectedMusic.setText("Playing: " + getMusicTitle(selectedMusic));
                    scheduleRandomStopsGame();
                }
            }.start();
        }
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
        if (countdownPlayer != null && countdownPlayer.isPlaying()) {
            countdownPlayer.pause();
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
        if (countdownPlayer != null) {
            countdownPlayer.release();
            countdownPlayer = null;
        }
        handler.removeCallbacksAndMessages(null);
        releaseCamera();
    }
}
