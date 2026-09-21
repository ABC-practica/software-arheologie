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

// Testeaza PairMatchingLauncher (bridge-ul spre ai/pair_matching_standalone.py). Aceeasi
// structura ca ArheoAxisLauncherTest (acelasi tipar de integrare, acelasi autor): garduri
// Java pure, logica de esec rapid pe procese sintetice, un singur test real end-to-end.
// pair_matching_standalone.py e chiar mai lent decat axis.py (fiteaza axa pt AMBELE
// cioburi, apoi pana la 1800 de incercari brute-force) si deschide propria fereastra -
// niciun test de-aici nu lasa un asemenea proces sa ruleze pana la capat.
class PairMatchingLauncherTest
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

    // ---------- Garduri pure Java (fara subproces) ----------

    @Test
    void missingFirstMeshFileThrowsIoException() throws IOException
    {
        File mesh2 = File.createTempFile("pairmatch_2_", ".obj");
        mesh2.deleteOnExit();
        Path missing = Path.of("nu_exista_" + System.nanoTime() + ".obj");

        IOException ex = assertThrows(IOException.class,
                () -> PairMatchingLauncher.launch(missing, mesh2.toPath(), 0.5));
        assertTrue(ex.getMessage().contains("Primul fisier"), "mesajul ar trebui sa mentioneze primul fisier");
    }

    @Test
    void missingSecondMeshFileThrowsIoException() throws IOException
    {
        File mesh1 = File.createTempFile("pairmatch_1_", ".obj");
        mesh1.deleteOnExit();
        Path missing = Path.of("nu_exista_" + System.nanoTime() + ".obj");

        IOException ex = assertThrows(IOException.class,
                () -> PairMatchingLauncher.launch(mesh1.toPath(), missing, 0.5));
        assertTrue(ex.getMessage().contains("Al doilea fisier"), "mesajul ar trebui sa mentioneze al doilea fisier");
    }

    @Test
    void firstMeshCheckHappensBeforeSecondMeshCheck()
    {
        // Cand ambele fisiere lipsesc, userul ar trebui sa vada mesajul despre PRIMUL -
        // comportament previzibil, blocat printr-un test explicit.
        Path missing1 = Path.of("lipsa1_" + System.nanoTime() + ".obj");
        Path missing2 = Path.of("lipsa2_" + System.nanoTime() + ".obj");

        IOException ex = assertThrows(IOException.class,
                () -> PairMatchingLauncher.launch(missing1, missing2, 0.5));
        assertTrue(ex.getMessage().contains("Primul fisier"), "primul fisier lipsa ar trebui raportat, nu al doilea");
    }

    @Test
    void secondMeshCheckHappensBeforeThicknessCheck() throws IOException
    {
        // Cand al doilea fisier lipseste SI grosimea e invalida, ar trebui raportat
        // fisierul lipsa (problema mai de baza), nu grosimea.
        File mesh1 = File.createTempFile("pairmatch_1_", ".obj");
        mesh1.deleteOnExit();
        Path missing2 = Path.of("lipsa2_" + System.nanoTime() + ".obj");

        Exception ex = assertThrows(Exception.class,
                () -> PairMatchingLauncher.launch(mesh1.toPath(), missing2, -5.0));
        assertTrue(ex instanceof IOException, "ar trebui sa raporteze fisierul lipsa, nu grosimea invalida");
    }

    @Test
    void zeroThicknessThrowsIllegalArgumentException() throws IOException
    {
        File mesh1 = File.createTempFile("pairmatch_1_", ".obj");
        File mesh2 = File.createTempFile("pairmatch_2_", ".obj");
        mesh1.deleteOnExit();
        mesh2.deleteOnExit();

        assertThrows(IllegalArgumentException.class,
                () -> PairMatchingLauncher.launch(mesh1.toPath(), mesh2.toPath(), 0.0));
    }

    @Test
    void negativeThicknessThrowsIllegalArgumentException() throws IOException
    {
        File mesh1 = File.createTempFile("pairmatch_1_", ".obj");
        File mesh2 = File.createTempFile("pairmatch_2_", ".obj");
        mesh1.deleteOnExit();
        mesh2.deleteOnExit();

        assertThrows(IllegalArgumentException.class,
                () -> PairMatchingLauncher.launch(mesh1.toPath(), mesh2.toPath(), -1.5));
    }

    @Test
    void nanThicknessThrowsIllegalArgumentException() throws IOException
    {
        File mesh1 = File.createTempFile("pairmatch_1_", ".obj");
        File mesh2 = File.createTempFile("pairmatch_2_", ".obj");
        mesh1.deleteOnExit();
        mesh2.deleteOnExit();

        assertThrows(IllegalArgumentException.class,
                () -> PairMatchingLauncher.launch(mesh1.toPath(), mesh2.toPath(), Double.NaN));
    }

    @Test
    void infiniteThicknessThrowsIllegalArgumentException() throws IOException
    {
        File mesh1 = File.createTempFile("pairmatch_1_", ".obj");
        File mesh2 = File.createTempFile("pairmatch_2_", ".obj");
        mesh1.deleteOnExit();
        mesh2.deleteOnExit();

        assertThrows(IllegalArgumentException.class,
                () -> PairMatchingLauncher.launch(mesh1.toPath(), mesh2.toPath(), Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class,
                () -> PairMatchingLauncher.launch(mesh1.toPath(), mesh2.toPath(), Double.NEGATIVE_INFINITY));
    }

    @Test
    void sameMeshPassedTwiceIsNotRejectedByGuards() throws IOException
    {
        // Nu exista o verificare explicita "cele doua fisiere trebuie sa fie diferite" -
        // documentam ca asta trece de garduri (esecul, daca exista unul, ar veni din
        // scriptul Python, nu din bridge-ul Java).
        File mesh = File.createTempFile("pairmatch_same_", ".obj");
        mesh.deleteOnExit();

        PairMatchingLauncher.LaunchedProcess launched = null;
        try
        {
            launched = PairMatchingLauncher.start(mesh.toPath(), mesh.toPath(), 0.5);
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
        File meshA = File.createTempFile("pairmatch_a_", ".obj");
        File meshB = File.createTempFile("pairmatch_b_", ".obj");
        meshA.deleteOnExit();
        meshB.deleteOnExit();

        PairMatchingLauncher.LaunchedProcess first = null, second = null;
        try
        {
            first = PairMatchingLauncher.start(meshA.toPath(), meshB.toPath(), 0.5);
            spawnedProcesses.add(first.process());
            second = PairMatchingLauncher.start(meshA.toPath(), meshB.toPath(), 0.5);
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

    // ---------- checkForFastFailure cu procese sintetice (fara pyvista/trimesh reale) ----------

    @Test
    void checkForFastFailureDoesNotThrowWhenProcessExitsZeroQuickly() throws Exception
    {
        Process p = new ProcessBuilder("cmd", "/c", "exit", "0").start();
        spawnedProcesses.add(p);
        Path log = Files.createTempFile("fake_log_", ".log");

        assertDoesNotThrowFastFailure(new PairMatchingLauncher.LaunchedProcess(p, log));
    }

    @Test
    void checkForFastFailureThrowsWithLoggedContentWhenProcessExitsNonZeroQuickly() throws Exception
    {
        Path log = Files.createTempFile("fake_log_", ".log");
        ProcessBuilder pb = new ProcessBuilder("cmd", "/c", "echo eroare-potrivire-test & exit 1");
        pb.redirectOutput(log.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        spawnedProcesses.add(p);

        PairMatchingLauncher.LaunchedProcess launched = new PairMatchingLauncher.LaunchedProcess(p, log);
        IOException ex = assertThrows(IOException.class, () -> PairMatchingLauncher.checkForFastFailure(launched));
        assertTrue(ex.getMessage().contains("cod 1"), "mesajul ar trebui sa mentioneze codul de iesire");
        assertTrue(ex.getMessage().contains("eroare-potrivire-test"), "mesajul ar trebui sa includa log-ul procesului");
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
        p.waitFor(PairMatchingLauncher.FAST_FAIL_GRACE_SECONDS, TimeUnit.SECONDS);

        PairMatchingLauncher.LaunchedProcess launched = new PairMatchingLauncher.LaunchedProcess(p, log);
        IOException ex = assertThrows(IOException.class, () -> PairMatchingLauncher.checkForFastFailure(launched));
        assertTrue(ex.getMessage().contains("linia-50"), "ultima linie ar trebui pastrata");
        assertTrue(!ex.getMessage().contains("linia-1\n"), "liniile vechi (dincolo de ultimele 30) nu ar trebui incluse");
    }

    @Test
    void checkForFastFailureDoesNotThrowWhenProcessIsStillRunningAfterGracePeriod() throws Exception
    {
        Process p = new ProcessBuilder("python", "-c", "import time; time.sleep(30)").start();
        spawnedProcesses.add(p);
        Path log = Files.createTempFile("fake_log_", ".log");

        assertDoesNotThrowFastFailure(new PairMatchingLauncher.LaunchedProcess(p, log));
        assertTrue(p.isAlive(), "procesul ar trebui sa fie inca activ dupa verificare (nu a fost omorat de check)");
    }

    // ---------- Test real, end-to-end, gardat ----------

    @Test
    void invalidMeshContentCausesReportedFastFailureViaRealScript() throws Exception
    {
        Assumptions.assumeTrue(Files.exists(PairMatchingLauncher.SCRIPT_PATH),
                "ai/pair_matching_standalone.py nu exista in repo - sar testul.");
        Assumptions.assumeTrue(pythonWithMatchingDepsAvailable(),
                "Python/pyvista/trimesh/scipy nu par disponibile pe aceasta masina - sar testul.");

        File garbageMesh1 = File.createTempFile("not_a_real_mesh_1", ".obj");
        File garbageMesh2 = File.createTempFile("not_a_real_mesh_2", ".obj");
        garbageMesh1.deleteOnExit();
        garbageMesh2.deleteOnExit();
        Files.writeString(garbageMesh1.toPath(), "acesta nu e continut OBJ valid\n", StandardCharsets.UTF_8);
        Files.writeString(garbageMesh2.toPath(), "nici asta\n", StandardCharsets.UTF_8);

        IOException ex = assertThrows(IOException.class,
                () -> PairMatchingLauncher.launch(garbageMesh1.toPath(), garbageMesh2.toPath(), 0.5));
        assertTrue(ex.getMessage().contains("esuat imediat"),
                "doua mesh-uri invalide ar trebui detectate ca esec rapid, nu lasate sa astepte minute");
    }

    private static void assertDoesNotThrowFastFailure(PairMatchingLauncher.LaunchedProcess launched) throws IOException
    {
        try
        {
            PairMatchingLauncher.checkForFastFailure(launched);
        }
        catch (IOException e)
        {
            throw new AssertionError("nu ar fi trebuit sa raporteze esec rapid: " + e.getMessage(), e);
        }
    }

    private static boolean pythonWithMatchingDepsAvailable()
    {
        try
        {
            Process p = new ProcessBuilder("python", "-c", "import pyvista, trimesh, numpy, scipy").start();
            return p.waitFor(15, TimeUnit.SECONDS) && p.exitValue() == 0;
        }
        catch (Exception e)
        {
            return false;
        }
    }
}
