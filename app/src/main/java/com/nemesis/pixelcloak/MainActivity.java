package com.nemesis.pixelcloak;

import android.Manifest;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Color;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Log;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetector;
import com.google.mlkit.vision.face.FaceDetectorOptions;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.math.BigInteger;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    private static final int MAX_PROCESS_DIM = 1024;

    private ImageView preview;
    private MaterialButton pickBtn;
    private MaterialButton obfuscateBtn;

    private Bitmap loadedBitmap;
    private Bitmap lastBitmap;

    private final SecureRandom secureRandom = new SecureRandom();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private Future<?> runningTask = null;
    private final AtomicBoolean isCancelled = new AtomicBoolean(false);

    private ActivityResultLauncher<Intent> pickLauncher;
    private ActivityResultLauncher<String[]> permissionLauncher;

    private boolean useEmojiMode = false;
    private String selectedEmoji = "😶";
    private MaterialButton modeButton;
    private MaterialButton emojiButton;

    private MaterialButton rotateBtn;
    private int userRotation = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        preview = findViewById(R.id.previewImage);
        pickBtn = findViewById(R.id.pickButton);
        obfuscateBtn = findViewById(R.id.obfuscateButton);
        modeButton = findViewById(R.id.modeButton);
        emojiButton = findViewById(R.id.emojiButton);
        rotateBtn = findViewById(R.id.rotateButton);

        modeButton.setOnClickListener(v -> {
            useEmojiMode = !useEmojiMode;
            if (useEmojiMode) {
                modeButton.setText("Mode: Emoji");
                emojiButton.setVisibility(View.VISIBLE);
            } else {
                modeButton.setText("Mode: Black Box");
                emojiButton.setVisibility(View.GONE);
            }
        });

        emojiButton.setOnClickListener(v -> showEmojiPicker());

        rotateBtn.setOnClickListener(v -> {
            if (lastBitmap == null) return;

            userRotation = (userRotation + 90) % 360;

            Matrix m = new Matrix();
            m.postRotate(userRotation);

            Bitmap rotated = Bitmap.createBitmap(
                    lastBitmap,
                    0, 0,
                    lastBitmap.getWidth(),
                    lastBitmap.getHeight(),
                    m,
                    true
            );

            lastBitmap = rotated;
            preview.setImageBitmap(rotated);
        });

        permissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(),
                result -> {
                    Boolean read = result.getOrDefault(Manifest.permission.READ_EXTERNAL_STORAGE, false);
                    Boolean write = result.getOrDefault(Manifest.permission.WRITE_EXTERNAL_STORAGE, false);
                    if (!read || !write) {
                        Toast.makeText(this, "Storage permission recommended for older Android when saving images", Toast.LENGTH_SHORT).show();
                    }
                }
        );

        pickLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                res -> {
                    if (res.getResultCode() == RESULT_OK && res.getData() != null) {
                        Uri uri = res.getData().getData();
                        loadBitmapFromUri(uri);
                    }
                }
        );

        pickBtn.setOnClickListener(v -> pickImage());

        obfuscateBtn.setOnClickListener(v -> {
            if (loadedBitmap == null && lastBitmap == null) {
                Toast.makeText(this, "Pick an image first", Toast.LENGTH_SHORT).show();
                return;
            }
            if (runningTask != null && !runningTask.isDone()) {
                Toast.makeText(this, "Already processing", Toast.LENGTH_SHORT).show();
                return;
            }
            isCancelled.set(false);
            setBusy(true);

            runningTask = executor.submit(() -> {
                try {
                    final Bitmap source = (lastBitmap != null) ? lastBitmap : loadedBitmap;
                    Bitmap small = downscaleForProcessing(source, MAX_PROCESS_DIM);

                    double strength = 0.6;
                    double patchDensity = 0.06;
                    int blockSize = 8;
                    double targetSsim = 0.95;
                    int maxIters = 6;
                    int jpegQuality = 60;

                    final float[] orig = bitmapToFloatArray(small);
                    Result r = strongPerturbPreserveBitmap(orig, small.getWidth(), small.getHeight(),
                            strength, 3, targetSsim, maxIters, patchDensity, blockSize, jpegQuality);

                    if (isCancelled.get()) {
                        runOnUiThread(() -> {
                            setBusy(false);
                            Toast.makeText(MainActivity.this, "Processing cancelled", Toast.LENGTH_SHORT).show();
                        });
                        return;
                    }

                    Bitmap perturbed = floatArrayToBitmap(r.rgb, r.w, r.h);

                    Bitmap censored = useEmojiMode
                            ? censorFacesWithEmoji(perturbed, selectedEmoji)
                            : censorFacesWithBlack(perturbed);


                    Bitmap out;
                    if (censored.getWidth() != source.getWidth() || censored.getHeight() != source.getHeight()) {
                        out = Bitmap.createScaledBitmap(censored, source.getWidth(), source.getHeight(), true);
                    } else {
                        out = censored;
                    }

                    lastBitmap = out;

                    runOnUiThread(() -> {
                        preview.setImageBitmap(out);
                        setBusy(false);
                        Toast.makeText(MainActivity.this, String.format("Obfuscation done (SSIM=%.4f) — saving...", r.ssim), Toast.LENGTH_LONG).show();
                    });

                    final Bitmap toSave = out;
                    executor.submit(() -> {

                        String filename = generateRandomNumericFilename();
                        boolean ok = saveBitmapToGallery(toSave, filename);
                        runOnUiThread(() -> {
                            if (ok) Toast.makeText(MainActivity.this, "Image saved to gallery: " + filename, Toast.LENGTH_SHORT).show();
                            else Toast.makeText(MainActivity.this, "Failed to save image", Toast.LENGTH_SHORT).show();
                        });
                    });

                } catch (OutOfMemoryError oom) {
                    Log.e(TAG, "OOM during obfuscation", oom);
                    runOnUiThread(() -> {
                        setBusy(false);
                        Toast.makeText(MainActivity.this, "Processing ran out of memory", Toast.LENGTH_LONG).show();
                    });
                } catch (Exception ex) {
                    Log.e(TAG, "Obfuscation error", ex);
                    runOnUiThread(() -> {
                        setBusy(false);
                        Toast.makeText(MainActivity.this, "Obfuscation failed", Toast.LENGTH_SHORT).show();
                    });
                }
            });
        });

        requestPermissionsIfNeeded();
    }

    private void showEmojiPicker() {
        final String[] emojis = new String[]{
                "😈","😶","😐","😑","😁","😂","😎","🤖","👽","😡","😱","🥶","🤡","💀",
                "🙂","🙃","😉","😊","😇","🥰","😍","🤩","😘","😗","😙","😚","😋","😛",
                "😜","🤪","😝","🤑","🤗","🤭","🤫","🤔","🤐","🤨","😏","😒","🙄","😬",
                "🤥","😌","😔","😪","🤤","😴","😷","🤒","🤕","🤧","🥵","🥴","😵",
                "🐵","🐱","🐸","🐲","👹","🐻","🦊","🐯","🦁","🤠","🥳",
                "🤓","🧐"
        };

        GridView gridView = new GridView(this);
        gridView.setNumColumns(6);
        gridView.setAdapter(new ArrayAdapter<String>(this, R.layout.item_emoji, R.id.emoji_text, emojis));

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Pick Emoji");
        builder.setView(gridView);

        AlertDialog dialog = builder.create();

        gridView.setOnItemClickListener((parent, view, position, id) -> {
            selectedEmoji = emojis[position];
            Toast.makeText(this, "Emoji selected: " + selectedEmoji, Toast.LENGTH_SHORT).show();
            dialog.dismiss();
        });

        dialog.show();
    }

    private String generateRandomNumericFilename() {

        byte[] bytes = new byte[12];
        secureRandom.nextBytes(bytes);
        BigInteger bi = new BigInteger(1, bytes);

        return bi.toString() + ".jpg";
    }

    private void requestPermissionsIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                permissionLauncher.launch(new String[]{
                        Manifest.permission.READ_EXTERNAL_STORAGE,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                });
            }
        }
    }

    private void pickImage() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("image/*");
        pickLauncher.launch(Intent.createChooser(intent, "Select image"));
    }

    private void loadBitmapFromUri(Uri uri) {
        try {
            Bitmap b = MediaStore.Images.Media.getBitmap(this.getContentResolver(), uri);
            if (b == null) throw new IllegalStateException("Loaded bitmap is null");

            int rotation = getExifRotation(uri);
            Matrix matrix = new Matrix();
            matrix.postRotate(rotation);

            Bitmap rotated = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), matrix, true);

            loadedBitmap = rotated.copy(Bitmap.Config.ARGB_8888, true);
            lastBitmap  = loadedBitmap;

            preview.setImageBitmap(loadedBitmap);

        } catch (Exception e) {
            Log.e(TAG, "Failed to load image", e);
            Toast.makeText(this, "Failed to load image", Toast.LENGTH_SHORT).show();
        }
    }

    private int getExifRotation(Uri uri) {
        try {
            InputStream in = getContentResolver().openInputStream(uri);
            ExifInterface exif = new ExifInterface(in);

            int orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);

            switch (orientation) {
                case ExifInterface.ORIENTATION_ROTATE_90: return 90;
                case ExifInterface.ORIENTATION_ROTATE_180: return 180;
                case ExifInterface.ORIENTATION_ROTATE_270: return 270;
                default: return 0;
            }
        } catch (Exception e) {
            return 0;
        }
    }

    @MainThread
    private void setBusy(boolean busy) {
        runOnUiThread(() -> {
            pickBtn.setEnabled(!busy);
            obfuscateBtn.setEnabled(!busy);
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }

    private static Bitmap downscaleForProcessing(Bitmap src, int maxDim) {
        int w = src.getWidth(), h = src.getHeight();
        int max = Math.max(w, h);
        if (max <= maxDim) return src;
        double scale = (double) maxDim / (double) max;
        int nw = (int) Math.max(1, Math.round(w * scale));
        int nh = (int) Math.max(1, Math.round(h * scale));
        return Bitmap.createScaledBitmap(src, nw, nh, true);
    }

    private static float[] bitmapToFloatArray(Bitmap bmp) {
        final int w = bmp.getWidth();
        final int h = bmp.getHeight();
        final int n = w * h;
        int[] pixels = new int[n];
        bmp.getPixels(pixels, 0, w, 0, 0, w, h);
        float[] out = new float[n * 3];
        for (int i = 0; i < n; i++) {
            int c = pixels[i];
            out[i * 3] = (c >> 16) & 0xFF;
            out[i * 3 + 1] = (c >> 8) & 0xFF;
            out[i * 3 + 2] = c & 0xFF;
        }
        return out;
    }

    private static Bitmap floatArrayToBitmap(float[] arr, int w, int h) {
        int n = w * h;
        int[] outPixels = new int[n];
        for (int i = 0; i < n; i++) {
            int r = (int) Math.max(0, Math.min(255, Math.round(arr[i * 3])));
            int g = (int) Math.max(0, Math.min(255, Math.round(arr[i * 3 + 1])));
            int b = (int) Math.max(0, Math.min(255, Math.round(arr[i * 3 + 2])));
            outPixels[i] = android.graphics.Color.rgb(r, g, b);
        }
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        bmp.setPixels(outPixels, 0, w, 0, 0, w, h);
        return bmp;
    }

    private float[] secureUniformArrayFloat(int size) {
        if (size <= 0) return new float[0];
        byte[] raw = new byte[8 * size];
        secureRandom.nextBytes(raw);
        float[] out = new float[size];
        ByteBuffer bb = ByteBuffer.wrap(raw);
        for (int i = 0; i < size; i++) {
            long v = bb.getLong();
            double u = (v & 0x7FFFFFFFFFFFFFFFL) / (double) (0x1L << 63);
            if (u == 0.0) u = Double.MIN_VALUE;
            out[i] = (float) u;
        }
        return out;
    }

    private double secureUniformFloat(double a, double b) {
        return secureRandom.nextDouble() * (b - a) + a;
    }

    private int secureRandInt(int a, int b) {
        return secureRandom.nextInt((b - a) + 1) + a;
    }

    private double secureRandomDouble() {
        return secureRandom.nextDouble();
    }

    private float[] secureNormalArray(int size, float mean, float std) {
        if (size <= 0) return new float[0];
        int pairs = (size + 1) / 2;
        float[] u1 = secureUniformArrayFloat(pairs);
        float[] u2 = secureUniformArrayFloat(pairs);
        float[] z = new float[pairs * 2];
        for (int i = 0; i < pairs; i++) {
            double r = Math.sqrt(-2.0 * Math.log(Math.max(u1[i], 1e-12)));
            double theta = 2.0 * Math.PI * u2[i];
            z[2 * i] = (float) (r * Math.cos(theta));
            z[2 * i + 1] = (float) (r * Math.sin(theta));
        }
        float[] out = new float[size];
        System.arraycopy(z, 0, out, 0, size);
        for (int i = 0; i < size; i++) out[i] = out[i] * std + mean;
        return out;
    }

    private int[] securePermutation(int n) {
        float[] keys = secureUniformArrayFloat(n);
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i;
        Arrays.sort(order, (i1, i2) -> Float.compare(keys[i1], keys[i2]));
        int[] out = new int[n];
        for (int i = 0; i < n; i++) out[i] = order[i];
        return out;
    }

    private float[] blockPixelShuffleLayerFloat(float[] layerRgb, int w, int h, int blockSize, double intensity, float[] maskGray) {
        float[] out = layerRgb.clone();
        int channels = 3;
        for (int y = 0; y < h; y += blockSize) {
            for (int x = 0; x < w; x += blockSize) {
                int by = Math.min(blockSize, h - y);
                int bx = Math.min(blockSize, w - x);
                ArrayList<Integer> indices = new ArrayList<>(by * bx);
                for (int yy = 0; yy < by; yy++) for (int xx = 0; xx < bx; xx++)
                    indices.add(((y + yy) * w + (x + xx)) * channels);
                if (indices.isEmpty()) continue;
                double p = intensity;
                if (maskGray != null) {
                    double sum = 0;
                    for (int yy = 0; yy < by; yy++)
                        for (int xx = 0; xx < bx; xx++) sum += maskGray[(y + yy) * w + (x + xx)];
                    double mean = (sum / (by * bx)) / 255.0;
                    if (mean > 0.1) p = intensity + 0.4;
                }
                int k = (int) (p * indices.size());
                if (k <= 1) continue;
                int n = indices.size();
                int[] perm = securePermutation(n);
                int[] selected = new int[k];
                for (int i = 0; i < k; i++) selected[i] = perm[i];

                float[] keys = secureUniformArrayFloat(k);
                Integer[] order = new Integer[k];
                for (int i = 0; i < k; i++) order[i] = i;
                Arrays.sort(order, (i1, i2) -> Float.compare(keys[i1], keys[i2]));
                float[][] tmp = new float[k][3];
                for (int i = 0; i < k; i++) {
                    int idx = indices.get(selected[i]);
                    tmp[i][0] = out[idx];
                    tmp[i][1] = out[idx + 1];
                    tmp[i][2] = out[idx + 2];
                }
                for (int i = 0; i < k; i++) {
                    int destPos = indices.get(selected[order[i]]);
                    out[destPos] = tmp[i][0];
                    out[destPos + 1] = tmp[i][1];
                    out[destPos + 2] = tmp[i][2];
                }
            }
        }
        return out;
    }

    private float[] addNoiseLayerFloat(float[] layerRgb, int w, int h, double sigma, double saltProb) {
        float[] out = layerRgb.clone();
        int channels = 3;
        int total = w * h * channels;
        if (sigma > 0) {
            float[] gauss = secureNormalArray(total, 0f, (float) sigma);
            for (int i = 0; i < total; i++) {
                float v = out[i] + gauss[i];
                out[i] = Math.max(0f, Math.min(255f, v));
            }
        }
        if (saltProb > 0) {
            float[] uni = secureUniformArrayFloat(w * h);
            for (int i = 0; i < uni.length; i++) {
                if (uni[i] < saltProb) {
                    int val = secureRandom.nextBoolean() ? 255 : 0;
                    int base = i * channels;
                    out[base] = val;
                    out[base + 1] = val;
                    out[base + 2] = val;
                }
            }
        }
        return out;
    }

    private float[] overlayPatchesLayerFloat(float[] layerRgb, int w, int h, int patchSize, double density, double strength, float[] maskGray) {
        float[] out = layerRgb.clone();
        int numPatches = Math.max(1, (int) (w * h * density / (patchSize * patchSize) * 8));
        for (int i = 0; i < numPatches; i++) {
            if (isCancelled.get()) return out;
            int x = secureRandInt(0, Math.max(0, w - patchSize));
            int y = secureRandInt(0, Math.max(0, h - patchSize));
            if (maskGray != null) {
                double sum = 0;
                for (int yy = y; yy < Math.min(y + patchSize, h); yy++)
                    for (int xx = x; xx < Math.min(x + patchSize, w); xx++) sum += maskGray[yy * w + xx];
                if (sum < (patchSize * patchSize) / 6.0 * 255.0 && secureRandomDouble() > 0.4) continue;
            }
            boolean solid = secureRandom.nextBoolean();
            float[] patch = new float[patchSize * patchSize * 3];
            if (solid) {
                byte[] color = new byte[3];
                secureRandom.nextBytes(color);
                for (int yy = 0; yy < patchSize; yy++)
                    for (int xx = 0; xx < patchSize; xx++) {
                        int k = (yy * patchSize + xx) * 3;
                        patch[k] = (color[0] & 0xFF);
                        patch[k + 1] = (color[1] & 0xFF);
                        patch[k + 2] = (color[2] & 0xFF);
                    }
            } else {
                byte[] raw = new byte[patchSize * patchSize * 3];
                secureRandom.nextBytes(raw);
                for (int k = 0; k < raw.length; k++) patch[k] = raw[k] & 0xFF;
            }
            double alpha = secureUniformFloat(0.4, 1.0) * strength;
            for (int yy = 0; yy < patchSize; yy++)
                for (int xx = 0; xx < patchSize; xx++) {
                    int yy2 = Math.min(h - 1, y + yy);
                    int xx2 = Math.min(w - 1, x + xx);
                    int idx = (yy2 * w + xx2) * 3;
                    int pidx = (yy * patchSize + xx) * 3;
                    for (int c = 0; c < 3; c++) {
                        out[idx + c] = (float) ((1.0 - alpha) * out[idx + c] + alpha * patch[pidx + c]);
                    }
                }
        }
        return out;
    }

    private float[] computeSaliencyMaskSimple(float[] rgb, int w, int h) {
        float[] gray = toGrayscale(rgb, w, h);
        float[] out = new float[w * h];

        for (int y = 1; y < h - 1; y++) {
            for (int x = 1; x < w - 1; x++) {
                int idx = y * w + x;
                float gx = -gray[(y - 1) * w + (x - 1)] - 2f * gray[y * w + (x - 1)] - gray[(y + 1) * w + (x - 1)]
                        + gray[(y - 1) * w + (x + 1)] + 2f * gray[y * w + (x + 1)] + gray[(y + 1) * w + (x + 1)];
                float gy = -gray[(y - 1) * w + (x - 1)] - 2f * gray[(y - 1) * w + x] - gray[(y - 1) * w + (x + 1)]
                        + gray[(y + 1) * w + (x - 1)] + 2f * gray[(y + 1) * w + x] + gray[(y + 1) * w + (x + 1)];
                out[idx] = (float) Math.min(255.0, Math.hypot(gx, gy));
            }
        }

        float min = Float.MAX_VALUE, max = -Float.MAX_VALUE;
        for (int i = 0; i < out.length; i++) {
            if (out[i] < min) min = out[i];
            if (out[i] > max) max = out[i];
        }
        if (max > min) {
            float range = max - min;
            for (int i = 0; i < out.length; i++) {
                out[i] = ((out[i] - min) / range) * 255f;
            }
        }
        return out;
    }

    private static class Result {
        public float[] rgb;
        public int w, h;
        public double ssim;
        public Result(float[] r, int w, int h, double s) { this.rgb = r; this.w = w; this.h = h; this.ssim = s; }
    }

    private Result strongPerturbPreserveBitmap(float[] origRgb, int w, int h,
                                               double strength,
                                               int levels,
                                               double targetSsim,
                                               int maxIters,
                                               double patchDensity,
                                               int blockSize,
                                               int jpegQuality) {

        float[] salMask = computeSaliencyMaskSimple(origRgb, w, h);

        float[] combined = salMask;

        ArrayList<float[]> lpOrig = new ArrayList<>();
        lpOrig.add(origRgb);

        ArrayList<float[]> maskLayers = new ArrayList<>();
        maskLayers.add(combined);

        int attempt = 0;
        double curScale = strength;
        float[] bestImg = origRgb.clone();
        double bestSsim = -1.0;

        while (attempt < maxIters && !isCancelled.get()) {
            attempt++;
            ArrayList<float[]> lpWork = new ArrayList<>();
            for (float[] l : lpOrig) lpWork.add(l.clone());

            for (int li = 0; li < lpWork.size(); li++) {
                float[] layer = lpWork.get(li);
                float[] m = maskLayers.get(li);

                layer = blockPixelShuffleLayerFloat(layer, w, h, Math.max(4, (int) (blockSize * (1.0 - li * 0.2))),
                        0.25 + 0.5 * curScale, m);

                layer = overlayPatchesLayerFloat(layer, w, h, Math.max(6, (int) (8 * (1 + li * 0.2))),
                        patchDensity * (1 + curScale), 0.35 + 0.7 * curScale, m);

                double sigma = (6.0 * curScale) * (1.0 - 0.18 * li);
                layer = addNoiseLayerFloat(layer, w, h, sigma, 0.0006 * (1 + curScale));

                float[] origLayer = lpOrig.get(li);
                float[] mixed = new float[layer.length];
                for (int k = 0; k < layer.length; k++) {
                    mixed[k] = (float) ((1.0 - 0.15 * curScale) * origLayer[k] + (0.15 * curScale) * layer[k]);
                }
                lpWork.set(li, mixed);
            }

            float[] cand = lpWork.get(0);

            for (int i = 0; i < w * h && !isCancelled.get(); i++) {
                int idx = i * 3;
                float r = cand[idx];
                float g = cand[idx + 1];
                float b = cand[idx + 2];
                float[] hsv = rgbToHsv(r, g, b);
                double sMult = 1.0 + (secureUniformFloat(-0.03, 0.03) * curScale);
                double vMult = 1.0 + (secureUniformFloat(-0.02, 0.02) * curScale);
                hsv[1] = (float) Math.max(0.0, Math.min(1.0, hsv[1] * sMult));
                hsv[2] = (float) Math.max(0.0, Math.min(255.0, hsv[2] * vMult));
                int[] rgb2 = hsvToRgbInt(hsv);
                cand[idx] = rgb2[0];
                cand[idx + 1] = rgb2[1];
                cand[idx + 2] = rgb2[2];
            }

            float[] origGray = toGrayscale(origRgb, w, h);
            float[] candGray = toGrayscale(cand, w, h);
            double curSsim = ssimIndexFloatArrays(origGray, candGray, w, h);
            if (curSsim > bestSsim) {
                bestSsim = curSsim;
                bestImg = cand.clone();
            }
            if (curSsim >= targetSsim || curScale <= 0.02 || isCancelled.get()) {
                break;
            }
            curScale *= 0.72;
        }

        return new Result(bestImg, w, h, bestSsim);
    }

    private static double ssimIndexFloatArrays(float[] aGray, float[] bGray, int w, int h) {
        int ksize = 11;
        float sigma = 1.5f;
        float[] mu1 = gaussianBlurGray(aGray, w, h, ksize, sigma);
        float[] mu2 = gaussianBlurGray(bGray, w, h, ksize, sigma);

        float[] aSq = new float[w * h];
        float[] bSq = new float[w * h];
        float[] ab = new float[w * h];
        for (int i = 0; i < w * h; i++) {
            aSq[i] = aGray[i] * aGray[i];
            bSq[i] = bGray[i] * bGray[i];
            ab[i] = aGray[i] * bGray[i];
        }
        float[] sigma1Sq = gaussianBlurGray(aSq, w, h, ksize, sigma);
        float[] sigma2Sq = gaussianBlurGray(bSq, w, h, ksize, sigma);
        float[] sigma12 = gaussianBlurGray(ab, w, h, ksize, sigma);

        for (int i = 0; i < w * h; i++) {
            sigma1Sq[i] -= mu1[i] * mu1[i];
            sigma2Sq[i] -= mu2[i] * mu2[i];
            sigma12[i] -= mu1[i] * mu2[i];
        }

        double C1 = Math.pow(0.01 * 255.0, 2);
        double C2 = Math.pow(0.03 * 255.0, 2);

        double meanSsim = 0.0;
        for (int i = 0; i < w * h; i++) {
            double top = (2.0 * mu1[i] * mu2[i] + C1) * (2.0 * sigma12[i] + C2);
            double bot = (mu1[i] * mu1[i] + mu2[i] * mu2[i] + C1) * (sigma1Sq[i] + sigma2Sq[i] + C2);
            double v = 1.0;
            if (bot != 0) v = top / bot;
            meanSsim += v;
        }
        return meanSsim / (w * h);
    }

    private static float[] gaussianBlurGray(float[] src, int w, int h, int ksize, float sigma) {
        int half = ksize / 2;
        float[] kernel = new float[ksize];
        float sum = 0f;
        for (int i = 0; i < ksize; i++) {
            int x = i - half;
            kernel[i] = (float) Math.exp(-(x * x) / (2.0 * sigma * sigma));
            sum += kernel[i];
        }
        for (int i = 0; i < ksize; i++) kernel[i] /= sum;

        float[] tmp = new float[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float v = 0f;
                for (int k = -half; k <= half; k++) {
                    int xx = Math.min(w - 1, Math.max(0, x + k));
                    v += src[y * w + xx] * kernel[k + half];
                }
                tmp[y * w + x] = v;
            }
        }
        float[] dst = new float[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float v = 0f;
                for (int k = -half; k <= half; k++) {
                    int yy = Math.min(h - 1, Math.max(0, y + k));
                    v += tmp[yy * w + x] * kernel[k + half];
                }
                dst[y * w + x] = v;
            }
        }
        return dst;
    }

    private static float[] rgbToHsv(float r, float g, float b) {
        float rf = r / 255f, gf = g / 255f, bf = b / 255f;
        float max = Math.max(rf, Math.max(gf, bf));
        float min = Math.min(rf, Math.min(gf, bf));
        float h = 0f, s = 0f, v = max * 255f;
        float d = max - min;
        if (max != 0) s = d / max;
        if (max == min) h = 0f;
        else {
            if (max == rf) h = (gf - bf) / d + (gf < bf ? 6f : 0f);
            else if (max == gf) h = (bf - rf) / d + 2f;
            else h = (rf - gf) / d + 4f;
            h *= 60f;
        }
        return new float[]{h, s, v};
    }

    private static int[] hsvToRgbInt(float[] hsv) {
        float h = hsv[0], s = hsv[1], v = hsv[2] / 255f;
        int r, g, b;
        if (s == 0) {
            int val = Math.round(v * 255);
            r = g = b = val;
        } else {
            h /= 60f;
            int i = (int) Math.floor(h);
            float f = h - i;
            float p = v * (1 - s);
            float q = v * (1 - s * f);
            float t = v * (1 - s * (1 - f));
            float rf, gf, bf;
            switch (i) {
                case 0: rf = v; gf = t; bf = p; break;
                case 1: rf = q; gf = v; bf = p; break;
                case 2: rf = p; gf = v; bf = t; break;
                case 3: rf = p; gf = q; bf = v; break;
                case 4: rf = t; gf = p; bf = v; break;
                default: rf = v; gf = p; bf = q; break;
            }
            r = Math.round(rf * 255); g = Math.round(gf * 255); b = Math.round(bf * 255);
        }
        return new int[]{r, g, b};
    }

    private Bitmap censorFacesWithBlack(Bitmap src) throws Exception {
        FaceDetectorOptions options = new FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
                .build();

        FaceDetector detector = FaceDetection.getClient(options);

        InputImage image = InputImage.fromBitmap(src, 0);

        final CountDownLatch latch = new CountDownLatch(1);
        final ArrayList<Face> faces = new ArrayList<>();
        final Exception[] exceptionHolder = new Exception[1];

        detector.process(image)
                .addOnSuccessListener(detectedFaces -> {
                    faces.addAll(detectedFaces);
                    latch.countDown();
                })
                .addOnFailureListener(e -> {
                    exceptionHolder[0] = new Exception(e);
                    latch.countDown();
                });

        latch.await();

        if (exceptionHolder[0] != null) {
            try { detector.close(); } catch (Exception ignored) {}
            throw exceptionHolder[0];
        }

        if (faces.isEmpty()) {
            try { detector.close(); } catch (Exception ignored) {}
            return src;
        }

        Bitmap mutable = src.copy(Bitmap.Config.ARGB_8888, true);
        Canvas canvas = new Canvas(mutable);
        Paint blackPaint = new Paint();
        blackPaint.setStyle(Paint.Style.FILL);
        blackPaint.setColor(Color.BLACK);
        blackPaint.setAntiAlias(true);

        for (Face face : faces) {
            if (isCancelled.get()) break;

            Rect bbox = face.getBoundingBox();

            Rect safe = new Rect(
                    Math.max(0, bbox.left),
                    Math.max(0, bbox.top),
                    Math.min(src.getWidth(), bbox.right),
                    Math.min(src.getHeight(), bbox.bottom)
            );

            if (safe.width() <= 4 || safe.height() <= 4) continue;

            int padW = (int) (safe.width() * 0.12f);
            int padH = (int) (safe.height() * 0.12f);

            safe.left   = Math.max(0, safe.left - padW);
            safe.top    = Math.max(0, safe.top - padH);
            safe.right  = Math.min(src.getWidth(), safe.right + padW);
            safe.bottom = Math.min(src.getHeight(), safe.bottom + padH);

            canvas.drawRect(safe, blackPaint);
        }

        try { detector.close(); } catch (Exception ignored) {}
        return mutable;
    }

    private Bitmap censorFacesWithEmoji(Bitmap src, String emoji) throws Exception {

        FaceDetectorOptions options = new FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
                .build();

        FaceDetector detector = FaceDetection.getClient(options);
        InputImage image = InputImage.fromBitmap(src, 0);

        final CountDownLatch latch = new CountDownLatch(1);
        final ArrayList<Face> faces = new ArrayList<>();
        final Exception[] exceptionHolder = new Exception[1];

        detector.process(image)
                .addOnSuccessListener(f -> { faces.addAll(f); latch.countDown(); })
                .addOnFailureListener(e -> { exceptionHolder[0] = e; latch.countDown(); });

        latch.await();
        detector.close();

        if (exceptionHolder[0] != null) throw exceptionHolder[0];
        if (faces.isEmpty()) return src;

        Bitmap mutable = src.copy(Bitmap.Config.ARGB_8888, true);
        Canvas canvas = new Canvas(mutable);

        for (Face face : faces) {
            if (isCancelled.get()) break;

            Rect box = face.getBoundingBox();
            Rect safe = new Rect(
                    Math.max(0, box.left),
                    Math.max(0, box.top),
                    Math.min(src.getWidth(),  box.right),
                    Math.min(src.getHeight(), box.bottom)
            );

            int padW = (int)(safe.width() * 0.12f);
            int padH = (int)(safe.height() * 0.12f);

            safe.left   = Math.max(0, safe.left - padW);
            safe.top    = Math.max(0, safe.top - padH);
            safe.right  = Math.min(src.getWidth(), safe.right + padW);
            safe.bottom = Math.min(src.getHeight(), safe.bottom + padH);

            Bitmap emojiBmp = renderEmoji(emoji, safe.width(), safe.height());

            canvas.drawBitmap(emojiBmp, null, safe, null);
        }

        return mutable;
    }

    private Bitmap renderEmoji(String emoji, int width, int height) {
        Bitmap bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);

        Paint paint = new Paint();
        paint.setTextSize(height * 0.8f);
        paint.setAntiAlias(true);
        paint.setTextAlign(Paint.Align.CENTER);

        Paint.FontMetrics fm = paint.getFontMetrics();
        float x = width / 2f;
        float y = (height - fm.ascent - fm.descent) / 2f;

        canvas.drawText(emoji, x, y, paint);
        return bmp;
    }

    private static float[] toGrayscale(float[] rgb, int w, int h) {
        int n = w * h;
        float[] g = new float[n];
        for (int i = 0; i < n; i++) {
            float r = rgb[i * 3];
            float gg = rgb[i * 3 + 1];
            float b = rgb[i * 3 + 2];
            g[i] = (0.299f * r + 0.587f * gg + 0.114f * b);
        }
        return g;
    }

    private boolean saveBitmapToGallery(Bitmap bmp, String filename) {
        Uri uri = null;
        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, filename);
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PixelCloak");
            }

            uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) {
                Log.e(TAG, "Couldn't create MediaStore entry");
                return false;
            }

            try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                if (out == null) {
                    Log.e(TAG, "OutputStream is null");
                    getContentResolver().delete(uri, null, null);
                    return false;
                }
                if (!bmp.compress(Bitmap.CompressFormat.JPEG, 90, out)) {
                    Log.e(TAG, "Bitmap.compress returned false");
                    getContentResolver().delete(uri, null, null);
                    return false;
                }
            }

            return true;

        } catch (SecurityException se) {
            Log.e(TAG, "SecurityException saving image", se);
            if (uri != null) try { getContentResolver().delete(uri, null, null); } catch (Exception ignore) {}
            return false;
        } catch (Exception ex) {
            Log.e(TAG, "Unexpected error saving image", ex);
            if (uri != null) try { getContentResolver().delete(uri, null, null); } catch (Exception ignore) {}
            return false;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }
}

