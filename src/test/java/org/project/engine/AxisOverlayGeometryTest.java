package org.project.engine;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Testeaza OpenGLRenderer.buildAxisOverlayGeometry - constructia liniilor GL_LINES pt cele
// 5 inele (esantionate la 5 inaltimi de-a lungul ciobului, fiecare cu raza REALA gasita in
// mesh la acea inaltime, nu o raza fixa) + axa care le uneste, afisate in fereastra
// principala cand selectezi un ciob. Pur geometrie (fara context OpenGL), deci testabila
// direct.
class AxisOverlayGeometryTest
{
    @Test
    void producesFiveRingsPlusAxisLineWithCorrectVertexCount()
    {
        float[] positions = {0, -1, 0,  1, 0, 0,  0, 1, 0,  -1, 0, 0};
        CurvatureClassifier.VesselAxisEstimate axis =
                new CurvatureClassifier.VesselAxisEstimate(new Vector3f(0, 1, 0), new Vector3f(0, 0, 0), 2.0f, true);

        float[] lineData = OpenGLRenderer.buildAxisOverlayGeometry(positions, axis);

        // 5 inele x 48 segmente x 2 capete + 1 segment pt axa (2 capete) = 5*48*2 + 2
        assertEquals((5 * 48 * 2 + 2) * 3, lineData.length);
    }

    @Test
    void everyRingSamplesTheActualMeshRadiusAtItsOwnHeight()
    {
        // 5 inele de puncte reale in mesh, exact la inaltimile pe care algoritmul le va
        // alege (span/4 intre tMin=-2 si tMax=2 da exact -2,-1,0,1,2 pt 5 inele), toate la
        // aceeasi raza cunoscuta - fiecare inel ar trebui sa gaseasca exact raza asta din
        // punctele reale, nu o valoare fixa din axis.radius.
        float radius = 3.0f;
        int pointsPerRing = 8;
        List<Float> verts = new ArrayList<>();
        for (int ring = 0; ring < 5; ring++)
        {
            float y = -2 + ring; // -2,-1,0,1,2
            for (int i = 0; i < pointsPerRing; i++)
            {
                double angle = 2 * Math.PI * i / pointsPerRing;
                verts.add((float) Math.cos(angle) * radius);
                verts.add(y);
                verts.add((float) Math.sin(angle) * radius);
            }
        }
        float[] positions = toArray(verts);
        CurvatureClassifier.VesselAxisEstimate axis =
                new CurvatureClassifier.VesselAxisEstimate(new Vector3f(0, 1, 0), new Vector3f(0, 0, 0), 99f, true);

        float[] lineData = OpenGLRenderer.buildAxisOverlayGeometry(positions, axis);

        // Ultimele 2 puncte (6 floats) sunt capetele axei, nu puncte de inel - le excludem.
        int ringFloats = lineData.length - 6;
        for (int i = 0; i < ringFloats; i += 3)
        {
            float x = lineData[i], z = lineData[i + 2];
            float distXZ = (float) Math.sqrt(x * x + z * z);
            assertEquals(radius, distXZ, 1e-2f, "punctul de inel la index " + i + " nu e la raza reala din mesh");
        }
    }

    @Test
    void axisLineEndpointsMatchTheSherdsOwnHeightExtentExactly()
    {
        // Fara marja - capetele axei coincid exact cu tMin/tMax proiectate pe axa Y.
        float[] positions = {0, -1, 0,  0.1f, 1, 0};
        CurvatureClassifier.VesselAxisEstimate axis =
                new CurvatureClassifier.VesselAxisEstimate(new Vector3f(0, 1, 0), new Vector3f(0, 0, 0), 1.0f, true);

        float[] lineData = OpenGLRenderer.buildAxisOverlayGeometry(positions, axis);
        int n = lineData.length;
        float bottomY = lineData[n - 6 + 1];
        float topY = lineData[n - 3 + 1];

        assertEquals(-1f, bottomY, 1e-3f);
        assertEquals(1f, topY, 1e-3f);
    }

    @Test
    void degenerateZeroAxisDirectionFallsBackWithoutCrashingOrNaN()
    {
        float[] positions = {0, 0, 0,  1, 1, 1};
        CurvatureClassifier.VesselAxisEstimate axis =
                new CurvatureClassifier.VesselAxisEstimate(new Vector3f(0, 0, 0), new Vector3f(0, 0, 0), 1.0f, false);

        float[] lineData = OpenGLRenderer.buildAxisOverlayGeometry(positions, axis);

        assertTrue(lineData.length > 0);
        for (float v : lineData)
        {
            assertTrue(Float.isFinite(v), "coordonata non-finita in geometria overlay-ului: " + v);
        }
    }

    @Test
    void emptyMeshProducesFiniteDegenerateGeometryWithoutCrashing()
    {
        CurvatureClassifier.VesselAxisEstimate axis =
                new CurvatureClassifier.VesselAxisEstimate(new Vector3f(0, 1, 0), new Vector3f(0, 0, 0), 1.0f, false);

        float[] lineData = OpenGLRenderer.buildAxisOverlayGeometry(new float[0], axis);

        assertTrue(lineData.length > 0);
        for (float v : lineData)
        {
            assertTrue(Float.isFinite(v));
        }
    }

    private static float[] toArray(List<Float> list)
    {
        float[] array = new float[list.size()];
        for (int i = 0; i < array.length; i++) array[i] = list.get(i);
        return array;
    }
}
