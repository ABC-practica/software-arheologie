package org.project.engine;

import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

public class Mesh
{
    private final int vaoId;
    private final int vboId;
    private final int normalVboId;
    private final int texVboId;
    private final int eboId;
    private final int vertexCount;

    public Mesh(float[] positions, float[] normals, float[] texCoords, int[] indices)
    {
        this.vertexCount = indices.length;

        FloatBuffer posBuffer = MemoryUtil.memAllocFloat(positions.length);
        posBuffer.put(positions).flip();

        FloatBuffer normBuffer = MemoryUtil.memAllocFloat(normals.length);
        normBuffer.put(normals).flip();

        FloatBuffer texBuffer = MemoryUtil.memAllocFloat(texCoords.length);
        texBuffer.put(texCoords).flip();

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
        GL30.glBufferData(GL30.GL_ARRAY_BUFFER, normBuffer, GL30.GL_STATIC_DRAW);
        GL30.glVertexAttribPointer(1, 3, GL30.GL_FLOAT, false, 0, 0);
        GL30.glEnableVertexAttribArray(1);

        texVboId = GL30.glGenBuffers();
        GL30.glBindBuffer(GL30.GL_ARRAY_BUFFER, texVboId);
        GL30.glBufferData(GL30.GL_ARRAY_BUFFER, texBuffer, GL30.GL_STATIC_DRAW);
        GL30.glVertexAttribPointer(2, 2, GL30.GL_FLOAT, false, 0, 0);
        GL30.glEnableVertexAttribArray(2);

        eboId = GL30.glGenBuffers();
        GL30.glBindBuffer(GL30.GL_ELEMENT_ARRAY_BUFFER, eboId);
        GL30.glBufferData(GL30.GL_ELEMENT_ARRAY_BUFFER, indicesBuffer, GL30.GL_STATIC_DRAW);

        GL30.glBindBuffer(GL30.GL_ARRAY_BUFFER, 0);
        GL30.glBindVertexArray(0);

        MemoryUtil.memFree(posBuffer);
        MemoryUtil.memFree(normBuffer);
        MemoryUtil.memFree(texBuffer);
        MemoryUtil.memFree(indicesBuffer);
    }

    public void render()
    {
        GL30.glBindVertexArray(vaoId);
        GL30.glDrawElements(GL30.GL_TRIANGLES, vertexCount, GL30.GL_UNSIGNED_INT, 0);
        GL30.glBindVertexArray(0);
    }
}