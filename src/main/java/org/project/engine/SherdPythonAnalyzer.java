package org.project.engine;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Runs the existing sherdtool.py analysis (axis fit, cross-section, measurements)
 * as a subprocess and parses its summary.csv output. No classification logic is
 * duplicated in Java: this is a thin bridge to the Python tool.
 */
public class SherdPythonAnalyzer {

    private static final Path SCRIPT_PATH = Path.of("python", "sherdtool.py");
    private static final String PYTHON_EXECUTABLE = System.getProperty("sherdtool.python.exe", "python");
    private static final long TIMEOUT_SECONDS = 180;

    public record SherdAnalysisResult(
            String name,
            String unit,
            int nVertices,
            int nFaces,
            String bboxExtent,
            String axisDir,
            String axisPoint,
            double rimDiameter,
            double maxDiameter,
            double shoulderHeight,
            double preservedArcDeg,
            double preservedHeight,
            double fitResidualRmse,
            String quality,
            double sectionAzimuthDeg,
            String formClass,
            double formAspectRatio,
            double rimEversionDeg,
            String notes
    ) {}

    public static SherdAnalysisResult analyze(Path meshPath) throws IOException, InterruptedException {
        if (!Files.exists(SCRIPT_PATH)) {
            throw new IOException("Nu gasesc scriptul Python la " + SCRIPT_PATH.toAbsolutePath()
                    + ". Ruleaza aplicatia din radacina proiectului (unde exista folderul python/).");
        }
        if (!Files.exists(meshPath)) {
            throw new IOException("Fisierul mesh nu exista: " + meshPath.toAbsolutePath());
        }

        Path outputDir = Files.createTempDirectory("sherdtool_");
        ProcessBuilder pb = new ProcessBuilder(
                PYTHON_EXECUTABLE,
                SCRIPT_PATH.toAbsolutePath().toString(),
                meshPath.toAbsolutePath().toString(),
                "-o", outputDir.toAbsolutePath().toString(),
                "--no-report"
        );
        pb.redirectErrorStream(true);

        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            throw new IOException("Nu am putut porni Python (\"" + PYTHON_EXECUTABLE + "\"). "
                    + "Verifica ca Python este instalat si in PATH. Detalii: " + e.getMessage(), e);
        }

        StringBuilder outputLog = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                outputLog.append(line).append('\n');
            }
        }

        boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("Scriptul Python a depasit limita de " + TIMEOUT_SECONDS + "s.\n"
                    + tail(outputLog.toString(), 40));
        }
        if (process.exitValue() != 0) {
            throw new IOException("Scriptul Python a esuat (cod " + process.exitValue() + "):\n"
                    + tail(outputLog.toString(), 40));
        }

        Path csvPath = outputDir.resolve("summary.csv");
        if (!Files.exists(csvPath)) {
            throw new IOException("Scriptul Python nu a produs summary.csv.\n" + tail(outputLog.toString(), 40));
        }

        List<Map<String, String>> rows = parseCsv(csvPath);
        if (rows.isEmpty()) {
            throw new IOException("summary.csv este gol.");
        }
        return toResult(rows.get(0));
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

    private static SherdAnalysisResult toResult(Map<String, String> row) {
        return new SherdAnalysisResult(
                row.getOrDefault("name", ""),
                row.getOrDefault("unit", ""),
                parseInt(row.get("n_vertices")),
                parseInt(row.get("n_faces")),
                row.getOrDefault("bbox_extent", ""),
                row.getOrDefault("axis_dir", ""),
                row.getOrDefault("axis_point", ""),
                parseDouble(row.get("rim_diameter")),
                parseDouble(row.get("max_diameter")),
                parseDouble(row.get("shoulder_height")),
                parseDouble(row.get("preserved_arc_deg")),
                parseDouble(row.get("preserved_height")),
                parseDouble(row.get("fit_residual_rmse")),
                row.getOrDefault("quality", ""),
                parseDouble(row.get("section_azimuth_deg")),
                row.getOrDefault("form_class", "unknown"),
                parseDouble(row.get("form_aspect_ratio")),
                parseDouble(row.get("rim_eversion_deg")),
                row.getOrDefault("notes", "")
        );
    }

    private static int parseInt(String s) {
        try {
            return (s == null || s.isBlank()) ? 0 : Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static double parseDouble(String s) {
        try {
            return (s == null || s.isBlank()) ? 0.0 : Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    /** Minimal CSV parser (RFC4180-style quoting) — matches Python's csv.DictWriter output. */
    private static List<Map<String, String>> parseCsv(Path csvPath) throws IOException {
        // sherdtool.py writes the CSV without an explicit encoding, so on Windows notes
        // containing non-ASCII punctuation (e.g. an em-dash) can land in the platform
        // codepage instead of UTF-8. Decode leniently (invalid bytes -> U+FFFD) instead
        // of Files.readString, which throws on the first malformed byte.
        String content = new String(Files.readAllBytes(csvPath), StandardCharsets.UTF_8);
        List<List<String>> records = new ArrayList<>();
        List<String> field = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        int n = content.length();
        for (int i = 0; i < n; i++) {
            char c = content.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < n && content.charAt(i + 1) == '"') {
                        cur.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    cur.append(c);
                }
            } else if (c == '"') {
                inQuotes = true;
            } else if (c == ',') {
                field.add(cur.toString());
                cur.setLength(0);
            } else if (c == '\r') {
                // skip
            } else if (c == '\n') {
                field.add(cur.toString());
                cur.setLength(0);
                records.add(new ArrayList<>(field));
                field.clear();
            } else {
                cur.append(c);
            }
        }
        if (cur.length() > 0 || !field.isEmpty()) {
            field.add(cur.toString());
            records.add(new ArrayList<>(field));
        }
        if (records.isEmpty()) return List.of();

        List<String> header = records.get(0);
        List<Map<String, String>> rows = new ArrayList<>();
        for (int r = 1; r < records.size(); r++) {
            List<String> rec = records.get(r);
            if (rec.size() == 1 && rec.get(0).isEmpty()) continue;
            Map<String, String> map = new LinkedHashMap<>();
            for (int c = 0; c < header.size() && c < rec.size(); c++) {
                map.put(header.get(c), rec.get(c));
            }
            rows.add(map);
        }
        return rows;
    }
}
