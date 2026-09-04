package org.project.engine;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

// Fum-test pentru puntea catre sherdtool.py: verifica doar ca subprocesul porneste,
// scriptul ruleaza pana la capat pe un mesh real si summary.csv e parsat corect -
// nu verificam corectitudinea masuratorilor (asta e responsabilitatea sherdtool.py,
// nu a Java). Sare peste test daca Python sau mesh-ul de test lipsesc pe masina asta,
// la fel ca CurvatureClassifierReferenceTest.
class SherdPythonAnalyzerTest
{
    private static final String TEST_MESH = "C:\\Users\\Radu\\Desktop\\gb\\GB_013.obj";

    @Test
    void analyzeRunsScriptAndParsesSummaryCsv() throws Exception
    {
        Assumptions.assumeTrue(new File("python", "sherdtool.py").exists(),
                "python/sherdtool.py nu exista in repo - sar testul.");
        File meshFile = new File(TEST_MESH);
        Assumptions.assumeTrue(meshFile.exists(), "Mesh-ul de test " + meshFile + " nu exista pe masina asta - sar testul.");
        Assumptions.assumeTrue(pythonAvailable(), "Python nu e disponibil in PATH - sar testul.");

        SherdPythonAnalyzer.SherdAnalysisResult result = SherdPythonAnalyzer.analyze(Path.of(TEST_MESH));

        assertNotNull(result);
        assertEquals("GB_013", result.name());
        assertEquals("cm", result.unit());
        assertNotNull(result.quality());
        assertNotNull(result.formClass());
    }

    private static boolean pythonAvailable()
    {
        try
        {
            Process p = new ProcessBuilder("python", "--version").start();
            return p.waitFor() == 0;
        }
        catch (Exception e)
        {
            return false;
        }
    }
}
