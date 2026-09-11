package org.project.engine;

import org.joml.Vector3f;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class CurvatureClassifier {

    /**
     * General implicit quadric ax²+by²+cz²+dxy+eyz+fxz+gx+hy+iz+j=0, fitted to one wall
     * (exterior or interior) as a curved patch instead of the flat plane the wall's mean
     * normal/centroid describe. The fit is done as a Monge patch h=f(u,v) in the plane's
     * own tangent frame (numerically simple, always a well-posed 6-unknown least squares
     * problem) and then expanded back into global x,y,z coefficients for display.
     */
    public static class QuadricSurface {
        public final float x2, y2, z2, xy, yz, xz, x, y, z, c;

        QuadricSurface(float x2, float y2, float z2, float xy, float yz, float xz,
                       float x, float y, float z, float c) {
            this.x2 = x2; this.y2 = y2; this.z2 = z2;
            this.xy = xy; this.yz = yz; this.xz = xz;
            this.x = x; this.y = y; this.z = z;
            this.c = c;
        }

        static QuadricSurface zero() {
            return new QuadricSurface(0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        }

        public String toEquationText() {
            // Locale.ROOT: mereu "." ca separator zecimal, indiferent de limba sistemului
            // (altfel, pe un JVM cu locale romanesc, String.format produce virgula si
            // ecuatia devine ambigua/neparsabila - "0,1234x² +0,5xy" etc.).
            return String.format(Locale.ROOT,
                    "%.4fx² %+.4fy² %+.4fz² %+.4fxy %+.4fyz %+.4fxz %+.4fx %+.4fy %+.4fz %+.4f = 0",
                    x2, y2, z2, xy, yz, xz, x, y, z, c);
        }
    }

    public static class Result {
        public final float[] exteriorTriangles;
        public final float[] interiorTriangles;
        public final Vector3f exteriorPlaneNormal;
        public final Vector3f exteriorPlanePoint;
        public final Vector3f interiorPlaneNormal;
        public final Vector3f interiorPlanePoint;
        public final QuadricSurface exteriorQuadric;
        public final QuadricSurface interiorQuadric;

        Result(float[] exteriorTriangles, float[] interiorTriangles,
               Vector3f exteriorPlaneNormal, Vector3f exteriorPlanePoint,
               Vector3f interiorPlaneNormal, Vector3f interiorPlanePoint,
               QuadricSurface exteriorQuadric, QuadricSurface interiorQuadric) {
            this.exteriorTriangles = exteriorTriangles;
            this.interiorTriangles = interiorTriangles;
            this.exteriorPlaneNormal = exteriorPlaneNormal;
            this.exteriorPlanePoint = exteriorPlanePoint;
            this.interiorPlaneNormal = interiorPlaneNormal;
            this.interiorPlanePoint = interiorPlanePoint;
            this.exteriorQuadric = exteriorQuadric;
            this.interiorQuadric = interiorQuadric;
        }
    }

    public static Result classify(float[] positions, int[] indices) {
        int numTriangles = indices.length / 3;
        Vector3f[] normals = new Vector3f[numTriangles];
        float[] areas = new float[numTriangles];
        Vector3f[] centroids = new Vector3f[numTriangles];

        int maxAreaIdx = -1;
        float maxArea = -1.0f;

        Vector3f a = new Vector3f();
        Vector3f b = new Vector3f();
        Vector3f c = new Vector3f();

        for (int i = 0; i < numTriangles; i++) {
            int ia = indices[i * 3] * 3;
            int ib = indices[i * 3 + 1] * 3;
            int ic = indices[i * 3 + 2] * 3;

            a.set(positions[ia], positions[ia + 1], positions[ia + 2]);
            b.set(positions[ib], positions[ib + 1], positions[ib + 2]);
            c.set(positions[ic], positions[ic + 1], positions[ic + 2]);

            Vector3f edge1 = new Vector3f(b).sub(a);
            Vector3f edge2 = new Vector3f(c).sub(a);
            Vector3f cross = new Vector3f(edge1).cross(edge2);
            float area = cross.length() * 0.5f;

            areas[i] = area;
            centroids[i] = new Vector3f(a).add(b).add(c).mul(1f / 3f);

            if (area > 1e-8f) {
                normals[i] = new Vector3f(cross).normalize();
                if (area > maxArea) {
                    maxArea = area;
                    maxAreaIdx = i;
                }
            } else {
                normals[i] = new Vector3f(0, 1, 0);
            }
        }

        if (maxAreaIdx == -1) {
            return new Result(new float[0], new float[0], new Vector3f(), new Vector3f(), new Vector3f(), new Vector3f(),
                    QuadricSurface.zero(), QuadricSurface.zero());
        }

        Vector3f refNormal = normals[maxAreaIdx];

        Vector3f meanA = new Vector3f();
        Vector3f meanB = new Vector3f();

        for (int i = 0; i < numTriangles; i++) {
            float dot = normals[i].dot(refNormal);
            if (dot > 0.5f) {
                meanA.add(new Vector3f(normals[i]).mul(areas[i]));
            } else if (dot < -0.5f) {
                meanB.add(new Vector3f(normals[i]).mul(areas[i]));
            }
        }

        if (meanA.lengthSquared() > 1e-6f) meanA.normalize(); else meanA.set(refNormal);
        if (meanB.lengthSquared() > 1e-6f) meanB.normalize(); else meanB.set(new Vector3f(refNormal).negate());

        List<Float> groupAVerts = new ArrayList<>();
        List<Float> groupBVerts = new ArrayList<>();

        List<Vector3f> sampleCentroidsA = new ArrayList<>();
        List<Float> sampleWeightsA = new ArrayList<>();
        List<Vector3f> sampleCentroidsB = new ArrayList<>();
        List<Float> sampleWeightsB = new ArrayList<>();

        Vector3f centroidA = new Vector3f(); float areaA = 0;
        Vector3f centroidB = new Vector3f(); float areaB = 0;

        for (int i = 0; i < numTriangles; i++) {
            float dotA = normals[i].dot(meanA);
            float dotB = normals[i].dot(meanB);

            if (dotA < 0.35f && dotB < 0.35f) continue;

            int ia = indices[i * 3] * 3;
            int ib = indices[i * 3 + 1] * 3;
            int ic = indices[i * 3 + 2] * 3;

            if (dotA > dotB) {
                addTri(groupAVerts, positions, ia, ib, ic);
                centroidA.add(new Vector3f(centroids[i]).mul(areas[i]));
                areaA += areas[i];
                sampleCentroidsA.add(centroids[i]);
                sampleWeightsA.add(areas[i]);
            } else {
                addTri(groupBVerts, positions, ia, ib, ic);
                centroidB.add(new Vector3f(centroids[i]).mul(areas[i]));
                areaB += areas[i];
                sampleCentroidsB.add(centroids[i]);
                sampleWeightsB.add(areas[i]);
            }
        }

        if (areaA > 0) centroidA.mul(1f / areaA);
        if (areaB > 0) centroidB.mul(1f / areaB);

        Vector3f BtoA = new Vector3f(centroidA).sub(centroidB);
        boolean isAExterior = BtoA.dot(meanA) > 0;

        float[] exteriorTriangles = toArray(isAExterior ? groupAVerts : groupBVerts);
        float[] interiorTriangles = toArray(isAExterior ? groupBVerts : groupAVerts);
        Vector3f exteriorNormal = isAExterior ? meanA : meanB;
        Vector3f exteriorPoint = isAExterior ? centroidA : centroidB;
        Vector3f interiorNormal = isAExterior ? meanB : meanA;
        Vector3f interiorPoint = isAExterior ? centroidB : centroidA;

        QuadricSurface quadricA = fitQuadricSurface(sampleCentroidsA, sampleWeightsA, meanA, centroidA);
        QuadricSurface quadricB = fitQuadricSurface(sampleCentroidsB, sampleWeightsB, meanB, centroidB);
        QuadricSurface exteriorQuadric = isAExterior ? quadricA : quadricB;
        QuadricSurface interiorQuadric = isAExterior ? quadricB : quadricA;

        return new Result(exteriorTriangles, interiorTriangles, exteriorNormal, exteriorPoint, interiorNormal, interiorPoint,
                exteriorQuadric, interiorQuadric);
    }

    /**
     * Fits h = A*u² + B*v² + C*u*v + D*u + E*v + F in the tangent frame (uAxis, vAxis, normal)
     * around origin, via area-weighted least squares, then expands the result into a global
     * quadric in x,y,z. Falls back to the flat plane (h=0) when there are too few samples or
     * the least-squares system is singular (e.g. all samples share the same u,v).
     */
    private static QuadricSurface fitQuadricSurface(List<Vector3f> points, List<Float> weights,
                                                      Vector3f normal, Vector3f origin) {
        Vector3f nAxis = new Vector3f(normal);
        if (nAxis.lengthSquared() < 1e-12f) nAxis.set(0, 0, 1); else nAxis.normalize();

        Vector3f uAxis = new Vector3f();
        Vector3f vAxis = new Vector3f();
        buildTangentBasis(nAxis, uAxis, vAxis);

        int n = points.size();
        if (n >= 6) {
            double[][] ata = new double[6][6];
            double[] atb = new double[6];
            Vector3f rel = new Vector3f();

            for (int i = 0; i < n; i++) {
                rel.set(points.get(i)).sub(origin);
                double u = rel.dot(uAxis);
                double v = rel.dot(vAxis);
                double h = rel.dot(nAxis);
                double w = weights.get(i);
                double[] row = {u * u, v * v, u * v, u, v, 1.0};
                for (int r = 0; r < 6; r++) {
                    atb[r] += w * row[r] * h;
                    for (int c = 0; c < 6; c++) {
                        ata[r][c] += w * row[r] * row[c];
                    }
                }
            }

            double[] coeffs = solveLinearSystem(ata, atb);
            if (coeffs != null) {
                return buildGlobalQuadric(origin, uAxis, vAxis, nAxis,
                        coeffs[0], coeffs[1], coeffs[2], coeffs[3], coeffs[4], coeffs[5]);
            }
        }

        // Too few samples (or a degenerate/singular fit): flat plane, still expressed as
        // a (degree-2, all-curvature-terms-zero) quadric so the equation format stays uniform.
        return buildGlobalQuadric(origin, uAxis, vAxis, nAxis, 0, 0, 0, 0, 0, 0);
    }

    private static void buildTangentBasis(Vector3f n, Vector3f outU, Vector3f outV) {
        Vector3f helper = Math.abs(n.x) < 0.9f ? new Vector3f(1, 0, 0) : new Vector3f(0, 1, 0);
        outU.set(helper).sub(new Vector3f(n).mul(helper.dot(n)));
        if (outU.lengthSquared() < 1e-12f) outU.set(0, 0, 1);
        outU.normalize();
        outV.set(n).cross(outU).normalize();
    }

    /**
     * Expands h = A*u² + B*v² + C*u*v + D*u + E*v + F (with u,v,h affine functions of x,y,z
     * via the given frame) into global quadric coefficients for Q(x,y,z) = A*u²+B*v²+C*u*v+D*u+E*v+F-h = 0.
     */
    private static QuadricSurface buildGlobalQuadric(Vector3f origin, Vector3f uAxis, Vector3f vAxis, Vector3f nAxis,
                                                        double A, double B, double C, double D, double E, double F) {
        double Ux = uAxis.x, Uy = uAxis.y, Uz = uAxis.z;
        double Vx = vAxis.x, Vy = vAxis.y, Vz = vAxis.z;
        double Nx = nAxis.x, Ny = nAxis.y, Nz = nAxis.z;
        double Uc = -(Ux * origin.x + Uy * origin.y + Uz * origin.z);
        double Vc = -(Vx * origin.x + Vy * origin.y + Vz * origin.z);
        double Nc = -(Nx * origin.x + Ny * origin.y + Nz * origin.z);

        double x2 = A * Ux * Ux + B * Vx * Vx + C * Ux * Vx;
        double y2 = A * Uy * Uy + B * Vy * Vy + C * Uy * Vy;
        double z2 = A * Uz * Uz + B * Vz * Vz + C * Uz * Vz;
        double xy = A * 2 * Ux * Uy + B * 2 * Vx * Vy + C * (Ux * Vy + Uy * Vx);
        double yz = A * 2 * Uy * Uz + B * 2 * Vy * Vz + C * (Uy * Vz + Uz * Vy);
        double xz = A * 2 * Ux * Uz + B * 2 * Vx * Vz + C * (Ux * Vz + Uz * Vx);
        double x = A * 2 * Ux * Uc + B * 2 * Vx * Vc + C * (Ux * Vc + Uc * Vx) + D * Ux + E * Vx - Nx;
        double y = A * 2 * Uy * Uc + B * 2 * Vy * Vc + C * (Uy * Vc + Uc * Vy) + D * Uy + E * Vy - Ny;
        double z = A * 2 * Uz * Uc + B * 2 * Vz * Vc + C * (Uz * Vc + Uc * Vz) + D * Uz + E * Vz - Nz;
        double c = A * Uc * Uc + B * Vc * Vc + C * Uc * Vc + D * Uc + E * Vc + F - Nc;

        return new QuadricSurface((float) x2, (float) y2, (float) z2, (float) xy, (float) yz, (float) xz,
                (float) x, (float) y, (float) z, (float) c);
    }

    /** Gauss-Jordan elimination with partial pivoting. Returns null if the matrix is singular. */
    private static double[] solveLinearSystem(double[][] a, double[] b) {
        int n = b.length;
        double[][] m = new double[n][n + 1];
        for (int i = 0; i < n; i++) {
            System.arraycopy(a[i], 0, m[i], 0, n);
            m[i][n] = b[i];
        }

        for (int col = 0; col < n; col++) {
            int pivotRow = col;
            double maxAbs = Math.abs(m[col][col]);
            for (int row = col + 1; row < n; row++) {
                if (Math.abs(m[row][col]) > maxAbs) {
                    maxAbs = Math.abs(m[row][col]);
                    pivotRow = row;
                }
            }
            if (maxAbs < 1e-9) return null;

            double[] tmp = m[col]; m[col] = m[pivotRow]; m[pivotRow] = tmp;

            double pivot = m[col][col];
            for (int c = col; c <= n; c++) m[col][c] /= pivot;

            for (int row = 0; row < n; row++) {
                if (row == col) continue;
                double factor = m[row][col];
                if (factor == 0) continue;
                for (int c = col; c <= n; c++) {
                    m[row][c] -= factor * m[col][c];
                }
            }
        }

        double[] x = new double[n];
        for (int i = 0; i < n; i++) x[i] = m[i][n];
        return x;
    }

    private static void addTri(List<Float> list, float[] positions, int ia, int ib, int ic) {
        list.add(positions[ia]); list.add(positions[ia + 1]); list.add(positions[ia + 2]);
        list.add(positions[ib]); list.add(positions[ib + 1]); list.add(positions[ib + 2]);
        list.add(positions[ic]); list.add(positions[ic + 1]); list.add(positions[ic + 2]);
    }

    private static float[] toArray(List<Float> list) {
        float[] array = new float[list.size()];
        for (int i = 0; i < array.length; i++) array[i] = list.get(i);
        return array;
    }
}