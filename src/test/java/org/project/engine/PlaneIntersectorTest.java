package org.project.engine;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// PlaneIntersector nu are nevoie de OpenGL/JavaFX ca sa functioneze - primeste
// doar array-uri de float/int si intoarce alte array-uri. De-asta poate fi
// testat direct, cu date mock, fara fereastra, fara context GL.
class PlaneIntersectorTest
{
    private static final Vector3f PLANE_POINT = new Vector3f(0, 0, 0);
    private static final Vector3f PLANE_NORMAL = new Vector3f(0, 0, 1);

    @Test
    void triangleEntirelyOnPositiveSideIsExcluded()
    {
        float[] positions = triangle(
                0, 0, 1,
                1, 0, 1,
                0, 1, 1);
        int[] indices = {0, 1, 2};

        float[] result = PlaneIntersector.findCrossedTriangles(positions, indices, PLANE_POINT, PLANE_NORMAL);

        assertEquals(0, result.length);
    }

    @Test
    void triangleEntirelyOnNegativeSideIsExcluded()
    {
        float[] positions = triangle(
                0, 0, -1,
                1, 0, -1,
                0, 1, -1);
        int[] indices = {0, 1, 2};

        float[] result = PlaneIntersector.findCrossedTriangles(positions, indices, PLANE_POINT, PLANE_NORMAL);

        assertEquals(0, result.length);
    }

    @Test
    void triangleStraddlingThePlaneIsIncluded()
    {
        float[] positions = triangle(
                0, 0, -1,
                1, 0, 1,
                0, 1, 1);
        int[] indices = {0, 1, 2};

        float[] result = PlaneIntersector.findCrossedTriangles(positions, indices, PLANE_POINT, PLANE_NORMAL);

        assertEquals(9, result.length);
    }

    @Test
    void triangleTouchingThePlaneWithOneVertexIsIncluded()
    {
        // Un varf exact pe plan (distanta 0) nu e nici ">0" nici "<0", deci
        // triunghiul nu se incadreaza la "allPositive"/"allNegative" si e pastrat.
        // E un caz-limita real, bun de documentat printr-un test explicit.
        float[] positions = triangle(
                0, 0, 0,
                1, 0, 1,
                0, 1, 1);
        int[] indices = {0, 1, 2};

        float[] result = PlaneIntersector.findCrossedTriangles(positions, indices, PLANE_POINT, PLANE_NORMAL);

        assertEquals(9, result.length);
    }

    @Test
    void onlyCrossedTrianglesAreKeptOutOfSeveral()
    {
        float[] positions = concat(
                triangleVerts(0, 0, 1, 1, 0, 1, 0, 1, 1),   // nu traverseaza
                triangleVerts(0, 0, -1, 1, 0, 1, 0, 1, 1),  // traverseaza
                triangleVerts(0, 0, -2, 1, 0, -1, 0, 1, -1) // nu traverseaza
        );
        int[] indices = {0, 1, 2, 3, 4, 5, 6, 7, 8};

        float[] result = PlaneIntersector.findCrossedTriangles(positions, indices, PLANE_POINT, PLANE_NORMAL);

        assertEquals(9, result.length);
        assertEquals(-1f, result[2]); // z-ul primului varf al triunghiului gasit
    }

    @Test
    void movingThePlaneChangesWhichTrianglesCross()
    {
        float[] positions = triangle(
                0, 0, -1,
                1, 0, 1,
                0, 1, 1);
        int[] indices = {0, 1, 2};

        // fata de planul z=0, triunghiul traverseaza; mutat departe (z=5), planul nu-l mai atinge
        Vector3f farPlanePoint = new Vector3f(0, 0, 5);

        float[] result = PlaneIntersector.findCrossedTriangles(positions, indices, farPlanePoint, PLANE_NORMAL);

        assertEquals(0, result.length);
    }

    @Test
    void emptyMeshReturnsNoTriangles()
    {
        float[] result = PlaneIntersector.findCrossedTriangles(new float[0], new int[0], PLANE_POINT, PLANE_NORMAL);

        assertEquals(0, result.length);
    }

    @Test
    void degenerateZeroAreaTriangleOnPlaneIsIncluded()
    {
        // Cele 3 varfuri coincid (arie zero) - un mesh "murdar" plauzibil dintr-un
        // export prost sau o decimare agresiva. Toate cele 3 distante sunt 0,
        // deci nu e nici allPositive nici allNegative -> ramane inclus.
        float[] positions = triangle(0, 0, 0, 0, 0, 0, 0, 0, 0);
        int[] indices = {0, 1, 2};

        float[] result = PlaneIntersector.findCrossedTriangles(positions, indices, PLANE_POINT, PLANE_NORMAL);

        assertEquals(9, result.length);
    }

    @Test
    void degenerateZeroAreaTriangleOffPlaneIsExcluded()
    {
        float[] positions = triangle(0, 0, 5, 0, 0, 5, 0, 0, 5);
        int[] indices = {0, 1, 2};

        float[] result = PlaneIntersector.findCrossedTriangles(positions, indices, PLANE_POINT, PLANE_NORMAL);

        assertEquals(0, result.length);
    }

    @Test
    void planeNormalMagnitudeDoesNotAffectClassification()
    {
        // Functia nu cere o normala unitara - doar semnul produsului scalar conteaza.
        // O normala de 1000x mai "lunga" trebuie sa dea exact acelasi rezultat.
        float[] positions = triangle(
                0, 0, -1,
                1, 0, 1,
                0, 1, 1);
        int[] indices = {0, 1, 2};
        Vector3f hugeNormal = new Vector3f(0, 0, 1000);

        float[] resultUnitNormal = PlaneIntersector.findCrossedTriangles(positions, indices, PLANE_POINT, PLANE_NORMAL);
        float[] resultHugeNormal = PlaneIntersector.findCrossedTriangles(positions, indices, PLANE_POINT, hugeNormal);

        assertArrayEquals(resultUnitNormal, resultHugeNormal);
    }

    @Test
    void zeroLengthNormalIncludesEveryTriangleRegardlessOfPosition()
    {
        // Caz-limita periculos, bun de documentat explicit: cu o normala (0,0,0),
        // toate distantele calculate sunt 0 pentru orice varf, deci NICIUN triunghi
        // nu e clasificat "allPositive"/"allNegative" - absolut toate raman incluse,
        // indiferent cat de departe sunt fata de plan. Nu trebuie sa se intample
        // niciodata cu o normala calculata corect, dar merita prins printr-un test.
        float[] positions = concat(
                triangleVerts(0, 0, 5, 1, 0, 5, 0, 1, 5),
                triangleVerts(0, 0, -5, 1, 0, -5, 0, 1, -5)
        );
        int[] indices = {0, 1, 2, 3, 4, 5};
        Vector3f zeroNormal = new Vector3f(0, 0, 0);

        float[] result = PlaneIntersector.findCrossedTriangles(positions, indices, PLANE_POINT, zeroNormal);

        assertEquals(18, result.length);
    }

    @Test
    void planeMissingEntireLargeMeshReturnsNoTriangles()
    {
        int triangleCount = 500;
        float[] positions = new float[triangleCount * 9];
        int[] indices = new int[triangleCount * 3];
        for (int t = 0; t < triangleCount; t++)
        {
            int base = t * 9;
            positions[base] = t;     positions[base + 1] = 10;      positions[base + 2] = 10;
            positions[base + 3] = t; positions[base + 4] = 10.5f;   positions[base + 5] = 10;
            positions[base + 6] = t; positions[base + 7] = 10;      positions[base + 8] = 10.5f;

            int idxBase = t * 3;
            indices[idxBase] = t * 3;
            indices[idxBase + 1] = t * 3 + 1;
            indices[idxBase + 2] = t * 3 + 2;
        }

        float[] result = PlaneIntersector.findCrossedTriangles(positions, indices, PLANE_POINT, PLANE_NORMAL);

        assertEquals(0, result.length);
    }

    @Test
    void handlesManyFacetsWithoutExcessiveSlowdown()
    {
        int triangleCount = 200_000;
        float[] positions = new float[triangleCount * 9];
        int[] indices = new int[triangleCount * 3];

        for (int t = 0; t < triangleCount; t++)
        {
            boolean crosses = (t % 2 == 0);
            float thirdVertexZ = crosses ? 0.5f : -0.5f;

            int base = t * 9;
            positions[base] = t;     positions[base + 1] = 0;    positions[base + 2] = -0.5f;
            positions[base + 3] = t; positions[base + 4] = 1;    positions[base + 5] = -0.5f;
            positions[base + 6] = t; positions[base + 7] = 0.5f; positions[base + 8] = thirdVertexZ;

            int idxBase = t * 3;
            indices[idxBase] = t * 3;
            indices[idxBase + 1] = t * 3 + 1;
            indices[idxBase + 2] = t * 3 + 2;
        }

        long start = System.nanoTime();
        float[] result = PlaneIntersector.findCrossedTriangles(positions, indices, PLANE_POINT, PLANE_NORMAL);
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        assertEquals((triangleCount / 2) * 9, result.length);
        assertTrue(elapsedMillis < 2000, "a durat " + elapsedMillis + "ms pentru " + triangleCount + " triunghiuri, posibila regresie O(n^2)");
    }

    @Test
    void nanVertexCoordinateIsSilentlyIncludedNotRejected()
    {
        // O coordonata NaN (posibila dintr-un mesh generat prost, de exemplu de
        // un model AI care intoarce un .obj) face ca TOATE comparatiile ">0"/"<0"
        // sa fie false pentru varful respectiv (NaN nu e nici mai mare, nici mai
        // mic decat 0). Deci triunghiul nu mai poate fi clasificat "allPositive"
        // sau "allNegative" si ramane inclus - silentios, fara nicio exceptie.
        // Un triunghi cu NaN ajunge direct in bufferul trimis mai departe la GPU.
        float[] positions = triangle(
                Float.NaN, 0, 0,
                1, 0, 1,
                0, 1, 1);
        int[] indices = {0, 1, 2};

        float[] result = PlaneIntersector.findCrossedTriangles(positions, indices, PLANE_POINT, PLANE_NORMAL);

        assertEquals(9, result.length);
        assertTrue(Float.isNaN(result[0]));
    }

    @Test
    void negativeIndexFailsLoudlyInsteadOfCorruptingData()
    {
        // Spre deosebire de NaN (esec tacut), un index negativ (posibil dintr-un
        // buffer de indici corupt/gresit generat) crapa imediat cu o exceptie -
        // e de fapt modul "bun" de a esua, pentru ca oprește procesarea in loc
        // sa produca un rezultat gresit fara nicio urma.
        float[] positions = triangle(0, 0, -1, 1, 0, 1, 0, 1, 1);
        int[] indices = {-1, 1, 2};

        assertThrows(ArrayIndexOutOfBoundsException.class, () ->
                PlaneIntersector.findCrossedTriangles(positions, indices, PLANE_POINT, PLANE_NORMAL));
    }

    @Test
    void triangleLyingExactlyInThePlaneIsIncluded()
    {
        // Nu e degenerat (are arie reala) - doar se intampla sa coincida cu planul,
        // gen un capac plat al mesh-ului exact acolo unde userul a pus taietura.
        float[] positions = triangle(
                -1, -1, 0,
                1, -1, 0,
                0, 1, 0);
        int[] indices = {0, 1, 2};

        float[] result = PlaneIntersector.findCrossedTriangles(positions, indices, PLANE_POINT, PLANE_NORMAL);

        assertEquals(9, result.length);
    }

    @Test
    void obliqueNonAxisAlignedPlaneClassifiesCorrectly()
    {
        // Toate celelalte teste folosesc o normala aliniata pe o axa (0,0,1) - asta
        // verifica faptul ca formula generalizeaza corect la o normala oblica,
        // nu doar la cazul particular usor de calculat gresit "din intamplare".
        Vector3f obliqueNormal = new Vector3f(1, 1, 1).normalize();
        Vector3f origin = new Vector3f(0, 0, 0);
        int[] indices = {0, 1, 2};

        float[] straddling = triangle(3, 0, 0, -3, 0, 0, 0, 3, 0);
        float[] sameSide = triangle(3, 0, 0, 0, 3, 0, 0, 0, 3);

        assertEquals(9, PlaneIntersector.findCrossedTriangles(straddling, indices, origin, obliqueNormal).length);
        assertEquals(0, PlaneIntersector.findCrossedTriangles(sameSide, indices, origin, obliqueNormal).length);
    }

    @Test
    void vertexWindingOrderDoesNotAffectClassification()
    {
        // Functia nu calculeaza normala triunghiului (doar distanta fiecarui varf
        // fata de plan), deci ordinea varfurilor nu ar trebui sa conteze deloc -
        // spre deosebire de CurvatureClassifier, unde ordinea chiar conteaza.
        float[] originalOrder = triangle(0, 0, -1, 1, 0, 1, 0, 1, 1);
        float[] reversedOrder = triangle(0, 1, 1, 1, 0, 1, 0, 0, -1);

        float[] resultA = PlaneIntersector.findCrossedTriangles(originalOrder, new int[]{0, 1, 2}, PLANE_POINT, PLANE_NORMAL);
        float[] resultB = PlaneIntersector.findCrossedTriangles(reversedOrder, new int[]{0, 1, 2}, PLANE_POINT, PLANE_NORMAL);

        assertEquals(9, resultA.length);
        assertEquals(9, resultB.length);
    }

    @Test
    void findsAllCrossedTrianglesAmongManyNonCrossing()
    {
        float[] positions = concat(
                triangleVerts(0, 0, 5, 1, 0, 5, 0, 1, 5),     // nu traverseaza (departe, +)
                triangleVerts(0, 0, -1, 1, 0, 1, 0, 1, 1),    // traverseaza #1
                triangleVerts(2, 0, -1, 3, 0, 1, 2, 1, 1),    // traverseaza #2
                triangleVerts(0, 0, -5, 1, 0, -5, 0, 1, -5),  // nu traverseaza (departe, -)
                triangleVerts(4, 0, -1, 5, 0, 1, 4, 1, 1)     // traverseaza #3
        );
        int[] indices = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14};

        float[] result = PlaneIntersector.findCrossedTriangles(positions, indices, PLANE_POINT, PLANE_NORMAL);

        assertEquals(27, result.length); // 3 triunghiuri gasite * 9 valori fiecare
    }

    private static float[] triangle(float ax, float ay, float az, float bx, float by, float bz, float cx, float cy, float cz)
    {
        return triangleVerts(ax, ay, az, bx, by, bz, cx, cy, cz);
    }

    private static float[] triangleVerts(float ax, float ay, float az, float bx, float by, float bz, float cx, float cy, float cz)
    {
        return new float[]{ax, ay, az, bx, by, bz, cx, cy, cz};
    }

    private static float[] concat(float[]... arrays)
    {
        int total = 0;
        for (float[] array : arrays) total += array.length;
        float[] result = new float[total];
        int offset = 0;
        for (float[] array : arrays)
        {
            System.arraycopy(array, 0, result, offset, array.length);
            offset += array.length;
        }
        return result;
    }
}
