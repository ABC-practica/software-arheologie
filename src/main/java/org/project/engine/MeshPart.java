package org.project.engine;

public class MeshPart
{
    public final int indexOffset;
    public final int indexCount;
    public final int textureId;

    public MeshPart(int indexOffset, int indexCount, int textureId)
    {
        this.indexOffset = indexOffset;
        this.indexCount = indexCount;
        this.textureId = textureId;
    }
}
