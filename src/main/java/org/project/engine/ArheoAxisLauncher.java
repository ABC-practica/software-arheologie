package org.project.engine;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Launches ai/axis.py's InteractiveArheoAxis tool as a separate, independent process with
 * its own PyVista window - a much more rigorous (RANSAC over real cross-section arcs +
 * global nonlinear least-squares fit) but also much slower and fully external axis-fitting
 * tool, complementing (not replacing) CurvatureClassifier's instant in-app heuristic used
 * for the automatic axis overlay in the main scene. Fire-and-forget for a successful run:
 * this bridge doesn't read anything back from the script - it manages its own interactive
 * window (draggable circle centers, radius sliders) independently of the JavaFX app.
 */
public class ArheoAxisLauncher {

    static final Path SCRIPT_PATH = Path.of("ai", "axis.py");
    private static final String PYTHON_EXECUTABLE = System.getProperty("sherdtool.python.exe", "python");

    /** How long to wait before assuming the process is doing real work (not a fast-fail
     * like a missing dependency, invalid mesh content, or the Windows-console encoding
     * crash we hit testing this) and returning without further waiting. A real run's own
     * analysis alone took several minutes on the sample mesh - this is only a startup
     * sanity check, not remotely enough time for the actual computation to finish. */
    static final long FAST_FAIL_GRACE_SECONDS = 4;

    /** A started subprocess plus the file capturing its merged stdout/stderr. Package-visible
     * so tests can start the process, inspect it directly, and guarantee cleanup themselves
     * (destroyForcibly in a finally) instead of relying on the fire-and-forget public API,
     * which by design never hands back a Process to the caller. */
    record LaunchedProcess(Process process, Path logFile) {}

    public static void launch(Path meshPath, double thickness) throws IOException {
        LaunchedProcess launched = start(meshPath, thickness);
        checkForFastFailure(launched);
    }

    static LaunchedProcess start(Path meshPath, double thickness) throws IOException {
        if (!Files.exists(SCRIPT_PATH)) {
            throw new IOException("Nu gasesc scriptul Python la " + SCRIPT_PATH.toAbsolutePath()
                    + ". Ruleaza aplicatia din radacina proiectului (unde exista folderul ai/).");
        }
        if (!Files.exists(meshPath)) {
            throw new IOException("Fisierul mesh nu exista: " + meshPath.toAbsolutePath());
        }
        if (!Double.isFinite(thickness) || thickness <= 0) {
            throw new IllegalArgumentException("Grosimea trebuie sa fie strict pozitiva.");
        }

        Path logFile = Files.createTempFile("arheo_axis_", ".log");
        ProcessBuilder pb = new ProcessBuilder(
                PYTHON_EXECUTABLE,
                SCRIPT_PATH.toAbsolutePath().toString(),
                "--mesh", meshPath.toAbsolutePath().toString(),
                "--thickness", String.valueOf(thickness)
        );
        // Same encoding landmine as sherdtool.py: on Windows, the console's default
        // codepage (e.g. cp1250) can't encode this script's Romanian-diacritic print()
        // calls, which crashes it with UnicodeEncodeError before the window even opens.
        // Confirmed by actually hitting this crash while testing the integration.
        pb.environment().put("PYTHONIOENCODING", "utf-8");
        // Captured to a file (not DISCARDed) so a fast failure (missing pyvista/trimesh,
        // invalid mesh content, the encoding crash) has a message to show the user instead
        // of silently doing nothing. Never read for the (expected, common) slow-success
        // case - we don't want to hold this file open indefinitely for a background window.
        pb.redirectOutput(logFile.toFile());
        pb.redirectErrorStream(true);

        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            throw new IOException("Nu am putut porni Python (\"" + PYTHON_EXECUTABLE + "\"). "
                    + "Verifica ca Python este instalat si in PATH. Detalii: " + e.getMessage(), e);
        }
        return new LaunchedProcess(process, logFile);
    }

    static void checkForFastFailure(LaunchedProcess launched) throws IOException {
        boolean exitedQuickly;
        try {
            exitedQuickly = launched.process().waitFor(FAST_FAIL_GRACE_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            exitedQuickly = false;
        }

        if (exitedQuickly && launched.process().exitValue() != 0) {
            String log = readLogSafely(launched.logFile());
            throw new IOException("Scriptul ai/axis.py a esuat imediat (cod " + launched.process().exitValue() + "):\n"
                    + tail(log, 30));
        }
        // Either it's still running (the expected case - real analysis takes minutes) or it
        // exited with code 0 almost instantly (unlikely but not our problem either way).
        // We intentionally stop watching it here: axis.py owns its own window/lifecycle from
        // this point on.
    }

    private static String readLogSafely(Path logFile) {
        try {
            return new String(Files.readAllBytes(logFile), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "(nu s-a putut citi log-ul procesului: " + e.getMessage() + ")";
        }
    }

    private static String tail(String text, int maxLines) {
        String[] lines = text.split("\n");
        int start = Math.max(0, lines.length - maxLines);
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < lines.length; i++) {
            sb.append(lines[i]).append('\n');
        }
        return sb.toString();
    }
}
