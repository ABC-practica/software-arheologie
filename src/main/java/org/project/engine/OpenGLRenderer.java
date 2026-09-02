package org.project.engine;

import javafx.application.Platform;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

import java.io.File;
import java.nio.FloatBuffer;
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
    private final ConcurrentLinkedQueue<File> pendingSections = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<Integer> pendingDeletions = new ConcurrentLinkedQueue<>();
    private int nextObjectId = 1;

    private volatile boolean mouseClicked = false;
    private volatile int clickX = 0;
    private volatile int clickY = 0;
    private volatile boolean multiSelectModifier = false;

    private final Set<Integer> selectedObjectIds = ConcurrentHashMap.newKeySet();
    private Consumer<Set<Integer>> onSelectionChanged;

    private volatile float yaw = -90.0f;
    private volatile float pitch = -15.0f;
    private volatile Vector3f camPos = new Vector3f(0.0f, 2.0f, 5.0f);
    private volatile Vector3f camFront = new Vector3f(0.0f, 0.0f, -1.0f);
    private volatile Vector3f camUp = new Vector3f(0.0f, 1.0f, 0.0f);

    public volatile boolean moveW = false;
    public volatile boolean moveS = false;
    public volatile boolean moveA = false;
    public volatile boolean moveD = false;
    public volatile boolean moveQ = false;
    public volatile boolean moveE = false;

    public OpenGLRenderer(WritableImage fxImage) {
        this.fxImage = fxImage;
        updateCameraVectors();
    }

    public void queueModelLoad(String filePath) { pendingModels.add(filePath); }
    public void queueSectionLoad(File folder) { pendingSections.add(folder); }
    public void queueDeleteObject(int objectId) { pendingDeletions.add(objectId); }

    public void registerClick(int x, int y, boolean multiSelect) {
        this.clickX = x;
        this.clickY = y;
        this.multiSelectModifier = multiSelect;
        this.mouseClicked = true;
    }

    public Set<Integer> getSelectedObjectIds() { return selectedObjectIds; }
    public void setOnSelectionChanged(Consumer<Set<Integer>> callback) { this.onSelectionChanged = callback; }

    public boolean isSectionObject(int id) {
        for (SceneObject obj : objects) {
            if (obj.getId() == id) {
                return obj.isSectionBox || "MARKER_NW".equals(obj.getSourcePath());
            }
        }
        return false;
    }

    public void clearSelection() {
        selectedObjectIds.clear();
        if (onSelectionChanged != null) {
            Platform.runLater(() -> onSelectionChanged.accept(new HashSet<>()));
        }
    }

    public void updateCameraLook(float deltaX, float deltaY) {
        yaw += deltaX * 0.15f;
        pitch += deltaY * 0.15f;

        if (pitch > 89.0f) pitch = 89.0f;
        if (pitch < -89.0f) pitch = -89.0f;

        updateCameraVectors();
    }

    private void updateCameraVectors() {
        float rYaw = (float) Math.toRadians(yaw);
        float rPitch = (float) Math.toRadians(pitch);

        camFront.x = (float) (Math.cos(rYaw) * Math.cos(rPitch));
        camFront.y = (float) Math.sin(rPitch);
        camFront.z = (float) (Math.sin(rYaw) * Math.cos(rPitch));
        camFront.normalize();
    }

    public void resetCamera() {
        camPos.set(0.0f, 2.0f, 5.0f);
        yaw = -90.0f;
        pitch = -15.0f;
        updateCameraVectors();
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

        ShaderProgram shader, overlayShader;
        try {
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
            return;
        }

        List<Float> gridVerts = new ArrayList<>();
        float gridExtent = 50.0f;
        float gridStep = 1.0f;
        for(float i = -gridExtent; i <= gridExtent; i += gridStep) {
            gridVerts.add(i); gridVerts.add(0f); gridVerts.add(-gridExtent);
            gridVerts.add(i); gridVerts.add(0f); gridVerts.add(gridExtent);
            gridVerts.add(-gridExtent); gridVerts.add(0f); gridVerts.add(i);
            gridVerts.add(gridExtent); gridVerts.add(0f); gridVerts.add(i);
        }
        float[] gridData = new float[gridVerts.size()];
        for(int i = 0; i < gridData.length; i++) gridData[i] = gridVerts.get(i);

        int gridVao = GL30.glGenVertexArrays();
        int gridVbo = GL30.glGenBuffers();
        GL30.glBindVertexArray(gridVao);
        GL30.glBindBuffer(GL30.GL_ARRAY_BUFFER, gridVbo);
        FloatBuffer gridBuf = MemoryUtil.memAllocFloat(gridData.length);
        gridBuf.put(gridData).flip();
        GL30.glBufferData(GL30.GL_ARRAY_BUFFER, gridBuf, GL30.GL_STATIC_DRAW);
        GL30.glVertexAttribPointer(0, 3, GL30.GL_FLOAT, false, 0, 0);
        GL30.glEnableVertexAttribArray(0);
        MemoryUtil.memFree(gridBuf);
        GL30.glBindVertexArray(0);

        Matrix4f projection = new Matrix4f().perspective((float) Math.toRadians(45.0f), (float) width / height, 0.1f, 100.0f);

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
                if (newModelPath != null) {
                    try {
                        Mesh mesh = ModelLoader.loadModel(newModelPath);
                        SceneObject newPiece = new SceneObject(nextObjectId++, mesh, newModelPath);
                        objects.add(newPiece);
                    } catch (Exception e) { e.printStackTrace(); }
                }

                File sectionFolder;
                while ((sectionFolder = pendingSections.poll()) != null) {
                    try {
                        File txtFile = new File(sectionFolder, "sectiune.txt");
                        if (!txtFile.exists()) throw new Exception("Fisierul sectiune.txt lipseste!");

                        List<String> lines = Files.readAllLines(txtFile.toPath());
                        SceneObject sectionBox = null;

                        for (String line : lines) {
                            if (line.trim().isEmpty()) continue;
                            String[] parts = line.split("\\s+");

                            if (parts[0].equals("dim:") && parts.length >= 4) {
                                float w = Float.parseFloat(parts[1]);
                                float h = Float.parseFloat(parts[2]);
                                float d = Float.parseFloat(parts[3]);

                                Mesh boxMesh = PrimitiveFactory.createLinesBox(w, h, d, Texture.createDefaultWhite());
                                sectionBox = new SceneObject(nextObjectId++, boxMesh, "SECTIUNE");
                                sectionBox.isSectionBox = true;
                                sectionBox.scale = 0.35f;
                                objects.add(sectionBox);

                                int blueTexId = Texture.createColorTexture(0, 100, 255);
                                Mesh markerMesh = PrimitiveFactory.createBox(0.2f, 0.2f, 0.2f, blueTexId);
                                SceneObject nwMarker = new SceneObject(nextObjectId++, markerMesh, "MARKER_NW");
                                nwMarker.parent = sectionBox;

                                nwMarker.position.set(-w / 2, h / 2, -d / 2);
                                objects.add(nwMarker);
                            }
                            else if (parts[0].equals("obj:") && parts.length >= 8 && sectionBox != null) {
                                String filename = parts[1];
                                float px = Float.parseFloat(parts[2]);
                                float py = Float.parseFloat(parts[3]);
                                float pz = Float.parseFloat(parts[4]);
                                float rx = Float.parseFloat(parts[5]);
                                float ry = Float.parseFloat(parts[6]);
                                float rz = Float.parseFloat(parts[7]);

                                float objScale = (parts.length >= 9) ? Float.parseFloat(parts[8]) : 0.15f;

                                File objFile = new File(sectionFolder, filename);
                                if (!objFile.exists()) continue;

                                Mesh mesh = ModelLoader.loadModel(objFile.getAbsolutePath());
                                SceneObject child = new SceneObject(nextObjectId++, mesh, objFile.getAbsolutePath());

                                child.parent = sectionBox;
                                child.position.set(px, py, pz);
                                child.rotation.set((float)Math.toRadians(rx), (float)Math.toRadians(ry), (float)Math.toRadians(rz));
                                child.scale = objScale;
                                objects.add(child);
                            }
                        }
                    } catch (Exception e) { e.printStackTrace(); }
                }

                processPendingDeletions();

                float speed = 0.08f;
                if (moveW) camPos.add(new Vector3f(camFront).mul(speed));
                if (moveS) camPos.sub(new Vector3f(camFront).mul(speed));
                Vector3f right = new Vector3f(camFront).cross(camUp).normalize();
                if (moveA) camPos.sub(new Vector3f(right).mul(speed));
                if (moveD) camPos.add(new Vector3f(right).mul(speed));

                if (selectedObjectIds.isEmpty()) {
                    if (moveQ) camPos.sub(new Vector3f(camUp).mul(speed));
                    if (moveE) camPos.add(new Vector3f(camUp).mul(speed));
                } else {
                    if (moveQ) moveSelectedObjectVertical(-speed);
                    if (moveE) moveSelectedObjectVertical(speed);
                }

                Matrix4f view = new Matrix4f().lookAt(camPos, new Vector3f(camPos).add(camFront), camUp);

                GL30.glClearColor(0.16f, 0.16f, 0.16f, 1.0f);
                GL30.glClear(GL30.GL_COLOR_BUFFER_BIT | GL30.GL_DEPTH_BUFFER_BIT);

                GL30.glEnable(GL30.GL_BLEND);
                GL30.glBlendFunc(GL30.GL_SRC_ALPHA, GL30.GL_ONE_MINUS_SRC_ALPHA);
                overlayShader.bind();
                overlayShader.setUniform("projection", projection);
                overlayShader.setUniform("view", view);

                Matrix4f gridModel = new Matrix4f().translate(0.0f, -0.02f, 0.0f);
                overlayShader.setUniform("model", gridModel);
                overlayShader.setUniform("color", new Vector4f(0.3f, 0.5f, 0.8f, 0.5f));

                GL30.glBindVertexArray(gridVao);
                GL30.glDrawArrays(GL30.GL_LINES, 0, gridData.length / 3);
                GL30.glBindVertexArray(0);

                overlayShader.unbind();
                GL30.glDisable(GL30.GL_BLEND);

                shader.bind();
                shader.setUniform("projection", projection);
                shader.setUniform("view", view);

                for (SceneObject obj : objects) {
                    shader.setUniform("model", obj.getModelMatrix());
                    shader.setUniform("objectIdColor", obj.getPickingColor());
                    shader.setUniform("isSelected", selectedObjectIds.contains(obj.getId()) ? 1 : 0);

                    if (obj.isSectionBox) {
                        GL30.glPolygonMode(GL30.GL_FRONT_AND_BACK, GL30.GL_LINE);
                        GL30.glLineWidth(2.0f);
                    } else {
                        GL30.glPolygonMode(GL30.GL_FRONT_AND_BACK, GL30.GL_FILL);
                    }

                    obj.getMesh().render();
                }
                GL30.glPolygonMode(GL30.GL_FRONT_AND_BACK, GL30.GL_FILL);

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
                        for (SceneObject o : objects) {
                            if (o.getId() == newSelection && "MARKER_NW".equals(o.getSourcePath()) && o.parent != null) {
                                newSelection = o.parent.getId();
                                break;
                            }
                        }

                        if (multiSelectModifier) {
                            if (selectedObjectIds.contains(newSelection)) selectedObjectIds.remove(newSelection);
                            else selectedObjectIds.add(newSelection);
                            changed = true;
                        } else {
                            if (!selectedObjectIds.contains(newSelection)) {
                                selectedObjectIds.clear();
                                selectedObjectIds.add(newSelection);
                                changed = true;
                            }
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

    void processPendingDeletions() {
        Integer deleteId;
        while ((deleteId = pendingDeletions.poll()) != null) {
            final int idToRemove = deleteId;
            objects.removeIf(obj -> obj.getId() == idToRemove);
            SessionDatabase.removeSection(idToRemove);
            if (selectedObjectIds.contains(idToRemove)) {
                selectedObjectIds.remove(idToRemove);
                if (onSelectionChanged != null) {
                    Set<Integer> copy = new HashSet<>(selectedObjectIds);
                    Platform.runLater(() -> onSelectionChanged.accept(copy));
                }
            }
        }
    }

    public void moveSelectedObject(float deltaX, float deltaY) {
        if (selectedObjectIds.isEmpty()) return;

        Vector3f camRight = new Vector3f(camFront).cross(camUp).normalize();

        for (SceneObject obj : objects) {
            if (selectedObjectIds.contains(obj.getId()) && obj.parent == null) {
                Vector3f rightMovement = new Vector3f(camRight).mul(deltaX * 0.01f);
                Vector3f depthMovement = new Vector3f(camFront).mul(-deltaY * 0.01f);

                obj.position.add(rightMovement);
                obj.position.add(depthMovement);
            }
        }
    }

    public void moveSelectedObjectVertical(float delta) {
        if (selectedObjectIds.isEmpty()) return;
        for (SceneObject obj : objects) {
            if (selectedObjectIds.contains(obj.getId()) && obj.parent == null) {
                Vector3f upMovement = new Vector3f(camUp).mul(delta);
                obj.position.add(upMovement);
            }
        }
    }

    public void rotateSelectedObject(float deltaX, float deltaY) {
        rotateSelectedObject(deltaX, deltaY, 0f);
    }

    public void rotateSelectedObject(float deltaX, float deltaY, float deltaRoll) {
        if (selectedObjectIds.isEmpty()) return;

        Vector3f camRight = new Vector3f(camFront).cross(camUp).normalize();

        for (SceneObject obj : objects) {
            if (selectedObjectIds.contains(obj.getId()) && obj.parent == null) {
                Quaternionf q = new Quaternionf().rotationXYZ(obj.rotation.x, obj.rotation.y, obj.rotation.z);

                if (deltaX != 0) {
                    Quaternionf rotY = new Quaternionf().rotateAxis(deltaX * 0.01f, camUp.x, camUp.y, camUp.z);
                    q = rotY.mul(q);
                }
                if (deltaY != 0) {
                    Quaternionf rotX = new Quaternionf().rotateAxis(-deltaY * 0.01f, camRight.x, camRight.y, camRight.z);
                    q = rotX.mul(q);
                }
                if (deltaRoll != 0) {
                    Quaternionf rotZ = new Quaternionf().rotateAxis(-deltaRoll * 0.01f, camFront.x, camFront.y, camFront.z);
                    q = rotZ.mul(q);
                }

                obj.rotation.set(q.getEulerAnglesXYZ(new Vector3f()));
            }
        }
    }

    public void scaleSelectedObject(float delta) {
        if (selectedObjectIds.isEmpty()) return;
        for (SceneObject obj : objects) {
            if (selectedObjectIds.contains(obj.getId())) {
                if (obj.parent != null) continue;
                obj.scale += delta;
                if (obj.scale < 0.05f) obj.scale = 0.05f;
            }
        }
    }
}