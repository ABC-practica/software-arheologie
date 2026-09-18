package org.project.engine;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

// Testeaza SherdAnalysisMerger - regulile exacte de combinare Java/Python discutate: nu un
// prag generic de incredere, ci semnaturi exacte ale valorilor dubioase din sherdtool.py.
class SherdAnalysisMergerTest
{
    @Test
    void degenerateAxisSignatureFallsBackToJavaAxis()
    {
        // Semnatura exacta a fallback-ului din sherdtool.py: axa (0,1,0) SI rmse == 0.0.
        SherdPythonAnalyzer.SherdAnalysisResult python = pythonResult(
                "0.0000,1.0000,0.0000", "0.000,0.000,0.000", 0.0, new float[0], new float[0]);
        CurvatureClassifier.VesselAxisEstimate javaAxis =
                new CurvatureClassifier.VesselAxisEstimate(new Vector3f(0.3f, 0.1f, 0.9f), new Vector3f(1, 2, 3), 4f, true);
        SherdShapeClassifier.Result shape = shapeResult();

        SherdAnalysisMerger.MergedResult merged = SherdAnalysisMerger.merge(python, javaAxis, shape, javaProfile());

        assertEquals(javaAxis.axisDirection, merged.axisDirection);
        assertEquals(javaAxis.axisPoint, merged.axisPoint);
    }

    @Test
    void genuineNonDegenerateAxisFromPythonIsKept()
    {
        // Axa NU e (0,1,0) - un fit real, chiar daca rmse e mic din intamplare.
        SherdPythonAnalyzer.SherdAnalysisResult python = pythonResult(
                "0.7071,0.7071,0.0000", "1.500,2.500,0.000", 0.0, new float[0], new float[0]);
        CurvatureClassifier.VesselAxisEstimate javaAxis =
                new CurvatureClassifier.VesselAxisEstimate(new Vector3f(0, 1, 0), new Vector3f(9, 9, 9), 4f, true);
        SherdShapeClassifier.Result shape = shapeResult();

        SherdAnalysisMerger.MergedResult merged = SherdAnalysisMerger.merge(python, javaAxis, shape, javaProfile());

        assertEquals(0.7071, merged.axisDirection.x, 1e-3);
        assertEquals(0.7071, merged.axisDirection.y, 1e-3);
        assertEquals(1.5, merged.axisPoint.x, 1e-3);
    }

    @Test
    void axisExactlyYButNonZeroRmseIsNotTreatedAsDegenerate()
    {
        // Doar axa (0,1,0) NU e suficient - trebuie SI rmse~0. Un vas care intamplator are
        // axa reala pe Y (posibil!) cu un rmse real, nenul, nu trebuie aruncat.
        SherdPythonAnalyzer.SherdAnalysisResult python = pythonResult(
                "0.0000,1.0000,0.0000", "0.500,0.500,0.500", 0.42, new float[0], new float[0]);
        CurvatureClassifier.VesselAxisEstimate javaAxis =
                new CurvatureClassifier.VesselAxisEstimate(new Vector3f(1, 0, 0), new Vector3f(9, 9, 9), 4f, true);
        SherdShapeClassifier.Result shape = shapeResult();

        SherdAnalysisMerger.MergedResult merged = SherdAnalysisMerger.merge(python, javaAxis, shape, javaProfile());

        assertEquals(0.0, merged.axisDirection.x, 1e-4);
        assertEquals(1.0, merged.axisDirection.y, 1e-4);
        assertEquals(0.5, merged.axisPoint.x, 1e-4);
    }

    @Test
    void unparseableAxisStringFallsBackToJavaAxis()
    {
        SherdPythonAnalyzer.SherdAnalysisResult python = pythonResult(
                "", "0,0,0", 0.0, new float[0], new float[0]);
        CurvatureClassifier.VesselAxisEstimate javaAxis =
                new CurvatureClassifier.VesselAxisEstimate(new Vector3f(0, 0, 1), new Vector3f(5, 5, 5), 2f, true);
        SherdShapeClassifier.Result shape = shapeResult();

        SherdAnalysisMerger.MergedResult merged = SherdAnalysisMerger.merge(python, javaAxis, shape, javaProfile());

        assertEquals(javaAxis.axisDirection, merged.axisDirection);
    }

    @Test
    void pythonProfileUsedWhenHasProfileIsTrue()
    {
        float[] pyH = {0.1f, 0.2f, 0.3f};
        float[] pyR = {1f, 1.1f, 1.2f};
        SherdPythonAnalyzer.SherdAnalysisResult python = pythonResult(
                "0.7071,0.7071,0.0000", "0,0,0", 0.1, pyH, pyR);
        python = withPreservedHeight(python, 7.5);

        SherdAnalysisMerger.MergedResult merged = SherdAnalysisMerger.merge(
                python, javaAxisDefault(), shapeResult(), javaProfile());

        assertEquals(pyH, merged.profileHeights);
        assertEquals(pyR, merged.profileRadii);
        assertEquals(7.5f, merged.preservedHeight, 1e-4);
    }

    @Test
    void javaProfileUsedWhenPythonProfileIsMissing()
    {
        SherdPythonAnalyzer.SherdAnalysisResult python = pythonResult(
                "0.7071,0.7071,0.0000", "0,0,0", 0.1, new float[0], new float[0]);
        GhostVesselGenerator.VesselProfile javaProf = new GhostVesselGenerator.VesselProfile(
                new float[]{0f, 1f, 2f}, new float[]{2f, 2.5f, 3f});
        SherdShapeClassifier.Result shape = new SherdShapeClassifier.Result(
                SherdShapeClassifier.Category.BUZA, 0.8f, 6f, 6f, 3.3f, 270f);

        SherdAnalysisMerger.MergedResult merged = SherdAnalysisMerger.merge(python, javaAxisDefault(), shape, javaProf);

        assertEquals(javaProf.heights, merged.profileHeights);
        assertEquals(javaProf.radii, merged.profileRadii);
        assertEquals(3.3f, merged.preservedHeight, 1e-4);
    }

    @Test
    void rimMaxDiameterAndArcAlwaysComeFromJavaRegardlessOfPythonValues()
    {
        // Chiar daca Python ar avea (ipotetic) valori nenule pt astea, tot le ignoram -
        // sunt cod mort pe calea CLI, nu depindem de un semnal ca sa decidem asta.
        SherdPythonAnalyzer.SherdAnalysisResult python = new SherdPythonAnalyzer.SherdAnalysisResult(
                "n", "cm", 10, 20, "1,1,1", "0.7071,0.7071,0.0000", "0,0,0",
                999.0, 999.0, 999.0, 999.0, 5.0, 0.1, "good", 0.0, "unknown", 0.0, 0.0, "",
                new float[0], new float[0]);
        SherdShapeClassifier.Result shape = new SherdShapeClassifier.Result(
                SherdShapeClassifier.Category.FUND, 0.6f, 4f, 1f, 2f, 180f);

        SherdAnalysisMerger.MergedResult merged = SherdAnalysisMerger.merge(python, javaAxisDefault(), shape, javaProfile());

        assertEquals(4f, merged.maxDiameter);
        assertEquals(1f, merged.rimDiameter);
        assertEquals(180f, merged.preservedArcDeg);
    }

    @Test
    void formAspectRatioIsComputedFromMergedFieldsAndZeroWhenRimDiameterIsZero()
    {
        SherdShapeClassifier.Result shapeZeroRim = new SherdShapeClassifier.Result(
                SherdShapeClassifier.Category.NECUNOSCUT, 0f, 0f, 0f, 5f, 0f);
        SherdPythonAnalyzer.SherdAnalysisResult python = pythonResult(
                "0.7071,0.7071,0.0000", "0,0,0", 0.1, new float[0], new float[0]);

        SherdAnalysisMerger.MergedResult merged = SherdAnalysisMerger.merge(python, javaAxisDefault(), shapeZeroRim, javaProfile());

        assertEquals(0f, merged.formAspectRatio);
    }

    @Test
    void parseVector3HandlesValidMalformedAndBlankStrings()
    {
        Vector3f valid = SherdAnalysisMerger.parseVector3("1.5,-2.25,0.0");
        assertEquals(1.5f, valid.x); assertEquals(-2.25f, valid.y); assertEquals(0.0f, valid.z);

        assertNull(SherdAnalysisMerger.parseVector3(""));
        assertNull(SherdAnalysisMerger.parseVector3(null));
        assertNull(SherdAnalysisMerger.parseVector3("1.0,2.0"));
        assertNull(SherdAnalysisMerger.parseVector3("1.0,2.0,abc"));
        assertNull(SherdAnalysisMerger.parseVector3("1.0,2.0,3.0,4.0"));
    }

    @Test
    void pendingFromJavaOnlyUsesJavaValuesAndPlaceholdersForPythonOnlyFields()
    {
        // Rezultatul provizoriu (afisat instant, inainte sa raspunda Python) trebuie sa aiba
        // exact aceleasi valori Java ca rezultatul final combinat - doar campurile pe care
        // NUMAI Python le poate da (calitate, note, unitate, bbox) sunt placeholder.
        CurvatureClassifier.VesselAxisEstimate javaAxis = javaAxisDefault();
        SherdShapeClassifier.Result shape = shapeResult();
        GhostVesselGenerator.VesselProfile profile = javaProfile();

        SherdAnalysisMerger.MergedResult provisional =
                SherdAnalysisMerger.pendingFromJavaOnly("ciob.obj", 100, 50, javaAxis, shape, profile);

        assertEquals("ciob.obj", provisional.name);
        assertEquals(100, provisional.nVertices);
        assertEquals(50, provisional.nFaces);
        assertEquals(javaAxis.axisDirection, provisional.axisDirection);
        assertEquals(javaAxis.axisPoint, provisional.axisPoint);
        assertEquals(shape.rimDiameter, provisional.rimDiameter);
        assertEquals(shape.maxDiameter, provisional.maxDiameter);
        assertEquals(shape.preservedHeight, provisional.preservedHeight);
        assertEquals(shape.preservedArcDeg, provisional.preservedArcDeg);
        assertEquals(shape.category, provisional.category);
        assertEquals(shape.confidence, provisional.categoryConfidence);
        assertEquals(profile.heights, provisional.profileHeights);
        assertEquals(profile.radii, provisional.profileRadii);
        // Astea nu se pot sti fara Python - trebuie sa fie clar niste placeholder-e, nu 0/null.
        assertEquals("se calculeaza...", provisional.pythonQuality);
    }

    private static SherdPythonAnalyzer.SherdAnalysisResult pythonResult(
            String axisDir, String axisPoint, double rmse, float[] profH, float[] profR) {
        return new SherdPythonAnalyzer.SherdAnalysisResult(
                "n", "cm", 0, 0, "", axisDir, axisPoint, 0.0, 0.0, 0.0, 0.0, 3.0, rmse, "good", 0.0,
                "unknown", 0.0, 0.0, "", profH, profR);
    }

    private static SherdPythonAnalyzer.SherdAnalysisResult withPreservedHeight(
            SherdPythonAnalyzer.SherdAnalysisResult r, double preservedHeight) {
        return new SherdPythonAnalyzer.SherdAnalysisResult(
                r.name(), r.unit(), r.nVertices(), r.nFaces(), r.bboxExtent(), r.axisDir(), r.axisPoint(),
                r.rimDiameter(), r.maxDiameter(), r.shoulderHeight(), r.preservedArcDeg(), preservedHeight,
                r.fitResidualRmse(), r.quality(), r.sectionAzimuthDeg(), r.formClass(), r.formAspectRatio(),
                r.rimEversionDeg(), r.notes(), r.profileHeights(), r.profileRadii());
    }

    private static CurvatureClassifier.VesselAxisEstimate javaAxisDefault() {
        return new CurvatureClassifier.VesselAxisEstimate(new Vector3f(0, 1, 0), new Vector3f(0, 0, 0), 3f, true);
    }

    private static SherdShapeClassifier.Result shapeResult() {
        return new SherdShapeClassifier.Result(SherdShapeClassifier.Category.BUZA, 0.7f, 6f, 6f, 4f, 300f);
    }

    private static GhostVesselGenerator.VesselProfile javaProfile() {
        return new GhostVesselGenerator.VesselProfile(new float[]{0f, 1f}, new float[]{1f, 2f});
    }
}
