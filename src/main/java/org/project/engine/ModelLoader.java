package org.project.engine;

import org.lwjgl.assimp.AIFace;
import org.lwjgl.assimp.AIMesh;
import org.lwjgl.assimp.AIScene;
import org.lwjgl.assimp.Assimp;

public class ModelLoader
{
    public static Mesh loadModel(String filePath)
    {
        AIScene scene = Assimp.aiImportFile(filePath,
                Assimp.aiProcess_Triangulate |
                        Assimp.aiProcess_JoinIdenticalVertices |
                        Assimp.aiProcess_GenSmoothNormals);

        if (scene == null || scene.mRootNode() == null)
        {
            throw new RuntimeException("Eroare la incarcarea modelului: " + Assimp.aiGetErrorString());
        }

        AIMesh aiMesh = AIMesh.create(scene.mMeshes().get(0));

        int vertexCount = aiMesh.mNumVertices();
        float[] vertices = new float[vertexCount * 3];
        float[] normals = new float[vertexCount * 3];
        float[] texCoords = new float[vertexCount * 2];

        float minX=Float.MAX_VALUE, minY=Float.MAX_VALUE, minZ=Float.MAX_VALUE;
        float maxX=-Float.MAX_VALUE, maxY=-Float.MAX_VALUE, maxZ=-Float.MAX_VALUE;

        for (int i = 0; i < vertexCount; i++)
        {
            float x = aiMesh.mVertices().get(i).x();
            float y = aiMesh.mVertices().get(i).y();
            float z = aiMesh.mVertices().get(i).z();

            vertices[i * 3] = x;
            vertices[i * 3 + 1] = y;
            vertices[i * 3 + 2] = z;

            if (aiMesh.mNormals() != null)
            {
                normals[i * 3] = aiMesh.mNormals().get(i).x();
                normals[i * 3 + 1] = aiMesh.mNormals().get(i).y();
                normals[i * 3 + 2] = aiMesh.mNormals().get(i).z();
            }

            if (aiMesh.mTextureCoords(0) != null)
            {
                texCoords[i * 2] = aiMesh.mTextureCoords(0).get(i).x();
                texCoords[i * 2 + 1] = aiMesh.mTextureCoords(0).get(i).y();
            }

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
        float scale = 3.0f / (maxExtent == 0 ? 1 : maxExtent);

        for (int i = 0; i < vertexCount; i++)
        {
            vertices[i * 3]     = (vertices[i * 3] - centerX) * scale;
            vertices[i * 3 + 1] = (vertices[i * 3 + 1] - centerY) * scale;
            vertices[i * 3 + 2] = (vertices[i * 3 + 2] - centerZ) * scale;
        }

        int[] indices = new int[aiMesh.mNumFaces() * 3];
        for (int i = 0; i < aiMesh.mNumFaces(); i++)
        {
            AIFace face = aiMesh.mFaces().get(i);
            indices[i * 3] = face.mIndices().get(0);
            indices[i * 3 + 1] = face.mIndices().get(1);
            indices[i * 3 + 2] = face.mIndices().get(2);
        }

        return new Mesh(vertices, normals, texCoords, indices);
    }
}