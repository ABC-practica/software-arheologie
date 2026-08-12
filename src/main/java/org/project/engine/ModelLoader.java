package org.project.engine;

import org.lwjgl.assimp.AIFace;
import org.lwjgl.assimp.AIMesh;
import org.lwjgl.assimp.AIScene;
import org.lwjgl.assimp.Assimp;

public class ModelLoader
{
    public static Mesh loadModel(String filePath)
    {
        AIScene scene = Assimp.aiImportFile(filePath, Assimp.aiProcess_Triangulate | Assimp.aiProcess_JoinIdenticalVertices);
        if (scene == null || scene.mRootNode() == null)
        {
            throw new RuntimeException("Eroare la incarcarea modelului: " + Assimp.aiGetErrorString());
        }

        AIMesh aiMesh = AIMesh.create(scene.mMeshes().get(0));
        float[] vertices = new float[aiMesh.mNumVertices() * 3];

        float minX=Float.MAX_VALUE, minY=Float.MAX_VALUE, minZ=Float.MAX_VALUE;
        float maxX=-Float.MAX_VALUE, maxY=-Float.MAX_VALUE, maxZ=-Float.MAX_VALUE;

        for (int i = 0; i < aiMesh.mNumVertices(); i++) {
            float x = aiMesh.mVertices().get(i).x();
            float y = aiMesh.mVertices().get(i).y();
            float z = aiMesh.mVertices().get(i).z();

            vertices[i * 3] = x;
            vertices[i * 3 + 1] = y;
            vertices[i * 3 + 2] = z;

            if (x < minX) minX = x;
            if (y < minY) minY = y;
            if (z < minZ) minZ = z;
            if (x > maxX) maxX = x;
            if (y > maxY) maxY = y;
            if (z > maxZ) maxZ = z;
        }

        float centerX = (minX + maxX) / 2.0f;
        float centerY = (minY + maxY) / 2.0f;
        float centerZ = (minZ + maxZ) / 2.0f;

        float extentX = maxX - minX;
        float extentY = maxY - minY;
        float extentZ = maxZ - minZ;

        float maxExtent = Math.max(extentX, Math.max(extentY, extentZ));
        float scale = 12.0f / maxExtent;

        for (int i = 0; i < aiMesh.mNumVertices(); i++) {
            vertices[i * 3]     = (vertices[i * 3] - centerX) * scale;
            vertices[i * 3 + 1] = (vertices[i * 3 + 1] - centerY) * scale;
            vertices[i * 3 + 2] = (vertices[i * 3 + 2] - centerZ) * scale;
        }

        int[] indices = new int[aiMesh.mNumFaces() * 3];
        for (int i = 0; i < aiMesh.mNumFaces(); i++) {
            AIFace face = aiMesh.mFaces().get(i);
            indices[i * 3] = face.mIndices().get(0);
            indices[i * 3 + 1] = face.mIndices().get(1);
            indices[i * 3 + 2] = face.mIndices().get(2);
        }
        return new Mesh(vertices, indices);
    }
}