package org.project.engine;

import javafx.application.Platform;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class SingleObjectRenderer implements Runnable
{
    private final String modelPath;
    private final WritableImage fxImage;
    private final int width;
    private final int height;

    private volatile float pendingRotateX = 0;
    private volatile float pendingRotateY = 0;
    private volatile float pendingScale = 0;

    private volatile boolean computeRequested = false;
    private volatile boolean showExterior = false;
    private volatile boolean showInterior = false;

    private volatile float crossSectionYaw = 0f;
    private volatile float crossSectionPitch = 0f;
    private volatile float crossSectionOffset = 0f;
    private volatile boolean crossSectionComputeRequested = true;
    private volatile boolean topViewCaptureRequested = false;
    private volatile boolean centerOfMassRequested = false;

    private Consumer<CurvatureClassifier.Result> onCurvatureComputed;
    private Consumer<WritableImage> onTopViewCaptured;
    private Consumer<Vector3f> onCenterOfMassComputed;

    public SingleObjectRenderer(String modelPath, WritableImage fxImage, int width, int height) {
        this.modelPath = modelPath;
        this.fxImage = fxImage;
        this.width = width;
        this.height = height;
    }

    public void rotate(float deltaX, float deltaY) {
        pendingRotateX += deltaX;
        pendingRotateY += deltaY;
    }

    public void scale(float delta) {
        pendingScale += delta;
    }

    public void requestComputeCurvature() { this.computeRequested = true; }
    public void setOnCurvatureComputed(Consumer<CurvatureClassifier.Result> callback) { this.onCurvatureComputed = callback; }
    public void setShowExterior(boolean show) { this.showExterior = show; }
    public void setShowInterior(boolean show) { this.showInterior = show; }

    public void setCrossSectionYaw(float radians) {
        this.crossSectionYaw = radians;
        this.crossSectionComputeRequested = true;
    }
    public void setCrossSectionPitch(float radians) {
        this.crossSectionPitch = radians;
        this.crossSectionComputeRequested = true;
    }
    public void setCrossSectionOffset(float offset) {
        this.crossSectionOffset = offset;
        this.crossSectionComputeRequested = true;
    }
    public void setCrossSectionThickness(float thickness) {}
    public void requestComputeCrossSection() { this.crossSectionComputeRequested = true; }
    public void requestTopViewCapture() { this.topViewCaptureRequested = true; }
    public void setOnTopViewCaptured(Consumer<WritableImage> callback) { this.onTopViewCaptured = callback; }
    public void requestComputeCenterOfMass() { this.centerOfMassRequested = true; }
    public void setOnCenterOfMassComputed(Consumer<Vector3f> callback) { this.onCenterOfMassComputed = callback; }

    @Override
    public void run()
    {
        if (!GLFW.glfwInit()) throw new IllegalStateException("Nu s-a putut initializa GLFW");

        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        long window = GLFW.glfwCreateWindow(width, height, "Offscreen", 0, 0);
        GLFW.glfwMakeContextCurrent(window);
        GL.createCapabilities();

        int fbo = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);

        int textureColor = GL30.glGenTextures();
        GL30.glBindTexture(GL30.GL_TEXTURE_2D, textureColor);
        GL30.glTexImage2D(GL30.GL_TEXTURE_2D, 0, GL30.GL_RGBA, width, height, 0, GL30.GL_RGBA, GL30.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL30.GL_TEXTURE_2D, textureColor, 0);

        int rbo = GL30.glGenRenderbuffers();
        GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, rbo);
        GL30.glRenderbufferStorage(GL30.GL_RENDERBUFFER, GL30.GL_DEPTH24_STENCIL8, width, height);
        GL30.glFramebufferRenderbuffer(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_STENCIL_ATTACHMENT, GL30.GL_RENDERBUFFER, rbo);

        SceneObject object;
        ShaderProgram shader, overlayShader;
        try {
            Mesh mesh = ModelLoader.loadModel(modelPath);
            object = new SceneObject(0, mesh, modelPath);

            shader = new ShaderProgram();
            shader.createVertexShader(Files.readString(Paths.get("src/main/resources/shaders/vertex.glsl")));
            shader.createFragmentShader(Files.readString(Paths.get("src/main/resources/shaders/fragment.glsl")));
            shader.link();
            shader.bind();
            shader.setUniform("texture1", 0);
            shader.unbind();

            overlayShader = new ShaderProgram();
            overlayShader.createVertexShader(Files.readString(Paths.get("src/main/resources/shaders/overlay_vertex.glsl")));
            overlayShader.createFragmentShader(Files.readString(Paths.get("src/main/resources/shaders/overlay_fragment.glsl")));
            overlayShader.link();
        } catch (Exception e) {
            e.printStackTrace();
            GLFW.glfwDestroyWindow(window);
            return;
        }

        Matrix4f projection = new Matrix4f().perspective((float) Math.toRadians(45.0f), (float) width / height, 0.1f, 100.0f);
        Matrix4f view = new Matrix4f().lookAt(0.0f, 0.0f, 5.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f);

        int crossSectionPlaneVao = GL30.glGenVertexArrays();
        int crossSectionPlaneVbo = GL30.glGenBuffers();
        GL30.glBindVertexArray(crossSectionPlaneVao);
        GL30.glBindBuffer(GL30.GL_ARRAY_BUFFER, crossSectionPlaneVbo);
        GL30.glBufferData(GL30.GL_ARRAY_BUFFER, 12L * 3 * 4, GL30.GL_DYNAMIC_DRAW);
        GL30.glVertexAttribPointer(0, 3, GL30.GL_FLOAT, false, 0, 0);
        GL30.glEnableVertexAttribArray(0);

        int intersectionLinesVao = GL30.glGenVertexArrays();
        int intersectionLinesVbo = GL30.glGenBuffers();
        GL30.glBindVertexArray(intersectionLinesVao);
        GL30.glBindBuffer(GL30.GL_ARRAY_BUFFER, intersectionLinesVbo);
        GL30.glVertexAttribPointer(0, 3, GL30.GL_FLOAT, false, 0, 0);
        GL30.glEnableVertexAttribArray(0);
        GL30.glBindVertexArray(0);

        int exteriorVao = GL30.glGenVertexArrays();
        int exteriorVbo = GL30.glGenBuffers();
        GL30.glBindVertexArray(exteriorVao);
        GL30.glBindBuffer(GL30.GL_ARRAY_BUFFER, exteriorVbo);
        GL30.glVertexAttribPointer(0, 3, GL30.GL_FLOAT, false, 0, 0);
        GL30.glEnableVertexAttribArray(0);

        int interiorVao = GL30.glGenVertexArrays();
        int interiorVbo = GL30.glGenBuffers();
        GL30.glBindVertexArray(interiorVao);
        GL30.glBindBuffer(GL30.GL_ARRAY_BUFFER, interiorVbo);
        GL30.glVertexAttribPointer(0, 3, GL30.GL_FLOAT, false, 0, 0);
        GL30.glEnableVertexAttribArray(0);
        GL30.glBindVertexArray(0);

        int centerOfMassVao = GL30.glGenVertexArrays();
        int centerOfMassVbo = GL30.glGenBuffers();
        GL30.glBindVertexArray(centerOfMassVao);
        GL30.glBindBuffer(GL30.GL_ARRAY_BUFFER, centerOfMassVbo);
        GL30.glVertexAttribPointer(0, 3, GL30.GL_FLOAT, false, 0, 0);
        GL30.glEnableVertexAttribArray(0);
        GL30.glBindVertexArray(0);

        int intersectionVertexCount = 0;
        int exteriorVertexCount = 0;
        int interiorVertexCount = 0;
        int centerOfMassVertexCount = 0;

        ByteBuffer pixelBuffer = MemoryUtil.memAlloc(width * height * 4);
        byte[] safePixelData = new byte[width * height * 4];
        PixelWriter pixelWriter = fxImage.getPixelWriter();

        GL30.glEnable(GL30.GL_DEPTH_TEST);

        try
        {
            while (!Thread.interrupted())
            {
                if (pendingRotateX != 0 || pendingRotateY != 0) {
                    object.rotation.y -= pendingRotateX * 0.01f;
                    object.rotation.x -= pendingRotateY * 0.01f;
                    pendingRotateX = 0;
                    pendingRotateY = 0;
                }
                if (pendingScale != 0) {
                    object.scale += pendingScale;
                    if (object.scale < 0.05f) object.scale = 0.05f;
                    pendingScale = 0;
                }

                GL30.glClearColor(0.16f, 0.16f, 0.16f, 1.0f);
                GL30.glClear(GL30.GL_COLOR_BUFFER_BIT | GL30.GL_DEPTH_BUFFER_BIT);

                float yaw = crossSectionYaw;
                float pitch = crossSectionPitch;
                float offset = crossSectionOffset;

                Vector3f localNormal = new Vector3f(
                        (float) (Math.sin(yaw) * Math.cos(pitch)),
                        (float) Math.sin(pitch),
                        (float) (Math.cos(yaw) * Math.cos(pitch))
                ).normalize();
                Vector3f localPoint = new Vector3f(localNormal).mul(offset);

                Matrix4f modelMat = object.getModelMatrix();
                Vector3f worldNormal = modelMat.transformDirection(new Vector3f(localNormal)).normalize();
                Vector3f worldPoint = modelMat.transformPosition(new Vector3f(localPoint));

                if (computeRequested) {
                    computeRequested = false;
                    CurvatureClassifier.Result result = CurvatureClassifier.classify(
                            object.getMesh().getPositions(), object.getMesh().getIndices());

                    uploadVertexData(exteriorVbo, result.exteriorTriangles);
                    exteriorVertexCount = result.exteriorTriangles.length / 3;

                    uploadVertexData(interiorVbo, result.interiorTriangles);
                    interiorVertexCount = result.interiorTriangles.length / 3;

                    if (onCurvatureComputed != null) {
                        Platform.runLater(() -> onCurvatureComputed.accept(result));
                    }
                }

                if (crossSectionComputeRequested) {
                    crossSectionComputeRequested = false;
                    float[] linesData = computeRawThickSegments(
                            object.getMesh().getPositions(), object.getMesh().getIndices(),
                            localPoint, localNormal, 0.04f);
                    uploadVertexData(intersectionLinesVbo, linesData);
                    intersectionVertexCount = linesData.length / 3;
                }

                if (centerOfMassRequested) {
                    centerOfMassRequested = false;
                    Vector3f centerOfMass = CenterOfMassCalculator.compute(
                            object.getMesh().getPositions(), object.getMesh().getIndices());
                    float[] sphereData = generateSphereTriangles(centerOfMass, 0.15f, 10, 16);
                    uploadVertexData(centerOfMassVbo, sphereData);
                    centerOfMassVertexCount = sphereData.length / 3;

                    if (onCenterOfMassComputed != null) {
                        Consumer<Vector3f> callback = onCenterOfMassComputed;
                        Platform.runLater(() -> callback.accept(centerOfMass));
                    }
                }

                shader.bind();
                shader.setUniform("projection", projection);
                shader.setUniform("view", view);
                shader.setUniform("model", modelMat);
                object.getMesh().render();

                GL30.glEnable(GL30.GL_BLEND);
                GL30.glBlendFunc(GL30.GL_SRC_ALPHA, GL30.GL_ONE_MINUS_SRC_ALPHA);

                overlayShader.bind();
                overlayShader.setUniform("projection", projection);
                overlayShader.setUniform("view", view);

                if (showExterior && exteriorVertexCount > 0) {
                    GL30.glEnable(GL30.GL_POLYGON_OFFSET_FILL);
                    GL30.glPolygonOffset(-1.0f, -1.0f);
                    overlayShader.setUniform("color", new Vector4f(1.0f, 0.3f, 0.2f, 0.7f));
                    overlayShader.setUniform("model", modelMat);
                    GL30.glBindVertexArray(exteriorVao);
                    GL30.glDrawArrays(GL30.GL_TRIANGLES, 0, exteriorVertexCount);
                    GL30.glDisable(GL30.GL_POLYGON_OFFSET_FILL);
                }

                if (showInterior && interiorVertexCount > 0) {
                    GL30.glEnable(GL30.GL_POLYGON_OFFSET_FILL);
                    GL30.glPolygonOffset(-1.0f, -1.0f);
                    overlayShader.setUniform("color", new Vector4f(0.2f, 0.6f, 1.0f, 0.7f));
                    overlayShader.setUniform("model", modelMat);
                    GL30.glBindVertexArray(interiorVao);
                    GL30.glDrawArrays(GL30.GL_TRIANGLES, 0, interiorVertexCount);
                    GL30.glDisable(GL30.GL_POLYGON_OFFSET_FILL);
                }

                if (centerOfMassVertexCount > 0) {
                    GL30.glDisable(GL30.GL_DEPTH_TEST);
                    overlayShader.setUniform("color", new Vector4f(0.2f, 1.0f, 0.3f, 1.0f));
                    overlayShader.setUniform("model", modelMat);
                    GL30.glBindVertexArray(centerOfMassVao);
                    GL30.glDrawArrays(GL30.GL_TRIANGLES, 0, centerOfMassVertexCount);
                    GL30.glEnable(GL30.GL_DEPTH_TEST);
                }

                overlayShader.setUniform("model", new Matrix4f().identity());
                Vector3f reference = Math.abs(worldNormal.y) > 0.99f ? new Vector3f(1, 0, 0) : new Vector3f(0, 1, 0);
                Vector3f tangent = new Vector3f(reference).cross(worldNormal).normalize();
                Vector3f bitangent = new Vector3f(worldNormal).cross(tangent).normalize();
                float half = 3.5f;

                try (MemoryStack stack = MemoryStack.stackPush()) {
                    FloatBuffer quad = stack.mallocFloat(18);
                    putQuadVertex(quad, worldPoint, tangent, bitangent, -half, -half);
                    putQuadVertex(quad, worldPoint, tangent, bitangent, half, -half);
                    putQuadVertex(quad, worldPoint, tangent, bitangent, half, half);
                    putQuadVertex(quad, worldPoint, tangent, bitangent, -half, -half);
                    putQuadVertex(quad, worldPoint, tangent, bitangent, half, half);
                    putQuadVertex(quad, worldPoint, tangent, bitangent, -half, half);
                    quad.flip();
                    GL30.glBindBuffer(GL30.GL_ARRAY_BUFFER, crossSectionPlaneVbo);
                    GL30.glBufferSubData(GL30.GL_ARRAY_BUFFER, 0, quad);
                }
                overlayShader.setUniform("color", new Vector4f(1.0f, 0.85f, 0.2f, 0.15f));
                GL30.glBindVertexArray(crossSectionPlaneVao);
                GL30.glDrawArrays(GL30.GL_TRIANGLES, 0, 6);

                if (intersectionVertexCount > 0) {
                    GL30.glDisable(GL30.GL_DEPTH_TEST);
                    overlayShader.setUniform("color", new Vector4f(1.0f, 0.1f, 0.1f, 1.0f));
                    overlayShader.setUniform("model", modelMat);
                    GL30.glBindVertexArray(intersectionLinesVao);
                    GL30.glDrawArrays(GL30.GL_TRIANGLES, 0, intersectionVertexCount);
                    GL30.glEnable(GL30.GL_DEPTH_TEST);
                }

                GL30.glBindVertexArray(0);
                GL30.glDisable(GL30.GL_BLEND);
                overlayShader.unbind();

                if (topViewCaptureRequested) {
                    topViewCaptureRequested = false;
                    captureExactContour(overlayShader, modelMat, worldNormal, bitangent, worldPoint, half, width, height, intersectionLinesVao, intersectionVertexCount);
                }

                GL30.glReadPixels(0, 0, width, height, GL30.GL_BGRA, GL30.GL_UNSIGNED_BYTE, pixelBuffer);
                pixelBuffer.get(safePixelData);
                pixelBuffer.clear();

                Platform.runLater(() -> {
                    pixelWriter.setPixels(0, 0, width, height, PixelFormat.getByteBgraPreInstance(), safePixelData, 0, width * 4);
                });

                try { Thread.sleep(16); } catch (InterruptedException e) { break; }
            }
        }
        finally
        {
            MemoryUtil.memFree(pixelBuffer);
            GLFW.glfwDestroyWindow(window);
        }
    }

    private void captureExactContour(ShaderProgram overlayShader, Matrix4f modelMatrix, Vector3f worldNormal, Vector3f bitangent,
                                     Vector3f worldPoint, float halfExtent, int captureWidth, int captureHeight,
                                     int linesVao, int vertexCount)
    {
        float viewDistance = 5f;
        Vector3f capEye = new Vector3f(worldPoint).add(new Vector3f(worldNormal).mul(viewDistance));
        Matrix4f sliceView = new Matrix4f().lookAt(capEye, worldPoint, bitangent);
        Matrix4f sliceProjection = new Matrix4f().ortho(
                -halfExtent, halfExtent, -halfExtent, halfExtent, -20.0f, 20.0f);

        GL30.glClearColor(1.0f, 1.0f, 1.0f, 1.0f);
        GL30.glClear(GL30.GL_COLOR_BUFFER_BIT | GL30.GL_DEPTH_BUFFER_BIT);
        GL30.glDisable(GL30.GL_DEPTH_TEST);

        overlayShader.bind();
        overlayShader.setUniform("model", modelMatrix);
        overlayShader.setUniform("view", sliceView);
        overlayShader.setUniform("projection", sliceProjection);
        overlayShader.setUniform("color", new Vector4f(0.0f, 0.0f, 0.0f, 1.0f));

        if (vertexCount > 0) {
            GL30.glBindVertexArray(linesVao);
            GL30.glDrawArrays(GL30.GL_TRIANGLES, 0, vertexCount);
            GL30.glBindVertexArray(0);
        }

        overlayShader.unbind();
        GL30.glEnable(GL30.GL_DEPTH_TEST);

        ByteBuffer captureBuffer = MemoryUtil.memAlloc(captureWidth * captureHeight * 4);
        GL30.glReadPixels(0, 0, captureWidth, captureHeight, GL30.GL_BGRA, GL30.GL_UNSIGNED_BYTE, captureBuffer);
        byte[] captureData = new byte[captureWidth * captureHeight * 4];
        captureBuffer.get(captureData);
        MemoryUtil.memFree(captureBuffer);

        if (onTopViewCaptured != null) {
            Consumer<WritableImage> callback = onTopViewCaptured;
            Platform.runLater(() -> {
                WritableImage capturedImage = new WritableImage(captureWidth, captureHeight);
                capturedImage.getPixelWriter().setPixels(
                        0, 0, captureWidth, captureHeight, PixelFormat.getByteBgraPreInstance(), captureData, 0, captureWidth * 4);
                callback.accept(capturedImage);
            });
        }
    }

    private float[] computeRawThickSegments(float[] positions, int[] indices, Vector3f localPoint, Vector3f localNormal, float thickness) {
        List<Float> verts = new ArrayList<>();
        Vector3f a = new Vector3f(), b = new Vector3f(), c = new Vector3f();

        for (int i = 0; i < indices.length; i += 3) {
            int ia = indices[i] * 3, ib = indices[i + 1] * 3, ic = indices[i + 2] * 3;
            a.set(positions[ia], positions[ia + 1], positions[ia + 2]);
            b.set(positions[ib], positions[ib + 1], positions[ib + 2]);
            c.set(positions[ic], positions[ic + 1], positions[ic + 2]);

            float da = localNormal.dot(a.x - localPoint.x, a.y - localPoint.y, a.z - localPoint.z);
            float db = localNormal.dot(b.x - localPoint.x, b.y - localPoint.y, b.z - localPoint.z);
            float dc = localNormal.dot(c.x - localPoint.x, c.y - localPoint.y, c.z - localPoint.z);

            if ((da > 0 && db > 0 && dc > 0) || (da < 0 && db < 0 && dc < 0)) continue;

            List<Vector3f> pts = new ArrayList<>();
            if (da * db < 0) pts.add(interpolate(a, b, da, db));
            if (db * dc < 0) pts.add(interpolate(b, c, db, dc));
            if (dc * da < 0) pts.add(interpolate(c, a, dc, da));

            if (da == 0) pts.add(new Vector3f(a));
            if (db == 0) pts.add(new Vector3f(b));
            if (dc == 0) pts.add(new Vector3f(c));

            if (pts.size() >= 2) {
                Vector3f p1 = pts.get(0);
                Vector3f p2 = pts.get(1);

                Vector3f lineDir = new Vector3f(p2).sub(p1);
                if (lineDir.lengthSquared() < 1e-8) continue;
                lineDir.normalize();

                Vector3f segmentNormal = new Vector3f(localNormal).cross(lineDir).normalize().mul(thickness / 2.0f);

                Vector3f v1 = new Vector3f(p1).add(segmentNormal);
                Vector3f v2 = new Vector3f(p1).sub(segmentNormal);
                Vector3f v3 = new Vector3f(p2).add(segmentNormal);
                Vector3f v4 = new Vector3f(p2).sub(segmentNormal);

                addVert(verts, v1); addVert(verts, v2); addVert(verts, v3);
                addVert(verts, v3); addVert(verts, v2); addVert(verts, v4);
            }
        }

        float[] result = new float[verts.size()];
        for(int i=0; i<result.length; i++) result[i] = verts.get(i);
        return result;
    }

    private Vector3f interpolate(Vector3f p1, Vector3f p2, float d1, float d2) {
        float t = d1 / (d1 - d2);
        return new Vector3f(p1.x + t * (p2.x - p1.x), p1.y + t * (p2.y - p1.y), p1.z + t * (p2.z - p1.z));
    }

    private void addVert(List<Float> list, Vector3f v) {
        list.add(v.x); list.add(v.y); list.add(v.z);
    }

    private float[] generateSphereTriangles(Vector3f center, float radius, int rings, int segments) {
        List<Float> verts = new ArrayList<>();
        for (int r = 0; r < rings; r++) {
            float theta1 = (float) Math.PI * r / rings;
            float theta2 = (float) Math.PI * (r + 1) / rings;
            for (int s = 0; s < segments; s++) {
                float phi1 = (float) (2 * Math.PI * s / segments);
                float phi2 = (float) (2 * Math.PI * (s + 1) / segments);

                Vector3f p00 = spherePoint(center, radius, theta1, phi1);
                Vector3f p10 = spherePoint(center, radius, theta2, phi1);
                Vector3f p11 = spherePoint(center, radius, theta2, phi2);
                Vector3f p01 = spherePoint(center, radius, theta1, phi2);

                addVert(verts, p00); addVert(verts, p10); addVert(verts, p11);
                addVert(verts, p00); addVert(verts, p11); addVert(verts, p01);
            }
        }
        float[] array = new float[verts.size()];
        for (int i = 0; i < array.length; i++) array[i] = verts.get(i);
        return array;
    }

    private Vector3f spherePoint(Vector3f center, float radius, float theta, float phi) {
        float sinTheta = (float) Math.sin(theta);
        float x = sinTheta * (float) Math.cos(phi);
        float y = (float) Math.cos(theta);
        float z = sinTheta * (float) Math.sin(phi);
        return new Vector3f(center.x + radius * x, center.y + radius * y, center.z + radius * z);
    }

    private static void uploadVertexData(int vbo, float[] data) {
        GL30.glBindBuffer(GL30.GL_ARRAY_BUFFER, vbo);
        if (data.length == 0) {
            GL30.glBufferData(GL30.GL_ARRAY_BUFFER, 0, GL30.GL_DYNAMIC_DRAW);
            return;
        }
        FloatBuffer buffer = MemoryUtil.memAllocFloat(data.length);
        buffer.put(data).flip();
        GL30.glBufferData(GL30.GL_ARRAY_BUFFER, buffer, GL30.GL_DYNAMIC_DRAW);
        MemoryUtil.memFree(buffer);
    }

    private static void putQuadVertex(FloatBuffer target, Vector3f center, Vector3f tangent, Vector3f bitangent, float tangentOffset, float bitangentOffset) {
        target.put(center.x + tangent.x * tangentOffset + bitangent.x * bitangentOffset);
        target.put(center.y + tangent.y * tangentOffset + bitangent.y * bitangentOffset);
        target.put(center.z + tangent.z * tangentOffset + bitangent.z * bitangentOffset);
    }
}