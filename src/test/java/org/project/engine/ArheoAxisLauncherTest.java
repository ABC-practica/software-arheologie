package org.project.engine;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Testeaza ArheoAxisLauncher (bridge-ul spre ai/axis.py, tool-ul extern al colegei pt fitul
// riguros de axa). Impartit in 3 categorii:
//  1. Gardurile pure Java (fara subproces deloc) - rapide, deterministe, mereu ruleaza.
//  2. Logica de "esec rapid" (checkForFastFailure) testata cu procese SINTETICE (cmd/python
//     simplu), nu cu axis.py real - decupleaza logica de detectie de nevoia de
//     pyvista/trimesh instalate sau de un mesh valid.
//  3. Un singur test real, end-to-end, cu scriptul adevarat - gardat cu Assumptions, ca sa
//     nu pice pe o masina fara Python/dependinte.
// axis.py poate rula minute intregi si deschide o fereastra PyVista pt input valid, deci
// NU testam niciodata "lansare cu succes pe un mesh real" aici - ar lasa un proces greu si
// o fereastra deschisa in urma unui test automat. Orice proces pornit e distrus in finally.
class ArheoAxisLauncherTest
{
    private final List<Process> spawnedProcesses = new ArrayList<>();

    @AfterEach
    void killAnyLeftoverProcesses()
    {
        for (Process p : spawnedProcesses)
        {
            if (p.isAlive()) p.destroyForcibly();
        }
        spawnedProcesses.clear();
    }

    // ---------- 1. Garduri pure Java (fara subproces) ----------

    @Test
    void missingMeshFileThrowsIoException()
    {
        Path missing = Path.of("nu_exista_" + System.nanoTime() + ".obj");

        IOException ex = assertThrows(IOException.class, () -> ArheoAxisLauncher.launch(missing, 0.5));
        assertTrue(ex.getMessage().contains("nu exista"), "mesajul ar trebui sa mentioneze ca fisierul lipseste");
    }

    @Test
    void zeroThicknessThrowsIllegalArgumentException() throws IOException
    {
        File tempMesh = File.createTempFile("arheoaxis", ".obj");
        tempMesh.deleteOnExit();

        assertThrows(IllegalArgumentException.class, () -> ArheoAxisLauncher.launch(tempMesh.toPath(), 0.0));
    }

    @Test
    void negativeThicknessThrowsIllegalArgumentException() throws IOException
    {
        File tempMesh = File.createTempFile("arheoaxis", ".obj");
        tempMesh.deleteOnExit();

        assertThrows(IllegalArgumentException.class, () -> ArheoAxisLauncher.launch(tempMesh.toPath(), -1.5));
    }

    @Test
    void nanThicknessThrowsIllegalArgumentException() throws IOException
    {
        File tempMesh = File.createTempFile("arheoaxis", ".obj");
        tempMesh.deleteOnExit();

        assertThrows(IllegalArgumentException.class, () -> ArheoAxisLauncher.launch(tempMesh.toPath(), Double.NaN));
    }

    @Test
    void positiveInfinityThicknessThrowsIllegalArgumentException() throws IOException
    {
        File tempMesh = File.createTempFile("arheoaxis", ".obj");
        tempMesh.deleteOnExit();

        assertThrows(IllegalArgumentException.class,
                () -> ArheoAxisLauncher.launch(tempMesh.toPath(), Double.POSITIVE_INFINITY));
    }

    @Test
    void negativeInfinityThicknessThrowsIllegalArgumentException() throws IOException
    {
        File tempMesh = File.createTempFile("arheoaxis", ".obj");
        tempMesh.deleteOnExit();

        assertThrows(IllegalArgumentException.class,
                () -> ArheoAxisLauncher.launch(tempMesh.toPath(), Double.NEGATIVE_INFINITY));
    }

    @Test
    void missingMeshCheckHappensBeforeThicknessCheck()
    {
        // Cand ambele sunt invalide (fisier lipsa SI grosime invalida), userul ar trebui sa
        // vada mesajul despre fisierul lipsa - e o problema mai de baza si mai usor de
        // inteles decat "grosimea trebuie sa fie pozitiva" pe un fisier care nici nu exista.
        Path missing = Path.of("nu_exista_deloc_" + System.nanoTime() + ".obj");

        Exception ex = assertThrows(Exception.class, () -> ArheoAxisLauncher.launch(missing, -5.0));
        assertTrue(ex instanceof IOException, "ar trebui sa raporteze fisierul lipsa, nu grosimea invalida");
    }

    @Test
    void directoryAsMeshPathPassesTheExistenceGuard() throws IOException
    {
        // Files.exists() e adevarat si pt directoare, nu doar fisiere - un path catre un
        // director trece garda de "fisier lipsa" desi evident nu e un mesh valid. Verificam
        // ca cel putin nu crapa la nivelul gardurilor Java (esecul real ar veni abia in
        // subproces, cand Python incearca sa incarce directorul ca mesh).
        Path tempDir = Files.createTempDirectory("arheoaxis_dir_");
        ArheoAxisLauncher.LaunchedProcess launched = null;
        try
        {
            launched = ArheoAxisLauncher.start(tempDir, 0.5);
            spawnedProcesses.add(launched.process());
        }
        catch (IOException e)
        {
            // Acceptabil daca Python insusi nu e pe PATH pe masina de test - nu e ce testam aici.
            Assumptions.assumeTrue(false, "Python nu pare disponibil pe aceasta masina: " + e.getMessage());
        }
        finally
        {
            if (launched != null) launched.process().destroyForcibly();
        }
    }

    @Test
    void veryLargeThicknessValueIsAcceptedByTheGuard() throws IOException
    {
        // Garda verifica doar "finit si > 0" - orice valoare pozitiva finita, oricat de
        // mare, trebuie acceptata (nu exista o limita superioara documentata sau doritä).
        File tempMesh = File.createTempFile("arheoaxis", ".obj");
        tempMesh.deleteOnExit();

        ArheoAxisLauncher.LaunchedProcess launched = null;
        try
        {
            launched = ArheoAxisLauncher.start(tempMesh.toPath(), Double.MAX_VALUE);
            spawnedProcesses.add(launched.process());
        }
        catch (IOException e)
        {
            Assumptions.assumeTrue(false, "Python nu pare disponibil pe aceasta masina: " + e.getMessage());
        }
        finally
        {
            if (launched != null) launched.process().destroyForcibly();
        }
    }

    @Test
    void logFilesAreUniquePerLaunch() throws IOException
    {
        File meshA = File.createTempFile("arheoaxis_a", ".obj");
        File meshB = File.createTempFile("arheoaxis_b", ".obj");
        meshA.deleteOnExit();
        meshB.deleteOnExit();

        ArheoAxisLauncher.LaunchedProcess first = null, second = null;
        try
        {
            first = ArheoAxisLauncher.start(meshA.toPath(), 0.5);
            spawnedProcesses.add(first.process());
            second = ArheoAxisLauncher.start(meshB.toPath(), 0.5);
            spawnedProcesses.add(second.process());

            assertNotEquals(first.logFile(), second.logFile(), "doua lansari nu ar trebui sa se scrie in acelasi fisier de log");
        }
        catch (IOException e)
        {
            Assumptions.assumeTrue(false, "Python nu pare disponibil pe aceasta masina: " + e.getMessage());
        }
        finally
        {
            if (first != null) first.process().destroyForcibly();
            if (second != null) second.process().destroyForcibly();
        }
    }

    // ---------- 2. checkForFastFailure cu procese sintetice (fara axis.py/pyvista) ----------

    @Test
    void checkForFastFailureDoesNotThrowWhenProcessExitsZeroQuickly() throws Exception
    {
        Process p = new ProcessBuilder("cmd", "/c", "exit", "0").start();
        spawnedProcesses.add(p);
        Path log = Files.createTempFile("fake_log_", ".log");

        assertDoesNotThrowFastFailure(new ArheoAxisLauncher.LaunchedProcess(p, log));
    }

    @Test
    void checkForFastFailureThrowsWithLoggedContentWhenProcessExitsNonZeroQuickly() throws Exception
    {
        Path log = Files.createTempFile("fake_log_", ".log");
        ProcessBuilder pb = new ProcessBuilder("cmd", "/c", "echo eroare-de-test-diagnostic & exit 1");
        pb.redirectOutput(log.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        spawnedProcesses.add(p);

        ArheoAxisLauncher.LaunchedProcess launched = new ArheoAxisLauncher.LaunchedProcess(p, log);
        IOException ex = assertThrows(IOException.class, () -> ArheoAxisLauncher.checkForFastFailure(launched));
        assertTrue(ex.getMessage().contains("cod 1"), "mesajul ar trebui sa mentioneze codul de iesire");
        assertTrue(ex.getMessage().contains("eroare-de-test-diagnostic"), "mesajul ar trebui sa includa log-ul procesului");
    }

    @Test
    void checkForFastFailureTailsLogToLastThirtyLines() throws Exception
    {
        Path log = Files.createTempFile("fake_log_", ".log");
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 50; i++) sb.append("linia-").append(i).append('\n');
        Files.writeString(log, sb.toString(), StandardCharsets.UTF_8);

        Process p = new ProcessBuilder("cmd", "/c", "exit", "1").start();
        spawnedProcesses.add(p);
        p.waitFor(FAST_FAIL_GRACE(), TimeUnit.SECONDS);

        ArheoAxisLauncher.LaunchedProcess launched = new ArheoAxisLauncher.LaunchedProcess(p, log);
        IOException ex = assertThrows(IOException.class, () -> ArheoAxisLauncher.checkForFastFailure(launched));
        assertTrue(ex.getMessage().contains("linia-50"), "ultima linie ar trebui pastrata");
        assertTrue(!ex.getMessage().contains("linia-1\n"), "liniile vechi (dincolo de ultimele 30) nu ar trebui incluse");
    }

    @Test
    void checkForFastFailureDoesNotThrowWhenProcessIsStillRunningAfterGracePeriod() throws Exception
    {
        // Un proces care doarme mai mult decat fereastra de gratie - trebuie tratat ca "inca
        // lucreaza", nu ca esec, exact comportamentul dorit pt o rulare reala (grea) a
        // axis.py. Testul asteapta el insusi fereastra de gratie (cateva secunde), e ok.
        Process p = new ProcessBuilder("python", "-c", "import time; time.sleep(30)").start();
        spawnedProcesses.add(p);
        Path log = Files.createTempFile("fake_log_", ".log");

        assertDoesNotThrowFastFailure(new ArheoAxisLauncher.LaunchedProcess(p, log));
        assertTrue(p.isAlive(), "procesul ar trebui sa fie inca activ dupa verificare (nu a fost omorat de check)");
    }

    // ---------- 3. Test real, end-to-end, gardat ----------

    @Test
    void invalidMeshContentCausesReportedFastFailureViaRealScript() throws Exception
    {
        Assumptions.assumeTrue(Files.exists(ArheoAxisLauncher.SCRIPT_PATH), "ai/axis.py nu exista in repo - sar testul.");
        Assumptions.assumeTrue(pythonWithVisualizationDepsAvailable(),
                "Python/pyvista/trimesh nu par disponibile pe aceasta masina - sar testul.");

        File garbageMesh = File.createTempFile("not_a_real_mesh", ".obj");
        garbageMesh.deleteOnExit();
        Files.writeString(garbageMesh.toPath(), "acesta nu e continut OBJ valid\n", StandardCharsets.UTF_8);

        IOException ex = assertThrows(IOException.class,
                () -> ArheoAxisLauncher.launch(garbageMesh.toPath(), 0.5));
        assertTrue(ex.getMessage().contains("esuat imediat"), "un mesh invalid ar trebui detectat ca esec rapid, nu lasat sa astepte minute");
    }

    private static void assertDoesNotThrowFastFailure(ArheoAxisLauncher.LaunchedProcess launched) throws IOException
    {
        try
        {
            ArheoAxisLauncher.checkForFastFailure(launched);
        }
        catch (IOException e)
        {
            throw new AssertionError("nu ar fi trebuit sa raporteze esec rapid: " + e.getMessage(), e);
        }
    }

    private static long FAST_FAIL_GRACE()
    {
        return ArheoAxisLauncher.FAST_FAIL_GRACE_SECONDS;
    }

    private static boolean pythonWithVisualizationDepsAvailable()
    {
        try
        {
            Process p = new ProcessBuilder("python", "-c", "import pyvista, trimesh, numpy").start();
            return p.waitFor(15, TimeUnit.SECONDS) && p.exitValue() == 0;
        }
        catch (Exception e)
        {
            return false;
        }
    }
}
