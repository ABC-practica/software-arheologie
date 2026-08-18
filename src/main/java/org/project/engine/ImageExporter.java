package org.project.engine;

import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

public class ImageExporter
{
    public static void savePng(WritableImage image, File file) throws IOException
    {
        int width = (int) image.getWidth();
        int height = (int) image.getHeight();
        PixelReader reader = image.getPixelReader();

        BufferedImage buffered = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++)
        {
            for (int x = 0; x < width; x++)
            {
                buffered.setRGB(x, y, reader.getArgb(x, y));
            }
        }

        ImageIO.write(buffered, "png", file);
    }
}
