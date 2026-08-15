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

    private volatile float crossSectionYaw = 0f;
    private volatile float crossSectionPitch = 0f;
    private volatile float crossSectionOffset = 0f;
    private volatile float crossSectionThickness = 0.2f;
    private volatile boolean crossSectionComputeRequested = false;

    private Consumer<CurvatureClassifier.Result> onCurvatureComputed;

    public SingleObjectRenderer(String modelPath, WritableImage fxImage, int width, int height)
    {
        this.modelPath = modelPath;
        this.fxImage = fxImage;
        this.width = width;
        this.height = height;
    }

    public void rotate(float deltaX, float deltaY)
    {
        pendingRotateX += deltaX;
        pendingRotateY += deltaY;
    }

    public void scale(float delta)
    {
        pendingScale += delta;
    }

    public void requestComputeCurvature()
    {
        this.computeRequested = true;
    }

    public void setOnCurvatureComputed(Consumer<CurvatureClassifier.Result> callback)
    {
        this.onCurvatureComputed = callback;
    }

    public void setCrossSectionYaw(float radians)
    {
        this.crossSectionYaw = radians;
    }

    public void setCrossSectionPitch(float radians)
    {
        this.crossSectionPitch = radians;
    }

    public void setCrossSectionOffset(float offset)
    {
        this.crossSectionOffset = offset;
    }

    public void setCrossSectionThickness(float thickness)
    {
        this.crossSectionThickness = thickness;
    }

    public void requestComputeCrossSection()
    {
        this.crossSectionComputeRequested = true;
    }

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
        ShaderProgram shader;
        ShaderProgram overlayShader;
        try
        {
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
        }
        catch (Exception e)
        {
            e.printStackTrace();
            GLFW.glfwDestroyWindow(window);
            return;
        }

        Matrix4f projection = new Matrix4f().perspective((float) Math.toRadians(45.0f), (float) width / height, 0.1f, 100.0f);
        Matrix4f view = new Matrix4f().lookAt(0.0f, 0.0f, 5.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f);

        int exteriorFacetsVao = GL30.glGenVertexArrays();
        int exteriorFacetsVbo = GL30.glGenBuffers();
        GL30.glBindVertexArray(exteriorFacetsVao);
        GL30.glBindBuffer(GL30.GL_ARRAY_BUFFER, exteriorFacetsVbo);
        GL30.glVertexAttribPointer(0, 3, GL30.GL_FLOAT, false, 0, 0);
        GL30.glEnableVertexAttribArray(0);

        int interiorFacetsVao = GL30.glGenVertexArrays();
        int interiorFacetsVbo = GL30.glGenBuffers();
        GL30.glBindVertexArray(interiorFacetsVao);
        GL30.glBindBuffer(GL30.GL_ARRAY_BUFFER, interiorFacetsVbo);
        GL30.glVertexAttribPointer(0, 3, GL30.GL_FLOAT, false, 0, 0);
        GL30.glEnableVertexAttribArray(0);

        int crossSectionPlaneVao = GL30.glGenVertexArrays();
        int crossSectionPlaneVbo = GL30.glGenBuffers();
        GL30.glBindVertexArray(crossSectionPlaneVao);
        GL30.glBindBuffer(GL30.GL_ARRAY_BUFFER, crossSectionPlaneVbo);
        GL30.glBufferData(GL30.GL_ARRAY_BUFFER, 12L * 3 * 4, GL30.GL_DYNAMIC_DRAW);
        GL30.glVertexAttribPointer(0, 3, GL30.GL_FLOAT, false, 0, 0);
        GL30.glEnableVertexAttribArray(0);

        int crossSectionNearVao = GL30.glGenVertexArrays();
        int crossSectionNearVbo = GL30.glGenBuffers();
        GL30.glBindVertexArray(crossSectionNearVao);
        GL30.glBindBuffer(GL30.GL_ARRAY_BUFFER, crossSectionNearVbo);
        GL30.glVertexAttribPointer(0, 3, GL30.GL_FLOAT, false, 0, 0);
        GL30.glEnableVertexAttribArray(0);

        int crossSectionFarVao = GL30.glGenVertexArrays();
        int crossSectionFarVbo = GL30.glGenBuffers();
        GL30.glBindVertexArray(crossSectionFarVao);
        GL30.glBindBuffer(GL30.GL_ARRAY_BUFFER, crossSectionFarVbo);
        GL30.glVertexAttribPointer(0, 3, GL30.GL_FLOAT, false, 0, 0);
        GL30.glEnableVertexAttribArray(0);

        GL30.glBindVertexArray(0);

        int exteriorFacetVertexCount = 0;
        int interiorFacetVertexCount = 0;
        int crossSectionNearVertexCount = 0;
        int crossSectionFarVertexCount = 0;

        ByteBuffer pixelBuffer = MemoryUtil.memAlloc(width * height * 4);
        byte[] safePixelData = new byte[width * height * 4];
        PixelWriter pixelWriter = fxImage.getPixelWriter();

        GL30.glEnable(GL30.GL_DEPTH_TEST);

        try
        {
            while (!Thread.interrupted())
            {
                if (pendingRotateX != 0 || pendingRotateY != 0)
                {
                    object.rotation.y -= pendingRotateX * 0.01f;
                    object.rotation.x -= pendingRotateY * 0.01f;
                    pendingRotateX = 0;
                    pendingRotateY = 0;
                }
                if (pendingScale != 0)
                {
                    object.scale += pendingScale;
                    if (object.scale < 0.05f) object.scale = 0.05f;
                    pendingScale = 0;
                }

                GL30.glClearColor(0.16f, 0.16f, 0.16f, 1.0f);
                GL30.glClear(GL30.GL_COLOR_BUFFER_BIT | GL30.GL_DEPTH_BUFFER_BIT);

                shader.bind();
                shader.setUniform("projection", projection);
                shader.setUniform("view", view);
                shader.setUniform("model", object.getModelMatrix());
                object.getMesh().render();

                if (computeRequested)
                {
                    computeRequested = false;

                    CurvatureClassifier.Result result = CurvatureClassifier.classify(
                            object.getMesh().getPositions(), object.getMesh().getIndices());

                    uploadVertexData(exteriorFacetsVbo, result.exteriorTriangles);
                    uploadVertexData(interiorFacetsVbo, result.interiorTriangles);
                    exteriorFacetVertexCount = result.exteriorTriangles.length / 3;
                    interiorFacetVertexCount = result.interiorTriangles.length / 3;

                    if (onCurvatureComputed != null)
                    {
                        Platform.runLater(() -> onCurvatureComputed.accept(result));
                    }
                }

                float yaw = crossSectionYaw;
                float pitch = crossSectionPitch;
                float offset = crossSectionOffset;
                float thickness = crossSectionThickness;

                Vector3f crossNormal = new Vector3f(
                        (float) (Math.sin(yaw) * Math.cos(pitch)),
                        (float) Math.sin(pitch),
                        (float) (Math.cos(yaw) * Math.cos(pitch))
                ).normalize();
                Vector3f crossPoint = new Vector3f(crossNormal).mul(offset);
                Vector3f nearFacePoint = new Vector3f(crossPoint).add(new Vector3f(crossNormal).mul(-thickness / 2f));
                Vector3f farFacePoint = new Vector3f(crossPoint).add(new Vector3f(crossNormal).mul(thickness / 2f));

                if (crossSectionComputeRequested)
                {
                    crossSectionComputeRequested = false;

                    float[] nearTriangles = PlaneIntersector.findCrossedTriangles(
                            object.getMesh().getPositions(), object.getMesh().getIndices(), nearFacePoint, crossNormal);
                    float[] farTriangles = PlaneIntersector.findCrossedTriangles(
                            object.getMesh().getPositions(), object.getMesh().getIndices(), farFacePoint, crossNormal);

                    uploadVertexData(crossSectionNearVbo, nearTriangles);
                    uploadVertexData(crossSectionFarVbo, farTriangles);
                    crossSectionNearVertexCount = nearTriangles.length / 3;
                    crossSectionFarVertexCount = farTriangles.length / 3;
                }

                GL30.glEnable(GL30.GL_BLEND);
                GL30.glBlendFunc(GL30.GL_SRC_ALPHA, GL30.GL_ONE_MINUS_SRC_ALPHA);

                overlayShader.bind();
                overlayShader.setUniform("projection", projection);
                overlayShader.setUniform("view", view);
                overlayShader.setUniform("model", object.getModelMatrix());

                GL30.glEnable(GL30.GL_POLYGON_OFFSET_FILL);
                GL30.glPolygonOffset(-1.0f, -1.0f);
                if (exteriorFacetVertexCount > 0)
                {
                    overlayShader.setUniform("color", new Vector4f(1.0f, 0.2f, 0.2f, 0.85f));
                    GL30.glBindVertexArray(exteriorFacetsVao);
                    GL30.glDrawArrays(GL30.GL_TRIANGLES, 0, exteriorFacetVertexCount);
                }
                if (interiorFacetVertexCount > 0)
                {
                    overlayShader.setUniform("color", new Vector4f(0.2f, 0.5f, 1.0f, 0.85f));
                    GL30.glBindVertexArray(interiorFacetsVao);
                    GL30.glDrawArrays(GL30.GL_TRIANGLES, 0, interiorFacetVertexCount);
                }
                if (crossSectionNearVertexCount > 0)
                {
                    overlayShader.setUniform("color", new Vector4f(1.0f, 0.65f, 0.0f, 0.85f));
                    GL30.glBindVertexArray(crossSectionNearVao);
                    GL30.glDrawArrays(GL30.GL_TRIANGLES, 0, crossSectionNearVertexCount);
                }
                if (crossSectionFarVertexCount > 0)
                {
                    overlayShader.setUniform("color", new Vector4f(0.6f, 0.2f, 1.0f, 0.85f));
                    GL30.glBindVertexArray(crossSectionFarVao);
                    GL30.glDrawArrays(GL30.GL_TRIANGLES, 0, crossSectionFarVertexCount);
                }
                GL30.glDisable(GL30.GL_POLYGON_OFFSET_FILL);

                Vector3f reference = Math.abs(crossNormal.y) > 0.99f ? new Vector3f(1, 0, 0) : new Vector3f(0, 1, 0);
                Vector3f tangent = new Vector3f(reference).cross(crossNormal).normalize();
                Vector3f bitangent = new Vector3f(crossNormal).cross(tangent).normalize();
                float half = 2.5f;
                Vector3f nearFaceCenter = nearFacePoint;
                Vector3f farFaceCenter = farFacePoint;
                try (MemoryStack stack = MemoryStack.stackPush())
                {
                    FloatBuffer quad = stack.mallocFloat(36);
                    putQuadVertex(quad, nearFaceCenter, tangent, bitangent, -half, -half);
                    putQuadVertex(quad, nearFaceCenter, tangent, bitangent, half, -half);
                    putQuadVertex(quad, nearFaceCenter, tangent, bitangent, half, half);
                    putQuadVertex(quad, nearFaceCenter, tangent, bitangent, -half, -half);
                    putQuadVertex(quad, nearFaceCenter, tangent, bitangent, half, half);
                    putQuadVertex(quad, nearFaceCenter, tangent, bitangent, -half, half);
                    putQuadVertex(quad, farFaceCenter, tangent, bitangent, -half, -half);
                    putQuadVertex(quad, farFaceCenter, tangent, bitangent, half, -half);
                    putQuadVertex(quad, farFaceCenter, tangent, bitangent, half, half);
                    putQuadVertex(quad, farFaceCenter, tangent, bitangent, -half, -half);
                    putQuadVertex(quad, farFaceCenter, tangent, bitangent, half, half);
                    putQuadVertex(quad, farFaceCenter, tangent, bitangent, -half, half);
                    quad.flip();
                    GL30.glBindBuffer(GL30.GL_ARRAY_BUFFER, crossSectionPlaneVbo);
                    GL30.glBufferSubData(GL30.GL_ARRAY_BUFFER, 0, quad);
                }
                overlayShader.setUniform("color", new Vector4f(1.0f, 0.85f, 0.2f, 0.15f));
                GL30.glBindVertexArray(crossSectionPlaneVao);
                GL30.glDrawArrays(GL30.GL_TRIANGLES, 0, 12);

                GL30.glBindVertexArray(0);
                GL30.glDisable(GL30.GL_BLEND);

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

    private static void uploadVertexData(int vbo, float[] data)
    {
        GL30.glBindBuffer(GL30.GL_ARRAY_BUFFER, vbo);
        if (data.length == 0)
        {
            GL30.glBufferData(GL30.GL_ARRAY_BUFFER, 0, GL30.GL_DYNAMIC_DRAW);
            return;
        }
        FloatBuffer buffer = MemoryUtil.memAllocFloat(data.length);
        buffer.put(data).flip();
        GL30.glBufferData(GL30.GL_ARRAY_BUFFER, buffer, GL30.GL_DYNAMIC_DRAW);
        MemoryUtil.memFree(buffer);
    }

    private static void putQuadVertex(FloatBuffer target, Vector3f center, Vector3f tangent, Vector3f bitangent, float tangentOffset, float bitangentOffset)
    {
        target.put(center.x + tangent.x * tangentOffset + bitangent.x * bitangentOffset);
        target.put(center.y + tangent.y * tangentOffset + bitangent.y * bitangentOffset);
        target.put(center.z + tangent.z * tangentOffset + bitangent.z * bitangentOffset);
    }
}
