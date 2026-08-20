package org.project.engine;

import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

public class CurvatureClassifier
{
    private static final float MAX_VERTICAL_NORMAL_COMPONENT = 0.5f;

    public static class Result
    {
        public final float[] exteriorTriangles;
        public final float[] interiorTriangles;
        public final Vector3f exteriorPlaneNormal;
        public final Vector3f exteriorPlanePoint;
        public final Vector3f interiorPlaneNormal;
        public final Vector3f interiorPlanePoint;

        Result(float[] exteriorTriangles, float[] interiorTriangles,
               Vector3f exteriorPlaneNormal, Vector3f exteriorPlanePoint,
               Vector3f interiorPlaneNormal, Vector3f interiorPlanePoint)
        {
            this.exteriorTriangles = exteriorTriangles;
            this.interiorTriangles = interiorTriangles;
            this.exteriorPlaneNormal = exteriorPlaneNormal;
            this.exteriorPlanePoint = exteriorPlanePoint;
            this.interiorPlaneNormal = interiorPlaneNormal;
            this.interiorPlanePoint = interiorPlanePoint;
        }
    }

    public static Result classify(float[] positions, int[] indices)
    {
        List<Float> groupAVerts = new ArrayList<>();
        List<Float> groupBVerts = new ArrayList<>();

        Vector3f groupANormalSum = new Vector3f();
        Vector3f groupACentroidSum = new Vector3f();
        int groupACount = 0;

        Vector3f groupBNormalSum = new Vector3f();
        Vector3f groupBCentroidSum = new Vector3f();
        int groupBCount = 0;

        Vector3f referenceNormal = null;

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

            Vector3f edge1 = new Vector3f(b).sub(a);
            Vector3f edge2 = new Vector3f(c).sub(a);
            Vector3f normal = new Vector3f(edge1).cross(edge2);
            if (normal.lengthSquared() < 1e-12f) continue;
            normal.normalize();

            if (Math.abs(normal.y) > MAX_VERTICAL_NORMAL_COMPONENT) continue;

            if (referenceNormal == null)
            {
                referenceNormal = new Vector3f(normal);
            }

            Vector3f centroid = new Vector3f(a).add(b).add(c).mul(1f / 3f);

            boolean inGroupA = normal.dot(referenceNormal) >= 0;
            List<Float> targetVerts = inGroupA ? groupAVerts : groupBVerts;
            targetVerts.add(a.x); targetVerts.add(a.y); targetVerts.add(a.z);
            targetVerts.add(b.x); targetVerts.add(b.y); targetVerts.add(b.z);
            targetVerts.add(c.x); targetVerts.add(c.y); targetVerts.add(c.z);

            if (inGroupA)
            {
                groupANormalSum.add(normal);
                groupACentroidSum.add(centroid);
                groupACount++;
            }
            else
            {
                groupBNormalSum.add(normal);
                groupBCentroidSum.add(centroid);
                groupBCount++;
            }
        }

        Vector3f groupANormal = groupACount > 0 ? new Vector3f(groupANormalSum).normalize() : new Vector3f(0, 0, 1);
        Vector3f groupACentroid = groupACount > 0 ? new Vector3f(groupACentroidSum).mul(1f / groupACount) : new Vector3f();
        Vector3f groupBNormal = groupBCount > 0 ? new Vector3f(groupBNormalSum).normalize() : new Vector3f(0, 0, 1);
        Vector3f groupBCentroid = groupBCount > 0 ? new Vector3f(groupBCentroidSum).mul(1f / groupBCount) : new Vector3f();

        boolean groupAIsExterior = groupACentroid.length() >= groupBCentroid.length();

        float[] exteriorTriangles = toArray(groupAIsExterior ? groupAVerts : groupBVerts);
        float[] interiorTriangles = toArray(groupAIsExterior ? groupBVerts : groupAVerts);
        Vector3f exteriorNormal = groupAIsExterior ? groupANormal : groupBNormal;
        Vector3f exteriorPoint = groupAIsExterior ? groupACentroid : groupBCentroid;
        Vector3f interiorNormal = groupAIsExterior ? groupBNormal : groupANormal;
        Vector3f interiorPoint = groupAIsExterior ? groupBCentroid : groupACentroid;

        return new Result(exteriorTriangles, interiorTriangles, exteriorNormal, exteriorPoint, interiorNormal, interiorPoint);
    }

    private static float[] toArray(List<Float> list)
    {
        float[] array = new float[list.size()];
        for (int i = 0; i < array.length; i++) array[i] = list.get(i);
        return array;
    }
}
