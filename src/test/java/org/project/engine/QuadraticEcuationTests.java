package org.project.engine;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Testeaza doar suprafata quadrica (gradul 2) adaugata pe langa planul existent in
// CurvatureClassifier.Result - CurvatureClassifierTest.java ramane responsabil pentru
// clasificarea in sine (exterior/interior, normale, praguri). Aici verificam ca fitul
// degenereaza corect la un plan pe un perete drept si ca recupereaza curbura reala
// pe un perete curbat sintetic (un "jgheab" parabolic, ca sectiunea unui vas).
class CurvatureEcuationTests
{
    @Test
    void flatWallQuadricHasNoCurvatureTerms()
    {
        // Acelasi perete drept ca in CurvatureClassifierTest, dar suficient de multe
        // triunghiuri (>=6) ca sa treaca prin fitul real, nu doar prin fallback-ul
        // pentru grupuri mici. Toate coplanare la x=2 - fara curbura de recuperat.
        List<Float> verts = new ArrayList<>();
        for (int i = -3; i < 3; i++)
        {
            float y0 = i, y1 = i + 1;
            // Winding ales ca sa dea normala +X (spre exterior), la fel ca in fixture-ul
            // "singleWallGroupLeavesInteriorEmptyWithoutCrashing" din CurvatureClassifierTest.
            addTri(verts, 2, y0, -1,  2, y1, -1,  2, y0, 1);
            addTri(verts, 2, y1, -1,  2, y1, 1,   2, y0, 1);
        }
        float[] positions = toArray(verts);
        int[] indices = sequentialIndices(positions.length / 3);

        CurvatureClassifier.Result result = CurvatureClassifier.classify(positions, indices);

        assertNotNull(result.exteriorQuadric);
        float eps = 1e-3f;
        assertEquals(0.0, result.exteriorQuadric.x2, eps);
        assertEquals(0.0, result.exteriorQuadric.y2, eps);
        assertEquals(0.0, result.exteriorQuadric.z2, eps);
        assertEquals(0.0, result.exteriorQuadric.xy, eps);
        assertEquals(0.0, result.exteriorQuadric.yz, eps);
        assertEquals(0.0, result.exteriorQuadric.xz, eps);
        // Planul e x=2: normala (1,0,0), deci ecuatia degenerata e -1*x + ... + 2 = 0.
        assertEquals(-1.0, result.exteriorQuadric.x, 1e-2);
        assertEquals(2.0, result.exteriorQuadric.c, 1e-2);
    }

    @Test
    void curvedWallQuadricRecoversKnownParabolicProfile()
    {
        // Un perete in forma de jgheab parabolic: x(y) = 2 + k*y^2, plat pe z (ca un
        // fragment de vas vazut in sectiune - curbura pe verticala, nu si pe orizontala).
        // Grid 3x5 (nu doar 2 coloane pe z) - cu numai 2 valori pe z, z^2 ar fi constant
        // pe tot esantionul si ar face sistemul liniar 6x6 singular (coloana z^2 ar
        // coincide cu coloana constanta), fortand fallback-ul la plan; cu 3 coloane
        // (z=-1,0,1) sistemul e bine determinat. Simetric fata de y=0, deci axa tangenta
        // locala aleasa de clasificator (Gram-Schmidt cu helper (0,1,0), pentru ca
        // normala medie e aproape +X) coincide aproape exact cu axa globala Y - de-aia ne
        // asteptam ca termenul y^2 global sa recupereze aproape exact k.
        float k = 0.1f;
        float[] ys = {-2, -1, 0, 1, 2};
        float[] zs = {-1, 0, 1};
        float[][][] grid = new float[ys.length][zs.length][3];
        for (int i = 0; i < ys.length; i++)
        {
            for (int j = 0; j < zs.length; j++)
            {
                grid[i][j] = new float[]{2 + k * ys[i] * ys[i], ys[i], zs[j]};
            }
        }

        List<Float> verts = new ArrayList<>();
        for (int i = 0; i < ys.length - 1; i++)
        {
            for (int j = 0; j < zs.length - 1; j++)
            {
                float[] a = grid[i][j], b = grid[i + 1][j], c = grid[i][j + 1], d = grid[i + 1][j + 1];
                addTri(verts, a[0], a[1], a[2],  b[0], b[1], b[2],  c[0], c[1], c[2]);
                addTri(verts, b[0], b[1], b[2],  d[0], d[1], d[2],  c[0], c[1], c[2]);
            }
        }
        float[] positions = toArray(verts);
        int[] indices = sequentialIndices(positions.length / 3);

        CurvatureClassifier.Result result = CurvatureClassifier.classify(positions, indices);

        assertEquals(0, result.interiorTriangles.length, "un singur perete in acest fixture");
        assertNotNull(result.exteriorQuadric);

        float tol = 0.03f;
        assertEquals(k, result.exteriorQuadric.y2, tol, "termenul y^2 global ar trebui sa recupereze curbura k a profilului");
        assertEquals(0.0, result.exteriorQuadric.x2, tol);
        assertEquals(0.0, result.exteriorQuadric.z2, tol);
        assertEquals(0.0, result.exteriorQuadric.xy, tol);
        assertEquals(0.0, result.exteriorQuadric.yz, tol);
        assertEquals(0.0, result.exteriorQuadric.xz, tol);
    }

    @Test
    void fewerThanSixSamplesFallsBackToFlatPlane()
    {
        // Fitul are 6 necunoscute (A..F) - sub 6 esantioane e sub-determinat, deci
        // codul trebuie sa renunte la fit si sa foloseasca planul plat (curbura 0),
        // chiar daca geometria data e vizibil curbata (k mare, ca sa fie clar ca
        // nu e doar o curbura mica rotunjita la 0 din greseala).
        Vector3f n0 = new Vector3f(1, 0, 0);
        Vector3f u0 = new Vector3f(0, 1, 0);
        Vector3f v0 = new Vector3f(0, 0, 1);
        float k = 0.2f;
        float[] positions = buildCurvedWallGrid(new Vector3f(2, 0, 0), n0, u0, v0,
                new float[]{-1, 0, 1}, new float[]{-1, 1}, (u, v) -> k * u * u);
        int[] indices = sequentialIndices(positions.length / 3);

        CurvatureClassifier.Result result = CurvatureClassifier.classify(positions, indices);

        assertEquals(4, result.exteriorTriangles.length / 9, "fixture-ul trebuie sa aiba sub 6 triunghiuri ca sa testeze pragul");
        assertNoCurvature(result.exteriorQuadric, 1e-6f);
    }

    @Test
    void collinearTangentSamplesFallBackToFlatPlaneInsteadOfSingularSolve()
    {
        // Caz descoperit in practica: daca toate esantioanele au doar 2 valori
        // distincte pe una din axele tangente locale (aici z = -1 sau +1), v^2 e
        // constant pe tot setul de date si coloana lui coincide cu coloana
        // constanta din sistemul 6x6 -> matrice singulara. Codul trebuie sa detecteze
        // asta (solveLinearSystem intoarce null) si sa degradeze la plan, nu sa
        // crape sau sa produca NaN.
        Vector3f n0 = new Vector3f(1, 0, 0);
        Vector3f u0 = new Vector3f(0, 1, 0);
        Vector3f v0 = new Vector3f(0, 0, 1);
        float k = 0.1f;
        float[] positions = buildCurvedWallGrid(new Vector3f(2, 0, 0), n0, u0, v0,
                new float[]{-2, -1, 0, 1, 2}, new float[]{-1, 1}, (u, v) -> k * u * u);
        int[] indices = sequentialIndices(positions.length / 3);

        CurvatureClassifier.Result result = CurvatureClassifier.classify(positions, indices);

        assertTrue(result.exteriorTriangles.length / 9 >= 6, "fixture-ul trebuie sa treaca de pragul de 6 esantioane");
        assertNoCurvature(result.exteriorQuadric, 1e-6f);
    }

    @Test
    void bothWallsEmptyProducesAllZeroQuadricsWithoutCrashing()
    {
        CurvatureClassifier.Result result = CurvatureClassifier.classify(new float[0], new int[0]);

        assertEquals(
                "0.0000x² +0.0000y² +0.0000z² +0.0000xy +0.0000yz +0.0000xz +0.0000x +0.0000y +0.0000z +0.0000 = 0",
                result.exteriorQuadric.toEquationText());
        assertEquals(
                "0.0000x² +0.0000y² +0.0000z² +0.0000xy +0.0000yz +0.0000xz +0.0000x +0.0000y +0.0000z +0.0000 = 0",
                result.interiorQuadric.toEquationText());
    }

    @Test
    void singleWallLeavesOtherSideAsFiniteDegenerateQuadric()
    {
        // Un singur perete real (grupul B ramane gol) - quadricul interior e derivat
        // dintr-un grup fara triunghiuri (normala/centrul implicite), dar tot trebuie
        // sa fie un numar valid (fara NaN/Infinity), nu doar "nu crapa".
        float[] positions = {
                2, -1, -1,  2, 1, -1,  2, 1, 1,
        };
        int[] indices = {0, 1, 2};

        CurvatureClassifier.Result result = CurvatureClassifier.classify(positions, indices);

        assertEquals(0, result.interiorTriangles.length);
        assertAllFinite(result.interiorQuadric);
        assertAllFinite(result.exteriorQuadric);
    }

    @Test
    void negativeCurvatureSignIsPreserved()
    {
        // Acelasi jgheab parabolic ca in curvedWallQuadricRecoversKnownParabolicProfile,
        // dar concav in sens opus (k negativ) - verificam ca fitul nu recupereaza doar
        // MAGNITUDINEA curburii, ci si semnul corect.
        Vector3f n0 = new Vector3f(1, 0, 0);
        Vector3f u0 = new Vector3f(0, 1, 0);
        Vector3f v0 = new Vector3f(0, 0, 1);
        float k = -0.1f;
        float[] positions = buildCurvedWallGrid(new Vector3f(2, 0, 0), n0, u0, v0,
                new float[]{-2, -1, 0, 1, 2}, new float[]{-1, 0, 1}, (u, v) -> k * u * u);
        int[] indices = sequentialIndices(positions.length / 3);

        CurvatureClassifier.Result result = CurvatureClassifier.classify(positions, indices);

        assertEquals(0, result.interiorTriangles.length);
        assertEquals(k, result.exteriorQuadric.y2, 0.03f);
    }

    @Test
    void saddleShapeRecoversIndependentCurvaturesOnEachTangentAxis()
    {
        // Suprafata h = ku*u^2 + kv*v^2 cu ku != kv si semne opuse (o "sa") - verificam
        // ca fitul separa corect curbura pe cele doua axe tangente, nu doar un singur
        // scalar global de "cat de curbat e peretele".
        Vector3f n0 = new Vector3f(1, 0, 0);
        Vector3f u0 = new Vector3f(0, 1, 0);
        Vector3f v0 = new Vector3f(0, 0, 1);
        float ku = 0.08f;
        float kv = -0.05f;
        float[] uv = {-2, -1, 0, 1, 2};
        float[] positions = buildCurvedWallGrid(new Vector3f(2, 0, 0), n0, u0, v0,
                uv, uv, (u, v) -> ku * u * u + kv * v * v);
        int[] indices = sequentialIndices(positions.length / 3);

        CurvatureClassifier.Result result = CurvatureClassifier.classify(positions, indices);

        assertEquals(0, result.interiorTriangles.length);
        float tol = 0.03f;
        assertEquals(ku, result.exteriorQuadric.y2, tol);
        assertEquals(kv, result.exteriorQuadric.z2, tol);
        assertEquals(0.0, result.exteriorQuadric.x2, tol);
        assertEquals(0.0, result.exteriorQuadric.xy, tol);
        assertEquals(0.0, result.exteriorQuadric.yz, tol);
        assertEquals(0.0, result.exteriorQuadric.xz, tol);
    }

    @Test
    void coupledSurfaceRecoversCrossTermWithoutLeakingIntoSquareTerms()
    {
        // Suprafata h = c*u*v (sa "rasucita", fara termeni patratici in u sau v
        // separat) - termenul de cuplaj local C ar trebui sa apara ca "yz" global
        // (pentru ca aici u=y, v=z), fara sa contamineze y^2/z^2.
        Vector3f n0 = new Vector3f(1, 0, 0);
        Vector3f u0 = new Vector3f(0, 1, 0);
        Vector3f v0 = new Vector3f(0, 0, 1);
        float c = 0.06f;
        float[] uv = {-2, -1, 0, 1, 2};
        float[] positions = buildCurvedWallGrid(new Vector3f(2, 0, 0), n0, u0, v0,
                uv, uv, (u, v) -> c * u * v);
        int[] indices = sequentialIndices(positions.length / 3);

        CurvatureClassifier.Result result = CurvatureClassifier.classify(positions, indices);

        assertEquals(0, result.interiorTriangles.length);
        float tol = 0.03f;
        assertEquals(c, result.exteriorQuadric.yz, tol);
        assertEquals(0.0, result.exteriorQuadric.y2, tol);
        assertEquals(0.0, result.exteriorQuadric.z2, tol);
        assertEquals(0.0, result.exteriorQuadric.xy, tol);
        assertEquals(0.0, result.exteriorQuadric.xz, tol);
    }

    @Test
    void curvatureTermsAreTranslationInvariantFarFromOrigin()
    {
        // Acelasi profil parabolic ca in curvedWallQuadricRecoversKnownParabolicProfile,
        // dar mutat departe de (0,0,0) pe toate cele 3 axe. Coeficientii patratici
        // (curbura propriu-zisa) nu ar trebui sa se schimbe cu o translatie - doar
        // termenii liniari/constanti reflecta pozitia. Verifica expansiunea globala
        // din buildGlobalQuadric (termenii Uc/Vc/Nc) pentru o origine mare, nu doar
        // aproape de (0,0,0) ca in restul testelor.
        Vector3f origin = new Vector3f(50, 100, -30);
        Vector3f n0 = new Vector3f(1, 0, 0);
        Vector3f u0 = new Vector3f(0, 1, 0);
        Vector3f v0 = new Vector3f(0, 0, 1);
        float k = 0.07f;
        float[] positions = buildCurvedWallGrid(origin, n0, u0, v0,
                new float[]{-2, -1, 0, 1, 2}, new float[]{-1, 0, 1}, (u, v) -> k * u * u);
        int[] indices = sequentialIndices(positions.length / 3);

        CurvatureClassifier.Result result = CurvatureClassifier.classify(positions, indices);

        assertEquals(0, result.interiorTriangles.length);
        assertEquals(k, result.exteriorQuadric.y2, 0.03f);
        assertAllFinite(result.exteriorQuadric);
    }

    @Test
    void nonAxisAlignedWallQuadricDescribesTheActualSurface()
    {
        // Normala medie NU e aliniata cu nicio axa globala (diagonala) - trece prin
        // ramura cealalta din buildTangentBasis (helper (1,0,0) in loc de (0,1,0)).
        // Aici nu mai are sens sa prezicem coeficientii x²/y²/z² individuali (depind
        // de axele tangente alese intern de clasificator), asa ca verificam contractul
        // direct: ecuatia Q(x,y,z)=0 trebuie sa fie aproape 0 in puncte CHIAR PE
        // suprafata cunoscuta, si clar diferita de 0 intr-un punct din afara ei
        // (acelasi u,v dar fara curbura) - altfel testul ar trece si cu un plan gol.
        Vector3f n0 = new Vector3f(1, 1, 1).normalize();
        Vector3f helper = Math.abs(n0.x) < 0.9f ? new Vector3f(1, 0, 0) : new Vector3f(0, 1, 0);
        Vector3f u0 = new Vector3f(helper).sub(new Vector3f(n0).mul(helper.dot(n0))).normalize();
        Vector3f v0 = new Vector3f(n0).cross(u0).normalize();

        float k = 0.05f;
        BiFunction<Float, Float, Float> heightFn = (u, v) -> k * (u * u + v * v);
        float[] uv = {-2, -1, 0, 1, 2};
        Vector3f origin = new Vector3f(0, 0, 0);
        float[] positions = buildCurvedWallGrid(origin, n0, u0, v0, uv, uv, heightFn);
        int[] indices = sequentialIndices(positions.length / 3);

        CurvatureClassifier.Result result = CurvatureClassifier.classify(positions, indices);
        assertEquals(0, result.interiorTriangles.length);
        assertAllFinite(result.exteriorQuadric);

        float uCorner = 2, vCorner = 2;
        float hCorner = heightFn.apply(uCorner, vCorner);
        Vector3f onSurface = new Vector3f(origin)
                .add(new Vector3f(n0).mul(hCorner))
                .add(new Vector3f(u0).mul(uCorner))
                .add(new Vector3f(v0).mul(vCorner));
        Vector3f onTangentPlane = new Vector3f(origin)
                .add(new Vector3f(u0).mul(uCorner))
                .add(new Vector3f(v0).mul(vCorner));

        double errorOnSurface = evaluateQuadric(result.exteriorQuadric, onSurface.x, onSurface.y, onSurface.z);
        double errorOffSurface = evaluateQuadric(result.exteriorQuadric, onTangentPlane.x, onTangentPlane.y, onTangentPlane.z);

        assertEquals(0.0, errorOnSurface, 0.05, "un punct chiar pe suprafata cunoscuta ar trebui sa aproximeze ecuatia cu eroare mica");
        assertTrue(Math.abs(errorOffSurface) > 0.15,
                "un punct in afara suprafetei (pe planul tangent, fara curbura) trebuie sa dea o eroare clar mai mare, altfel ecuatia ar fi degenerata/plata");
    }

    private static void assertNoCurvature(CurvatureClassifier.QuadricSurface q, float eps)
    {
        assertEquals(0.0, q.x2, eps);
        assertEquals(0.0, q.y2, eps);
        assertEquals(0.0, q.z2, eps);
        assertEquals(0.0, q.xy, eps);
        assertEquals(0.0, q.yz, eps);
        assertEquals(0.0, q.xz, eps);
    }

    private static void assertAllFinite(CurvatureClassifier.QuadricSurface q)
    {
        float[] coeffs = {q.x2, q.y2, q.z2, q.xy, q.yz, q.xz, q.x, q.y, q.z, q.c};
        for (float v : coeffs)
        {
            assertTrue(Float.isFinite(v), "coeficient non-finit in suprafata quadrica: " + v);
        }
    }

    private static double evaluateQuadric(CurvatureClassifier.QuadricSurface q, float x, float y, float z)
    {
        return q.x2 * x * x + q.y2 * y * y + q.z2 * z * z
                + q.xy * x * y + q.yz * y * z + q.xz * x * z
                + q.x * x + q.y * y + q.z * z + q.c;
    }

    /**
     * Construieste un perete-grid sintetic: pozitie(u,v) = origin + n0*h(u,v) + u0*u + v0*v,
     * cu (u0, v0, n0) baza ortonormala dreapta (u0 x v0 = n0). Winding-ul triunghiurilor e
     * ales sa dea normale orientate spre +n0 (verificat manual pentru cazul axe-aliniate
     * n0=(1,0,0),u0=(0,1,0),v0=(0,0,1) si valabil in general prin acelasi argument de
     * produs vectorial in limita plana).
     */
    private static float[] buildCurvedWallGrid(Vector3f origin, Vector3f n0, Vector3f u0, Vector3f v0,
                                                 float[] uValues, float[] vValues,
                                                 BiFunction<Float, Float, Float> heightFn)
    {
        int rows = uValues.length, cols = vValues.length;
        Vector3f[][] grid = new Vector3f[rows][cols];
        for (int i = 0; i < rows; i++)
        {
            for (int j = 0; j < cols; j++)
            {
                float h = heightFn.apply(uValues[i], vValues[j]);
                grid[i][j] = new Vector3f(origin)
                        .add(new Vector3f(n0).mul(h))
                        .add(new Vector3f(u0).mul(uValues[i]))
                        .add(new Vector3f(v0).mul(vValues[j]));
            }
        }

        List<Float> verts = new ArrayList<>();
        for (int i = 0; i < rows - 1; i++)
        {
            for (int j = 0; j < cols - 1; j++)
            {
                Vector3f a = grid[i][j], b = grid[i + 1][j], c = grid[i][j + 1], d = grid[i + 1][j + 1];
                addTri(verts, a.x, a.y, a.z,  b.x, b.y, b.z,  c.x, c.y, c.z);
                addTri(verts, b.x, b.y, b.z,  d.x, d.y, d.z,  c.x, c.y, c.z);
            }
        }
        return toArray(verts);
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