package tv.feathertv.home;

import android.content.Context;
import android.content.res.Configuration;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Plain-text configuration (one key=value per line), editable from a computer with adb:
 * /sdcard/Android/data/tv.feathertv.home/files/feather.conf
 * Only used from the main thread.
 */
final class Config {
    /** Incremented on every save in this process, so other screens notice it immediately. */
    private static int revision;

    private final File file;
    private final Map<String, String> values = new HashMap<>();
    private long signature = -1;
    private int seenRevision = -1;

    Config(Context c) {
        File dir = c.getExternalFilesDir(null);
        if (dir == null) dir = c.getFilesDir();
        file = new File(dir, "feather.conf");
    }

    /** Re-reads the file if it changed since the last read. Returns true if it changed. */
    boolean refresh() {
        long sig = file.exists() ? file.lastModified() * 31 + file.length() : 0;
        if (sig == signature && seenRevision == revision) return false;
        signature = sig;
        seenRevision = revision;
        values.clear();
        if (sig == 0) return true;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(file), "UTF-8"))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                int eq = line.indexOf('=');
                if (line.isEmpty() || line.charAt(0) == '#' || eq <= 0) continue;
                String v = line.substring(eq + 1).trim();
                if (!v.isEmpty()) values.put(line.substring(0, eq).trim(), v);
            }
        } catch (IOException ignored) {
            // Unreadable file: the defaults are used.
        }
        return true;
    }

    String get(String key, String def) {
        String v = values.get(key);
        return v != null ? v : def;
    }

    /** Comma-separated value, without empty entries. */
    List<String> list(String key) {
        List<String> out = new ArrayList<>();
        String v = values.get(key);
        if (v == null) return out;
        for (String part : v.split(",")) {
            part = part.trim();
            if (!part.isEmpty()) out.add(part);
        }
        return out;
    }

    /** All keys that start with the prefix, without the prefix (e.g. "name."). */
    Map<String, String> prefixed(String prefix) {
        Map<String, String> out = new HashMap<>();
        for (Map.Entry<String, String> e : values.entrySet()) {
            if (e.getKey().startsWith(prefix)) out.put(e.getKey().substring(prefix.length()), e.getValue());
        }
        return out;
    }

    /** An empty or null value removes the key. Call save() afterwards. */
    void set(String key, String value) {
        if (value != null) value = value.replace('\n', ' ').replace('\r', ' ').trim();
        if (value == null || value.isEmpty()) values.remove(key);
        else values.put(key, value);
    }

    void setList(String key, List<String> items) {
        StringBuilder sb = new StringBuilder();
        for (String s : items) {
            if (sb.length() > 0) sb.append(',');
            sb.append(s);
        }
        set(key, sb.toString());
    }

    void removePrefix(String prefix) {
        for (Iterator<String> it = values.keySet().iterator(); it.hasNext(); ) {
            if (it.next().startsWith(prefix)) it.remove();
        }
    }

    /** Writes the file atomically (temporary file + rename). */
    boolean save() {
        File tmp = new File(file.getPath() + ".tmp");
        List<String> keys = new ArrayList<>(values.keySet());
        Collections.sort(keys);
        try (Writer w = new OutputStreamWriter(new FileOutputStream(tmp), "UTF-8")) {
            w.write("# FeatherTV. The app rewrites this file; it can also be edited by hand.\n");
            for (String k : keys) w.write(k + "=" + values.get(k) + "\n");
        } catch (IOException e) {
            tmp.delete();
            return false;
        }
        if (!tmp.renameTo(file) && !(file.delete() && tmp.renameTo(file))) return false;
        revision++;
        seenRevision = revision;
        signature = file.lastModified() * 31 + file.length();
        return true;
    }

    // ---------------------------------------------------------------- typed values

    /** Language override for a screen, or null to follow the system. */
    static Configuration languageOverride(String language) {
        if (language == null || "auto".equalsIgnoreCase(language)) return null;
        Configuration c = new Configuration();
        Locale locale = Locale.forLanguageTag(language);
        c.setLocale(locale);
        c.setLayoutDirection(locale);
        return c;
    }

    static final String[] ACCENTS = {"white", "blue", "cyan", "green", "amber", "pink", "violet"};
    private static final int[] ACCENT_COLORS = {
            0xFFFFFFFF, 0xFF4D9FFF, 0xFF3DD6E0, 0xFF4CD964, 0xFFFFB340, 0xFFFF6FA8, 0xFFA78BFA};

    int accent() {
        String a = get("accent", "white");
        for (int i = 0; i < ACCENTS.length; i++) if (ACCENTS[i].equalsIgnoreCase(a)) return ACCENT_COLORS[i];
        return ACCENT_COLORS[0];
    }

    static final String[] TILE_SIZES = {"small", "medium", "large"};

    /** Tile width as a fraction of the screen width. */
    float tileFraction() {
        String s = get("tiles", "medium");
        if ("small".equalsIgnoreCase(s)) return 0.12f;
        if ("large".equalsIgnoreCase(s)) return 0.18f;
        return 0.15f;
    }
}
