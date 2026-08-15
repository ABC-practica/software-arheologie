package org.project.engine;

import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.List;

public class Mesh
{
    private final int vaoId;
    private final int vboId;
    private final int normalVboId;
    private final int uvVboId;
    private final int eboId;
    private final List<MeshPart> parts;

    public Mesh(float[] positions, float[] normals, float[] texCoords, int[] indices, List<MeshPart> parts)
    {
        this.parts = parts;

        FloatBuffer posBuffer = MemoryUtil.memAllocFloat(positions.length);
        posBuffer.put(positions).flip();

        FloatBuffer normalBuffer = MemoryUtil.memAllocFloat(normals.length);
        normalBuffer.put(normals).flip();

        FloatBuffer uvBuffer = MemoryUtil.memAllocFloat(texCoords.length);
        uvBuffer.put(texCoords).flip();

        IntBuffer indicesBuffer = MemoryUtil.memAllocInt(indices.length);
        indicesBuffer.put(indices).flip();

        vaoId = GL30.glGenVertexArrays();
        GL30.glBindVertexArray(vaoId);

        vboId = GL30.glGenBuffers();
        GL30.glBindBuffer(GL30.GL_ARRAY_BUFFER, vboId);
        GL30.glBufferData(GL30.GL_ARRAY_BUFFER, posBuffer, GL30.GL_STATIC_DRAW);
        GL30.glVertexAttribPointer(0, 3, GL30.GL_FLOAT, false, 0, 0);
        GL30.glEnableVertexAttribArray(0);

        normalVboId = GL30.glGenBuffers();
        GL30.glBindBuffer(GL30.GL_ARRAY_BUFFER, normalVboId);
        GL30.glBufferData(GL30.GL_ARRAY_BUFFER, normalBuffer, GL30.GL_STATIC_DRAW);
        GL30.glVertexAttribPointer(1, 3, GL30.GL_FLOAT, false, 0, 0);
        GL30.glEnableVertexAttribArray(1);

        uvVboId = GL30.glGenBuffers();
        GL30.glBindBuffer(GL30.GL_ARRAY_BUFFER, uvVboId);
        GL30.glBufferData(GL30.GL_ARRAY_BUFFER, uvBuffer, GL30.GL_STATIC_DRAW);
        GL30.glVertexAttribPointer(2, 2, GL30.GL_FLOAT, false, 0, 0);
        GL30.glEnableVertexAttribArray(2);

        eboId = GL30.glGenBuffers();
        GL30.glBindBuffer(GL30.GL_ELEMENT_ARRAY_BUFFER, eboId);
        GL30.glBufferData(GL30.GL_ELEMENT_ARRAY_BUFFER, indicesBuffer, GL30.GL_STATIC_DRAW);

        GL30.glBindBuffer(GL30.GL_ARRAY_BUFFER, 0);
        GL30.glBindVertexArray(0);

        MemoryUtil.memFree(posBuffer);
        MemoryUtil.memFree(normalBuffer);
        MemoryUtil.memFree(uvBuffer);
        MemoryUtil.memFree(indicesBuffer);
    }

    public void render()
    {
        GL30.glActiveTexture(GL30.GL_TEXTURE0);
        GL30.glBindVertexArray(vaoId);
        for (MeshPart part : parts)
        {
            GL30.glBindTexture(GL30.GL_TEXTURE_2D, part.textureId);
            GL30.glDrawElements(GL30.GL_TRIANGLES, part.indexCount, GL30.GL_UNSIGNED_INT, part.indexOffset * 4L);
        }
        GL30.glBindVertexArray(0);
    }
}
