package org.project.engine;

import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Classifies a sherd as FUND (base), BUZA (rim), MARGINE_LATERALA (plain body/side fragment,
 * both ends broken) or NECUNOSCUT (couldn't tell), purely from local Java geometry - no
 * Python subprocess involved. Also derives Java-side equivalents of the sherdtool.py
 * measurements that are unreliable through the CLI bridge (rim/max diameter, preserved
 * height, preserved arc) - see [[project_sherd_shape_classification]] for the reasoning and
 * the exact-signature merge rules used to combine these with SherdPythonAnalyzer's output.
 *
 * Heuristic, not validated against labeled ground truth (none is available): an end of the
 * sherd "looks original" (a manufactured rim or an intact base/footring) when its boundary
 * there is both smooth and roughly circular (BoundaryEdgeAnalyzer); which category it is
 * then comes down to whether that edge sits near the sherd's widest point (rim) or its
 * narrowest/near-axis point (base). Both ends looking equally broken means a plain body
 * sherd; both ends looking equally original is genuinely ambiguous from geometry alone.
 */
public class SherdShapeClassifier {

    public enum Category { FUND, BUZA, MARGINE_LATERALA, NECUNOSCUT }

    /** Below this combined (smoothness + circularity) score, an edge is considered
     * "manufactured/intact" rather than a random break. First calibrated to 0.05 against 3
     * meshes (GB_001/013/014, "clean" synthetic-like edges scoring ~0.005-0.012 vs breaks at
     * ~0.1-0.43). Raised to 0.12 after testing against 39 real meshes including several
     * filenamed "Jar_Rim" (Fredricks/Hillsboro, Check/Simple Stamped pottery): their genuine
     * rims scored 0.04-0.21, well above 0.05 - real, decorated/stamped pottery isn't as
     * perfectly smooth as an idealized circle, so 0.05 misclassified most of them as
     * MARGINE_LATERALA. 0.12 is a compromise, not a validated number: it still misses some
     * of the roughest labeled rims and may call a few genuine breaks "original" instead -
     * there's real overlap in this range between "intact but textured" and "actually broken"
     * that a single smoothness+circularity scalar can't cleanly resolve. If this keeps being
     * wrong, the more principled fix is probably filtering out decoration-scale (small,
     * high-frequency) radius variation before measuring smoothness, not another threshold
     * tweak - see [[project_sherd_shape_classification]]. */
    private static final float ORIGINAL_EDGE_THRESHOLD = 0.12f;

    /** An edge whose mean radius is below this fraction of the sherd's max radius is
     * considered "near the axis" (base-like) rather than "near the rim" (opening-like). */
    private static final float BASE_RADIUS_FRACTION = 0.4f;

    public static class Result {
        public final Category category;
        public final float confidence;
        public final float maxDiameter;
        public final float rimDiameter;
        public final float preservedHeight;
        public final float preservedArcDeg;

        Result(Category category, float confidence, float maxDiameter, float rimDiameter,
               float preservedHeight, float preservedArcDeg) {
            this.category = category;
            this.confidence = confidence;
            this.maxDiameter = maxDiameter;
            this.rimDiameter = rimDiameter;
            this.preservedHeight = preservedHeight;
            this.preservedArcDeg = preservedArcDeg;
        }
    }

    public static Result classify(float[] positions, int[] indices, CurvatureClassifier.VesselAxisEstimate axis,
                                    GhostVesselGenerator.VesselProfile profile) {
        BoundaryEdgeAnalyzer.EdgeBand[] bands = BoundaryEdgeAnalyzer.analyzeBands(positions, indices, axis, 0.2f);
        BoundaryEdgeAnalyzer.EdgeBand bottom = bands[0];
        BoundaryEdgeAnalyzer.EdgeBand top = bands[1];

        float maxRadius = 0;
        for (float r : profile.radii) if (r > maxRadius) maxRadius = r;
        float maxDiameter = 2 * maxRadius;
        float preservedHeight = profile.heights.length > 0
                ? profile.heights[profile.heights.length - 1] - profile.heights[0] : 0f;
        float preservedArcDeg = computePreservedArcDeg(positions, axis);

        boolean bottomOriginal = bottom.score() < ORIGINAL_EDGE_THRESHOLD;
        boolean topOriginal = top.score() < ORIGINAL_EDGE_THRESHOLD;

        Category category;
        float confidence;
        float rimDiameter = maxDiameter;

        if (bottomOriginal && !topOriginal) {
            category = radiusLooksLikeBase(bottom.meanRadius, maxRadius) ? Category.FUND : Category.BUZA;
            if (category == Category.BUZA) rimDiameter = 2 * bottom.meanRadius;
            confidence = confidenceFromScore(bottom.score());
        } else if (topOriginal && !bottomOriginal) {
            category = radiusLooksLikeBase(top.meanRadius, maxRadius) ? Category.FUND : Category.BUZA;
            if (category == Category.BUZA) rimDiameter = 2 * top.meanRadius;
            confidence = confidenceFromScore(top.score());
        } else if (!bottomOriginal && !topOriginal) {
            // Ambele capete arata ca rupturi - fragment pur lateral, fara nicio muchie
            // originala a vasului pastrata. Incredere INVERSATA fata de FUND/BUZA: aici
            // creste cu cat scorul (mai slab, adica al capatului "mai putin rupt") e mai
            // DEPARTE DEASUPRA pragului - confidenceFromScore ar da mereu 0 aici, fiindca
            // e gandita pt distanta SUB prag (cand un capat chiar arata original).
            category = Category.MARGINE_LATERALA;
            confidence = marginConfidenceFromScore(Math.min(bottom.score(), top.score()));
        } else {
            // Ambele capete arata "originale" deopotriva - geometric ambiguu, nu ghicim.
            category = Category.NECUNOSCUT;
            confidence = 0f;
        }

        return new Result(category, confidence, maxDiameter, rimDiameter, preservedHeight, preservedArcDeg);
    }

    private static boolean radiusLooksLikeBase(float edgeRadius, float maxRadius) {
        if (maxRadius <= 1e-6f) return false;
        return (edgeRadius / maxRadius) < BASE_RADIUS_FRACTION;
    }

    private static float confidenceFromScore(float score) {
        if (!Float.isFinite(score)) return 0f;
        float c = 1f - (score / ORIGINAL_EDGE_THRESHOLD);
        return Math.max(0f, Math.min(1f, c));
    }

    /** For MARGINE_LATERALA: confidence grows the further ABOVE the threshold the better
     * (less-jagged) of the two ends sits - a score just barely over the line is a borderline
     * call (low confidence), one clearly, safely into "broken" territory is a confident call. */
    private static float marginConfidenceFromScore(float score) {
        if (!Float.isFinite(score)) return 1f; // +Infinity (no boundary at all) - definitely no original edge
        float c = (score - ORIGINAL_EDGE_THRESHOLD) / ORIGINAL_EDGE_THRESHOLD;
        return Math.max(0f, Math.min(1f, c));
    }

    /** Angular coverage (degrees) of the sherd around the vessel axis: 360 minus the
     * largest angular gap between vertices when sorted by azimuth. */
    private static float computePreservedArcDeg(float[] positions, CurvatureClassifier.VesselAxisEstimate axis) {
        if (positions.length < 9) return 0f;

        Vector3f dir = new Vector3f(axis.axisDirection);
        if (dir.lengthSquared() < 1e-12f) dir.set(0, 1, 0); else dir.normalize();
        Vector3f helper = Math.abs(dir.x) < 0.9f ? new Vector3f(1, 0, 0) : new Vector3f(0, 1, 0);
        Vector3f u = new Vector3f(helper).sub(new Vector3f(dir).mul(helper.dot(dir)));
        if (u.lengthSquared() < 1e-12f) u.set(0, 0, 1);
        u.normalize();
        Vector3f w = new Vector3f(dir).cross(u).normalize();

        List<Double> angles = new ArrayList<>(positions.length / 3);
        Vector3f p = new Vector3f(), rel = new Vector3f();
        for (int i = 0; i + 2 < positions.length; i += 3) {
            p.set(positions[i], positions[i + 1], positions[i + 2]);
            rel.set(p).sub(axis.axisPoint);
            double uu = rel.dot(u), ww = rel.dot(w);
            if (uu * uu + ww * ww < 1e-12) continue; // punct chiar pe axa - unghi nedefinit
            angles.add(Math.atan2(ww, uu));
        }
        if (angles.size() < 2) return 0f;
        Collections.sort(angles);

        double maxGap = angles.get(0) + 2 * Math.PI - angles.get(angles.size() - 1);
        for (int i = 0; i < angles.size() - 1; i++) {
            double gap = angles.get(i + 1) - angles.get(i);
            if (gap > maxGap) maxGap = gap;
        }
        double coverage = 2 * Math.PI - maxGap;
        return (float) Math.toDegrees(Math.max(0, coverage));
    }
}
