package org.project.engine;

import javafx.application.Platform;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;
import org.joml.Matrix4f;
import java.nio.file.Files;
import java.nio.file.Paths;

import java.nio.ByteBuffer;

public class OpenGLRenderer implements Runnable
{
    private final String modelPath;
    private final WritableImage fxImage;
    private final int width = 800;
    private final int height = 600;

    public OpenGLRenderer(String modelPath, WritableImage fxImage)
    {
        this.modelPath = modelPath;
        this.fxImage = fxImage;
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

        int texture = GL30.glGenTextures();
        GL30.glBindTexture(GL30.GL_TEXTURE_2D, texture);
        GL30.glTexImage2D(GL30.GL_TEXTURE_2D, 0, GL30.GL_RGBA, width, height, 0, GL30.GL_RGBA, GL30.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL30.GL_TEXTURE_2D, texture, 0);

        int rbo = GL30.glGenRenderbuffers();
        GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, rbo);
        GL30.glRenderbufferStorage(GL30.GL_RENDERBUFFER, GL30.GL_DEPTH24_STENCIL8, width, height);
        GL30.glFramebufferRenderbuffer(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_STENCIL_ATTACHMENT, GL30.GL_RENDERBUFFER, rbo);

        Mesh mesh = ModelLoader.loadModel(modelPath);
        ShaderProgram shader;
        try
        {
            shader = new ShaderProgram();
            shader.createVertexShader(Files.readString(Paths.get("src/main/resources/shaders/vertex.glsl")));
            shader.createFragmentShader(Files.readString(Paths.get("src/main/resources/shaders/fragment.glsl")));
            shader.link();
        }
        catch (Exception e)
        {
            e.printStackTrace();
            return;
        }

        Matrix4f projection = new Matrix4f().perspective((float) Math.toRadians(45.0f), (float) width / height, 0.1f, 100.0f);
        Matrix4f view = new Matrix4f().lookAt(
                0.0f, 0.0f, 20.0f,
                0.0f, 0.0f, 0.0f,
                0.0f, 1.0f, 0.0f
        );
        Matrix4f model = new Matrix4f().identity();

        ByteBuffer pixelBuffer = MemoryUtil.memAlloc(width * height * 4);
        byte[] safePixelData = new byte[width * height * 4];
        PixelWriter pixelWriter = fxImage.getPixelWriter();

        GL30.glEnable(GL30.GL_DEPTH_TEST);

        while (!Thread.interrupted()) {
            GL30.glClearColor(0.16f, 0.16f, 0.16f, 1.0f);
            GL30.glClear(GL30.GL_COLOR_BUFFER_BIT | GL30.GL_DEPTH_BUFFER_BIT);

            shader.bind();
            shader.setUniform("projection", projection);
            shader.setUniform("view", view);

            model.rotateY(0.01f);
            shader.setUniform("model", model);
            mesh.render();
            shader.unbind();

            GL30.glReadPixels(0, 0, width, height, GL30.GL_BGRA, GL30.GL_UNSIGNED_BYTE, pixelBuffer);
            pixelBuffer.get(safePixelData);
            pixelBuffer.clear();
            Platform.runLater(() -> {
                pixelWriter.setPixels(0, 0, width, height, PixelFormat.getByteBgraPreInstance(), safePixelData, 0, width * 4);
            });
            try { Thread.sleep(16); } catch (InterruptedException e) { break; }
        }
        MemoryUtil.memFree(pixelBuffer);
    }
}