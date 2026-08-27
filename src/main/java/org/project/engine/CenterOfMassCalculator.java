package org.project.engine;

import org.joml.Vector3f;

public class CenterOfMassCalculator
{
    public static Vector3f compute(float[] positions, int[] indices)
    {
        Vector3f weightedSum = new Vector3f();
        float totalVolume = 0f;

        Vector3f a = new Vector3f();
        Vector3f b = new Vector3f();
        Vector3f c = new Vector3f();

        for (int i = 0; i < indices.length; i += 3)
        {
            int ia = indices[i] * 3;
            int ib = indices[i + 1] * 3;
            int ic = indices[i + 2] * 3;

            a.set(positions[ia], positions[ia + 1], positions[ia + 2]);
            b.set(positions[ib], positions[ib + 1], positions[ib + 2]);
            c.set(positions[ic], positions[ic + 1], positions[ic + 2]);

            // Tetraedru (origine, a, b, c) - volumul semnat = produsul mixt / 6.
            // Se aduna corect pentru orice mesh inchis, indiferent de punctul de referinta ales.
            Vector3f cross = new Vector3f(b).cross(c);
            float signedVolume = a.dot(cross) / 6f;

            Vector3f tetCentroid = new Vector3f(a).add(b).add(c).mul(0.25f);

            weightedSum.add(tetCentroid.mul(signedVolume));
            totalVolume += signedVolume;
        }

        if (Math.abs(totalVolume) < 1e-9f)
        {
            return new Vector3f();
        }

        return weightedSum.mul(1f / totalVolume);
    }
}
