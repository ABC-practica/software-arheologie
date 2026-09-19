package org.project.engine;

import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Looks at the two ends of a sherd (along its estimated vessel axis) and reports how much
 * each end's boundary looks like an INTACT ORIGINAL vessel edge (a manufactured rim or a
 * flat/footed base - smooth and roughly circular) versus a RANDOM BREAK (jagged, irregular).
 * Same idea as sherdtool.py's rim-disambiguation tests B and C (boundary smoothness + circle
 * fit residual, see python/sherdtool.py's orient_and_analyze_robust), reimplemented in Java
 * so it's always available without a Python subprocess. Used by SherdShapeClassifier to tell
 * fund/buza/margine laterala apart.
 */
public class BoundaryEdgeAnalyzer {

    private static final int MIN_BAND_POINTS = 8;

    public static class EdgeBand {
        public final float smoothness;   // MAD/median of radial distance - lower = smoother
        public final float circularity;  // mean circle-fit residual / R - lower = more circular
        public final float meanRadius;
        public final int pointCount;

        EdgeBand(float smoothness, float circularity, float meanRadius, int pointCount) {
            this.smoothness = smoothness;
            this.circularity = circularity;
            this.meanRadius = meanRadius;
            this.pointCount = pointCount;
        }

        /** Lower = looks more like an intact original edge. +Infinity when there aren't
         * enough boundary points in this band to judge at all (e.g. the sherd doesn't
         * actually have a mesh boundary near this end - a rare but possible edge case). */
        public float score() {
            if (pointCount < MIN_BAND_POINTS) return Float.POSITIVE_INFINITY;
            return smoothness + circularity;
        }
    }

    /**
     * Returns {bottomBand, topBand}: edge quality near the axis-projected minimum and
     * maximum of the sherd, each covering the outer bandFraction (e.g. 0.2 = 20%) of the
     * sherd's own boundary-point height range.
     */
    public static EdgeBand[] analyzeBands(float[] positions, int[] indices,
                                            CurvatureClassifier.VesselAxisEstimate axis, float bandFraction) {
        Vector3f dir = new Vector3f(axis.axisDirection);
        if (dir.lengthSquared() < 1e-12f) dir.set(0, 1, 0); else dir.normalize();

        int[] boundaryVertices = findBoundaryVertices(indices);
        EdgeBand none = new EdgeBand(0, 0, 0, 0);
        if (boundaryVertices.length == 0) {
            return new EdgeBand[]{none, none};
        }

        float[] t = new float[boundaryVertices.length];
        float tMin = Float.MAX_VALUE, tMax = -Float.MAX_VALUE;
        Vector3f p = new Vector3f();
        for (int i = 0; i < boundaryVertices.length; i++) {
            int vi = boundaryVertices[i] * 3;
            p.set(positions[vi], positions[vi + 1], positions[vi + 2]).sub(axis.axisPoint);
            t[i] = p.dot(dir);
            if (t[i] < tMin) tMin = t[i];
            if (t[i] > tMax) tMax = t[i];
        }
        float span = Math.max(tMax - tMin, 1e-6f);
        float band = span * bandFraction;

        Vector3f helper = Math.abs(dir.x) < 0.9f ? new Vector3f(1, 0, 0) : new Vector3f(0, 1, 0);
        Vector3f u = new Vector3f(helper).sub(new Vector3f(dir).mul(helper.dot(dir)));
        if (u.lengthSquared() < 1e-12f) u.set(0, 0, 1);
        u.normalize();
        Vector3f w = new Vector3f(dir).cross(u).normalize();

        EdgeBand bottom = analyzeBand(positions, boundaryVertices, t, u, w, axis.axisPoint, tMin, tMin + band);
        EdgeBand top = analyzeBand(positions, boundaryVertices, t, u, w, axis.axisPoint, tMax - band, tMax);
        return new EdgeBand[]{bottom, top};
    }

    private static EdgeBand analyzeBand(float[] positions, int[] boundaryVertices, float[] t,
                                          Vector3f u, Vector3f w, Vector3f axisPoint, float tLo, float tHi) {
        List<float[]> pts2d = new ArrayList<>();
        Vector3f p = new Vector3f(), rel = new Vector3f();
        for (int i = 0; i < boundaryVertices.length; i++) {
            if (t[i] < tLo || t[i] > tHi) continue;
            int vi = boundaryVertices[i] * 3;
            p.set(positions[vi], positions[vi + 1], positions[vi + 2]);
            rel.set(p).sub(axisPoint);
            pts2d.add(new float[]{rel.dot(u), rel.dot(w)});
        }
        int n = pts2d.size();
        if (n < MIN_BAND_POINTS) return new EdgeBand(0, 0, 0, 0);

        float[] radii = new float[n];
        for (int i = 0; i < n; i++) {
            float[] pt = pts2d.get(i);
            radii[i] = (float) Math.sqrt(pt[0] * pt[0] + pt[1] * pt[1]);
        }
        float median = median(radii);
        float[] absDevs = new float[n];
        for (int i = 0; i < n; i++) absDevs[i] = Math.abs(radii[i] - median);
        float mad = median(absDevs);
        float smoothness = median > 1e-6f ? mad / median : 0f;

        float meanRadius = 0;
        for (float r : radii) meanRadius += r;
        meanRadius /= n;

        float circularity = fitCircleResidualRatio(pts2d);

        return new EdgeBand(smoothness, circularity, meanRadius, n);
    }

    private static float fitCircleResidualRatio(List<float[]> pts2d) {
        int n = pts2d.size();
        double[][] ata = new double[3][3];
        double[] atb = new double[3];
        for (float[] pt : pts2d) {
            double x = pt[0], y = pt[1];
            double[] row = {2 * x, 2 * y, 1.0};
            double b = x * x + y * y;
            for (int r = 0; r < 3; r++) {
                atb[r] += row[r] * b;
                for (int c = 0; c < 3; c++) ata[r][c] += row[r] * row[c];
            }
        }
        double[] sol = CurvatureClassifier.solveLinearSystem(ata, atb);
        if (sol == null) return 1f; // degenerate (e.g. collinear points) - can't be a real circular edge

        double xc = sol[0], yc = sol[1], c = sol[2];
        double rr = c + xc * xc + yc * yc;
        if (rr <= 0) return 1f;
        double R = Math.sqrt(rr);

        double sumResid = 0;
        for (float[] pt : pts2d) {
            double d = Math.sqrt((pt[0] - xc) * (pt[0] - xc) + (pt[1] - yc) * (pt[1] - yc));
            sumResid += Math.abs(d - R);
        }
        double meanResid = sumResid / n;
        return R > 1e-6 ? (float) (meanResid / R) : 1f;
    }

    /** Boundary vertices: endpoints of edges shared by exactly one triangle (broken/open edges). */
    private static int[] findBoundaryVertices(int[] indices) {
        Map<Long, Integer> edgeCounts = new HashMap<>();
        int numTriangles = indices.length / 3;
        for (int i = 0; i < numTriangles; i++) {
            int a = indices[i * 3], b = indices[i * 3 + 1], c = indices[i * 3 + 2];
            countEdge(edgeCounts, a, b);
            countEdge(edgeCounts, b, c);
            countEdge(edgeCounts, c, a);
        }
        Set<Integer> boundarySet = new LinkedHashSet<>();
        for (Map.Entry<Long, Integer> e : edgeCounts.entrySet()) {
            if (e.getValue() == 1) {
                long key = e.getKey();
                boundarySet.add((int) (key >> 32));
                boundarySet.add((int) (key & 0xFFFFFFFFL));
            }
        }
        int[] result = new int[boundarySet.size()];
        int idx = 0;
        for (int v : boundarySet) result[idx++] = v;
        return result;
    }

    private static void countEdge(Map<Long, Integer> counts, int a, int b) {
        int lo = Math.min(a, b), hi = Math.max(a, b);
        long key = ((long) lo << 32) | (hi & 0xFFFFFFFFL);
        counts.merge(key, 1, Integer::sum);
    }

    private static float median(float[] arr) {
        float[] copy = arr.clone();
        Arrays.sort(copy);
        int n = copy.length;
        return (n % 2 == 1) ? copy[n / 2] : (copy[n / 2 - 1] + copy[n / 2]) / 2f;
    }
}
