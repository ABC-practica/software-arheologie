package org.project.engine;

import javafx.application.Platform;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import org.joml.Matrix4f;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

public class OpenGLRenderer implements Runnable
{
    private final WritableImage fxImage;
    private final int width = 800;
    private final int height = 600;

    public final List<SceneObject> objects = new ArrayList<>();

    private final ConcurrentLinkedQueue<String> pendingModels = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<Integer> pendingDeletions = new ConcurrentLinkedQueue<>();
    private int nextObjectId = 1;

    public void queueModelLoad(String filePath) { pendingModels.add(filePath); }
    public void queueDeleteObject(int objectId) { pendingDeletions.add(objectId); }

    private volatile boolean mouseClicked = false;
    private volatile int clickX = 0;
    private volatile int clickY = 0;
    private volatile boolean multiSelectModifier = false;

    private final Set<Integer> selectedObjectIds = ConcurrentHashMap.newKeySet();
    private Consumer<Set<Integer>> onSelectionChanged;

    public OpenGLRenderer(WritableImage fxImage)
    {
        this.fxImage = fxImage;
    }

    public void registerClick(int x, int y, boolean multiSelect)
    {
        this.clickX = x;
        this.clickY = y;
        this.multiSelectModifier = multiSelect;
        this.mouseClicked = true;
    }

    public Set<Integer> getSelectedObjectIds()
    {
        return selectedObjectIds;
    }

    public void setOnSelectionChanged(Consumer<Set<Integer>> callback)
    {
        this.onSelectionChanged = callback;
    }

    @Override
    public void run() {
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

        int texturePicking = GL30.glGenTextures();
        GL30.glBindTexture(GL30.GL_TEXTURE_2D, texturePicking);
        GL30.glTexImage2D(GL30.GL_TEXTURE_2D, 0, GL30.GL_RGBA8, width, height, 0, GL30.GL_RGBA, GL30.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT1, GL30.GL_TEXTURE_2D, texturePicking, 0);
        GL30.glDrawBuffers(new int[]{GL30.GL_COLOR_ATTACHMENT0, GL30.GL_COLOR_ATTACHMENT1});

        int rbo = GL30.glGenRenderbuffers();
        GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, rbo);
        GL30.glRenderbufferStorage(GL30.GL_RENDERBUFFER, GL30.GL_DEPTH24_STENCIL8, width, height);
        GL30.glFramebufferRenderbuffer(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_STENCIL_ATTACHMENT, GL30.GL_RENDERBUFFER, rbo);

        ShaderProgram shader;
        try
        {
            shader = new ShaderProgram();
            shader.createVertexShader(Files.readString(Paths.get("src/main/resources/shaders/vertex.glsl")));
            shader.createFragmentShader(Files.readString(Paths.get("src/main/resources/shaders/fragment.glsl")));
            shader.link();
            shader.bind();
            shader.setUniform("texture1", 0);
            shader.unbind();
        }
        catch (Exception e)
        {
            e.printStackTrace();
            return;
        }

        Matrix4f projection = new Matrix4f().perspective((float) Math.toRadians(45.0f), (float) width / height, 0.1f, 100.0f);
        Matrix4f view = new Matrix4f().lookAt(0.0f, 0.0f, 5.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f);

        ByteBuffer pixelBuffer = MemoryUtil.memAlloc(width * height * 4);
        ByteBuffer pickingPixelBuffer = MemoryUtil.memAlloc(4);
        byte[] safePixelData = new byte[width * height * 4];
        PixelWriter pixelWriter = fxImage.getPixelWriter();

        GL30.glEnable(GL30.GL_DEPTH_TEST);

        try
        {
            while (!Thread.interrupted())
            {
                String newModelPath = pendingModels.poll();
                if (newModelPath != null)
                {
                    try
                    {
                        Mesh mesh = ModelLoader.loadModel(newModelPath);
                        SceneObject newPiece = new SceneObject(nextObjectId++, mesh, newModelPath);
                        objects.add(newPiece);
                    }
                    catch (Exception e)
                    {
                        e.printStackTrace();
                    }
                }

                Integer deleteId;
                while ((deleteId = pendingDeletions.poll()) != null)
                {
                    final int idToRemove = deleteId;
                    objects.removeIf(obj -> obj.getId() == idToRemove);
                    if (selectedObjectIds.contains(idToRemove)) {
                        selectedObjectIds.remove(idToRemove);
                        if (onSelectionChanged != null) {
                            Set<Integer> copy = new HashSet<>(selectedObjectIds);
                            Platform.runLater(() -> onSelectionChanged.accept(copy));
                        }
                    }
                }
                GL30.glClearColor(0.16f, 0.16f, 0.16f, 1.0f);
                GL30.glClear(GL30.GL_COLOR_BUFFER_BIT | GL30.GL_DEPTH_BUFFER_BIT);

                shader.bind();
                shader.setUniform("projection", projection);
                shader.setUniform("view", view);
                for (SceneObject obj : objects)
                {
                    shader.setUniform("model", obj.getModelMatrix());
                    shader.setUniform("objectIdColor", obj.getPickingColor());

                    int isSelected = selectedObjectIds.contains(obj.getId()) ? 1 : 0;
                    shader.setUniform("isSelected", isSelected);

                    obj.getMesh().render();
                }
                if (mouseClicked) {
                    mouseClicked = false;
                    GL30.glReadBuffer(GL30.GL_COLOR_ATTACHMENT1);
                    GL30.glReadPixels(clickX, clickY, 1, 1, GL30.GL_RGBA, GL30.GL_UNSIGNED_BYTE, pickingPixelBuffer);

                    int r = pickingPixelBuffer.get(0) & 0xFF;
                    int g = pickingPixelBuffer.get(1) & 0xFF;
                    int b = pickingPixelBuffer.get(2) & 0xFF;
                    int pickedId = r + (g << 8) + (b << 16);

                    int newSelection = (pickedId == 0 || pickedId == 2697513) ? -1 : pickedId;
                    boolean changed = false;

                    if (newSelection != -1) {
                        if (multiSelectModifier) {
                            if (selectedObjectIds.contains(newSelection)) selectedObjectIds.remove(newSelection);
                            else selectedObjectIds.add(newSelection);
                        } else {
                            if (selectedObjectIds.size() != 1 || !selectedObjectIds.contains(newSelection)) {
                                selectedObjectIds.clear();
                                selectedObjectIds.add(newSelection);
                            }
                        }
                        changed = true;
                    } else {
                        if (!multiSelectModifier && !selectedObjectIds.isEmpty()) {
                            selectedObjectIds.clear();
                            changed = true;
                        }
                    }

                    if (changed && onSelectionChanged != null) {
                        Set<Integer> copy = new HashSet<>(selectedObjectIds);
                        Platform.runLater(() -> onSelectionChanged.accept(copy));
                    }

                    GL30.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
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
            MemoryUtil.memFree(pickingPixelBuffer);
            GLFW.glfwDestroyWindow(window);
        }
    }

    public void moveSelectedObject(float deltaX, float deltaY) {
        if (selectedObjectIds.isEmpty()) return;
        for (SceneObject obj : objects) {
            if (selectedObjectIds.contains(obj.getId())) {
                obj.position.x += deltaX * 0.01f;
                obj.position.y += deltaY * 0.01f;

                if (obj.position.x > 2.7f) obj.position.x = 2.7f;
                if (obj.position.x < -2.7f) obj.position.x = -2.7f;

                if (obj.position.y > 2.0f) obj.position.y = 2.0f;
                if (obj.position.y < -2.0f) obj.position.y = -2.0f;
            }
        }
    }

    public void rotateSelectedObject(float deltaX, float deltaY) {
        if (selectedObjectIds.isEmpty()) return;
        for (SceneObject obj : objects) {
            if (selectedObjectIds.contains(obj.getId())) {
                obj.rotation.y -= deltaX * 0.01f;
                obj.rotation.x -= deltaY * 0.01f;
            }
        }
    }

    public void scaleSelectedObject(float delta) {
        if (selectedObjectIds.isEmpty()) return;
        for (SceneObject obj : objects) {
            if (selectedObjectIds.contains(obj.getId())) {
                obj.scale += delta;
                if (obj.scale < 0.05f) obj.scale = 0.05f;
            }
        }
    }
}