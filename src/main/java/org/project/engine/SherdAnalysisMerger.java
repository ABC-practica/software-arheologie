package org.project.engine;

import org.joml.Vector3f;

/**
 * Combines SherdPythonAnalyzer's output (sherdtool.py, run as a subprocess) with the local
 * Java analysis (CurvatureClassifier + SherdShapeClassifier + GhostVesselGenerator) into one
 * final result, per-field - not "prefer Java" or "prefer Python" wholesale.
 *
 * The merge rules aren't a generic confidence threshold: each dubious Python value has an
 * exact, known signature (verified against python/sherdtool.py's source, not guessed):
 *   - rimDiameter, maxDiameter, shoulderHeight/preservedArcDeg, form_class/formAspectRatio/
 *     rimEversionDeg are hardcoded to 0.0/"unknown" on the CLI path SherdPythonAnalyzer
 *     actually calls (process_single_mesh) - they're only ever computed in the separate,
 *     unused --interactive code path. Always overridden with Java, unconditionally.
 *   - axisDir/axisPoint: sherdtool.py's "degenerate axis" fallback resets the axis to
 *     exactly (0,1,0) with fit_residual_rmse exactly 0.0 - that specific combination is the
 *     tell. Anything else is treated as a genuine fit and kept.
 *   - preservedHeight and the profile_h/profile_r curve: trusted exactly when
 *     SherdAnalysisResult.hasProfile() is true (Python's own multi-slice axis fit produced a
 *     real r(h) profile); otherwise Python fell back to a bounding-box guess and Java's
 *     axis-aware profile (GhostVesselGenerator) is used instead.
 * See [[project_sherd_shape_classification]] for how this was derived.
 */
public class SherdAnalysisMerger {

    public static class MergedResult {
        public final String name;
        public final String unit;
        public final int nVertices;
        public final int nFaces;
        public final String bboxExtent;
        public final Vector3f axisDirection;
        public final Vector3f axisPoint;
        public final float rimDiameter;
        public final float maxDiameter;
        public final float preservedHeight;
        public final float preservedArcDeg;
        public final float formAspectRatio;
        public final SherdShapeClassifier.Category category;
        public final float categoryConfidence;
        public final String pythonQuality;
        public final String pythonNotes;
        public final float[] profileHeights;
        public final float[] profileRadii;

        MergedResult(String name, String unit, int nVertices, int nFaces, String bboxExtent,
                     Vector3f axisDirection, Vector3f axisPoint, float rimDiameter, float maxDiameter,
                     float preservedHeight, float preservedArcDeg, float formAspectRatio,
                     SherdShapeClassifier.Category category, float categoryConfidence,
                     String pythonQuality, String pythonNotes, float[] profileHeights, float[] profileRadii) {
            this.name = name;
            this.unit = unit;
            this.nVertices = nVertices;
            this.nFaces = nFaces;
            this.bboxExtent = bboxExtent;
            this.axisDirection = axisDirection;
            this.axisPoint = axisPoint;
            this.rimDiameter = rimDiameter;
            this.maxDiameter = maxDiameter;
            this.preservedHeight = preservedHeight;
            this.preservedArcDeg = preservedArcDeg;
            this.formAspectRatio = formAspectRatio;
            this.category = category;
            this.categoryConfidence = categoryConfidence;
            this.pythonQuality = pythonQuality;
            this.pythonNotes = pythonNotes;
            this.profileHeights = profileHeights;
            this.profileRadii = profileRadii;
        }

        public boolean hasProfile() {
            return profileHeights.length >= 2 && profileHeights.length == profileRadii.length;
        }
    }

    public static MergedResult merge(SherdPythonAnalyzer.SherdAnalysisResult python,
                                       CurvatureClassifier.VesselAxisEstimate javaAxis,
                                       SherdShapeClassifier.Result shape,
                                       GhostVesselGenerator.VesselProfile javaProfile) {
        Vector3f pythonAxisDir = parseVector3(python.axisDir());
        boolean axisIsDegenerateFallback = pythonAxisDir != null
                && closeTo(pythonAxisDir, 0f, 1f, 0f, 1e-3f)
                && Math.abs(python.fitResidualRmse()) < 1e-6;

        Vector3f axisDirection;
        Vector3f axisPoint;
        if (pythonAxisDir == null || axisIsDegenerateFallback) {
            axisDirection = new Vector3f(javaAxis.axisDirection);
            axisPoint = new Vector3f(javaAxis.axisPoint);
        } else {
            Vector3f pythonAxisPoint = parseVector3(python.axisPoint());
            axisDirection = pythonAxisDir;
            axisPoint = pythonAxisPoint != null ? pythonAxisPoint : new Vector3f(javaAxis.axisPoint);
        }

        boolean pythonHasProfile = python.hasProfile();
        float preservedHeight = pythonHasProfile ? (float) python.preservedHeight() : shape.preservedHeight;
        float[] profileHeights = pythonHasProfile ? python.profileHeights() : javaProfile.heights;
        float[] profileRadii = pythonHasProfile ? python.profileRadii() : javaProfile.radii;

        // rim/max diameter, preserved arc: always dead (0.0) on the CLI path - unconditional override.
        float rimDiameter = shape.rimDiameter;
        float maxDiameter = shape.maxDiameter;
        float preservedArcDeg = shape.preservedArcDeg;
        float formAspectRatio = rimDiameter > 1e-6f ? preservedHeight / (rimDiameter / 2f) : 0f;

        return new MergedResult(
                python.name(), python.unit(), python.nVertices(), python.nFaces(), python.bboxExtent(),
                axisDirection, axisPoint, rimDiameter, maxDiameter, preservedHeight, preservedArcDeg,
                formAspectRatio, shape.category, shape.confidence,
                python.quality(), python.notes(), profileHeights, profileRadii);
    }

    /**
     * A MergedResult built from Java alone, before sherdtool.py has answered - same shape as
     * merge()'s output, with "se calculeaza..." placeholders standing in for the fields that
     * genuinely can't be known without Python (quality, notes) or that Python would otherwise
     * confirm (unit, bbox extent - name and vertex/face counts are exact from the mesh itself,
     * no need to wait). The point: the UI renders this with the SAME formatter it uses for the
     * real merge() result, so when Python answers only the placeholder fields visibly change -
     * the Java-computed numbers (category, diameters, arc, axis) stay exactly where they were,
     * instead of the whole report flashing to a differently-laid-out one.
     */
    public static MergedResult pendingFromJavaOnly(String name, int nVertices, int nFaces,
                                                      CurvatureClassifier.VesselAxisEstimate javaAxis,
                                                      SherdShapeClassifier.Result shape,
                                                      GhostVesselGenerator.VesselProfile javaProfile) {
        float formAspectRatio = shape.rimDiameter > 1e-6f
                ? shape.preservedHeight / (shape.rimDiameter / 2f) : 0f;
        return new MergedResult(
                name, "?", nVertices, nFaces, "se calculeaza...",
                new Vector3f(javaAxis.axisDirection), new Vector3f(javaAxis.axisPoint),
                shape.rimDiameter, shape.maxDiameter, shape.preservedHeight, shape.preservedArcDeg,
                formAspectRatio, shape.category, shape.confidence,
                "se calculeaza...", "", javaProfile.heights, javaProfile.radii);
    }

    private static boolean closeTo(Vector3f v, float x, float y, float z, float tol) {
        return Math.abs(v.x - x) < tol && Math.abs(v.y - y) < tol && Math.abs(v.z - z) < tol;
    }

    /** Parses sherdtool.py's "x,y,z" axis strings (e.g. "0.0000,1.0000,0.0000"). Null on any
     * parse failure - callers treat that the same as "no usable Python axis". */
    static Vector3f parseVector3(String csv) {
        if (csv == null || csv.isBlank()) return null;
        String[] parts = csv.split(",");
        if (parts.length != 3) return null;
        try {
            return new Vector3f(
                    Float.parseFloat(parts[0].trim()),
                    Float.parseFloat(parts[1].trim()),
                    Float.parseFloat(parts[2].trim()));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
