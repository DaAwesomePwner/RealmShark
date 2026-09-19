import realmshark.branding.FinIcon;

import javax.imageio.ImageIO;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/** Generates the resource PNGs and Windows ICO from the same application artwork. */
public final class GenerateBranding {
    private GenerateBranding() { }

    public static void main(String[] args) throws IOException {
        if (args.length != 1) throw new IllegalArgumentException("Usage: GenerateBranding <resource-directory>");
        Path directory = Paths.get(args[0]).resolve("icon");
        Files.createDirectories(directory);
        int[] sizes = FinIcon.sizes();
        List<byte[]> images = new ArrayList<>();
        int length = 6 + 16 * sizes.length;
        for (int size : sizes) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            if (!ImageIO.write(FinIcon.image(size), "png", bytes)) throw new IOException("PNG writer unavailable");
            byte[] png = bytes.toByteArray();
            Files.write(directory.resolve("realmshark-" + size + ".png"), png);
            images.add(png);
            length += png.length;
        }

        // Windows Vista+ supports PNG-compressed ICO entries, including full alpha at every size.
        ByteBuffer ico = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN);
        ico.putShort((short) 0).putShort((short) 1).putShort((short) sizes.length);
        int offset = 6 + 16 * sizes.length;
        for (int i = 0; i < sizes.length; i++) {
            int size = sizes[i];
            ico.put((byte) (size == 256 ? 0 : size)).put((byte) (size == 256 ? 0 : size));
            ico.put((byte) 0).put((byte) 0);
            ico.putShort((short) 1).putShort((short) 32);
            ico.putInt(images.get(i).length).putInt(offset);
            offset += images.get(i).length;
        }
        for (byte[] png : images) ico.put(png);
        Files.write(directory.resolve("realmshark.ico"), ico.array());
    }
}
