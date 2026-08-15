package org.project.engine;

import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

public class PlaneIntersector
{
    public static float[] findCrossedTriangles(float[] positions, int[] indices, Vector3f planePoint, Vector3f planeNormal)
    {
        List<Float> triangles = new ArrayList<>();

        Vector3f a = new Vector3f();
        Vector3f b = new Vector3f();
        Vector3f c = new Vector3f();

        for (int i = 0; i < indices.length; i += 3)
        {
            int ia = indices[i] * 3;
            int ib = indices[i + 1] * 3;
            int ic = indices[i + 2] * 3;

            a.set(positions[ia], positions[ia + 1], positions[ia + 2]);
            b.set(positions[ib], positions[ib + 1], positions[ib + 2]);
            c.set(positions[ic], positions[ic + 1], positions[ic + 2]);

            float da = planeNormal.dot(a.x - planePoint.x, a.y - planePoint.y, a.z - planePoint.z);
            float db = planeNormal.dot(b.x - planePoint.x, b.y - planePoint.y, b.z - planePoint.z);
            float dc = planeNormal.dot(c.x - planePoint.x, c.y - planePoint.y, c.z - planePoint.z);

            boolean allPositive = da > 0 && db > 0 && dc > 0;
            boolean allNegative = da < 0 && db < 0 && dc < 0;
            if (allPositive || allNegative) continue;

            triangles.add(a.x); triangles.add(a.y); triangles.add(a.z);
            triangles.add(b.x); triangles.add(b.y); triangles.add(b.z);
            triangles.add(c.x); triangles.add(c.y); triangles.add(c.z);
        }

        float[] array = new float[triangles.size()];
        for (int i = 0; i < array.length; i++) array[i] = triangles.get(i);
        return array;
    }
}
