package nexus.app.util;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import javafx.scene.image.Image;
import javafx.scene.image.PixelReader;

/** A minimal PNG encoder (RGBA, no filters) so screenshots need neither AWT nor javafx.swing. */
public final class Png {
    private Png() {
    }

    public static void write(Image image, Path file) throws IOException {
        int w = (int) image.getWidth(), h = (int) image.getHeight();
        PixelReader px = image.getPixelReader();
        var raw = new ByteArrayOutputStream(h * (w * 4 + 1));
        for (int y = 0; y < h; y++) {
            raw.write(0);   // filter type: none
            for (int x = 0; x < w; x++) {
                int argb = px.getArgb(x, y);
                raw.write((argb >> 16) & 0xff);
                raw.write((argb >> 8) & 0xff);
                raw.write(argb & 0xff);
                raw.write((argb >>> 24) & 0xff);
            }
        }
        var compressed = new ByteArrayOutputStream();
        try (var z = new DeflaterOutputStream(compressed, new Deflater(6))) {
            raw.writeTo(z);
        }
        var out = new ByteArrayOutputStream();
        var d = new DataOutputStream(out);
        d.write(new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'});
        var ihdr = new ByteArrayOutputStream();
        var hd = new DataOutputStream(ihdr);
        hd.writeInt(w);
        hd.writeInt(h);
        hd.writeByte(8);    // bit depth
        hd.writeByte(6);    // colour type: RGBA
        hd.writeByte(0);
        hd.writeByte(0);
        hd.writeByte(0);
        chunk(d, "IHDR", ihdr.toByteArray());
        chunk(d, "IDAT", compressed.toByteArray());
        chunk(d, "IEND", new byte[0]);
        Files.write(file, out.toByteArray());
    }

    private static void chunk(DataOutputStream d, String type, byte[] data) throws IOException {
        byte[] t = type.getBytes(StandardCharsets.US_ASCII);
        d.writeInt(data.length);
        d.write(t);
        d.write(data);
        var crc = new CRC32();
        crc.update(t);
        crc.update(data);
        d.writeInt((int) crc.getValue());
    }
}
