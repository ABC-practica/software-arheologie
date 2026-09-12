package org.project.engine;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Testeaza CurvatureClassifier.Result.exteriorAxisEstimate - ghicitul axei de rotatie a
// vasului din care facea parte ciobul, folosit de overlay-ul cu cele 3 cercuri din
// fereastra principala. Reutilizeaza acelasi tip de fixture (grid curbat sintetic) ca
// QuadraticEcuationTests.java, pentru ca fitul de curbura e acelasi mecanism.
class VesselAxisEstimateTest
{
    @Test
    void cylindricalPatchPicksFlatDirectionAsAxisAndRecoversRadius()
    {
        // Perete curbat DOAR pe o directie (h = k*y^2, plat pe z) - exact geometria unui
        // cilindru local. Axa vasului ar trebui sa fie directia FARA curbura (z, nu y),
        // iar raza estimata ar trebui sa recupereze raza reala a cilindrului: pt un profil
        // h ~= u^2/(2R) (aproximarea parabolica a unui cerc de raza R), avem k = 1/(2R),
        // deci R = 1/(2k). Aici k=0.1 -> R asteptat = 5.0.
        float k = 0.1f;
        float[] ys = {-2, -1, 0, 1, 2};
        float[] zs = {-1, 0, 1};
        List<Float> verts = new ArrayList<>();
        for (int i = 0; i < ys.length - 1; i++)
        {
            for (int j = 0; j < zs.length - 1; j++)
            {
                float[] a = {2 + k * ys[i] * ys[i], ys[i], zs[j]};
                float[] b = {2 + k * ys[i + 1] * ys[i + 1], ys[i + 1], zs[j]};
                float[] c = {2 + k * ys[i] * ys[i], ys[i], zs[j + 1]};
                float[] d = {2 + k * ys[i + 1] * ys[i + 1], ys[i + 1], zs[j + 1]};
                addTri(verts, a[0], a[1], a[2],  b[0], b[1], b[2],  c[0], c[1], c[2]);
                addTri(verts, b[0], b[1], b[2],  d[0], d[1], d[2],  c[0], c[1], c[2]);
            }
        }
        float[] positions = toArray(verts);
        int[] indices = sequentialIndices(positions.length / 3);

        CurvatureClassifier.Result result = CurvatureClassifier.classify(positions, indices);
        CurvatureClassifier.VesselAxisEstimate axis = result.exteriorAxisEstimate;

        assertTrue(axis.reliable, "curbura e clar mai mare decat pragul minim, fitul ar trebui considerat de incredere");
        // Directia axei ar trebui sa fie +-Z (directia fara curbura), nu Y.
        assertEquals(1.0, Math.abs(axis.axisDirection.z), 0.05);
        assertEquals(0.0, Math.abs(axis.axisDirection.y), 0.05);
        assertEquals(5.0, axis.radius, 0.5, "raza estimata ar trebui sa recupereze R=1/(2k) a cilindrului local");
    }

    @Test
    void flatWallHasUnreliableAxisEstimateButStillFinite()
    {
        // Perete complet drept - fara curbura, deci nu exista niciun semnal pe care sa se
        // bazeze o axa/raza reala. Trebuie marcat "reliable=false", dar tot sa produca
        // o directie/punct/raza finite (fallback), nu NaN/Infinity si nu crash.
        List<Float> verts = new ArrayList<>();
        for (int i = -3; i < 3; i++)
        {
            float y0 = i, y1 = i + 1;
            addTri(verts, 2, y0, -1,  2, y1, -1,  2, y0, 1);
            addTri(verts, 2, y1, -1,  2, y1, 1,   2, y0, 1);
        }
        float[] positions = toArray(verts);
        int[] indices = sequentialIndices(positions.length / 3);

        CurvatureClassifier.Result result = CurvatureClassifier.classify(positions, indices);
        CurvatureClassifier.VesselAxisEstimate axis = result.exteriorAxisEstimate;

        assertFalse(axis.reliable);
        assertTrue(Float.isFinite(axis.axisDirection.x) && Float.isFinite(axis.axisDirection.y) && Float.isFinite(axis.axisDirection.z));
        assertTrue(Float.isFinite(axis.axisPoint.x) && Float.isFinite(axis.axisPoint.y) && Float.isFinite(axis.axisPoint.z));
        assertTrue(Float.isFinite(axis.radius) && axis.radius > 0);
    }

    @Test
    void emptyMeshAxisEstimateIsUnreliableButFinite()
    {
        CurvatureClassifier.Result result = CurvatureClassifier.classify(new float[0], new int[0]);
        CurvatureClassifier.VesselAxisEstimate axis = result.exteriorAxisEstimate;

        assertFalse(axis.reliable);
        assertTrue(Float.isFinite(axis.radius));
    }

    private static void addTri(List<Float> out, float... v)
    {
        for (float f : v) out.add(f);
    }

    private static float[] toArray(List<Float> list)
    {
        float[] array = new float[list.size()];
        for (int i = 0; i < array.length; i++) array[i] = list.get(i);
        return array;
    }

    private static int[] sequentialIndices(int vertexCount)
    {
        int[] indices = new int[vertexCount];
        for (int i = 0; i < vertexCount; i++) indices[i] = i;
        return indices;
    }
}
