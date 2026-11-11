package com.locus.app.util;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.Base64;

import com.locus.app.model.ImageRules;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/** Turns a picked photo into small base64 JPEG text (and back). Blocking: call from a background thread. */
public final class ImageCodec {
    private ImageCodec() {
    }

    /** @return base64 text no longer than {@link ImageRules#MAX_BASE64}, or null when the picture cannot be read. */
    public static String encode(ContentResolver resolver, Uri uri) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream in = resolver.openInputStream(uri)) {
                BitmapFactory.decodeStream(in, null, bounds);
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = ImageRules.sampleSize(bounds.outWidth, bounds.outHeight);
            Bitmap bmp;
            try (InputStream in = resolver.openInputStream(uri)) {
                bmp = BitmapFactory.decodeStream(in, null, o);
            }
            if (bmp == null) return null;
            int[] size = ImageRules.fit(bmp.getWidth(), bmp.getHeight());
            if (size[0] != bmp.getWidth()) bmp = Bitmap.createScaledBitmap(bmp, size[0], size[1], true);
            for (int quality = 75; quality >= 30; quality -= 15) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                bmp.compress(Bitmap.CompressFormat.JPEG, quality, out);
                if (ImageRules.fits(out.size())) return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
            }
            return null; // still too big at the lowest quality
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
    }

    public static Bitmap decode(String base64) {
        try {
            byte[] raw = Base64.decode(base64, Base64.DEFAULT);
            return BitmapFactory.decodeByteArray(raw, 0, raw.length);
        } catch (Exception e) {
            return null;
        }
    }
}
