package tw.codeviva.cardreader;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteStatement;
import android.util.JsonReader;
import android.util.JsonToken;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import static tw.codeviva.cardreader.CardCatalog.obj;

final class ReadingHistory extends SQLiteOpenHelper {
    private static final String FORMAT = "TextReader.history.v1";

    ReadingHistory(Context context) {
        super(context.getApplicationContext(), "reading-history.db", null, 1);
    }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE readings(id TEXT PRIMARY KEY NOT NULL, created INTEGER NOT NULL, label TEXT NOT NULL, record TEXT NOT NULL)");
    }
    @Override public void onUpgrade(SQLiteDatabase db, int from, int to) {
        throw new IllegalStateException("無法升級辨識紀錄資料庫。");
    }

    static final class Entry {
        final String id, label;
        final long time;
        Entry(Cursor row) {
            id = row.getString(0);
            label = row.getString(1);
            time = row.getLong(2);
        }
    }

    List<Entry> list() {
        List<Entry> entries = new ArrayList<>();
        try (Cursor rows = getReadableDatabase().rawQuery("SELECT id,label,created FROM readings ORDER BY created DESC,rowid DESC", null)) {
            while (rows.moveToNext()) entries.add(new Entry(rows));
        }
        return entries;
    }

    String add(AzureReader.Reading reading) throws JSONException {
        String id = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        CardCatalog.Card type = reading.type;
        JSONObject record = obj("id", id, "createdAt", now, "type", obj("code", type.code, "label", type.label,
            "criteria", type.criteria, "fields", type.fields), "text", reading.text);
        if (reading.fields != null) record.put("fields", reading.fields);
        if (reading.local.length() > 0) record.put("local", reading.local);
        insert(getWritableDatabase(), id, now, type.label, record);
        return id;
    }

    private static boolean insert(SQLiteDatabase db, String id, long time, String label, JSONObject record) {
        try (SQLiteStatement stmt = db.compileStatement("INSERT INTO readings(id,created,label,record) SELECT ?,?,?,? WHERE NOT EXISTS (SELECT 1 FROM readings WHERE id=?)")) {
            stmt.bindString(1, id);
            stmt.bindLong(2, time);
            stmt.bindString(3, label);
            stmt.bindString(4, record.toString());
            stmt.bindString(5, id);
            return stmt.executeUpdateDelete() == 1;
        }
    }

    private static String record(SQLiteDatabase db, String id) {
        // Query the single document directly so a long transcript does not need to fit a CursorWindow.
        try (SQLiteStatement query = db.compileStatement("SELECT record FROM readings WHERE id=?")) {
            query.bindString(1, id);
            return query.simpleQueryForString();
        }
    }
    AzureReader.Reading get(String id) throws JSONException {
        return decode(new JSONObject(record(getReadableDatabase(), id)));
    }
    private static AzureReader.Reading decode(JSONObject item) throws JSONException {
        CardCatalog.Card type = new CardCatalog.Card(item.getJSONObject("type"));
        for (int i = 0; i < type.fields.length(); i++) {
            JSONObject field = type.fields.getJSONObject(i);
            field.getString("key");
            field.getString("label");
        }
        return new AzureReader.Reading(type, item.getString("text"), item.has("fields") ? item.getJSONObject("fields") : null,
                item.has("local") ? item.getJSONObject("local") : new JSONObject(), 0);
    }
    void clear() {
        getWritableDatabase().delete("readings", null, null);
    }

    int exportTo(OutputStream output) throws IOException {
        SQLiteDatabase db = getReadableDatabase();
        int count = 0;
        db.beginTransactionNonExclusive();
        try (Cursor rows = db.rawQuery("SELECT id FROM readings ORDER BY created,rowid", null)) {
            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8));
            writer.write("{\"format\":" + JSONObject.quote(FORMAT) + ",\"records\":[\n");
            while (rows.moveToNext()) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
                if (count > 0) writer.write(",\n");
                writer.write(record(db, rows.getString(0)));
                count++;
            }
            writer.write("\n]}\n");
            writer.flush();
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        return count;
    }

    int importFrom(InputStream input) throws IOException, JSONException {
        SQLiteDatabase db = getWritableDatabase();
        int added = 0;
        boolean validFormat = false, hasRecords = false;
        db.beginTransaction();
        try (JsonReader reader = new JsonReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            reader.beginObject();
            while (reader.hasNext()) {
                switch (reader.nextName()) {
                    case "format":
                        if (validFormat || !FORMAT.equals(reader.nextString())) throw new IOException("備份版本不相容。");
                        validFormat = true;
                        break;
                    case "records":
                        if (hasRecords) throw new IOException("備份含重複的紀錄清單。");
                        hasRecords = true;
                        reader.beginArray();
                        while (reader.hasNext()) {
                            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
                            Object value = readValue(reader, 0);
                            if (!(value instanceof JSONObject)) throw new IOException("辨識紀錄格式錯誤。");
                            JSONObject item = (JSONObject) value;
                            AzureReader.Reading reading = decode(item);
                            String id = item.getString("id");
                            if (!UUID.fromString(id).toString().equals(id)) throw new IOException("紀錄識別碼無效。");
                            if (insert(db, id, item.getLong("createdAt"), reading.type.label, item)) added++;
                        }
                        reader.endArray();
                        break;
                    default:
                        reader.skipValue();
                }
            }
            reader.endObject();
            if (!validFormat || !hasRecords || reader.peek() != JsonToken.END_DOCUMENT)
                throw new IOException("請選擇完整的 TextReader 辨識紀錄備份。");
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        return added;
    }

    // Read one backup record at a time, preserving nested rules, field values and confidence.
    private static Object readValue(JsonReader reader, int depth) throws IOException, JSONException {
        if (depth > 64) throw new IOException("備份資料巢狀層級過深。");
        switch (reader.peek()) {
            case BEGIN_OBJECT:
                JSONObject object = new JSONObject();
                reader.beginObject();
                while (reader.hasNext()) object.put(reader.nextName(), readValue(reader, depth + 1));
                reader.endObject();
                return object;
            case BEGIN_ARRAY:
                JSONArray array = new JSONArray();
                reader.beginArray();
                while (reader.hasNext()) array.put(readValue(reader, depth + 1));
                reader.endArray();
                return array;
            case STRING: return reader.nextString();
            case NUMBER: return new BigDecimal(reader.nextString());
            case BOOLEAN: return reader.nextBoolean();
            case NULL: reader.nextNull(); return JSONObject.NULL;
            default: throw new IOException("備份 JSON 格式錯誤。");
        }
    }
}
