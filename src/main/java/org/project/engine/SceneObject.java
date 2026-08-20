package org.project.engine;

import org.joml.Matrix4f;
import org.joml.Vector3f;

public class SceneObject
{
    private final int id;
    private final Mesh mesh;
    private final String sourcePath;

    public Vector3f position = new Vector3f(0, 0, 0);
    public Vector3f rotation = new Vector3f(0, 0, 0);
    public float scale = 1.0f;

    public SceneObject parent = null;
    public boolean isSectionBox = false;

    public SceneObject(int id, Mesh mesh, String sourcePath)
    {
        this.id = id;
        this.mesh = mesh;
        this.sourcePath = sourcePath;
    }

    public int getId() { return id; }
    public Mesh getMesh() { return mesh; }
    public String getSourcePath() { return sourcePath; }

    public Vector3f getPickingColor()
    {
        int r = (id & 0x000000FF);
        int g = (id & 0x0000FF00) >> 8;
        int b = (id & 0x00FF0000) >> 16;
        return new Vector3f(r / 255.0f, g / 255.0f, b / 255.0f);
    }

    public Matrix4f getModelMatrix()
    {
        Matrix4f localMat = new Matrix4f().identity()
                .translate(position)
                .rotateX(rotation.x)
                .rotateY(rotation.y)
                .rotateZ(rotation.z)
                .scale(scale);

        if (parent != null) {
            return parent.getModelMatrix().mul(localMat);
        }
        return localMat;
    }
}