package org.project.engine;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Launches ai/pair_matching_standalone.py as a separate, independent process with its own
 * PyVista window - same integration shape as ArheoAxisLauncher (same teammate, same
 * underlying axis-fitting code embedded in both standalone scripts), but for a DIFFERENT
 * question: given two sherds sharing a common wall thickness, how well do they fit together
 * as adjacent fragments of the same vessel (matching broken edges + compatible r(h)
 * profile), not just what a single sherd's own vessel axis looks like.
 *
 * Like axis.py, this is not a "call it, get data back" API: it opens an interactive window
 * (drag/rotate one shard against the other, brute-force auto-search for a good alignment)
 * and only writes its match_report.json + aligned .obj files if the user presses 'S' inside
 * that window. Fire-and-forget from Java's side, same as ArheoAxisLauncher.
 */
public class PairMatchingLauncher {

    static final Path SCRIPT_PATH = Path.of("ai", "pair_matching_standalone.py");
    private static final String PYTHON_EXECUTABLE = System.getProperty("sherdtool.python.exe", "python");

    /** Same rationale as ArheoAxisLauncher: this script fits an axis for BOTH meshes first
     * (as slow as axis.py alone) and then runs up to --max-iterations (1800 by default) of
     * brute-force alignment search - a real run is at least as slow as axis.py, likely
     * slower. 4s is only enough to catch a fast failure (missing dependency, invalid mesh,
     * the Windows-console encoding crash), never enough for a real result. */
    static final long FAST_FAIL_GRACE_SECONDS = 4;

    record LaunchedProcess(Process process, Path logFile) {}

    public static void launch(Path mesh1Path, Path mesh2Path, double thickness) throws IOException {
        LaunchedProcess launched = start(mesh1Path, mesh2Path, thickness);
        checkForFastFailure(launched);
    }

    static LaunchedProcess start(Path mesh1Path, Path mesh2Path, double thickness) throws IOException {
        if (!Files.exists(SCRIPT_PATH)) {
            throw new IOException("Nu gasesc scriptul Python la " + SCRIPT_PATH.toAbsolutePath()
                    + ". Ruleaza aplicatia din radacina proiectului (unde exista folderul ai/).");
        }
        if (!Files.exists(mesh1Path)) {
            throw new IOException("Primul fisier mesh nu exista: " + mesh1Path.toAbsolutePath());
        }
        if (!Files.exists(mesh2Path)) {
            throw new IOException("Al doilea fisier mesh nu exista: " + mesh2Path.toAbsolutePath());
        }
        if (!Double.isFinite(thickness) || thickness <= 0) {
            throw new IllegalArgumentException("Grosimea trebuie sa fie strict pozitiva.");
        }

        Path logFile = Files.createTempFile("pair_matching_", ".log");
        ProcessBuilder pb = new ProcessBuilder(
                PYTHON_EXECUTABLE,
                SCRIPT_PATH.toAbsolutePath().toString(),
                "--mesh1", mesh1Path.toAbsolutePath().toString(),
                "--mesh2", mesh2Path.toAbsolutePath().toString(),
                "--thickness", String.valueOf(thickness)
        );
        // Same encoding landmine as sherdtool.py/axis.py: Windows console's default codepage
        // can't encode this script's Romanian-diacritic print()/argparse help text.
        pb.environment().put("PYTHONIOENCODING", "utf-8");
        // Captured to a file (not DISCARDed) so a fast failure (missing pyvista/trimesh/
        // scipy, invalid mesh, the encoding crash) has a message to show instead of silently
        // doing nothing. Never read for the (expected, common) slow-running case.
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
            throw new IOException("Scriptul ai/pair_matching_standalone.py a esuat imediat (cod "
                    + launched.process().exitValue() + "):\n" + tail(log, 30));
        }
        // Either it's still running (the expected case - fitting two axes plus up to 1800
        // brute-force alignment attempts takes a while) or it exited with code 0 almost
        // instantly (unlikely but not our problem either way). We stop watching it here:
        // the script owns its own window/lifecycle from this point on.
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
