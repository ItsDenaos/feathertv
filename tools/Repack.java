import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Recompresses every entry of an unsigned APK at maximum level. aapt2 stores resources.arsc
 * uncompressed; with a targetSdk below 30 Android accepts it compressed, and it holds the text of
 * six languages. Usage: java Repack input.apk output.apk
 */
public final class Repack {
    public static void main(String[] args) throws IOException {
        byte[] buf = new byte[16384];
        try (ZipFile in = new ZipFile(args[0]);
             ZipOutputStream out = new ZipOutputStream(new FileOutputStream(args[1]))) {
            out.setLevel(Deflater.BEST_COMPRESSION);
            for (Enumeration<? extends ZipEntry> e = in.entries(); e.hasMoreElements(); ) {
                ZipEntry src = e.nextElement();
                if (src.isDirectory()) continue;
                ZipEntry dst = new ZipEntry(src.getName());
                dst.setTime(src.getTime());
                out.putNextEntry(dst);
                try (InputStream is = in.getInputStream(src)) {
                    int n;
                    while ((n = is.read(buf)) > 0) out.write(buf, 0, n);
                }
                out.closeEntry();
            }
        }
    }
}
