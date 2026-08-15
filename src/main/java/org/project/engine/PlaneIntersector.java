package org.project.engine;

import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

public class PlaneIntersector
{
    public static class Result
    {
        public final float[] exteriorLines;
        public final float[] interiorLines;

        Result(float[] exteriorLines, float[] interiorLines)
        {
            this.exteriorLines = exteriorLines;
            this.interiorLines = interiorLines;
        }
    }

    public static Result computeCrossSection(float[] positions, int[] indices, Vector3f planePoint, Vector3f planeNormal)
    {
        List<float[]> segments = new ArrayList<>();
        List<Float> midDistances = new ArrayList<>();

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

            List<Vector3f> crossPoints = new ArrayList<>(2);
            addEdgeCrossing(a, da, b, db, crossPoints);
            addEdgeCrossing(b, db, c, dc, crossPoints);
            addEdgeCrossing(c, dc, a, da, crossPoints);

            if (crossPoints.size() == 2)
            {
                Vector3f p1 = crossPoints.get(0);
                Vector3f p2 = crossPoints.get(1);
                segments.add(new float[]{p1.x, p1.y, p1.z, p2.x, p2.y, p2.z});

                float midDist = (p1.length() + p2.length()) / 2.0f;
                midDistances.add(midDist);
            }
        }

        if (segments.isEmpty())
        {
            return new Result(new float[0], new float[0]);
        }

        float meanDist = 0f;
        for (float d : midDistances) meanDist += d;
        meanDist /= midDistances.size();

        List<Float> exterior = new ArrayList<>();
        List<Float> interior = new ArrayList<>();

        for (int s = 0; s < segments.size(); s++)
        {
            List<Float> target = (midDistances.get(s) > meanDist) ? exterior : interior;
            for (float value : segments.get(s)) target.add(value);
        }

        return new Result(toFloatArray(exterior), toFloatArray(interior));
    }

    private static void addEdgeCrossing(Vector3f p1, float d1, Vector3f p2, float d2, List<Vector3f> out)
    {
        if ((d1 > 0 && d2 > 0) || (d1 < 0 && d2 < 0)) return;
        if (d1 == d2) return;

        float t = d1 / (d1 - d2);
        out.add(new Vector3f(
                p1.x + t * (p2.x - p1.x),
                p1.y + t * (p2.y - p1.y),
                p1.z + t * (p2.z - p1.z)
        ));
    }

    private static float[] toFloatArray(List<Float> list)
    {
        float[] array = new float[list.size()];
        for (int i = 0; i < array.length; i++) array[i] = list.get(i);
        return array;
    }
}
