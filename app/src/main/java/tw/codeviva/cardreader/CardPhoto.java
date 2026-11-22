package tw.codeviva.cardreader;

import android.content.Context;
import android.graphics.*;
import android.net.Uri;
import java.io.*;

final class CardPhoto {
    final Bitmap preview;
    final byte[] jpeg;
    CardPhoto(Bitmap preview,byte[] jpeg){this.preview=preview;this.jpeg=jpeg;}
    static byte[] encoded(Bitmap b,Bitmap.CompressFormat format,int quality) throws IOException {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        if (!b.compress(format,quality,bytes)) throw new IOException("影像轉換失敗，請重新選圖。");
        return bytes.toByteArray();
    }
    static byte[] decisionJpeg(Bitmap source) throws IOException {
        // Bound both sides for Microsoft Decision. Pad extreme aspect ratios without cropping the card.
        int w = source.getWidth(), h = source.getHeight();
        float fit = Math.min(896f / Math.max(w, h), 672f / Math.min(w, h));
        float scale = Math.min(fit, Math.max(1f, 280f / Math.min(w, h)));
        int width = Math.max(1, Math.round(w * scale));
        int height = Math.max(1, Math.round(h * scale));
        Bitmap image = Bitmap.createBitmap(Math.max(280, width), Math.max(280, height), Bitmap.Config.ARGB_8888);
        try {
            Canvas canvas = new Canvas(image);
            canvas.drawColor(Color.WHITE);
            int x = (image.getWidth() - width) / 2, y = (image.getHeight() - height) / 2;
            canvas.drawBitmap(source, null, new Rect(x, y, x + width, y + height), new Paint(Paint.FILTER_BITMAP_FLAG));
            return encoded(image, Bitmap.CompressFormat.JPEG, 90);
        } finally {
            image.recycle();
        }
    }

    static CardPhoto load(Context c, Uri uri) throws IOException {
        Bitmap full=ImageDecoder.decodeBitmap(ImageDecoder.createSource(c.getContentResolver(),uri),(decoder,info,src)->{
            int w=info.getSize().getWidth(),h=info.getSize().getHeight();
            float scale=Math.min(1f,2400f/Math.max(w,h));
            decoder.setTargetSize(Math.max(1,Math.round(w*scale)),Math.max(1,Math.round(h*scale)));
            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
        });
        Bitmap preview = null;
        try {
            byte[] data = encoded(full, Bitmap.CompressFormat.JPEG, 93);
            float s = Math.min(1f, 960f / Math.max(full.getWidth(), full.getHeight()));
            preview = Bitmap.createScaledBitmap(full, Math.max(1, Math.round(full.getWidth() * s)),
                    Math.max(1, Math.round(full.getHeight() * s)), true);
            return new CardPhoto(preview, data);
        } finally {
            if (full != preview) full.recycle();
        }
    }
}
