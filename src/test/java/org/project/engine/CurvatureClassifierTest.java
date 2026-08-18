package org.project.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Aici nu testam geometrie bruta, ci "ecuatia" rezultata: normala planului
// potrivit peste un grup de triunghiuri. Construim mesh-uri sintetice (mock)
// cu geometrie cunoscuta si verificam ce iese, inclusiv cazurile-limita unde
// nu exista un al doilea perete, mesh-ul e gol, sau are triunghiuri degenerate.
class CurvatureClassifierTest
{
    @Test
    void classifiesFartherWallAsExteriorAndFitsExpectedNormals()
    {
        float[] positions = {
                // triunghi 1: perete la x=2, normala spre +x (mai departe de origine -> exterior)
                2, -1, -1,
                2, 1, -1,
                2, 1, 1,
                // triunghi 2: perete la x=1.8, normala spre -x (mai aproape de origine -> interior)
                1.8f, -1, -1,
                1.8f, 1, 1,
                1.8f, 1, -1,
        };
        int[] indices = {0, 1, 2, 3, 4, 5};

        CurvatureClassifier.Result result = CurvatureClassifier.classify(positions, indices);

        float epsilon = 1e-4f;
        assertEquals(1.0, result.exteriorPlaneNormal.x, epsilon);
        assertEquals(0.0, result.exteriorPlaneNormal.y, epsilon);
        assertEquals(0.0, result.exteriorPlaneNormal.z, epsilon);

        assertEquals(-1.0, result.interiorPlaneNormal.x, epsilon);

        assertEquals(9, result.exteriorTriangles.length);
        assertEquals(9, result.interiorTriangles.length);
    }

    @Test
    void nearVerticalFacesAreIgnored()
    {
        // Fete aproape verticale (normala predominant pe Y, gen podea/plafon) nu
        // fac parte din "peretele" vasului si trebuie ignorate de clasificator.
        float[] positions = {
                -1, 0, -1,
                1, 0, -1,
                1, 0, 1,
        };
        int[] indices = {0, 1, 2};

        CurvatureClassifier.Result result = CurvatureClassifier.classify(positions, indices);

        assertEquals(0, result.exteriorTriangles.length);
        assertEquals(0, result.interiorTriangles.length);
    }

    @Test
    void emptyMeshReturnsEmptyGroupsWithDefaultsWithoutCrashing()
    {
        CurvatureClassifier.Result result = CurvatureClassifier.classify(new float[0], new int[0]);

        assertEquals(0, result.exteriorTriangles.length);
        assertEquals(0, result.interiorTriangles.length);
        assertEquals(1.0, result.exteriorPlaneNormal.z, 1e-6);
        assertEquals(1.0, result.interiorPlaneNormal.z, 1e-6);
    }

    @Test
    void singleWallGroupLeavesInteriorEmptyWithoutCrashing()
    {
        // Un ciob real poate avea doar suprafata exterioara scanata, fara perechea
        // interioara - grupul B ramane complet gol, nu doar mai mic.
        float[] positions = {
                2, -1, -1,
                2, 1, -1,
                2, 1, 1,
        };
        int[] indices = {0, 1, 2};

        CurvatureClassifier.Result result = CurvatureClassifier.classify(positions, indices);

        assertEquals(9, result.exteriorTriangles.length);
        assertEquals(0, result.interiorTriangles.length);
        assertEquals(1.0, result.exteriorPlaneNormal.x, 1e-4);
    }

    @Test
    void degenerateZeroAreaTriangleIsIgnored()
    {
        // Varfuri identice -> normala calculata are lungime 0 -> triunghiul e sarit,
        // nu produce NaN si nu ajunge in niciun grup.
        float[] positions = {0, 0, 0, 0, 0, 0, 0, 0, 0};
        int[] indices = {0, 1, 2};

        CurvatureClassifier.Result result = CurvatureClassifier.classify(positions, indices);

        assertEquals(0, result.exteriorTriangles.length);
        assertEquals(0, result.interiorTriangles.length);
    }

    @Test
    void tiedDistanceGroupsPickFirstGroupAsExteriorDeterministically()
    {
        // Doua "pereti" simetrici, exact la aceeasi distanta de origine (egalitate
        // perfecta). Comparatia foloseste ">=", deci grupul primului triunghi
        // (referenceNormal) castiga mereu deterministic egalitatea, nu e la noroc.
        float[] positions = {
                2, -1, -1,
                2, 1, -1,
                2, 1, 1,
                -2, -1, -1,
                -2, 1, 1,
                -2, 1, -1,
        };
        int[] indices = {0, 1, 2, 3, 4, 5};

        CurvatureClassifier.Result result = CurvatureClassifier.classify(positions, indices);

        assertEquals(1.0, result.exteriorPlaneNormal.x, 1e-4);
        assertEquals(-1.0, result.interiorPlaneNormal.x, 1e-4);
    }

    @Test
    void resultDependsOnTriangleIterationOrder()
    {
        // Trei fatete cu normale la 0, 120 si 240 de grade (perete curbat, gen
        // vas cu multe fatete) - toate valide, toate orizontale. Clasificarea in
        // doua grupuri se face dupa normala PRIMULUI triunghi valid intalnit
        // (referenceNormal), deci acelasi set de triunghiuri, doar reordonat,
        // poate produce o grupare complet diferita. Triunghiul cu varf in origine
        // (0 grade) e demonstrativ: e clasificat interior intr-o ordine si
        // exterior in alta, fara nicio schimbare de geometrie - doar ordinea.
        float[] triangle0 = {0, 0, 0, 0, 1, 0, 0, 0, 1};                          // normala (1,0,0) - 0 grade
        float[] triangle1 = {5, 0, 0, 5, 1, 0, 4.1339746f, 0, -0.5f};             // normala (-0.5,0,0.8660254) - 120 grade
        float[] triangle2 = {10, 0, 0, 10, 1, 0, 10.8660254f, 0, -0.5f};          // normala (-0.5,0,-0.8660254) - 240 grade
        int[] indices = {0, 1, 2, 3, 4, 5, 6, 7, 8};

        float[] positionsOrderA = concatFloats(triangle0, triangle1, triangle2);
        float[] positionsOrderB = concatFloats(triangle1, triangle0, triangle2);

        CurvatureClassifier.Result resultA = CurvatureClassifier.classify(positionsOrderA, indices);
        CurvatureClassifier.Result resultB = CurvatureClassifier.classify(positionsOrderB, indices);

        boolean triangle0IsInteriorInOrderA = containsVertexNearOrigin(resultA.interiorTriangles);
        boolean triangle0IsInteriorInOrderB = containsVertexNearOrigin(resultB.interiorTriangles);

        assertTrue(triangle0IsInteriorInOrderA != triangle0IsInteriorInOrderB,
                "acelasi triunghi (varf in origine) ar trebui sa fie clasificat diferit doar prin reordonare");
    }

    @Test
    void faceJustPastVerticalThresholdIsFiltered()
    {
        // Normala (0.8, 0.6, 0) - componenta Y=0.6, chiar peste pragul de 0.5
        // folosit pentru filtrarea peretilor aproape verticali. Verificam ca
        // pragul e chiar cel documentat (0.5), nu o alta valoare la intamplare.
        float[] positions = {
                0, 0, 0,
                0, 0, 1,
                0.6f, -0.8f, 0,
        };
        int[] indices = {0, 1, 2};

        CurvatureClassifier.Result result = CurvatureClassifier.classify(positions, indices);

        assertEquals(0, result.exteriorTriangles.length);
        assertEquals(0, result.interiorTriangles.length);
    }

    @Test
    void multiTriangleGroupsAverageNormalAndCentroidCorrectly()
    {
        // Toate testele de pana acum au un singur triunghi per grup, deci media
        // pe grup e trivial egala cu singura valoare - nu testeaza de fapt suma+
        // impartirea la numarul de triunghiuri. Aici grupul exterior are 2
        // triunghiuri identice ca forma dar cu centre diferite (unul deplasat pe
        // Y) - centrul rezultat trebuie sa fie MEDIA lor, nu unul din ele.
        float[] positions = {
                // grup exterior: 2 triunghiuri la x=2, normala (1,0,0), centre diferite
                2, -1, -1,  2, 1, -1,  2, 1, 1,
                2, 9, -1,   2, 11, -1, 2, 11, 1,
                // grup interior: 1 triunghi la x=1.8, normala (-1,0,0)
                1.8f, -1, -1,  1.8f, 1, 1,  1.8f, 1, -1,
        };
        int[] indices = {0, 1, 2, 3, 4, 5, 6, 7, 8};

        CurvatureClassifier.Result result = CurvatureClassifier.classify(positions, indices);

        float epsilon = 1e-3f;
        assertEquals(1.0, result.exteriorPlaneNormal.x, epsilon);
        assertEquals(2.0, result.exteriorPlanePoint.x, epsilon);
        assertEquals(16.0 / 3.0, result.exteriorPlanePoint.y, epsilon); // media (1/3 + 31/3) / 2, nu unul din centre
        assertEquals(18, result.exteriorTriangles.length);
    }

    @Test
    void exactlyOrthogonalNormalJoinsReferenceGroupPerInclusiveComparison()
    {
        // Comparatia e "normal.dot(referenceNormal) >= 0", nu strict ">". Deci un
        // triunghi cu normala EXACT perpendiculara pe referinta (produs scalar 0)
        // se alatura grupului de referinta, nu celuilalt. Caz de granita, bun de
        // fixat printr-un test explicit ca sa nu se schimbe accidental la un refactor.
        float[] positions = {
                // triunghi 1 (referinta): normala (1,0,0)
                2, -1, -1,  2, 1, -1,  2, 1, 1,
                // triunghi 2: normala (0,0,1) - exact perpendiculara pe referinta
                0, 0, 2,  1, 0, 2,  0, 1, 2,
        };
        int[] indices = {0, 1, 2, 3, 4, 5};

        CurvatureClassifier.Result result = CurvatureClassifier.classify(positions, indices);

        boolean bothInSameGroup = result.exteriorTriangles.length == 18 || result.interiorTriangles.length == 18;
        assertTrue(bothInSameGroup, "cele 2 triunghiuri (produs scalar exact 0) ar trebui sa ajunga in acelasi grup");
    }

    @Test
    void mixedValidInvalidAndDegenerateFacesAreHandledTogether()
    {
        // Un mesh "murdar" realist: un triunghi valid, unul degenerat (arie 0),
        // unul aproape vertical (filtrat), si inca unul valid - verificam ca
        // zgomotul din mijloc nu strica clasificarea celor doua valide.
        float[] positions = {
                // valid (exterior): normala (1,0,0)
                2, -1, -1,  2, 1, -1,  2, 1, 1,
                // degenerat - trebuie sarit
                9, 9, 9,  9, 9, 9,  9, 9, 9,
                // aproape vertical - trebuie filtrat
                -1, 0, -1,  1, 0, -1,  1, 0, 1,
                // valid (interior): normala (-1,0,0)
                1.8f, -1, -1,  1.8f, 1, 1,  1.8f, 1, -1,
        };
        int[] indices = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11};

        CurvatureClassifier.Result result = CurvatureClassifier.classify(positions, indices);

        assertEquals(9, result.exteriorTriangles.length);
        assertEquals(9, result.interiorTriangles.length);
        assertEquals(1.0, result.exteriorPlaneNormal.x, 1e-4);
        assertEquals(-1.0, result.interiorPlaneNormal.x, 1e-4);
    }

    private static boolean containsVertexNearOrigin(float[] triangles)
    {
        for (int i = 0; i + 2 < triangles.length; i += 3)
        {
            if (Math.abs(triangles[i]) < 1e-4f && Math.abs(triangles[i + 1]) < 1e-4f && Math.abs(triangles[i + 2]) < 1e-4f)
            {
                return true;
            }
        }
        return false;
    }

    private static float[] concatFloats(float[]... arrays)
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

    @Test
    void handlesManyFacetsWithoutExcessiveSlowdown()
    {
        int wallPairs = 50_000;
        float[] positions = new float[wallPairs * 18];
        int[] indices = new int[wallPairs * 6];

        for (int i = 0; i < wallPairs; i++)
        {
            int posBase = i * 18;
            positions[posBase] = 2;     positions[posBase + 1] = -1; positions[posBase + 2] = -1;
            positions[posBase + 3] = 2; positions[posBase + 4] = 1;  positions[posBase + 5] = -1;
            positions[posBase + 6] = 2; positions[posBase + 7] = 1;  positions[posBase + 8] = 1;

            positions[posBase + 9] = 1.8f;  positions[posBase + 10] = -1; positions[posBase + 11] = -1;
            positions[posBase + 12] = 1.8f; positions[posBase + 13] = 1;  positions[posBase + 14] = 1;
            positions[posBase + 15] = 1.8f; positions[posBase + 16] = 1;  positions[posBase + 17] = -1;

            int idxBase = i * 6;
            int vBase = i * 6;
            indices[idxBase] = vBase;
            indices[idxBase + 1] = vBase + 1;
            indices[idxBase + 2] = vBase + 2;
            indices[idxBase + 3] = vBase + 3;
            indices[idxBase + 4] = vBase + 4;
            indices[idxBase + 5] = vBase + 5;
        }

        long start = System.nanoTime();
        CurvatureClassifier.Result result = CurvatureClassifier.classify(positions, indices);
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        assertEquals(wallPairs * 9, result.exteriorTriangles.length);
        assertEquals(wallPairs * 9, result.interiorTriangles.length);
        assertTrue(elapsedMillis < 5000, "a durat " + elapsedMillis + "ms pentru " + wallPairs + " perechi de triunghiuri");
    }
}
