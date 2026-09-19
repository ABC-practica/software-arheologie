package org.project.engine;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Testeaza SherdShapeClassifier pe tuburi sintetice (fara capace) cu combinatii cunoscute
// de capete netede/circulare (= "originale") vs zimtate (= rupturi), la raze mici (langa
// axa, tip fund) sau mari (tip buza). Nu testam praguri exacte "corecte" arheologic (nu
// avem date reale etichetate) - testam ca euristica reactioneaza in directia asteptata pe
// cazuri clare, fara ambiguitate.
class SherdShapeClassifierTest
{
    @Test
    void wideSmoothTopAndNarrowJaggedBottomClassifiesAsBuza()
    {
        // Sus: cerc perfect la raza mare (3.0) - o buza intacta. Jos: zimtat la raza mica -
        // o ruptura oarecare mai aproape de baza vasului.
        float[] positions = buildTube(32, 1.5f, true, 3.0f, false, 0, 5);
        int[] indices = buildTubeIndices(32);

        CurvatureClassifier.VesselAxisEstimate axis =
                new CurvatureClassifier.VesselAxisEstimate(new Vector3f(0, 1, 0), new Vector3f(0, 0, 0), 3.0f, true);
        GhostVesselGenerator.VesselProfile profile = buildProfileStub(positions, axis);

        SherdShapeClassifier.Result result = SherdShapeClassifier.classify(positions, indices, axis, profile);

        assertEquals(SherdShapeClassifier.Category.BUZA, result.category);
        assertTrue(result.confidence > 0, "un caz clar nu ar trebui sa dea incredere zero");
        assertEquals(6.0, result.maxDiameter, 0.3);
    }

    @Test
    void narrowSmoothBottomAndWideJaggedTopClassifiesAsFund()
    {
        // Jos: cerc perfect la raza mica (1.0, aproape de axa) - un fund/picior intact.
        // Sus: zimtat la raza mare - o ruptura undeva mai sus pe corpul vasului.
        float[] positions = buildTube(32, 1.0f, false, 3.0f, true, 0, 5);
        int[] indices = buildTubeIndices(32);

        CurvatureClassifier.VesselAxisEstimate axis =
                new CurvatureClassifier.VesselAxisEstimate(new Vector3f(0, 1, 0), new Vector3f(0, 0, 0), 1.0f, true);
        GhostVesselGenerator.VesselProfile profile = buildProfileStub(positions, axis);

        SherdShapeClassifier.Result result = SherdShapeClassifier.classify(positions, indices, axis, profile);

        assertEquals(SherdShapeClassifier.Category.FUND, result.category);
    }

    @Test
    void bothEndsJaggedClassifiesAsMargineLaterala()
    {
        // Niciun capat nu are o muchie originala - fragment pur de corp/lateral.
        float[] positions = buildTube(32, 1.5f, true, 3.0f, true, 0, 5);
        int[] indices = buildTubeIndices(32);

        CurvatureClassifier.VesselAxisEstimate axis =
                new CurvatureClassifier.VesselAxisEstimate(new Vector3f(0, 1, 0), new Vector3f(0, 0, 0), 2.0f, true);
        GhostVesselGenerator.VesselProfile profile = buildProfileStub(positions, axis);

        SherdShapeClassifier.Result result = SherdShapeClassifier.classify(positions, indices, axis, profile);

        assertEquals(SherdShapeClassifier.Category.MARGINE_LATERALA, result.category);
        // Regresie: increderea pt MARGINE_LATERALA foloseste o formula INVERSATA fata de
        // FUND/BUZA (creste cu cat scorul e mai departe DEASUPRA pragului) - refolosirea
        // gresita a formulei celeilalte dadea mereu 0% aici, indiferent cat de clar zimtate
        // erau ambele capete (bug real, gasit rulind pe mesh-uri reale).
        assertTrue(result.confidence > 0.5f, "un caz clar zimtat pe ambele capete ar trebui sa dea incredere mare, nu 0%");
    }

    @Test
    void bothEndsSmoothAndCircularClassifiesAsNecunoscutWithZeroConfidence()
    {
        // Ambele capete arata deopotriva "originale" (netede+circulare) - geometric
        // ambiguu (cioburi reale nu ar trebui sa aiba asta des, dar cand se intampla nu
        // trebuie sa ghicim). Spre deosebire de MARGINE_LATERALA, aici 0% e chiar
        // comportamentul corect, nu un bug.
        float[] positions = buildTube(32, 1.0f, false, 3.0f, false, 0, 5);
        int[] indices = buildTubeIndices(32);

        CurvatureClassifier.VesselAxisEstimate axis =
                new CurvatureClassifier.VesselAxisEstimate(new Vector3f(0, 1, 0), new Vector3f(0, 0, 0), 2.0f, true);
        GhostVesselGenerator.VesselProfile profile = buildProfileStub(positions, axis);

        SherdShapeClassifier.Result result = SherdShapeClassifier.classify(positions, indices, axis, profile);

        assertEquals(SherdShapeClassifier.Category.NECUNOSCUT, result.category);
        assertEquals(0f, result.confidence);
    }

    @Test
    void quarterArcSherdReportsPreservedArcAroundNinetyDegrees()
    {
        // Doar un sfert din cercul complet (0..90 grade) - restul de 270 de grade lipsesc,
        // deci acoperirea unghiulara detectata trebuie sa fie aproape de 90, nu de 360.
        int n = 16;
        List<Float> verts = new ArrayList<>();
        for (int i = 0; i <= n; i++)
        {
            double angle = (Math.PI / 2.0) * i / n; // 0..90 grade
            float x = (float) Math.cos(angle) * 3f, z = (float) Math.sin(angle) * 3f;
            verts.add(x); verts.add(0f); verts.add(z);
            verts.add(x); verts.add(5f); verts.add(z);
        }
        List<Integer> idx = new ArrayList<>();
        for (int i = 0; i < n; i++)
        {
            int b0 = i * 2, t0 = i * 2 + 1, b1 = (i + 1) * 2, t1 = (i + 1) * 2 + 1;
            idx.add(b0); idx.add(b1); idx.add(t0);
            idx.add(b1); idx.add(t1); idx.add(t0);
        }
        float[] positions = toArray(verts);
        int[] indices = toIntArray(idx);

        CurvatureClassifier.VesselAxisEstimate axis =
                new CurvatureClassifier.VesselAxisEstimate(new Vector3f(0, 1, 0), new Vector3f(0, 0, 0), 3.0f, true);
        GhostVesselGenerator.VesselProfile profile = buildProfileStub(positions, axis);

        SherdShapeClassifier.Result result = SherdShapeClassifier.classify(positions, indices, axis, profile);

        assertEquals(90.0, result.preservedArcDeg, 15.0);
    }

    @Test
    void emptyMeshReturnsNecunoscutWithoutCrashing()
    {
        CurvatureClassifier.VesselAxisEstimate axis =
                new CurvatureClassifier.VesselAxisEstimate(new Vector3f(0, 1, 0), new Vector3f(0, 0, 0), 1.0f, false);
        GhostVesselGenerator.VesselProfile emptyProfile = new GhostVesselGenerator.VesselProfile(new float[0], new float[0]);

        SherdShapeClassifier.Result result = SherdShapeClassifier.classify(new float[0], new int[0], axis, emptyProfile);

        assertEquals(0f, result.maxDiameter);
        assertEquals(0f, result.preservedArcDeg);
        assertTrue(Float.isFinite(result.confidence));
    }

    /** Tub fara capace: un inel "jos" (y=0) si unul "sus" (y=topY), fiecare fie cerc perfect
     * (jagged=false) fie zimtat puternic (jagged=true, raza alterneaza intre vecini). */
    private static float[] buildTube(int n, float bottomR, boolean bottomJagged,
                                       float topR, boolean topJagged, float bottomY, float topY)
    {
        List<Float> verts = new ArrayList<>();
        for (int i = 0; i < n; i++)
        {
            double angle = 2 * Math.PI * i / n;
            float rBottom = bottomJagged ? bottomR + (i % 2 == 0 ? 0.6f : -0.6f) : bottomR;
            verts.add((float) Math.cos(angle) * rBottom);
            verts.add(bottomY);
            verts.add((float) Math.sin(angle) * rBottom);
        }
        for (int i = 0; i < n; i++)
        {
            double angle = 2 * Math.PI * i / n;
            float rTop = topJagged ? topR + (i % 2 == 0 ? 0.6f : -0.6f) : topR;
            verts.add((float) Math.cos(angle) * rTop);
            verts.add(topY);
            verts.add((float) Math.sin(angle) * rTop);
        }
        return toArray(verts);
    }

    private static int[] buildTubeIndices(int n)
    {
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

    /** SherdShapeClassifier doesn't need a SceneObject - construim profilul direct din
     * pozitii, la fel cum ar face GhostVesselGenerator.extractProfile intern. */
    private static GhostVesselGenerator.VesselProfile buildProfileStub(float[] positions, CurvatureClassifier.VesselAxisEstimate axis)
    {
        int bins = 20;
        Vector3f dir = new Vector3f(axis.axisDirection).normalize();
        float hMin = Float.MAX_VALUE, hMax = -Float.MAX_VALUE;
        for (int i = 0; i < positions.length; i += 3)
        {
            Vector3f p = new Vector3f(positions[i], positions[i + 1], positions[i + 2]).sub(axis.axisPoint);
            float h = p.dot(dir);
            if (h < hMin) hMin = h;
            if (h > hMax) hMax = h;
        }
        float[] heights = new float[bins];
        float[] radii = new float[bins];
        int[] counts = new int[bins];
        for (int i = 0; i < bins; i++) heights[i] = hMin + (hMax - hMin) * i / (bins - 1);
        for (int i = 0; i < positions.length; i += 3)
        {
            Vector3f p = new Vector3f(positions[i], positions[i + 1], positions[i + 2]).sub(axis.axisPoint);
            float h = p.dot(dir);
            Vector3f proj = new Vector3f(dir).mul(h);
            float r = p.sub(proj).length();
            int bin = Math.max(0, Math.min(bins - 1, (int) (((h - hMin) / Math.max(1e-6f, hMax - hMin)) * (bins - 1))));
            if (r > radii[bin]) radii[bin] = r;
            counts[bin]++;
        }
        return new GhostVesselGenerator.VesselProfile(heights, radii);
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
