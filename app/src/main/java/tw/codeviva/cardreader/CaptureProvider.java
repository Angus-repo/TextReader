package tw.codeviva.cardreader;

import android.content.*;
import android.database.*;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.*;

public final class CaptureProvider extends ContentProvider {
    static final String AUTHORITY="tw.codeviva.cardreader.capture";
    static File dir(Context c) { return new File(c.getCacheDir(),"capture"); }
    static File file(Context c,Uri uri) {
        String name=uri.getLastPathSegment();
        if (!AUTHORITY.equals(uri.getAuthority()) || uri.getPathSegments().size()!=1 ||
            name==null || !name.matches("[a-f0-9-]{36}\\.jpg")) throw new IllegalArgumentException("無效的相機檔案");
        return new File(dir(c),name);
    }
    @Override public boolean onCreate(){return true;}
    @Override public String getType(Uri uri){ file(getContext(),uri);return "image/jpeg"; }
    @Override public ParcelFileDescriptor openFile(Uri uri,String mode) throws FileNotFoundException {
        File f=file(getContext(),uri);
        if (!f.isFile()) throw new FileNotFoundException();
        return ParcelFileDescriptor.open(f,ParcelFileDescriptor.parseMode(mode));
    }
    @Override public Cursor query(Uri uri,String[] projection,String selection,String[] args,String order){
        File f=file(getContext(),uri);
        String[] cols=projection==null?new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE}:projection;
        MatrixCursor c=new MatrixCursor(cols); MatrixCursor.RowBuilder row=c.newRow();
        for(String col:cols) row.add(OpenableColumns.DISPLAY_NAME.equals(col)?f.getName():OpenableColumns.SIZE.equals(col)?f.length():null);
        return c;
    }
    @Override public Uri insert(Uri u,ContentValues v){throw new UnsupportedOperationException();}
    @Override public int update(Uri u,ContentValues v,String s,String[] a){throw new UnsupportedOperationException();}
    @Override public int delete(Uri u,String s,String[] a){throw new UnsupportedOperationException();}
}
