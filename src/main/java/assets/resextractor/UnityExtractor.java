package assets.resextractor;

import assets.AssetExtractor;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.image.*;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.TreeSet;

/**
 * Class extracted from UnityPy https://github.com/K0lb3/UnityPy
 */
public class UnityExtractor {
    private int counter = 0;
    private TreeSet<String> checkDupes = new TreeSet<>();

    public void extract(File input, File[] output) throws IOException {
        AssetExtractor.setDisplay("Creating Folders");
        createFolders(output);

        Resources res = new Resources(input);

        AssetExtractor.setDisplay("Extracting Spritesheet File");
        extractSpritesheet(res, output[0]);
        AssetExtractor.setDisplay("Extracting Sprites");
        extractSprites(res, output[1]);
        AssetExtractor.setDisplay("Extracting Xml Files");
        extractXml(res, output[2]);
    }

    private void createFolders(File[] output) throws IOException {
        for (File f : output) {
            java.nio.file.Files.createDirectories(f.toPath());
        }
    }

    private void extractXml(Resources res, File outputFolder) throws IOException {
        for (TextAsset t : res.assetTextAsset) {
            counter++;
            AssetExtractor.setDisplay("Extracting Xml Files " + counter);

            if (!Arrays.asList(TextAsset.NON_XML_FILES).contains(t.name)) {
                String name = checkDuplicates(t.name);
                File outputFile = new File(outputFolder + "/" + name + ".xml");
                try (FileOutputStream outputStream = new FileOutputStream(outputFile)) {
                    outputStream.write(t.m_Script);
                }
            }
        }
    }

    private String checkDuplicates(String name) {
        String n = name;
        int count = 1;
        while (true) {
            if (!checkDupes.contains(n)) {
                checkDupes.add(n);
                return n;
            } else {
                count++;
                n = name + count;
            }
        }
    }

    private void extractSpritesheet(Resources res, File outputFolder) throws IOException {
        if (res.spritesheet != null) {
            File outputFile = new File(outputFolder + "/spritesheetf");
            try (FileOutputStream outputStream = new FileOutputStream(outputFile)) {
                outputStream.write(res.spritesheet.m_Script);
            }
        }
    }

    private void extractSprites(Resources res, File outputFolder) throws IOException {
        for (Texture2D t : res.assetTexture2D) {
            if (Arrays.asList(Texture2D.SPRITESHEET_NAMES).contains(t.name)) {
                AssetExtractor.setDisplay("Extracting Sprite: " + t.name);
                File outputFile = new File(outputFolder + "/" + t.name + ".png");
                int width = t.m_Width;
                int height = t.m_Height;
                byte[] data = t.image_data;

                DataBuffer buffer = new DataBufferByte(data, data.length);

                WritableRaster raster = Raster.createInterleavedRaster(buffer, width, height, 4 * width, 4, new int[]{0, 1, 2, 3}, null);
                ColorModel cm = new ComponentColorModel(ColorModel.getRGBdefault().getColorSpace(), true, true, Transparency.TRANSLUCENT, DataBuffer.TYPE_BYTE);
                BufferedImage image = new BufferedImage(cm, raster, true, null);

                AffineTransform at = new AffineTransform();
                at.concatenate(AffineTransform.getScaleInstance(1, -1));
                at.concatenate(AffineTransform.getTranslateInstance(0, -image.getHeight()));
                BufferedImage newImage = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = newImage.createGraphics();
                g.transform(at);
                g.drawImage(image, 0, 0, null);
                g.dispose();

                if (!ImageIO.write(newImage, "png", outputFile)) throw new IOException("No PNG writer available.");
            }
        }
    }
}
