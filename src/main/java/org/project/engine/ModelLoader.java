package org.project.engine;

import org.lwjgl.assimp.AIFace;
import org.lwjgl.assimp.AIMaterial;
import org.lwjgl.assimp.AIMesh;
import org.lwjgl.assimp.AIScene;
import org.lwjgl.assimp.AIString;
import org.lwjgl.assimp.AITexture;
import org.lwjgl.assimp.AIVector3D;
import org.lwjgl.assimp.Assimp;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ModelLoader
{
    public static Mesh loadModel(String filePath)
    {
        AIScene scene = Assimp.aiImportFile(filePath, Assimp.aiProcess_Triangulate | Assimp.aiProcess_JoinIdenticalVertices | Assimp.aiProcess_FlipUVs);
        if (scene == null || scene.mRootNode() == null)
        {
            throw new RuntimeException("Eroare la incarcarea modelului: " + Assimp.aiGetErrorString());
        }

        int numMeshes = scene.mNumMeshes();
        int totalVertices = 0;
        int totalIndices = 0;
        for (int m = 0; m < numMeshes; m++)
        {
            AIMesh aiMesh = AIMesh.create(scene.mMeshes().get(m));
            totalVertices += aiMesh.mNumVertices();
            totalIndices += aiMesh.mNumFaces() * 3;
        }

        float[] vertices = new float[totalVertices * 3];
        float[] texCoords = new float[totalVertices * 2];
        int[] indices = new int[totalIndices];
        List<MeshPart> parts = new ArrayList<>();

        float minX=Float.MAX_VALUE, minY=Float.MAX_VALUE, minZ=Float.MAX_VALUE;
        float maxX=-Float.MAX_VALUE, maxY=-Float.MAX_VALUE, maxZ=-Float.MAX_VALUE;

        Map<Integer, Integer> textureCache = new HashMap<>();
        int vertexCursor = 0;
        int indexCursor = 0;
        for (int m = 0; m < numMeshes; m++)
        {
            AIMesh aiMesh = AIMesh.create(scene.mMeshes().get(m));
            AIVector3D.Buffer aiTexCoords = aiMesh.mTextureCoords(0);
            int baseVertex = vertexCursor;

            for (int i = 0; i < aiMesh.mNumVertices(); i++) {
                float x = aiMesh.mVertices().get(i).x();
                float y = aiMesh.mVertices().get(i).y();
                float z = aiMesh.mVertices().get(i).z();

                int vi = baseVertex + i;
                vertices[vi * 3] = x;
                vertices[vi * 3 + 1] = y;
                vertices[vi * 3 + 2] = z;

                if (aiTexCoords != null)
                {
                    AIVector3D uv = aiTexCoords.get(i);
                    texCoords[vi * 2] = uv.x();
                    texCoords[vi * 2 + 1] = uv.y();
                }

                if (x < minX) minX = x;
                if (y < minY) minY = y;
                if (z < minZ) minZ = z;
                if (x > maxX) maxX = x;
                if (y > maxY) maxY = y;
                if (z > maxZ) maxZ = z;
            }

            int faceCount = aiMesh.mNumFaces();
            for (int i = 0; i < faceCount; i++) {
                AIFace face = aiMesh.mFaces().get(i);
                indices[indexCursor + i * 3] = baseVertex + face.mIndices().get(0);
                indices[indexCursor + i * 3 + 1] = baseVertex + face.mIndices().get(1);
                indices[indexCursor + i * 3 + 2] = baseVertex + face.mIndices().get(2);
            }

            int materialIndex = aiMesh.mMaterialIndex();
            int textureId = textureCache.computeIfAbsent(materialIndex, key -> loadMaterialTexture(scene, aiMesh, filePath));
            parts.add(new MeshPart(indexCursor, faceCount * 3, textureId));

            vertexCursor += aiMesh.mNumVertices();
            indexCursor += faceCount * 3;
        }

        float centerX = (minX + maxX) / 2.0f;
        float centerY = (minY + maxY) / 2.0f;
        float centerZ = (minZ + maxZ) / 2.0f;

        float extentX = maxX - minX;
        float extentY = maxY - minY;
        float extentZ = maxZ - minZ;

        float maxExtent = Math.max(extentX, Math.max(extentY, extentZ));
        float scale = 12.0f / maxExtent;

        for (int i = 0; i < totalVertices; i++) {
            vertices[i * 3]     = (vertices[i * 3] - centerX) * scale;
            vertices[i * 3 + 1] = (vertices[i * 3 + 1] - centerY) * scale;
            vertices[i * 3 + 2] = (vertices[i * 3 + 2] - centerZ) * scale;
        }

        return new Mesh(vertices, texCoords, indices, parts);
    }

    private static int loadMaterialTexture(AIScene scene, AIMesh aiMesh, String modelFilePath)
    {
        if (aiMesh.mMaterialIndex() < 0 || scene.mMaterials() == null)
        {
            return Texture.createDefaultWhite();
        }

        AIMaterial material = AIMaterial.create(scene.mMaterials().get(aiMesh.mMaterialIndex()));
        AIString texPath = AIString.calloc();
        int result = Assimp.aiGetMaterialTexture(material, Assimp.aiTextureType_DIFFUSE, 0, texPath,
                (java.nio.IntBuffer) null, null, null, null, null, null);

        if (result != Assimp.aiReturn_SUCCESS)
        {
            texPath.free();
            return Texture.createDefaultWhite();
        }

        String texPathStr = texPath.dataString();
        texPath.free();

        try
        {
            if (texPathStr.startsWith("*"))
            {
                int texIndex = Integer.parseInt(texPathStr.substring(1));
                AITexture aiTexture = AITexture.create(scene.mTextures().get(texIndex));
                ByteBuffer compressedData = aiTexture.pcDataCompressed();
                return Texture.loadFromMemory(compressedData);
            }
            else
            {
                File modelDir = new File(modelFilePath).getParentFile();
                File textureFile = modelDir != null ? new File(modelDir, texPathStr) : new File(texPathStr);
                return Texture.loadFromFile(textureFile.getPath());
            }
        }
        catch (Exception e)
        {
            System.err.println("Nu s-a putut incarca textura modelului, se foloseste una alba: " + e.getMessage());
            return Texture.createDefaultWhite();
        }
    }
}