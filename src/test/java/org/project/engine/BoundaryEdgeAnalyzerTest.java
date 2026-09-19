package org.project.engine;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Testeaza BoundaryEdgeAnalyzer pe un tub sintetic (fara capace) cu doi pereti circulari:
// unul PERFECT circular (ca o buza/fund original, netaiat) si unul ZIMTAT (ca o ruptura).
// Verificam ca scorul distinge clar intre cele doua, nu doar ca nu crapa.
class BoundaryEdgeAnalyzerTest
{
    @Test
    void smoothCircularEndScoresMuchBetterThanJaggedBrokenEnd()
    {
        int n = 32;
        float bottomRadiusBase = 3.0f;
        float topRadius = 3.0f;

        Vector3f[] bottom = new Vector3f[n];
        Vector3f[] top = new Vector3f[n];
        for (int i = 0; i < n; i++)
        {
            double angle = 2 * Math.PI * i / n;
            // Capatul de jos: zimtat - raza alterneaza puternic intre vecini (o ruptura reala).
            float jaggedR = bottomRadiusBase + (i % 2 == 0 ? 1.2f : -1.2f);
            bottom[i] = new Vector3f((float) Math.cos(angle) * jaggedR, 0, (float) Math.sin(angle) * jaggedR);
            // Capatul de sus: cerc perfect - o muchie originala neteda (buza sau fund intact).
            top[i] = new Vector3f((float) Math.cos(angle) * topRadius, 5, (float) Math.sin(angle) * topRadius);
        }

        float[] positions = buildTubePositions(bottom, top);
        int[] indices = buildTubeIndices(n);

        CurvatureClassifier.VesselAxisEstimate axis =
                new CurvatureClassifier.VesselAxisEstimate(new Vector3f(0, 1, 0), new Vector3f(0, 0, 0), 3.0f, true);

        BoundaryEdgeAnalyzer.EdgeBand[] bands = BoundaryEdgeAnalyzer.analyzeBands(positions, indices, axis, 0.3f);
        BoundaryEdgeAnalyzer.EdgeBand bottomBand = bands[0];
        BoundaryEdgeAnalyzer.EdgeBand topBand = bands[1];

        assertTrue(bottomBand.pointCount >= 8, "capatul de jos ar trebui sa aiba destule puncte de granita de analizat");
        assertTrue(topBand.pointCount >= 8, "capatul de sus ar trebui sa aiba destule puncte de granita de analizat");

        assertTrue(topBand.score() < bottomBand.score() / 2,
                "capatul circular neted (sus, scor=" + topBand.score() + ") ar trebui sa arate mult mai 'original' decat cel zimtat (jos, scor=" + bottomBand.score() + ")");
        // Cercul de sus e aproape perfect - reziduul de circularitate trebuie sa fie foarte mic.
        assertEquals(0.0, topBand.circularity, 0.02);
    }

    @Test
    void closedMeshWithNoBoundaryReportsZeroPointsAtBothEnds()
    {
        // Un tub cu "capace" (triunghiuri care inchid ambele capete) nu are nicio muchie de
        // granita reala - toate muchiile sunt impartite intre exact 2 triunghiuri.
        int n = 12;
        Vector3f[] bottom = new Vector3f[n];
        Vector3f[] top = new Vector3f[n];
        for (int i = 0; i < n; i++)
        {
            double angle = 2 * Math.PI * i / n;
            bottom[i] = new Vector3f((float) Math.cos(angle) * 2, 0, (float) Math.sin(angle) * 2);
            top[i] = new Vector3f((float) Math.cos(angle) * 2, 4, (float) Math.sin(angle) * 2);
        }

        List<Float> verts = new ArrayList<>();
        List<Integer> idx = new ArrayList<>();
        int vCount = 0;
        vCount = addTubeSideWithCaps(verts, idx, bottom, top, vCount);

        float[] positions = toArray(verts);
        int[] indices = toIntArray(idx);

        CurvatureClassifier.VesselAxisEstimate axis =
                new CurvatureClassifier.VesselAxisEstimate(new Vector3f(0, 1, 0), new Vector3f(0, 0, 0), 2.0f, true);

        BoundaryEdgeAnalyzer.EdgeBand[] bands = BoundaryEdgeAnalyzer.analyzeBands(positions, indices, axis, 0.3f);

        assertEquals(0, bands[0].pointCount);
        assertEquals(0, bands[1].pointCount);
        assertEquals(Float.POSITIVE_INFINITY, bands[0].score());
        assertEquals(Float.POSITIVE_INFINITY, bands[1].score());
    }

    @Test
    void emptyMeshReturnsZeroPointBandsWithoutCrashing()
    {
        CurvatureClassifier.VesselAxisEstimate axis =
                new CurvatureClassifier.VesselAxisEstimate(new Vector3f(0, 1, 0), new Vector3f(0, 0, 0), 1.0f, false);

        BoundaryEdgeAnalyzer.EdgeBand[] bands = BoundaryEdgeAnalyzer.analyzeBands(new float[0], new int[0], axis, 0.3f);

        assertEquals(0, bands[0].pointCount);
        assertEquals(0, bands[1].pointCount);
    }

    private static float[] buildTubePositions(Vector3f[] bottom, Vector3f[] top)
    {
        List<Float> verts = new ArrayList<>();
        for (Vector3f v : bottom) { verts.add(v.x); verts.add(v.y); verts.add(v.z); }
        for (Vector3f v : top) { verts.add(v.x); verts.add(v.y); verts.add(v.z); }
        return toArray(verts);
    }

    private static int[] buildTubeIndices(int n)
    {
        // varfuri: bottom[0..n-1] apoi top[0..n-1] (offset n)
        List<Integer> idx = new ArrayList<>();
        for (int i = 0; i < n; i++)
        {
            int i1 = (i + 1) % n;
            int b0 = i, b1 = i1, t0 = n + i, t1 = n + i1;
            idx.add(b0); idx.add(b1); idx.add(t0);
            idx.add(b1); idx.add(t1); idx.add(t0);
        }
        return toIntArray(idx);
    }

    private static int addTubeSideWithCaps(List<Float> verts, List<Integer> idx, Vector3f[] bottom, Vector3f[] top, int vBase)
    {
        int n = bottom.length;
        for (Vector3f v : bottom) { verts.add(v.x); verts.add(v.y); verts.add(v.z); }
        for (Vector3f v : top) { verts.add(v.x); verts.add(v.y); verts.add(v.z); }
        int bottomCenterIdx = vBase + 2 * n;
        verts.add(0f); verts.add(bottom[0].y); verts.add(0f);
        int topCenterIdx = bottomCenterIdx + 1;
        verts.add(0f); verts.add(top[0].y); verts.add(0f);

        for (int i = 0; i < n; i++)
        {
            int i1 = (i + 1) % n;
            int b0 = vBase + i, b1 = vBase + i1, t0 = vBase + n + i, t1 = vBase + n + i1;
            idx.add(b0); idx.add(b1); idx.add(t0);
            idx.add(b1); idx.add(t1); idx.add(t0);
            // capac de jos (triunghi spre centru)
            idx.add(bottomCenterIdx); idx.add(b1); idx.add(b0);
            // capac de sus
            idx.add(topCenterIdx); idx.add(t0); idx.add(t1);
        }
        return vBase + 2 * n + 2;
    }

    private static float[] toArray(List<Float> list)
    {
        float[] array = new float[list.size()];
        for (int i = 0; i < array.length; i++) array[i] = list.get(i);
        return array;
    }

    private static int[] toIntArray(List<Integer> list)
    {
        int[] array = new int[list.size()];
        for (int i = 0; i < array.length; i++) array[i] = list.get(i);
        return array;
    }
}
