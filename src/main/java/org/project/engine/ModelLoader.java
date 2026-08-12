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
        for (int i = 0; i < aiMesh.mNumVertices(); i++) {
            vertices[i * 3] = aiMesh.mVertices().get(i).x();
            vertices[i * 3 + 1] = aiMesh.mVertices().get(i).y();
            vertices[i * 3 + 2] = aiMesh.mVertices().get(i).z();
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