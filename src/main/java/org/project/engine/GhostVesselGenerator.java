package org.project.engine;

import org.joml.Vector3f;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Locale;

public class GhostVesselGenerator {

    private static class ProfileData {
        float[] cylPos;
        int[] cylIdx;
    }

    private static ProfileData createRevolutionProfile(SceneObject sherd, CurvatureClassifier.VesselAxisEstimate axis) {
        float[] pos = sherd.getMesh().getPositions();
        Vector3f dir = new Vector3f(axis.axisDirection);
        if (dir.lengthSquared() < 1e-12f) dir.set(0, 1, 0); else dir.normalize();

        // Cautam inaltimea minima si maxima a ciobului pe axa vasului
        float hMin = Float.MAX_VALUE, hMax = -Float.MAX_VALUE;
        for (int i = 0; i < pos.length; i += 3) {
            float h = new Vector3f(pos[i], pos[i + 1], pos[i + 2]).sub(axis.axisPoint).dot(dir);
            if (h < hMin) hMin = h;
            if (h > hMax) hMax = h;
        }
        if (hMin > hMax) { hMin = 0; hMax = 0; }

        int BINS = 100;
        float[] radii = new float[BINS];
        int[] counts = new int[BINS];

        // Extragem profilul real al ciobului (raza la fiecare nivel de inaltime)
        for (int i = 0; i < pos.length; i += 3) {
            Vector3f p = new Vector3f(pos[i], pos[i + 1], pos[i + 2]);
            Vector3f rel = p.sub(axis.axisPoint);
            float h = rel.dot(dir);
            Vector3f proj = new Vector3f(dir).mul(h);
            float r = rel.sub(proj).length();

            int bin = (int) (((h - hMin) / (hMax - hMin)) * (BINS - 1));
            if (bin < 0) bin = 0;
            if (bin >= BINS) bin = BINS - 1;

            if (r > radii[bin]) radii[bin] = r;
            counts[bin]++;
        }

        // Daca exista goluri (ciobul e neregulat), interpolam linear gaurile
        for (int i = 0; i < BINS; i++) {
            if (counts[i] == 0) {
                int left = i - 1; while (left >= 0 && counts[left] == 0) left--;
                int right = i + 1; while (right < BINS && counts[right] == 0) right++;

                if (left >= 0 && right < BINS) {
                    float t = (float) (i - left) / (right - left);
                    radii[i] = radii[left] * (1 - t) + radii[right] * t;
                } else if (left >= 0) radii[i] = radii[left];
                else if (right < BINS) radii[i] = radii[right];
                else radii[i] = axis.radius;
            }
        }

        // Netezim profilul extrasa pentru a elimina zgomotul
        float[] smoothRadii = new float[BINS];
        for (int i = 0; i < BINS; i++) {
            float sum = 0; int c = 0;
            for (int j = Math.max(0, i - 2); j <= Math.min(BINS - 1, i + 2); j++) {
                sum += radii[j]; c++;
            }
            smoothRadii[i] = sum / c;
        }

        Vector3f helper = Math.abs(dir.x) < 0.9f ? new Vector3f(1, 0, 0) : new Vector3f(0, 1, 0);
        Vector3f u = new Vector3f(helper).sub(new Vector3f(dir).mul(helper.dot(dir)));
        if (u.lengthSquared() < 1e-12f) u.set(0, 0, 1);
        u.normalize();
        Vector3f w = new Vector3f(dir).cross(u).normalize();

        int segments = 64;
        float[] ghostPos = new float[BINS * segments * 3];
        for (int i = 0; i < BINS; i++) {
            float h = hMin + (i / (float)(BINS - 1)) * (hMax - hMin);
            float r = smoothRadii[i];
            Vector3f center = new Vector3f(axis.axisPoint).add(new Vector3f(dir).mul(h));

            for (int s = 0; s < segments; s++) {
                double angle = 2 * Math.PI * s / segments;
                float cosA = (float) Math.cos(angle);
                float sinA = (float) Math.sin(angle);

                int vIdx = (i * segments + s) * 3;
                ghostPos[vIdx] = center.x + r * (u.x * cosA + w.x * sinA);
                ghostPos[vIdx+1] = center.y + r * (u.y * cosA + w.y * sinA);
                ghostPos[vIdx+2] = center.z + r * (u.z * cosA + w.z * sinA);
            }
        }

        int[] ghostIdx = new int[(BINS - 1) * segments * 6];
        int idxPtr = 0;
        for (int i = 0; i < BINS - 1; i++) {
            for (int s = 0; s < segments; s++) {
                int nextS = (s + 1) % segments;
                int b1 = i * segments + s;
                int b2 = i * segments + nextS;
                int t1 = (i + 1) * segments + s;
                int t2 = (i + 1) * segments + nextS;

                ghostIdx[idxPtr++] = b1; ghostIdx[idxPtr++] = b2; ghostIdx[idxPtr++] = t1;
                ghostIdx[idxPtr++] = t1; ghostIdx[idxPtr++] = b2; ghostIdx[idxPtr++] = t2;
            }
        }

        ProfileData data = new ProfileData();
        data.cylPos = ghostPos;
        data.cylIdx = ghostIdx;
        return data;
    }

    public static void generateGhostOnly(SceneObject sherd, CurvatureClassifier.VesselAxisEstimate axis, File outputFile) throws IOException {
        ProfileData data = createRevolutionProfile(sherd, axis);
        try (PrintWriter pw = new PrintWriter(new FileWriter(outputFile))) {
            pw.println("o Inel_Fantoma");
            for (int i = 0; i < data.cylPos.length; i += 3) {
                pw.printf(Locale.ROOT, "v %.6f %.6f %.6f\n", data.cylPos[i], data.cylPos[i+1], data.cylPos[i+2]);
            }
            for (int i = 0; i < data.cylIdx.length; i += 3) {
                pw.printf("f %d %d %d\n", data.cylIdx[i]+1, data.cylIdx[i+1]+1, data.cylIdx[i+2]+1);
            }
        }
    }

    public static void generateAndExport(SceneObject sherd, CurvatureClassifier.VesselAxisEstimate axis, File outputFile) throws IOException {
        ProfileData data = createRevolutionProfile(sherd, axis);
        float[] pos = sherd.getMesh().getPositions();
        int[] idx = sherd.getMesh().getIndices();

        String objName = outputFile.getName();
        String mtlName = objName.substring(0, objName.lastIndexOf('.')) + ".mtl";
        File mtlFile = new File(outputFile.getParentFile(), mtlName);

        try (PrintWriter pw = new PrintWriter(new FileWriter(mtlFile))) {
            pw.println("newmtl Material_Ciob");
            pw.println("Kd 0.75 0.45 0.35");
            pw.println("d 1.0");
            pw.println("illum 2");
            pw.println();
            pw.println("newmtl Material_Fantoma");
            pw.println("Kd 0.4 0.7 0.9");
            pw.println("d 0.35"); // Transparență
            pw.println("illum 2");
        }

        try (PrintWriter pw = new PrintWriter(new FileWriter(outputFile))) {
            pw.printf("mtllib %s\n", mtlName);
            pw.println("o Ciob_Original");
            pw.println("usemtl Material_Ciob");
            for (int i = 0; i < pos.length; i += 3) {
                pw.printf(Locale.ROOT, "v %.6f %.6f %.6f\n", pos[i], pos[i+1], pos[i+2]);
            }
            for (int i = 0; i < idx.length; i += 3) {
                pw.printf("f %d %d %d\n", idx[i]+1, idx[i+1]+1, idx[i+2]+1);
            }

            int vOffset = pos.length / 3;
            pw.println("o Inel_Fantoma");
            pw.println("usemtl Material_Fantoma");
            for (int i = 0; i < data.cylPos.length; i += 3) {
                pw.printf(Locale.ROOT, "v %.6f %.6f %.6f\n", data.cylPos[i], data.cylPos[i+1], data.cylPos[i+2]);
            }
            for (int i = 0; i < data.cylIdx.length; i += 3) {
                pw.printf("f %d %d %d\n", data.cylIdx[i] + 1 + vOffset, data.cylIdx[i+1] + 1 + vOffset, data.cylIdx[i+2] + 1 + vOffset);
            }
        }
    }
}