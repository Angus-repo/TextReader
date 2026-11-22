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
    static CardPhoto load(Context c,Uri uri) throws IOException {
        Bitmap full=ImageDecoder.decodeBitmap(ImageDecoder.createSource(c.getContentResolver(),uri),(decoder,info,src)->{
            int w=info.getSize().getWidth(),h=info.getSize().getHeight();
            float scale=Math.min(1f,2400f/Math.max(w,h));
            decoder.setTargetSize(Math.max(1,Math.round(w*scale)),Math.max(1,Math.round(h*scale)));
            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
        });
        byte[] data=encoded(full,Bitmap.CompressFormat.JPEG,93);
        float s=Math.min(1f,960f/Math.max(full.getWidth(),full.getHeight()));
        Bitmap preview=Bitmap.createScaledBitmap(full,Math.max(1,Math.round(full.getWidth()*s)),Math.max(1,Math.round(full.getHeight()*s)),true);
        if (full!=preview) full.recycle();
        return new CardPhoto(preview,data);
    }
}
