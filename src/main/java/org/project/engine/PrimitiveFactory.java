package org.project.engine;

import org.lwjgl.opengl.GL30;
import java.util.ArrayList;

public class PrimitiveFactory {

    public static Mesh createBox(float w, float h, float d, int textureId) {
        float[] pos = {
                -w/2, -h/2, -d/2,   w/2, -h/2, -d/2,
                w/2,  h/2, -d/2,  -w/2,  h/2, -d/2,
                -w/2, -h/2,  d/2,   w/2, -h/2,  d/2,
                w/2,  h/2,  d/2,  -w/2,  h/2,  d/2
        };
        float[] norms = new float[24];
        float[] uvs = new float[16];
        int[] indices = {
                0,1,2, 2,3,0, 1,5,6, 6,2,1, 5,4,7, 7,6,5,
                4,0,3, 3,7,4, 3,2,6, 6,7,3, 4,5,1, 1,0,4
        };

        java.util.List<MeshPart> parts = new ArrayList<>();
        parts.add(new MeshPart(0, 36, textureId));
        return new Mesh(pos, norms, uvs, indices, parts);
    }

    public static Mesh createLinesBox(float w, float h, float d, int textureId) {
        float[] pos = {
                -w/2, -h/2, -d/2,   w/2, -h/2, -d/2,
                w/2,  h/2, -d/2,  -w/2,  h/2, -d/2,
                -w/2, -h/2,  d/2,   w/2, -h/2,  d/2,
                w/2,  h/2,  d/2,  -w/2,  h/2,  d/2
        };
        float[] norms = new float[24];
        float[] uvs = new float[16];

        int[] indices = {
                0,1, 1,2, 2,3, 3,0,
                4,5, 5,6, 6,7, 7,4,
                0,4, 1,5, 2,6, 3,7
        };

        java.util.List<MeshPart> parts = new ArrayList<>();
        parts.add(new MeshPart(0, 24, textureId));

        Mesh mesh = new Mesh(pos, norms, uvs, indices, parts);
        mesh.setDrawMode(GL30.GL_LINES);
        return mesh;
    }
}