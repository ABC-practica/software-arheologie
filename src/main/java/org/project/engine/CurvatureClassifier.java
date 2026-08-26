package org.project.engine;

import org.joml.Vector3f;
import java.util.ArrayList;
import java.util.List;

public class CurvatureClassifier {

    public static class Result {
        public final float[] exteriorTriangles;
        public final float[] interiorTriangles;
        public final Vector3f exteriorPlaneNormal;
        public final Vector3f exteriorPlanePoint;
        public final Vector3f interiorPlaneNormal;
        public final Vector3f interiorPlanePoint;

        Result(float[] exteriorTriangles, float[] interiorTriangles,
               Vector3f exteriorPlaneNormal, Vector3f exteriorPlanePoint,
               Vector3f interiorPlaneNormal, Vector3f interiorPlanePoint) {
            this.exteriorTriangles = exteriorTriangles;
            this.interiorTriangles = interiorTriangles;
            this.exteriorPlaneNormal = exteriorPlaneNormal;
            this.exteriorPlanePoint = exteriorPlanePoint;
            this.interiorPlaneNormal = interiorPlaneNormal;
            this.interiorPlanePoint = interiorPlanePoint;
        }
    }

    public static Result classify(float[] positions, int[] indices) {
        int numTriangles = indices.length / 3;
        Vector3f[] normals = new Vector3f[numTriangles];
        float[] areas = new float[numTriangles];
        Vector3f[] centroids = new Vector3f[numTriangles];

        int maxAreaIdx = -1;
        float maxArea = -1.0f;

        Vector3f a = new Vector3f();
        Vector3f b = new Vector3f();
        Vector3f c = new Vector3f();

        for (int i = 0; i < numTriangles; i++) {
            int ia = indices[i * 3] * 3;
            int ib = indices[i * 3 + 1] * 3;
            int ic = indices[i * 3 + 2] * 3;

            a.set(positions[ia], positions[ia + 1], positions[ia + 2]);
            b.set(positions[ib], positions[ib + 1], positions[ib + 2]);
            c.set(positions[ic], positions[ic + 1], positions[ic + 2]);

            Vector3f edge1 = new Vector3f(b).sub(a);
            Vector3f edge2 = new Vector3f(c).sub(a);
            Vector3f cross = new Vector3f(edge1).cross(edge2);
            float area = cross.length() * 0.5f;

            areas[i] = area;
            centroids[i] = new Vector3f(a).add(b).add(c).mul(1f / 3f);

            if (area > 1e-8f) {
                normals[i] = new Vector3f(cross).normalize();
                if (area > maxArea) {
                    maxArea = area;
                    maxAreaIdx = i;
                }
            } else {
                normals[i] = new Vector3f(0, 1, 0);
            }
        }

        if (maxAreaIdx == -1) {
            return new Result(new float[0], new float[0], new Vector3f(), new Vector3f(), new Vector3f(), new Vector3f());
        }

        Vector3f refNormal = normals[maxAreaIdx];

        Vector3f meanA = new Vector3f();
        Vector3f meanB = new Vector3f();

        for (int i = 0; i < numTriangles; i++) {
            float dot = normals[i].dot(refNormal);
            if (dot > 0.5f) {
                meanA.add(new Vector3f(normals[i]).mul(areas[i]));
            } else if (dot < -0.5f) {
                meanB.add(new Vector3f(normals[i]).mul(areas[i]));
            }
        }

        if (meanA.lengthSquared() > 1e-6f) meanA.normalize(); else meanA.set(refNormal);
        if (meanB.lengthSquared() > 1e-6f) meanB.normalize(); else meanB.set(new Vector3f(refNormal).negate());

        List<Float> groupAVerts = new ArrayList<>();
        List<Float> groupBVerts = new ArrayList<>();

        Vector3f centroidA = new Vector3f(); float areaA = 0;
        Vector3f centroidB = new Vector3f(); float areaB = 0;

        for (int i = 0; i < numTriangles; i++) {
            float dotA = normals[i].dot(meanA);
            float dotB = normals[i].dot(meanB);

            if (dotA < 0.35f && dotB < 0.35f) continue;

            int ia = indices[i * 3] * 3;
            int ib = indices[i * 3 + 1] * 3;
            int ic = indices[i * 3 + 2] * 3;

            if (dotA > dotB) {
                addTri(groupAVerts, positions, ia, ib, ic);
                centroidA.add(new Vector3f(centroids[i]).mul(areas[i]));
                areaA += areas[i];
            } else {
                addTri(groupBVerts, positions, ia, ib, ic);
                centroidB.add(new Vector3f(centroids[i]).mul(areas[i]));
                areaB += areas[i];
            }
        }

        if (areaA > 0) centroidA.mul(1f / areaA);
        if (areaB > 0) centroidB.mul(1f / areaB);

        Vector3f BtoA = new Vector3f(centroidA).sub(centroidB);
        boolean isAExterior = BtoA.dot(meanA) > 0;

        float[] exteriorTriangles = toArray(isAExterior ? groupAVerts : groupBVerts);
        float[] interiorTriangles = toArray(isAExterior ? groupBVerts : groupAVerts);
        Vector3f exteriorNormal = isAExterior ? meanA : meanB;
        Vector3f exteriorPoint = isAExterior ? centroidA : centroidB;
        Vector3f interiorNormal = isAExterior ? meanB : meanA;
        Vector3f interiorPoint = isAExterior ? centroidB : centroidA;

        return new Result(exteriorTriangles, interiorTriangles, exteriorNormal, exteriorPoint, interiorNormal, interiorPoint);
    }

    private static void addTri(List<Float> list, float[] positions, int ia, int ib, int ic) {
        list.add(positions[ia]); list.add(positions[ia + 1]); list.add(positions[ia + 2]);
        list.add(positions[ib]); list.add(positions[ib + 1]); list.add(positions[ib + 2]);
        list.add(positions[ic]); list.add(positions[ic + 1]); list.add(positions[ic + 2]);
    }

    private static float[] toArray(List<Float> list) {
        float[] array = new float[list.size()];
        for (int i = 0; i < array.length; i++) array[i] = list.get(i);
        return array;
    }
}