package org.project.engine;

import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;
import java.nio.ByteBuffer;

public class Texture
{
    private final int id;

    public Texture()
    {
        id = GL30.glGenTextures();
        GL30.glBindTexture(GL30.GL_TEXTURE_2D, id);

        ByteBuffer buffer = MemoryUtil.memAlloc(4);
        buffer.put((byte) 210).put((byte) 200).put((byte) 180).put((byte) 255);
        buffer.flip();

        GL30.glTexImage2D(GL30.GL_TEXTURE_2D, 0, GL30.GL_RGBA, 1, 1, 0, GL30.GL_RGBA, GL30.GL_UNSIGNED_BYTE, buffer);
        MemoryUtil.memFree(buffer);
    }

    public void bind()
    {
        GL30.glBindTexture(GL30.GL_TEXTURE_2D, id);
    }
}