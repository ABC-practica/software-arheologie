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

public class SingleObjectRenderer implements Runnable
{
    private final String modelPath;
    private final WritableImage fxImage;
    private final int width;
    private final int height;

    private volatile float pendingRotateX = 0;
    private volatile float pendingRotateY = 0;
    private volatile float pendingScale = 0;

    private volatile float planeAngle = 0f;
    private volatile float planeOffset = 0f;
    private volatile boolean computeRequested = false;

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

    public void setPlaneAngle(float radians)
    {
        this.planeAngle = radians;
    }

    public void setPlaneOffset(float offset)
    {
        this.planeOffset = offset;
    }

    public void requestComputeCurvature()
    {
        this.computeRequested = true;
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

        int planeVao = GL30.glGenVertexArrays();
        int planeVbo = GL30.glGenBuffers();
        GL30.glBindVertexArray(planeVao);
        GL30.glBindBuffer(GL30.GL_ARRAY_BUFFER, planeVbo);
        GL30.glBufferData(GL30.GL_ARRAY_BUFFER, 6L * 3 * 4, GL30.GL_DYNAMIC_DRAW);
        GL30.glVertexAttribPointer(0, 3, GL30.GL_FLOAT, false, 0, 0);
        GL30.glEnableVertexAttribArray(0);

        int exteriorLinesVao = GL30.glGenVertexArrays();
        int exteriorLinesVbo = GL30.glGenBuffers();
        GL30.glBindVertexArray(exteriorLinesVao);
        GL30.glBindBuffer(GL30.GL_ARRAY_BUFFER, exteriorLinesVbo);
        GL30.glVertexAttribPointer(0, 3, GL30.GL_FLOAT, false, 0, 0);
        GL30.glEnableVertexAttribArray(0);

        int interiorLinesVao = GL30.glGenVertexArrays();
        int interiorLinesVbo = GL30.glGenBuffers();
        GL30.glBindVertexArray(interiorLinesVao);
        GL30.glBindBuffer(GL30.GL_ARRAY_BUFFER, interiorLinesVbo);
        GL30.glVertexAttribPointer(0, 3, GL30.GL_FLOAT, false, 0, 0);
        GL30.glEnableVertexAttribArray(0);

        GL30.glBindVertexArray(0);

        int exteriorLineVertexCount = 0;
        int interiorLineVertexCount = 0;

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

                float currentAngle = planeAngle;
                float currentOffset = planeOffset;
                Vector3f planeNormal = new Vector3f((float) Math.sin(currentAngle), 0f, (float) Math.cos(currentAngle));
                Vector3f planePoint = new Vector3f(planeNormal).mul(currentOffset);

                if (computeRequested)
                {
                    computeRequested = false;
                    PlaneIntersector.Result result = PlaneIntersector.computeCrossSection(
                            object.getMesh().getPositions(), object.getMesh().getIndices(), planePoint, planeNormal);

                    uploadLineData(exteriorLinesVbo, result.exteriorLines);
                    uploadLineData(interiorLinesVbo, result.interiorLines);
                    exteriorLineVertexCount = result.exteriorLines.length / 3;
                    interiorLineVertexCount = result.interiorLines.length / 3;
                }

                GL30.glEnable(GL30.GL_BLEND);
                GL30.glBlendFunc(GL30.GL_SRC_ALPHA, GL30.GL_ONE_MINUS_SRC_ALPHA);

                overlayShader.bind();
                overlayShader.setUniform("projection", projection);
                overlayShader.setUniform("view", view);
                overlayShader.setUniform("model", object.getModelMatrix());

                Vector3f tangent = new Vector3f((float) Math.cos(currentAngle), 0f, -(float) Math.sin(currentAngle));
                Vector3f up = new Vector3f(0f, 1f, 0f);
                float half = 2.5f;
                try (MemoryStack stack = MemoryStack.stackPush())
                {
                    FloatBuffer quad = stack.mallocFloat(18);
                    putQuadVertex(quad, planePoint, tangent, up, -half, -half);
                    putQuadVertex(quad, planePoint, tangent, up, half, -half);
                    putQuadVertex(quad, planePoint, tangent, up, half, half);
                    putQuadVertex(quad, planePoint, tangent, up, -half, -half);
                    putQuadVertex(quad, planePoint, tangent, up, half, half);
                    putQuadVertex(quad, planePoint, tangent, up, -half, half);
                    quad.flip();
                    GL30.glBindBuffer(GL30.GL_ARRAY_BUFFER, planeVbo);
                    GL30.glBufferSubData(GL30.GL_ARRAY_BUFFER, 0, quad);
                }
                overlayShader.setUniform("color", new Vector4f(0.3f, 0.6f, 1.0f, 0.25f));
                GL30.glBindVertexArray(planeVao);
                GL30.glDrawArrays(GL30.GL_TRIANGLES, 0, 6);

                if (exteriorLineVertexCount > 0)
                {
                    overlayShader.setUniform("color", new Vector4f(1.0f, 0.2f, 0.2f, 1.0f));
                    GL30.glBindVertexArray(exteriorLinesVao);
                    GL30.glDrawArrays(GL30.GL_LINES, 0, exteriorLineVertexCount);
                }
                if (interiorLineVertexCount > 0)
                {
                    overlayShader.setUniform("color", new Vector4f(0.2f, 0.5f, 1.0f, 1.0f));
                    GL30.glBindVertexArray(interiorLinesVao);
                    GL30.glDrawArrays(GL30.GL_LINES, 0, interiorLineVertexCount);
                }

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

    private static void uploadLineData(int vbo, float[] data)
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

    private static void putQuadVertex(FloatBuffer target, Vector3f center, Vector3f tangent, Vector3f up, float tangentOffset, float upOffset)
    {
        target.put(center.x + tangent.x * tangentOffset + up.x * upOffset);
        target.put(center.y + tangent.y * tangentOffset + up.y * upOffset);
        target.put(center.z + tangent.z * tangentOffset + up.z * upOffset);
    }
}
