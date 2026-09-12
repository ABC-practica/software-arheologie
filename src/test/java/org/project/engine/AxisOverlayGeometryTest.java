package org.project.engine;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Testeaza OpenGLRenderer.buildAxisOverlayGeometry - constructia liniilor GL_LINES pt cele
// 3 cercuri (jos/mijloc/sus) + axa care le uneste, afisate in fereastra principala cand
// selectezi un ciob. Pur geometrie (fara context OpenGL), deci testabila direct.
class AxisOverlayGeometryTest
{
    @Test
    void producesThreeCirclesPlusAxisLineWithCorrectVertexCount()
    {
        float[] positions = {0, -1, 0,  1, 0, 0,  0, 1, 0,  -1, 0, 0};
        CurvatureClassifier.VesselAxisEstimate axis =
                new CurvatureClassifier.VesselAxisEstimate(new Vector3f(0, 1, 0), new Vector3f(0, 0, 0), 2.0f, true);

        float[] lineData = OpenGLRenderer.buildAxisOverlayGeometry(positions, axis);

        // 3 cercuri x 48 segmente x 2 capete + 1 segment pt axa (2 capete) = 3*48*2 + 2
        assertEquals((3 * 48 * 2 + 2) * 3, lineData.length);
    }

    @Test
    void everyCirclePointIsAtEstimatedRadiusFromItsCircleCenter()
    {
        // Toate punctele de mesh au acelasi t de-a lungul axei (y=0), deci span=0 -> cele
        // 3 cercuri sunt separate doar de marja minima (radius*0.3), usor de localizat.
        float[] positions = {1, 0, 0,  0, 0, 1,  -1, 0, 0,  0, 0, -1};
        float radius = 3.0f;
        CurvatureClassifier.VesselAxisEstimate axis =
                new CurvatureClassifier.VesselAxisEstimate(new Vector3f(0, 1, 0), new Vector3f(0, 0, 0), radius, true);

        float[] lineData = OpenGLRenderer.buildAxisOverlayGeometry(positions, axis);

        // Ultimele 2 puncte (6 floats) sunt capetele axei, nu puncte de cerc - le excludem.
        int circleFloats = lineData.length - 6;
        for (int i = 0; i < circleFloats; i += 3)
        {
            Vector3f p = new Vector3f(lineData[i], lineData[i + 1], lineData[i + 2]);
            // Centrul cercului lui e la aceeasi inaltime Y ca punctul (axa e verticala,
            // toate cercurile perpendiculare pe Y) - distanta pe planul XZ trebuie sa fie exact raza.
            float distXZ = (float) Math.sqrt(p.x * p.x + p.z * p.z);
            assertEquals(radius, distXZ, 1e-3f, "punct de cerc la index " + i + " nu e la raza asteptata");
        }
    }

    @Test
    void axisLineEndpointsMatchOutermostCircleCentersIncludingMargin()
    {
        // Mesh cu extindere cunoscuta pe axa Y: t in [-1, 1] (span=2). Marja = max(0.3*span, 0.3*radius).
        float radius = 1.0f;
        float[] positions = {0, -1, 0,  0.1f, 1, 0};
        CurvatureClassifier.VesselAxisEstimate axis =
                new CurvatureClassifier.VesselAxisEstimate(new Vector3f(0, 1, 0), new Vector3f(0, 0, 0), radius, true);

        float[] lineData = OpenGLRenderer.buildAxisOverlayGeometry(positions, axis);
        int n = lineData.length;
        float bottomY = lineData[n - 6 + 1];
        float topY = lineData[n - 3 + 1];

        float span = 2.0f; // tMax(1) - tMin(-1), proiectat pe axa Y
        float margin = Math.max(span * 0.3f, radius * 0.3f);
        assertEquals(-1 - margin, bottomY, 1e-3f);
        assertEquals(1 + margin, topY, 1e-3f);
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
}
