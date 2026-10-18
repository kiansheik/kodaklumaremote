package dev.framextv.lumaremote;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Size;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

/** Private, foreground-only Matter QR scanner. Frames never leave camera memory. */
public final class ScanMatterActivity extends Activity {
    public static final String EXTRA_SETUP_CODE = "matterSetupCode";
    public static final String EXTRA_MANUAL_ENTRY = "matterManualEntry";
    private static final int CAMERA_PERMISSION = 73;
    private final Object cameraLock = new Object();
    private final AtomicBoolean decoding = new AtomicBoolean();
    private final MatterQrDecoder decoder = new MatterQrDecoder();
    private TextureView preview;
    private TextView status;
    private Button retry;
    private HandlerThread workerThread;
    private Handler worker;
    private CameraDevice camera;
    private CameraCaptureSession session;
    private ImageReader imageReader;
    private Surface previewSurface;
    private Size frameSize;
    private int sensorOrientation;
    private int autofocusMode = CaptureRequest.CONTROL_AF_MODE_OFF;
    private volatile boolean resumed, destroyed, opening, completed;
    private volatile int cameraGeneration;
    private int openingGeneration = -1;
    private boolean permissionAsked;
    private long lastFrame;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        setResult(RESULT_CANCELED);
        getWindow().setStatusBarColor(Color.rgb(10, 17, 22));
        getWindow().setNavigationBarColor(Color.rgb(10, 17, 22));
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        buildUi();
        workerThread = new HandlerThread("Matter QR camera"); workerThread.start(); worker = new Handler(workerThread.getLooper());
    }

    private void buildUi() {
        boolean landscape = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(Color.rgb(10, 17, 22));
        root.setPadding(dp(20), dp(14), dp(20), dp(18)); root.setFitsSystemWindows(true);
        LinearLayout top = new LinearLayout(this); top.setGravity(Gravity.CENTER_VERTICAL);
        Button back = button("‹", false); back.setTextSize(30); back.setContentDescription("Back to Matter setup"); back.setOnClickListener(view -> cancel(false));
        top.addView(back, new LinearLayout.LayoutParams(dp(52), dp(52)));
        TextView title = text("Scan Matter QR", 23, Color.WHITE); title.setTypeface(Typeface.DEFAULT, Typeface.BOLD); title.setPadding(dp(12), 0, 0, 0);
        top.addView(title, new LinearLayout.LayoutParams(0, dp(52), 1)); root.addView(top);
        TextView hint = text("Point your camera at the Matter QR code shown on your TV or device.", 16, Color.rgb(185, 200, 209));
        hint.setPadding(dp(4), dp(landscape ? 8 : 20), dp(4), dp(landscape ? 10 : 18));
        FrameLayout frame = new FrameLayout(this); frame.setBackgroundColor(Color.BLACK);
        preview = new TextureView(this); frame.addView(preview, new FrameLayout.LayoutParams(-1, -1));
        frame.addView(new FinderOverlay(), new FrameLayout.LayoutParams(-1, -1));
        status = text("Keep the code inside the square.", 15, Color.rgb(190, 209, 217)); status.setGravity(Gravity.CENTER);
        status.setPadding(dp(8), dp(landscape ? 6 : 18), dp(8), dp(landscape ? 6 : 12));
        retry = button("Try camera again", true); retry.setVisibility(View.GONE); retry.setOnClickListener(view -> { permissionAsked = false; closeCamera(); ensureCamera(); });
        Button manual = button("Enter setup code instead", false); manual.setOnClickListener(view -> cancel(true));
        LinearLayout.LayoutParams manualLayout = new LinearLayout.LayoutParams(-1, dp(landscape ? 46 : 52)); manualLayout.topMargin = dp(8);
        if (landscape) {
            LinearLayout body = new LinearLayout(this); body.setOrientation(LinearLayout.HORIZONTAL);
            body.addView(frame, new LinearLayout.LayoutParams(0, -1, 1));
            LinearLayout panel = new LinearLayout(this); panel.setOrientation(LinearLayout.VERTICAL); panel.setPadding(dp(16), 0, 0, 0);
            panel.addView(hint); panel.addView(status, new LinearLayout.LayoutParams(-1, 0, 1));
            panel.addView(retry, new LinearLayout.LayoutParams(-1, dp(46))); panel.addView(manual, manualLayout);
            body.addView(panel, new LinearLayout.LayoutParams(dp(Math.min(290, getResources().getConfiguration().screenWidthDp * .42f)), -1));
            root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        } else {
            root.addView(hint); root.addView(frame, new LinearLayout.LayoutParams(-1, 0, 1));
            root.addView(status, new LinearLayout.LayoutParams(-1, dp(84)));
            root.addView(retry, new LinearLayout.LayoutParams(-1, dp(50))); root.addView(manual, manualLayout);
        }
        setContentView(root);
        preview.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) { ensureCamera(); }
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) { transformPreview(); }
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) { closeCamera(); return true; }
            @Override public void onSurfaceTextureUpdated(SurfaceTexture texture) { }
        });
    }
    private TextView text(String value, float size, int color) { TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(color); return view; }
    private Button button(String label, boolean primary) {
        Button button = new Button(this); button.setText(label); button.setAllCaps(false); button.setTextSize(16);
        button.setTextColor(primary ? Color.rgb(10, 24, 23) : Color.rgb(220, 234, 240));
        GradientDrawable background = new GradientDrawable(); background.setCornerRadius(dp(12));
        background.setColor(primary ? Color.rgb(111, 225, 195) : Color.rgb(28, 43, 52)); button.setBackground(background); return button;
    }
    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    @Override protected void onResume() { super.onResume(); resumed = true; ensureCamera(); }
    @Override protected void onPause() { resumed = false; closeCamera(); super.onPause(); }
    @Override protected void onDestroy() { destroyed = true; closeCamera(); stopWorkerIfClosed(); super.onDestroy(); }
    @Override public void onBackPressed() { cancel(false); }
    private void cancel(boolean manual) {
        if (completed) return; completed = true;
        setResult(RESULT_CANCELED, new Intent().putExtra(EXTRA_MANUAL_ENTRY, manual)); finish();
    }
    private void scanned(String setupCode) {
        runOnUiThread(() -> {
            if (completed || destroyed || !resumed) return;
            completed = true; setResult(RESULT_OK, new Intent().putExtra(EXTRA_SETUP_CODE, setupCode)); finish();
        });
    }
    private void unavailable(String message) {
        runOnUiThread(() -> {
            if (destroyed || completed) return;
            status.setText(message); retry.setVisibility(Build.VERSION.SDK_INT >= 24 ? View.VISIBLE : View.GONE);
        });
    }
    private void ensureCamera() {
        if (!resumed || destroyed || completed || opening || !preview.isAvailable()) return;
        if (Build.VERSION.SDK_INT < 24) { unavailable("Camera scanning needs Android 7 or newer. Enter the setup code instead."); return; }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            if (!permissionAsked) { permissionAsked = true; requestPermissions(new String[] {Manifest.permission.CAMERA}, CAMERA_PERMISSION); }
            else unavailable("Camera access is needed to scan. You can also enter the setup code instead.");
            return;
        }
        synchronized (cameraLock) { if (camera != null) return; }
        retry.setVisibility(View.GONE); status.setText("Keep the Matter code inside the square.");
        final int generation = cameraGeneration;
        synchronized (cameraLock) { opening = true; openingGeneration = generation; }
        worker.post(() -> openCamera(generation));
    }
    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != CAMERA_PERMISSION) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) ensureCamera();
        else unavailable("Camera access was denied. Enter the setup code instead, or try again.");
    }
    private boolean current(int generation) { return !destroyed && !completed && resumed && generation == cameraGeneration; }
    private void openingFinished(int generation) {
        synchronized (cameraLock) { if (openingGeneration == generation) { opening = false; openingGeneration = -1; } }
        stopWorkerIfClosed();
    }
    private void resumeAfterStaleOpen(int generation) {
        openingFinished(generation);
        // Pausing while openCamera is pending must not leave the next resume
        // waiting forever for a camera that was already discarded.
        runOnUiThread(this::ensureCamera);
    }
    private void cameraFailed(CameraDevice device, int generation, String message) {
        openingFinished(generation);
        synchronized (cameraLock) {
            if (generation != cameraGeneration || (camera != null && device != null && camera != device)) {
                if (device != null) device.close();
                runOnUiThread(this::ensureCamera); return;
            }
            closeCamera();
            if (device != null) device.close();
        }
        unavailable(message);
    }

    private void openCamera(int generation) {
        if (!current(generation)) { resumeAfterStaleOpen(generation); return; }
        try {
            CameraManager manager = (CameraManager) getSystemService(CAMERA_SERVICE);
            String chosen = null; CameraCharacteristics characteristics = null;
            for (String id : manager.getCameraIdList()) {
                CameraCharacteristics value = manager.getCameraCharacteristics(id);
                if (chosen == null) { chosen = id; characteristics = value; }
                Integer facing = value.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) { chosen = id; characteristics = value; break; }
            }
            if (chosen == null || characteristics == null) throw new CameraAccessException(CameraAccessException.CAMERA_ERROR);
            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            frameSize = selectSize(map); if (frameSize == null) throw new IllegalStateException("Unsupported camera output");
            Integer orientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION); sensorOrientation = orientation == null ? 0 : orientation;
            autofocusMode = CaptureRequest.CONTROL_AF_MODE_OFF;
            int[] modes = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
            if (modes != null) {
                for (int mode : modes) if (mode == CaptureRequest.CONTROL_AF_MODE_AUTO) autofocusMode = mode;
                for (int mode : modes) if (mode == CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO) autofocusMode = mode;
                for (int mode : modes) if (mode == CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE) autofocusMode = mode;
            }
            if (!current(generation)) { resumeAfterStaleOpen(generation); return; }
            // Permission is checked immediately before the camera API; revocation
            // during this call is still handled by the SecurityException branch.
            if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) throw new SecurityException();
            manager.openCamera(chosen, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice device) {
                    openingFinished(generation);
                    synchronized (cameraLock) {
                        if (!current(generation)) { device.close(); resumeAfterStaleOpen(generation); return; }
                        camera = device;
                    }
                    createSession(device, generation);
                }
                @Override public void onDisconnected(CameraDevice device) { cameraFailed(device, generation, "The camera disconnected. Try again or enter the setup code."); }
                @Override public void onError(CameraDevice device, int error) { cameraFailed(device, generation, "The camera is unavailable. Try again or enter the setup code."); }
            }, worker);
        } catch (CameraAccessException | SecurityException | IllegalArgumentException | IllegalStateException ignored) {
            cameraFailed(null, generation, "The camera is unavailable. Try again or enter the setup code.");
        }
    }
    private static Size selectSize(StreamConfigurationMap map) {
        if (map == null) return null;
        Size[] images = map.getOutputSizes(ImageFormat.YUV_420_888), textures = map.getOutputSizes(SurfaceTexture.class);
        if (images == null || textures == null) return null;
        Size best = null; long score = Long.MAX_VALUE;
        for (Size size : images) {
            if (size.getWidth() > 1920 || size.getHeight() > 1440 || size.getWidth() < 320 || size.getHeight() < 240) continue;
            boolean shared = false; for (Size output : textures) if (size.equals(output)) { shared = true; break; }
            if (!shared) continue;
            long distance = Math.abs((long) size.getWidth() * size.getHeight() - 1280L * 720);
            if (distance < score) { score = distance; best = size; }
        }
        return best;
    }
    private void createSession(CameraDevice device, int generation) {
        try {
            SurfaceTexture texture = preview.getSurfaceTexture(); if (texture == null || !current(generation)) return;
            texture.setDefaultBufferSize(frameSize.getWidth(), frameSize.getHeight());
            Surface surface = new Surface(texture);
            ImageReader images = ImageReader.newInstance(frameSize.getWidth(), frameSize.getHeight(), ImageFormat.YUV_420_888, 2);
            images.setOnImageAvailableListener(reader -> decodeImage(reader, generation), worker);
            synchronized (cameraLock) {
                if (!current(generation) || device != camera) { surface.release(); images.close(); return; }
                previewSurface = surface; imageReader = images;
            }
            runOnUiThread(this::transformPreview);
            CaptureRequest.Builder request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            request.addTarget(surface); request.addTarget(images.getSurface());
            request.set(CaptureRequest.CONTROL_AF_MODE, autofocusMode);
            request.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
            device.createCaptureSession(Arrays.asList(surface, images.getSurface()), new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(CameraCaptureSession capture) {
                    synchronized (cameraLock) {
                        if (!current(generation) || camera != device) { capture.close(); return; }
                        session = capture;
                    }
                    try {
                        capture.setRepeatingRequest(request.build(), null, worker);
                        if (autofocusMode == CaptureRequest.CONTROL_AF_MODE_AUTO) {
                            request.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START);
                            capture.capture(request.build(), null, worker);
                            request.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE);
                        }
                    }
                    catch (CameraAccessException | IllegalStateException | IllegalArgumentException ignored) { cameraFailed(device, generation, "Camera preview could not start. Try again or enter the setup code."); }
                }
                @Override public void onConfigureFailed(CameraCaptureSession capture) { capture.close(); cameraFailed(device, generation, "Camera preview could not start. Try again or enter the setup code."); }
            }, worker);
        } catch (CameraAccessException | IllegalStateException | IllegalArgumentException ignored) { cameraFailed(device, generation, "Camera preview could not start. Try again or enter the setup code."); }
    }
    private void decodeImage(ImageReader reader, int generation) {
        boolean acquired = false;
        try {
            MatterQrDecoder.GraySource source;
            synchronized (cameraLock) {
                Image image = reader.acquireLatestImage();
                if (image == null) return;
                try {
                    if (!current(generation) || SystemClock.elapsedRealtime() - lastFrame < 350 || !decoding.compareAndSet(false, true)) return;
                    acquired = true; lastFrame = SystemClock.elapsedRealtime();
                    Image.Plane plane = image.getPlanes()[0]; Rect crop = image.getCropRect();
                    int side = Math.min(crop.width(), crop.height()) * 9 / 10;
                    int left = crop.left + (crop.width() - side) / 2, top = crop.top + (crop.height() - side) / 2;
                    source = MatterQrDecoder.copyPlane(plane.getBuffer(), plane.getRowStride(), plane.getPixelStride(), image.getWidth(), image.getHeight(), left, top, side, side);
                } finally { image.close(); }
            }
            // Work only on our small copy outside the camera lock. Closing an
            // ImageReader must never invalidate a plane currently being read.
            String value = decoder.decode(source);
            if (value != null && current(generation)) scanned(value);
        } catch (IllegalArgumentException | IllegalStateException ignored) { /* A closing reader may invalidate its last frame. */ }
        finally { if (acquired) decoding.set(false); }
    }
    private void transformPreview() {
        if (frameSize == null || preview.getWidth() == 0 || preview.getHeight() == 0) return;
        int display = getWindowManager().getDefaultDisplay().getRotation();
        int degrees = display == Surface.ROTATION_90 ? 90 : display == Surface.ROTATION_180 ? 180 : display == Surface.ROTATION_270 ? 270 : 0;
        int rotation = (sensorOrientation - degrees + 360) % 360;
        float width = preview.getWidth(), height = preview.getHeight();
        boolean swap = rotation == 90 || rotation == 270;
        float outputWidth = swap ? frameSize.getHeight() : frameSize.getWidth(), outputHeight = swap ? frameSize.getWidth() : frameSize.getHeight();
        float scale = Math.max(width / outputWidth, height / outputHeight);
        Matrix transform = new Matrix();
        // TextureView first stretches the raw camera buffer to the view bounds.
        // Undo that stretch, rotate the sensor image, then fill the view uniformly.
        transform.postScale(frameSize.getWidth() / width, frameSize.getHeight() / height, width / 2, height / 2);
        transform.postRotate(rotation, width / 2, height / 2); transform.postScale(scale, scale, width / 2, height / 2);
        preview.setTransform(transform);
    }
    private void closeCamera() {
        synchronized (cameraLock) {
            cameraGeneration++;
            if (session != null) { session.close(); session = null; }
            if (camera != null) { camera.close(); camera = null; }
            if (imageReader != null) { imageReader.close(); imageReader = null; }
            if (previewSurface != null) { previewSurface.release(); previewSurface = null; }
        }
    }
    private void stopWorkerIfClosed() {
        if (destroyed && !opening && workerThread != null) { workerThread.quitSafely(); workerThread = null; }
    }

    private final class FinderOverlay extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        FinderOverlay() { super(ScanMatterActivity.this); setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO); }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas); float side = Math.min(getWidth(), getHeight()) * .74f;
            float left = (getWidth() - side) / 2, top = (getHeight() - side) / 2;
            paint.setColor(0x66070B11); paint.setStyle(Paint.Style.FILL);
            canvas.drawRect(0, 0, getWidth(), top, paint); canvas.drawRect(0, top + side, getWidth(), getHeight(), paint);
            canvas.drawRect(0, top, left, top + side, paint); canvas.drawRect(left + side, top, getWidth(), top + side, paint);
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(3)); paint.setColor(Color.rgb(111, 225, 195));
            canvas.drawRoundRect(new RectF(left, top, left + side, top + side), dp(12), dp(12), paint);
        }
    }
}
