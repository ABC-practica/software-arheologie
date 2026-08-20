package org.project.engine;

import org.lwjgl.opengl.GL30;
import org.lwjgl.stb.STBImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

public class Texture
{
    public static int loadFromFile(String path)
    {
        try (MemoryStack stack = MemoryStack.stackPush())
        {
            IntBuffer w = stack.mallocInt(1);
            IntBuffer h = stack.mallocInt(1);
            IntBuffer channels = stack.mallocInt(1);
            ByteBuffer pixels = STBImage.stbi_load(path, w, h, channels, 4);
            if (pixels == null)
            {
                throw new RuntimeException("Nu s-a putut incarca textura din fisier: " + path + " (" + STBImage.stbi_failure_reason() + ")");
            }
            int textureId = upload(pixels, w.get(0), h.get(0));
            STBImage.stbi_image_free(pixels);
            return textureId;
        }
    }

    public static int loadFromMemory(ByteBuffer encodedImageData)
    {
        try (MemoryStack stack = MemoryStack.stackPush())
        {
            IntBuffer w = stack.mallocInt(1);
            IntBuffer h = stack.mallocInt(1);
            IntBuffer channels = stack.mallocInt(1);

            ByteBuffer pixels = STBImage.stbi_load_from_memory(encodedImageData, w, h, channels, 4);
            if (pixels == null)
            {
                throw new RuntimeException("Nu s-a putut decoda textura embedded: " + STBImage.stbi_failure_reason());
            }
            int textureId = upload(pixels, w.get(0), h.get(0));
            STBImage.stbi_image_free(pixels);
            return textureId;
        }
    }

    public static int createDefaultWhite()
    {
        ByteBuffer whitePixel = MemoryUtil.memAlloc(4);
        whitePixel.put((byte) 255).put((byte) 255).put((byte) 255).put((byte) 255).flip();
        int textureId = upload(whitePixel, 1, 1);
        MemoryUtil.memFree(whitePixel);
        return textureId;
    }

    private static int upload(ByteBuffer pixels, int width, int height)
    {
        int textureId = GL30.glGenTextures();
        GL30.glBindTexture(GL30.GL_TEXTURE_2D, textureId);
        GL30.glTexParameteri(GL30.GL_TEXTURE_2D, GL30.GL_TEXTURE_WRAP_S, GL30.GL_REPEAT);
        GL30.glTexParameteri(GL30.GL_TEXTURE_2D, GL30.GL_TEXTURE_WRAP_T, GL30.GL_REPEAT);
        GL30.glTexParameteri(GL30.GL_TEXTURE_2D, GL30.GL_TEXTURE_MIN_FILTER, GL30.GL_LINEAR_MIPMAP_LINEAR);
        GL30.glTexParameteri(GL30.GL_TEXTURE_2D, GL30.GL_TEXTURE_MAG_FILTER, GL30.GL_LINEAR);
        GL30.glTexImage2D(GL30.GL_TEXTURE_2D, 0, GL30.GL_RGBA, width, height, 0, GL30.GL_RGBA, GL30.GL_UNSIGNED_BYTE, pixels);
        GL30.glGenerateMipmap(GL30.GL_TEXTURE_2D);
        GL30.glBindTexture(GL30.GL_TEXTURE_2D, 0);
        return textureId;
    }

    public static int createColorTexture(int r, int g, int b)
    {
        ByteBuffer pixel = MemoryUtil.memAlloc(4);
        pixel.put((byte) r).put((byte) g).put((byte) b).put((byte) 255).flip();
        int textureId = upload(pixel, 1, 1);
        MemoryUtil.memFree(pixel);
        return textureId;
    }
}
