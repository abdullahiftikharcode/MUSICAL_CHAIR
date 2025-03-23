package com.example.music_chair;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.AssetFileDescriptor;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Random;

import android.hardware.Camera;
import android.media.MediaPlayer;
import android.provider.MediaStore;
import android.database.Cursor;

public class MainActivity extends AppCompatActivity {

    private SurfaceView surfaceView;
    private MediaPlayer mediaPlayer;
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

    // Track which camera to use. True = front camera, false = back camera.
    private boolean useFrontCamera = true;

    // Game variables
    private int chairCount = 0;
    // List to collect chair responses from server during detection phase
    private ArrayList<Integer> detectionResponses = new ArrayList<>();
    // Server URLs for object detection and sit-stand endpoints (adjust as needed)
    private final String OBJECT_URL = "https://4dca-175-107-228-183.ngrok-free.app/object";
    private final String SITSTAND_URL = "https://4dca-175-107-228-183.ngrok-free.app/sitstand";

    // Interface for asynchronous sit-stand detection callback.
    interface DetectionCallback {
        void onDetectionResult(int sittingCount);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Initialize views.
        surfaceView = findViewById(R.id.surfaceView);
        tvSelectedMusic = findViewById(R.id.tvSelectedMusic);
        tvChairCount = findViewById(R.id.tvChairCount);
        tvCountdown = findViewById(R.id.tvCountdown);

        Button btnSelectMusic = findViewById(R.id.btnSelectMusic);
        Button btnStart = findViewById(R.id.btnStart);
        Button btnSwitchCamera = findViewById(R.id.btnSwitchCamera); // Ensure this exists in XML

        handler = new Handler();
        random = new Random();
        mediaPlayer = new MediaPlayer();

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
                if (isInternetAvailable()) {
                    startChairDetection();
                } else {
                    showChairInputDialog();
                }
            } else {
                tvSelectedMusic.setText("Please select music first");
            }
        });

        // Switch camera button toggles between front and back cameras.
        btnSwitchCamera.setOnClickListener(view -> switchCamera());
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
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
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
                openCamera(holder, useFrontCamera);
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

    // Open camera based on the useFrontCamera flag.
    private void openCamera(SurfaceHolder holder, boolean useFrontCamera) {
        releaseCamera();
        try {
            int cameraId = -1;
            int numberOfCameras = Camera.getNumberOfCameras();
            for (int i = 0; i < numberOfCameras; i++) {
                Camera.CameraInfo info = new Camera.CameraInfo();
                Camera.getCameraInfo(i, info);
                if (useFrontCamera && info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                    cameraId = i;
                    break;
                } else if (!useFrontCamera && info.facing == Camera.CameraInfo.CAMERA_FACING_BACK) {
                    cameraId = i;
                    break;
                }
            }
            if (cameraId == -1) {
                Log.w(TAG, "Requested camera not found, using default camera");
                camera = Camera.open();
            } else {
                camera = Camera.open(cameraId);
                Log.d(TAG, "Camera opened, id: " + cameraId);
            }

            // Set display orientation based on camera info.
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

    // Switch between front and back cameras.
    private void switchCamera() {
        useFrontCamera = !useFrontCamera;
        if (surfaceView.getHolder() != null) {
            openCamera(surfaceView.getHolder(), useFrontCamera);
        }
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

    private final ActivityResultLauncher<Intent> musicPickerLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
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

    // ----------------------- New Features -----------------------

    // Check for internet connectivity.
    private boolean isInternetAvailable() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm != null) {
            Network network = cm.getActiveNetwork();
            NetworkCapabilities capabilities = cm.getNetworkCapabilities(network);
            return capabilities != null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        }
        return false;
    }

    // Start the chair detection phase (10-second period to send images to the server).
    private void startChairDetection() {
        detectionResponses.clear();
        playCountdownAudio();
        new CountDownTimer(10000, 1000) {
            public void onTick(long millisUntilFinished) {
                tvCountdown.setText("Detecting chairs: " + (millisUntilFinished / 1000));
                captureAndSendFrame();
            }
            public void onFinish() {
                tvCountdown.setText("");
                chairCount = decideChairCount(detectionResponses);
                if (chairCount == 0) {
                    new AlertDialog.Builder(MainActivity.this)
                            .setTitle("No Chairs Detected")
                            .setMessage("No chairs were detected automatically. Please enter the number manually.")
                            .setPositiveButton("OK", (dialog, which) -> showChairInputDialog())
                            .setCancelable(false)
                            .show();
                } else {
                    tvChairCount.setText("Chairs: " + chairCount);
                    // When music stops, we now capture multiple sit-stand frames.
                    pauseAndProcessStop();
                }
            }
        }.start();
    }

    // Capture a frame from the camera preview and send it for chair detection.
    private void captureAndSendFrame() {
        if (camera != null) {
            try {
                camera.setOneShotPreviewCallback((data, cam) -> {
                    Camera.Parameters parameters = cam.getParameters();
                    Camera.Size size = parameters.getPreviewSize();
                    try {
                        android.graphics.YuvImage yuv = new android.graphics.YuvImage(
                                data, parameters.getPreviewFormat(), size.width, size.height, null);
                        ByteArrayOutputStream out = new ByteArrayOutputStream();
                        yuv.compressToJpeg(new android.graphics.Rect(0, 0, size.width, size.height), 80, out);
                        byte[] jpegData = out.toByteArray();
                        new Thread(() -> {
                            int chairsDetected = sendImageForDetection(jpegData);
                            if (chairsDetected != -1) {
                                synchronized (detectionResponses) {
                                    detectionResponses.add(chairsDetected);
                                }
                            }
                        }).start();
                    } catch (Exception e) {
                        Log.e(TAG, "Error converting preview frame: " + e.getMessage());
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Error capturing frame: " + e.getMessage());
            }
        }
    }

    // Send the JPEG image data to the object detection server and return the detected chair count.
    private int sendImageForDetection(byte[] imageData) {
        int detectedChairs = -1;
        String boundary = "*****" + System.currentTimeMillis() + "*****";
        String lineEnd = "\r\n";
        String twoHyphens = "--";
        try {
            URL url = new URL(OBJECT_URL);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setDoInput(true);
            connection.setDoOutput(true);
            connection.setUseCaches(false);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Connection", "Keep-Alive");
            connection.setRequestProperty("ENCTYPE", "multipart/form-data");
            connection.setRequestProperty("Content-Type", "multipart/form-data;boundary=" + boundary);
            connection.setRequestProperty("image", "uploaded_image.jpg");

            DataOutputStream dos = new DataOutputStream(connection.getOutputStream());
            dos.writeBytes(twoHyphens + boundary + lineEnd);
            dos.writeBytes("Content-Disposition: form-data; name=\"image\"; filename=\"uploaded_image.jpg\"" + lineEnd);
            dos.writeBytes("Content-Type: image/jpeg" + lineEnd);
            dos.writeBytes(lineEnd);
            dos.write(imageData);
            dos.writeBytes(lineEnd);
            dos.writeBytes(twoHyphens + boundary + twoHyphens + lineEnd);
            dos.flush();
            dos.close();

            int responseCode = connection.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK) {
                BufferedReader in = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                String inputLine;
                StringBuilder response = new StringBuilder();
                while ((inputLine = in.readLine()) != null) {
                    response.append(inputLine);
                }
                in.close();
                JSONObject jsonResponse = new JSONObject(response.toString());
                // The /object endpoint returns a key "chair_count"
                detectedChairs = jsonResponse.getInt("chair_count");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error sending image for detection: " + e.getMessage());
        }
        return detectedChairs;
    }

    // Decide the chair count using a majority vote from the detection responses.
    private int decideChairCount(ArrayList<Integer> responses) {
        if (responses.isEmpty()) return 0;
        int maxCount = 0;
        int majorityChairCount = responses.get(0);
        for (Integer num : responses) {
            int count = 0;
            for (Integer n : responses) {
                if (n.equals(num)) {
                    count++;
                }
            }
            if (count > maxCount) {
                maxCount = count;
                majorityChairCount = num;
            }
        }
        return majorityChairCount;
    }

    // Show dialog to manually enter the number of chairs.
    private void showChairInputDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Enter number of chairs");
        final EditText input = new EditText(this);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        builder.setView(input);
        builder.setPositiveButton("Start", (dialog, which) -> {
            String inputText = input.getText().toString().trim();
            if (!inputText.isEmpty()) {
                chairCount = Integer.parseInt(inputText);
                tvChairCount.setText("Chairs: " + chairCount);
                startInitialCountdown();
            }
        });
        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.cancel());
        builder.show();
    }

    // Play countdown audio from res/raw/countdown_voice.mp3.
    private void playCountdownAudio() {
        if (countdownPlayer != null) {
            countdownPlayer.release();
        }
        countdownPlayer = MediaPlayer.create(this, R.raw.countdown_voice);
        if (countdownPlayer != null) {
            countdownPlayer.start();
        }
    }

    // Initial 10-second countdown before the game starts.
    private void startInitialCountdown() {
        playCountdownAudio();
        new CountDownTimer(10000, 1000) {
            public void onTick(long millisUntilFinished) {
                tvCountdown.setText("Get Ready: " + (millisUntilFinished / 1000));
            }
            public void onFinish() {
                tvCountdown.setText("");
                startGameMusic();
            }
        }.start();
    }

    // Start playing music and schedule random stops.
    private void startGameMusic() {
        if (isPlaying) return;
        try {
            mediaPlayer.reset();
            Log.d(TAG, "Attempting to play: " + selectedMusic.toString());
            try (AssetFileDescriptor afd = getContentResolver().openAssetFileDescriptor(selectedMusic, "r")) {
                if (afd != null) {
                    mediaPlayer.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
                } else {
                    throw new Exception("Could not open file descriptor");
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
        } catch (Exception e) {
            String errorMsg = "Error: " + e.getMessage();
            Log.e(TAG, errorMsg, e);
            tvSelectedMusic.setText(errorMsg);
            isPlaying = false;
        }
    }

    // Schedule random stops between 30 and 45 seconds.
    private void scheduleRandomStopsGame() {
        int delay = (random.nextInt(16) + 30) * 1000;
        handler.postDelayed(() -> {
            if (mediaPlayer != null && mediaPlayer.isPlaying()) {
                pauseAndProcessStop();
            }
        }, delay);
    }

    // When the music stops, pause immediately and, after 2 seconds, start capturing multiple sit-stand frames.
    private void pauseAndProcessStop() {
        mediaPlayer.pause();
        tvSelectedMusic.setText("Paused! Find a chair!");
        // Wait 2 seconds for people to settle, then start sit-stand detection for 10 seconds.
        handler.postDelayed(() -> startSitStandDetectionDuringPause(), 2000);
    }

    // Capture multiple frames over 10 seconds to check sitting status.
    private void startSitStandDetectionDuringPause() {
        final int expectedSitting = chairCount;
        // Start a 10-second timer with 1-second ticks.
        new CountDownTimer(10000, 1000) {
            @Override
            public void onTick(long millisUntilFinished) {
                captureAndCheckSitStandFrame(new DetectionCallback() {
                    @Override
                    public void onDetectionResult(int sittingCount) {
                        if (sittingCount != expectedSitting && sittingCount != -1) {
                            // Mismatch detected in one frame: cancel timer and show warning.
                            cancel();
                            new AlertDialog.Builder(MainActivity.this)
                                    .setTitle("Mismatch Detected")
                                    .setMessage("Expected sitting count: " + expectedSitting + ", but detected: " + sittingCount)
                                    .setPositiveButton("OK", (dialog, which) -> continueRound())
                                    .setCancelable(false)
                                    .show();
                        }
                    }
                });
            }

            @Override
            public void onFinish() {
                // If timer completes without detecting any mismatches, continue round.
                continueRound();
            }
        }.start();
    }

    // Capture a single frame for sit-stand detection.
    private void captureAndCheckSitStandFrame(DetectionCallback callback) {
        if (camera != null) {
            try {
                camera.setOneShotPreviewCallback((data, cam) -> {
                    Camera.Parameters parameters = cam.getParameters();
                    Camera.Size size = parameters.getPreviewSize();
                    try {
                        android.graphics.YuvImage yuv = new android.graphics.YuvImage(
                                data, parameters.getPreviewFormat(), size.width, size.height, null);
                        ByteArrayOutputStream out = new ByteArrayOutputStream();
                        yuv.compressToJpeg(new android.graphics.Rect(0, 0, size.width, size.height), 80, out);
                        byte[] jpegData = out.toByteArray();
                        new Thread(() -> {
                            int sittingCount = sendImageForSitStand(jpegData);
                            runOnUiThread(() -> callback.onDetectionResult(sittingCount));
                        }).start();
                    } catch (Exception e) {
                        Log.e(TAG, "Error converting frame for sitstand: " + e.getMessage());
                        runOnUiThread(() -> callback.onDetectionResult(-1));
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Error capturing frame for sitstand: " + e.getMessage());
                callback.onDetectionResult(-1);
            }
        } else {
            callback.onDetectionResult(-1);
        }
    }

    // Send JPEG data to the sit-stand detection endpoint and return the count of "Sitting" statuses.
    private int sendImageForSitStand(byte[] imageData) {
        int sittingCount = 0;
        String boundary = "*****" + System.currentTimeMillis() + "*****";
        String lineEnd = "\r\n";
        String twoHyphens = "--";
        try {
            URL url = new URL(SITSTAND_URL);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setDoInput(true);
            connection.setDoOutput(true);
            connection.setUseCaches(false);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Connection", "Keep-Alive");
            connection.setRequestProperty("ENCTYPE", "multipart/form-data");
            connection.setRequestProperty("Content-Type", "multipart/form-data;boundary=" + boundary);
            connection.setRequestProperty("image", "uploaded_image.jpg");

            DataOutputStream dos = new DataOutputStream(connection.getOutputStream());
            dos.writeBytes(twoHyphens + boundary + lineEnd);
            dos.writeBytes("Content-Disposition: form-data; name=\"image\"; filename=\"uploaded_image.jpg\"" + lineEnd);
            dos.writeBytes("Content-Type: image/jpeg" + lineEnd);
            dos.writeBytes(lineEnd);
            dos.write(imageData);
            dos.writeBytes(lineEnd);
            dos.writeBytes(twoHyphens + boundary + twoHyphens + lineEnd);
            dos.flush();
            dos.close();

            int responseCode = connection.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK) {
                BufferedReader in = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                String inputLine;
                StringBuilder response = new StringBuilder();
                while ((inputLine = in.readLine()) != null) {
                    response.append(inputLine);
                }
                in.close();
                JSONObject jsonResponse = new JSONObject(response.toString());
                // The /sitstand endpoint returns an array "detections"
                JSONArray detections = jsonResponse.getJSONArray("detections");
                for (int i = 0; i < detections.length(); i++) {
                    JSONObject obj = detections.getJSONObject(i);
                    if (obj.optString("status").equalsIgnoreCase("Sitting")) {
                        sittingCount++;
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error sending image for sitstand: " + e.getMessage());
        }
        return sittingCount;
    }

    // Continue with the next round by starting a 10-second "get ready" countdown.
    private void continueRound() {
        playCountdownAudio();
        new CountDownTimer(10000, 1000) {
            public void onTick(long millisUntilFinished) {
                tvCountdown.setText("Resuming in: " + (millisUntilFinished / 1000));
            }
            public void onFinish() {
                tvCountdown.setText("");
                mediaPlayer.start();
                tvSelectedMusic.setText("Playing: " + getMusicTitle(selectedMusic));
                scheduleRandomStopsGame();
            }
        }.start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
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
