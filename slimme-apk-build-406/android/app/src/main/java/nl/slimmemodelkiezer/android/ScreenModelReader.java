package nl.slimmemodelkiezer.android;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.os.Handler;
import android.view.Display;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Local screen reader for ChatGPT's Compose model menu.
 *
 * Build 412 performs ON-DEVICE ML Kit text recognition. Screenshots and prompt
 * text are never uploaded. This fixes the previous stub which returned only a
 * synthetic "model" header item, making visible Instant/Medium/High rows
 * impossible to select when Compose omitted them from Accessibility.
 */
final class ScreenModelReader {
    static final class Item {
        final String text;
        final Rect bounds;
        Item(String text, Rect bounds) { this.text = text; this.bounds = bounds; }
    }

    interface Callback {
        void success(Bitmap bitmap, List<Item> items);
        void failure(String code);
    }

    private ScreenModelReader() {}

    static void read(AccessibilityService service, Handler handler, Callback callback) {
        if (Build.VERSION.SDK_INT < 30) {
            callback.failure("SCREEN_CAPTURE_ANDROID_TOO_OLD");
            return;
        }

        try {
            AtomicBoolean finished = new AtomicBoolean(false);
            Runnable timeout = () -> {
                if (finished.compareAndSet(false, true))
                    callback.failure("SCREEN_OCR_TIMEOUT");
            };
            handler.postDelayed(timeout, 2600L);

            service.takeScreenshot(Display.DEFAULT_DISPLAY, handler::post,
                new AccessibilityService.TakeScreenshotCallback() {
                    @Override public void onFailure(int errorCode) {
                        if (!finished.compareAndSet(false, true)) return;
                        handler.removeCallbacks(timeout);
                        callback.failure("SCREEN_CAPTURE_FAILED_" + errorCode);
                    }

                    @Override public void onSuccess(AccessibilityService.ScreenshotResult result) {
                        HardwareBuffer buffer = result.getHardwareBuffer();
                        Bitmap bitmap = null;
                        try {
                            Bitmap hardware = Bitmap.wrapHardwareBuffer(buffer, result.getColorSpace());
                            if (hardware == null) {
                                if (finished.compareAndSet(false, true)) {
                                    handler.removeCallbacks(timeout);
                                    callback.failure("SCREEN_BITMAP_UNAVAILABLE");
                                }
                                return;
                            }
                            bitmap = hardware.copy(Bitmap.Config.ARGB_8888, false);
                        } catch (RuntimeException error) {
                            if (finished.compareAndSet(false, true)) {
                                handler.removeCallbacks(timeout);
                                callback.failure("SCREEN_BITMAP_FAILED");
                            }
                            return;
                        } finally {
                            try { buffer.close(); } catch (RuntimeException ignored) { }
                        }

                        if (bitmap == null) {
                            if (finished.compareAndSet(false, true)) {
                                handler.removeCallbacks(timeout);
                                callback.failure("SCREEN_BITMAP_UNAVAILABLE");
                            }
                            return;
                        }

                        final Bitmap owned = bitmap;
                        try {
                            InputImage input = InputImage.fromBitmap(owned, 0);
                            TextRecognizer recognizer = TextRecognition.getClient(
                                TextRecognizerOptions.DEFAULT_OPTIONS);
                            recognizer.process(input)
                                .addOnSuccessListener(text -> handler.post(() -> {
                                    try {
                                        if (!finished.compareAndSet(false, true)) return;
                                        handler.removeCallbacks(timeout);
                                        List<Item> items = new ArrayList<>();
                                        for (Text.TextBlock block : text.getTextBlocks()) {
                                            for (Text.Line line : block.getLines()) {
                                                Rect bounds = line.getBoundingBox();
                                                String value = line.getText();
                                                if (bounds != null && value != null &&
                                                        !value.trim().isEmpty()) {
                                                    items.add(new Item(value.trim(), new Rect(bounds)));
                                                }
                                                for (Text.Element element : line.getElements()) {
                                                    Rect eb = element.getBoundingBox();
                                                    String ev = element.getText();
                                                    if (eb != null && ev != null &&
                                                            !ev.trim().isEmpty()) {
                                                        items.add(new Item(ev.trim(), new Rect(eb)));
                                                    }
                                                }
                                            }
                                        }
                                        // Preserve a conservative header target even if OCR
                                        // misses the current model label.
                                        float density = service.getResources()
                                            .getDisplayMetrics().density;
                                        int centerY = Math.max(
                                            Math.round(58f * density),
                                            Math.round(owned.getHeight() * 0.055f));
                                        int halfHeight = Math.max(
                                            Math.round(22f * density),
                                            Math.round(owned.getHeight() * 0.012f));
                                        items.add(new Item("model", new Rect(
                                            Math.round(owned.getWidth() * 0.18f),
                                            Math.max(0, centerY - halfHeight),
                                            Math.round(owned.getWidth() * 0.82f),
                                            Math.min(owned.getHeight(), centerY + halfHeight))));
                                        callback.success(owned, items);
                                    } finally {
                                        try { recognizer.close(); } catch (RuntimeException ignored) { }
                                        if (!owned.isRecycled()) owned.recycle();
                                    }
                                }))
                                .addOnFailureListener(error -> handler.post(() -> {
                                    try {
                                        if (!finished.compareAndSet(false, true)) return;
                                        handler.removeCallbacks(timeout);
                                        callback.failure("SCREEN_OCR_FAILED");
                                    } finally {
                                        try { recognizer.close(); } catch (RuntimeException ignored) { }
                                        if (!owned.isRecycled()) owned.recycle();
                                    }
                                }));
                        } catch (RuntimeException error) {
                            if (finished.compareAndSet(false, true)) {
                                handler.removeCallbacks(timeout);
                                callback.failure("SCREEN_OCR_NOT_AVAILABLE");
                            }
                            if (!owned.isRecycled()) owned.recycle();
                        }
                    }
                });
        } catch (RuntimeException error) {
            callback.failure("SCREEN_CAPTURE_NOT_ALLOWED");
        }
    }
}
