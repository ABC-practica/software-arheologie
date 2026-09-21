package org.project;

import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.*;
import javafx.scene.image.ImageView;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Popup;
import javafx.stage.Screen;
import javafx.stage.Stage;
import org.joml.Vector3f;
import org.project.engine.*;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class MainController {

    @FXML
    private StackPane canvasPlaceholder;

    private Thread renderThread;
    private OpenGLRenderer currentRenderer;
    private double lastMouseX = 0;
    private double lastMouseY = 0;
    private double lastClickScreenX = 0;
    private double lastClickScreenY = 0;
    private final Popup selectionPopup = new Popup();
    private final Map<Integer, Stage> openObjectWindows = new HashMap<>();

    @FXML
    private void handleFileUpload(ActionEvent event) {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Alege un fisier 3D");
        fileChooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Modele 3D", "*.gltf", "*.glb", "*.obj")
        );
        Stage stage = (Stage) canvasPlaceholder.getScene().getWindow();
        File selectedFile = fileChooser.showOpenDialog(stage);
        if (selectedFile != null) {
            initEngineIfNeeded();
            currentRenderer.queueModelLoad(selectedFile.getAbsolutePath());
        }
    }

    @FXML
    private void handleSectionUpload(ActionEvent event) {
        DirectoryChooser dirChooser = new DirectoryChooser();
        dirChooser.setTitle("Alege Folderul Sectiunii Arheologice");
        Stage stage = (Stage) canvasPlaceholder.getScene().getWindow();
        File selectedDir = dirChooser.showDialog(stage);
        if (selectedDir != null) {
            initEngineIfNeeded();
            currentRenderer.queueSectionLoad(selectedDir);
        }
    }

    @FXML
    private void handleResetScene(ActionEvent event) {
        if (currentRenderer != null) {
            currentRenderer.resetCamera();
            for (SceneObject obj : currentRenderer.objects) {
                if (obj.parent != null) continue;

                obj.position.set(0, 0, 0);
                obj.rotation.set(0, 0, 0);

                if (obj.isSectionBox) {
                    obj.scale = 0.35f;
                } else {
                    obj.scale = 1.0f;
                }
            }
        }
    }

    @FXML
    private void handleClearScene(ActionEvent event) {
        if (currentRenderer != null) {
            currentRenderer.objects.clear();
            currentRenderer.clearSelection();
            selectionPopup.hide();
            SessionDatabase.clear();
            for (Stage stage : openObjectWindows.values()) {
                stage.close();
            }
            openObjectWindows.clear();
        }
    }

    @FXML
    private void handleShowControls(ActionEvent event) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("Control Vizualizator 3D");
        alert.setHeaderText("Instructiuni de navigare si manipulare");
        alert.setContentText("• NAVIGARE LIBERA (FLY CAMERA):\n"
                + "  - Click Stanga + Tragere pe fundal: Roteste directia privirii\n"
                + "  - W / S: Zbori inainte / inapoi\n"
                + "  - A / D: Gliseaza stanga / dreapta\n"
                + "  - Q / E (fara obiect selectat): Coboara / Urca camera\n"
                + "  - Rotita Mouse (fara obiect selectat): Zoom camera\n\n"
                + "• MANIPULARE OBIECTE:\n"
                + "  - CTRL + Click: Selectie multipla\n"
                + "  - Click Stanga + Tragere: Rotiti obiectul pe orizontala/verticala\n"
                + "  - SHIFT + Click Stanga + Tragere: Rotiti obiectul in planul ecranului (volan)\n"
                + "  - Click Dreapta + Tragere: Mutati obiectul (Stanga/Dreapta, Inainte/Inapoi)\n"
                + "  - Q / E (cu obiect selectat): Muta obiectul pe verticala (Sus/Jos)\n"
                + "  - Rotita Mouse (cu obiect selectat): Scaleaza obiectul");
        alert.showAndWait();
    }

    @FXML
    private void handleExit(ActionEvent event) {
        Platform.exit();
        System.exit(0);
    }

    private void initEngineIfNeeded() {
        if (renderThread != null && renderThread.isAlive() && currentRenderer != null) return;

        canvasPlaceholder.getChildren().clear();
        WritableImage frameBufferImage = new WritableImage(800, 600);
        ImageView imageView = new ImageView(frameBufferImage);

        imageView.fitWidthProperty().bind(canvasPlaceholder.widthProperty());
        imageView.fitHeightProperty().bind(canvasPlaceholder.heightProperty());
        imageView.setPreserveRatio(true);
        canvasPlaceholder.getChildren().add(imageView);

        currentRenderer = new OpenGLRenderer(frameBufferImage);
        currentRenderer.setOnSelectionChanged(this::onSelectionChanged);

        canvasPlaceholder.setFocusTraversable(true);

        imageView.setOnMousePressed(event -> {
            canvasPlaceholder.requestFocus();

            double viewW = imageView.getLayoutBounds().getWidth();
            double viewH = imageView.getLayoutBounds().getHeight();
            double scale = Math.min(viewW / 800.0, viewH / 600.0);
            double actualW = 800.0 * scale;
            double actualH = 600.0 * scale;
            double offsetX = (viewW - actualW) / 2.0;
            double offsetY = (viewH - actualH) / 2.0;
            double mappedX = (event.getX() - offsetX) / scale;
            double mappedY = (event.getY() - offsetY) / scale;

            if (mappedX >= 0 && mappedX <= 800 && mappedY >= 0 && mappedY <= 600) {
                lastClickScreenX = event.getScreenX();
                lastClickScreenY = event.getScreenY();
                currentRenderer.registerClick((int) mappedX, (int) mappedY, event.isControlDown());
            }

            lastMouseX = event.getX();
            lastMouseY = event.getY();
        });

        imageView.setOnMouseDragged(event -> {
            double deltaX = event.getX() - lastMouseX;
            double deltaY = event.getY() - lastMouseY;

            if (currentRenderer != null) {
                if (!currentRenderer.getSelectedObjectIds().isEmpty()) {
                    if (event.isPrimaryButtonDown()) {
                        if (event.isShiftDown()) {
                            currentRenderer.rotateSelectedObject(0, 0, (float) deltaX);
                        } else {
                            currentRenderer.rotateSelectedObject((float) deltaX, (float) deltaY, 0);
                        }
                    } else if (event.isSecondaryButtonDown()) {
                        currentRenderer.moveSelectedObject((float) deltaX, (float) deltaY);
                    }
                } else {
                    if (event.isPrimaryButtonDown()) {
                        currentRenderer.updateCameraLook((float) deltaX, (float) deltaY);
                    }
                }
            }

            lastMouseX = event.getX();
            lastMouseY = event.getY();
        });

        imageView.setOnScroll(event -> {
            if (currentRenderer != null) {
                if (!currentRenderer.getSelectedObjectIds().isEmpty()) {
                    currentRenderer.scaleSelectedObject((float) event.getDeltaY() * 0.005f);
                } else {
                    currentRenderer.zoomCamera((float) event.getDeltaY() * 0.02f);
                }
            }
        });

        canvasPlaceholder.setOnKeyPressed(event -> {
            if (currentRenderer != null) {
                switch (event.getCode()) {
                    case W: currentRenderer.moveW = true; break;
                    case S: currentRenderer.moveS = true; break;
                    case A: currentRenderer.moveA = true; break;
                    case D: currentRenderer.moveD = true; break;
                    case Q: currentRenderer.moveQ = true; break;
                    case E: currentRenderer.moveE = true; break;
                    default: break;
                }
            }
        });

        canvasPlaceholder.setOnKeyReleased(event -> {
            if (currentRenderer != null) {
                switch (event.getCode()) {
                    case W: currentRenderer.moveW = false; break;
                    case S: currentRenderer.moveS = false; break;
                    case A: currentRenderer.moveA = false; break;
                    case D: currentRenderer.moveD = false; break;
                    case Q: currentRenderer.moveQ = false; break;
                    case E: currentRenderer.moveE = false; break;
                    default: break;
                }
            }
        });

        renderThread = new Thread(currentRenderer);
        renderThread.setDaemon(true);
        renderThread.start();
    }

    private Button createCloseBtn() {
        Button closeBtn = new Button("X");
        closeBtn.setStyle("-fx-background-color: transparent; -fx-text-fill: #aaaaaa; -fx-font-weight: bold; -fx-cursor: hand;");
        closeBtn.setOnAction(e -> {
            selectionPopup.hide();
            if (currentRenderer != null) currentRenderer.clearSelection();
        });
        closeBtn.setOnMouseEntered(e -> closeBtn.setStyle("-fx-background-color: transparent; -fx-text-fill: white; -fx-font-weight: bold; -fx-cursor: hand;"));
        closeBtn.setOnMouseExited(e -> closeBtn.setStyle("-fx-background-color: transparent; -fx-text-fill: #aaaaaa; -fx-font-weight: bold; -fx-cursor: hand;"));
        return closeBtn;
    }

    private void onSelectionChanged(Set<Integer> objectIds) {
        if (currentRenderer == null) return;

        Set<Integer> filteredIds = new HashSet<>();
        SceneObject selectedSection = null;

        for (int id : objectIds) {
            SceneObject obj = currentRenderer.getObjectById(id);
            if (obj != null) {
                if (obj.isSectionBox) {
                    selectedSection = obj;
                } else if (!"MARKER_NW".equals(obj.getSourcePath())) {
                    filteredIds.add(id);
                }
            }
        }

        if (filteredIds.isEmpty() && selectedSection == null) {
            selectionPopup.hide();
            return;
        }

        VBox content = new VBox(8);
        content.setStyle("-fx-background-color: #2b2b2b; -fx-padding: 10; -fx-border-color: #555; -fx-border-width: 1; -fx-border-radius: 3; -fx-background-radius: 3;");

        if (selectedSection != null && filteredIds.isEmpty()) {
            Label label = new Label("Sectiune Arheologica");
            label.setStyle("-fx-text-fill: #00ffcc; -fx-font-weight: bold;");

            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            HBox header = new HBox(label, spacer, createCloseBtn());
            header.setAlignment(Pos.CENTER_LEFT);
            header.setPrefWidth(180);
            content.getChildren().add(header);

            Button addShardBtn = new Button("Adauga Ciob Nou");
            addShardBtn.setStyle("-fx-background-color: #28a745; -fx-text-fill: white; -fx-font-weight: bold; -fx-cursor: hand;");
            addShardBtn.setMaxWidth(Double.MAX_VALUE);
            SceneObject finalSelectedSection = selectedSection;
            addShardBtn.setOnAction(e -> {
                selectionPopup.hide();
                openAddShardToSectionDialog(finalSelectedSection);
            });

            Button deselectButton = new Button("Deselecteaza");
            deselectButton.setMaxWidth(Double.MAX_VALUE);
            deselectButton.setStyle("-fx-background-color: #6c757d; -fx-text-fill: white; -fx-cursor: hand;");
            deselectButton.setOnAction(e -> {
                selectionPopup.hide();
                currentRenderer.clearSelection();
            });

            Button deleteButton = new Button("Sterge Sectiunea");
            deleteButton.setMaxWidth(Double.MAX_VALUE);
            deleteButton.setStyle("-fx-background-color: #d9534f; -fx-text-fill: white; -fx-cursor: hand;");
            int sectionId = selectedSection.getId();
            deleteButton.setOnAction(e -> {
                selectionPopup.hide();
                currentRenderer.queueDeleteObject(sectionId);
            });

            content.getChildren().addAll(addShardBtn, deselectButton, deleteButton);

            selectionPopup.getContent().setAll(content);
            Stage ownerWindow = (Stage) canvasPlaceholder.getScene().getWindow();
            selectionPopup.show(ownerWindow, lastClickScreenX, lastClickScreenY);
            return;
        }

        Label label = new Label(filteredIds.size() == 1 ? "Obiect #" + filteredIds.iterator().next() : filteredIds.size() + " Fragmente Selectate");
        label.setStyle("-fx-text-fill: white; -fx-font-weight: bold;");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox header = new HBox(label, spacer, createCloseBtn());
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPrefWidth(180);
        content.getChildren().add(header);

        if (filteredIds.size() == 1) {
            int singleId = filteredIds.iterator().next();

            Button openWindowButton = new Button("Deschide fereastra");
            openWindowButton.setMaxWidth(Double.MAX_VALUE);
            openWindowButton.setOnAction(e -> {
                selectionPopup.hide();
                openObjectWindow(singleId);
            });

            Button deselectButton = new Button("Deselecteaza");
            deselectButton.setMaxWidth(Double.MAX_VALUE);
            deselectButton.setStyle("-fx-background-color: #6c757d; -fx-text-fill: white; -fx-cursor: hand;");
            deselectButton.setOnAction(e -> {
                selectionPopup.hide();
                if (currentRenderer != null) currentRenderer.clearSelection();
            });

            Button deleteButton = new Button("Sterge obiectul");
            deleteButton.setMaxWidth(Double.MAX_VALUE);
            deleteButton.setStyle("-fx-background-color: #d9534f; -fx-text-fill: white; -fx-cursor: hand;");
            deleteButton.setOnAction(e -> {
                selectionPopup.hide();
                if (currentRenderer != null) currentRenderer.queueDeleteObject(singleId);
                Stage dedicatedWindow = openObjectWindows.remove(singleId);
                if (dedicatedWindow != null) dedicatedWindow.close();
            });

            content.getChildren().addAll(openWindowButton, deselectButton, deleteButton);
        } else {
            Button generateButton = new Button("Genereaza Vas");
            generateButton.setStyle("-fx-background-color: #0078D7; -fx-text-fill: white; -fx-font-weight: bold; -fx-cursor: hand;");
            generateButton.setMaxWidth(Double.MAX_VALUE);
            generateButton.setOnAction(e -> {
                try {
                    for (int id : filteredIds) {
                        if (!SessionDatabase.hasSection(id)) {
                            throw new IllegalStateException("Fragmentul cu ID-ul #" + id + " nu are sectiunea definita!");
                        }
                    }
                    selectionPopup.hide();
                    simulateAIRestoration(filteredIds);

                } catch (IllegalStateException ex) {
                    Alert alert = new Alert(Alert.AlertType.ERROR);
                    alert.setTitle("Eroare de Validare");
                    alert.setHeaderText("Actiune respinsa: Sectiuni lipsa");
                    alert.setContentText(ex.getMessage() + "\n\nTe rugam sa deschizi fereastra dedicata pentru acest obiect si sa salvezi o sectiune.");
                    alert.showAndWait();
                }
            });

            Button deselectButton = new Button("Deselecteaza");
            deselectButton.setMaxWidth(Double.MAX_VALUE);
            deselectButton.setStyle("-fx-background-color: #6c757d; -fx-text-fill: white; -fx-cursor: hand;");
            deselectButton.setOnAction(e -> {
                selectionPopup.hide();
                if (currentRenderer != null) currentRenderer.clearSelection();
            });

            Button deleteAllButton = new Button("Sterge Selectia");
            deleteAllButton.setMaxWidth(Double.MAX_VALUE);
            deleteAllButton.setStyle("-fx-background-color: #d9534f; -fx-text-fill: white; -fx-cursor: hand;");
            deleteAllButton.setOnAction(e -> {
                selectionPopup.hide();
                if (currentRenderer != null) {
                    for (int id : filteredIds) {
                        currentRenderer.queueDeleteObject(id);
                        Stage dedicatedWindow = openObjectWindows.remove(id);
                        if (dedicatedWindow != null) dedicatedWindow.close();
                    }
                }
            });

            content.getChildren().add(generateButton);

            if (filteredIds.size() == 2) {
                Iterator<Integer> pairIterator = filteredIds.iterator();
                SceneObject pairFirst = currentRenderer.getObjectById(pairIterator.next());
                SceneObject pairSecond = currentRenderer.getObjectById(pairIterator.next());

                Button pairMatchButton = new Button("Verifica Potrivire (extern)");
                pairMatchButton.setStyle("-fx-background-color: #6f42c1; -fx-text-fill: white; -fx-font-weight: bold; -fx-cursor: hand;");
                pairMatchButton.setMaxWidth(Double.MAX_VALUE);
                pairMatchButton.setOnAction(e -> {
                    selectionPopup.hide();
                    if (pairFirst != null && pairSecond != null) {
                        launchPairMatching(pairFirst.getSourcePath(), pairSecond.getSourcePath());
                    }
                });
                content.getChildren().add(pairMatchButton);
            }

            content.getChildren().addAll(deselectButton, deleteAllButton);
        }

        selectionPopup.getContent().setAll(content);
        Stage ownerWindow = (Stage) canvasPlaceholder.getScene().getWindow();
        selectionPopup.show(ownerWindow, lastClickScreenX, lastClickScreenY);
    }

    private void launchPairMatching(String mesh1Path, String mesh2Path) {
        TextInputDialog thicknessDialog = new TextInputDialog("0.5");
        thicknessDialog.setTitle("Potrivire cioburi (extern)");
        thicknessDialog.setHeaderText("Grosimea comuna a celor doua cioburi");
        thicknessDialog.setContentText("Grosime (aceleasi unitati ca mesh-ul):");
        Optional<String> input = thicknessDialog.showAndWait();
        if (input.isEmpty()) return;

        double thickness;
        try {
            thickness = Double.parseDouble(input.get().trim().replace(",", "."));
        } catch (NumberFormatException ex) {
            Alert err = new Alert(Alert.AlertType.ERROR);
            err.setTitle("Valoare invalida");
            err.setContentText("Grosimea trebuie sa fie un numar. Exemplu: 0.5");
            err.showAndWait();
            return;
        }

        Task<Void> launchTask = new Task<>() {
            @Override
            protected Void call() throws Exception {
                PairMatchingLauncher.launch(java.nio.file.Path.of(mesh1Path), java.nio.file.Path.of(mesh2Path), thickness);
                return null;
            }
        };
        launchTask.setOnSucceeded(ev -> {
            Alert info = new Alert(Alert.AlertType.INFORMATION);
            info.setTitle("Potrivire lansata");
            info.setHeaderText(null);
            info.setContentText("Scriptul ruleaza intr-o fereastra separata, externa. Poate dura CATEVA MINUTE "
                    + "(fit de axa pt ambele cioburi + cautare brute-force a celei mai bune pozitii) - aplicatia "
                    + "principala ramane utilizabila intre timp.\n\nIn fereastra lor: tasta A porneste cautarea "
                    + "automata, tasta S salveaza rezultatul (obj-uri aliniate + raport JSON).");
            info.showAndWait();
        });
        launchTask.setOnFailed(ev -> {
            Throwable ex = launchTask.getException();
            Alert err = new Alert(Alert.AlertType.ERROR);
            err.setTitle("Eroare Potrivire Cioburi");
            err.setHeaderText("Nu am putut porni scriptul ai/pair_matching_standalone.py");
            err.setContentText(ex != null ? ex.getMessage() : "eroare necunoscuta");
            err.getDialogPane().setMinHeight(Region.USE_PREF_SIZE);
            err.showAndWait();
        });

        Thread launchThread = new Thread(launchTask);
        launchThread.setDaemon(true);
        launchThread.start();
    }

    private void openAddShardToSectionDialog(SceneObject sectionBox) {
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("Adauga Ciob in Sectiune");
        dialog.setHeaderText("Alege fisierul 3D si introdu coordonatele.\nRotatiile trebuie introduse in grade.");

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);

        TextField fileField = new TextField();
        fileField.setEditable(false);
        Button browseBtn = new Button("Cauta...");

        TextField pxField = new TextField("0.0");
        TextField pyField = new TextField("0.0");
        TextField pzField = new TextField("0.0");

        TextField rxField = new TextField("0.0");
        TextField ryField = new TextField("0.0");
        TextField rzField = new TextField("0.0");

        TextField scaleField = new TextField("0.15");

        File[] selectedFile = new File[1];

        browseBtn.setOnAction(e -> {
            FileChooser fc = new FileChooser();
            fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Modele 3D", "*.obj", "*.glb", "*.gltf"));
            File f = fc.showOpenDialog(dialog.getOwner());
            if (f != null) {
                selectedFile[0] = f;
                fileField.setText(f.getName());
            }
        });

        grid.add(new Label("Fisier 3D:"), 0, 0); grid.add(fileField, 1, 0); grid.add(browseBtn, 2, 0);
        grid.add(new Label("Pozitie X:"), 0, 1); grid.add(pxField, 1, 1);
        grid.add(new Label("Pozitie Y:"), 0, 2); grid.add(pyField, 1, 2);
        grid.add(new Label("Pozitie Z:"), 0, 3); grid.add(pzField, 1, 3);
        grid.add(new Label("Rotatie X (°):"), 0, 4); grid.add(rxField, 1, 4);
        grid.add(new Label("Rotatie Y (°):"), 0, 5); grid.add(ryField, 1, 5);
        grid.add(new Label("Rotatie Z (°):"), 0, 6); grid.add(rzField, 1, 6);
        grid.add(new Label("Scale:"), 0, 7); grid.add(scaleField, 1, 7);

        dialog.getDialogPane().setContent(grid);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        dialog.setResultConverter(btn -> {
            if (btn == ButtonType.OK && selectedFile[0] != null) {
                try {
                    float px = Float.parseFloat(pxField.getText().trim());
                    float py = Float.parseFloat(pyField.getText().trim());
                    float pz = Float.parseFloat(pzField.getText().trim());
                    float rx = Float.parseFloat(rxField.getText().trim());
                    float ry = Float.parseFloat(ryField.getText().trim());
                    float rz = Float.parseFloat(rzField.getText().trim());
                    float scale = Float.parseFloat(scaleField.getText().trim());

                    File sectionDir = new File(sectionBox.getSourcePath());
                    File destFile = new File(sectionDir, selectedFile[0].getName());

                    if (!selectedFile[0].getAbsolutePath().equals(destFile.getAbsolutePath())) {
                        java.nio.file.Files.copy(selectedFile[0].toPath(), destFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }

                    File txtFile = new File(sectionDir, "sectiune.txt");
                    String newLine = String.format(Locale.ROOT, "\nobj: %s %.4f %.4f %.4f %.4f %.4f %.4f %.4f",
                            destFile.getName(), px, py, pz, rx, ry, rz, scale);
                    java.nio.file.Files.writeString(txtFile.toPath(), newLine, java.nio.file.StandardOpenOption.APPEND);

                    currentRenderer.queueChildModelLoad(destFile.getAbsolutePath(), sectionBox.getId(), px, py, pz, rx, ry, rz, scale);

                } catch (Exception ex) {
                    ex.printStackTrace();
                    Alert a = new Alert(Alert.AlertType.ERROR, "Date invalide:\n" + ex.getMessage());
                    a.showAndWait();
                }
            }
            return null;
        });

        dialog.showAndWait();
    }

    private void installAiLibraries(File aiDir, Alert infoAlert) {
        try {
            Platform.runLater(() -> {
                infoAlert.setHeaderText("Instalare librarii");
                infoAlert.setContentText("Se descarca librariile utilizate de AI. Te rog sa astepti");
            });

            String osName = System.getProperty("os.name").toLowerCase();
            String pythonCall = (osName.contains("win")) ? "python" : "python3";

            ProcessBuilder req = new ProcessBuilder(pythonCall, "-m", "pip", "install", "-r", "requirements.txt");
            req.directory(aiDir);
            req.redirectErrorStream(true);
            Process reqProcess = req.start();

            try (java.io.InputStream in = reqProcess.getInputStream()) {
                in.readAllBytes();
            }

            reqProcess.waitFor();

            File installedDummy = new File(aiDir, "installed.txt");
            installedDummy.createNewFile();

            Platform.runLater(() -> {
                infoAlert.setHeaderText("Trimitere date catre AI");
                infoAlert.setContentText("Asteapta te rog, se genereaza modelul 3D...");
            });

        } catch (Exception e) {
            e.printStackTrace();
            Platform.runLater(() -> {
                infoAlert.close();
                Alert err = new Alert(Alert.AlertType.ERROR);
                err.setTitle("Eroare Instalare Librarii");
                err.setHeaderText("Nu s-a putut instala librariile Python");
                err.setContentText("A aparut o eroare la executarea comenzii 'pip'. Asigura-te ca Python este instalat pe acest calculator si ca a fost adaugat in variabila de mediu PATH.");
                err.getDialogPane().setMinHeight(Region.USE_PREF_SIZE);
                err.showAndWait();
            });
        }
    }


    private void simulateAIRestoration(Set<Integer> objectIds) {
        File aiDir = new File("ai");
        File installedFile = new File(aiDir, "installed.txt");

        if (!installedFile.exists()) {
            Alert librariesMissingAlert = new Alert(Alert.AlertType.CONFIRMATION);
            librariesMissingAlert.setTitle("Instalare librarii");
            librariesMissingAlert.setHeaderText("Lipsesc librarii pentru AI");
            librariesMissingAlert.setContentText("Pentru a genera un vas, trebuie descarcate librariile utilizate de AI (poate dura cateva minute).\nDoresti sa le instalezi?");
            librariesMissingAlert.getDialogPane().setMinHeight(Region.USE_PREF_SIZE);

            librariesMissingAlert.getButtonTypes().setAll(ButtonType.YES, ButtonType.NO);

            Optional<ButtonType> result = librariesMissingAlert.showAndWait();

            if (result.isPresent() && result.get() == ButtonType.NO) {
                return;
            }
        }

        Alert infoAlert = new Alert(Alert.AlertType.INFORMATION);
        infoAlert.setTitle("Comunicare AI Backend");
        infoAlert.setHeaderText("Trimitere date catre AI");
        infoAlert.setContentText("Asteapta te rog, se genereaza modelul 3D...");

        ProgressIndicator progressIndicator = new ProgressIndicator();
        infoAlert.setGraphic(progressIndicator);
        infoAlert.getDialogPane().lookupButton(ButtonType.OK).setDisable(true);

        infoAlert.show();

        new Thread(() -> {
            try {
                if (!installedFile.exists()) {
                    installAiLibraries(aiDir, infoAlert);
                }

                int firstId = objectIds.iterator().next();
                WritableImage sectionImage = SessionDatabase.getSection(firstId);

                String userHome = System.getProperty("user.home");
                File safeDataDir = new File(userHome, ".software-arheologie");

                File inputDir = new File(safeDataDir, "shards");
                File outputDir = new File(safeDataDir, "output");

                inputDir.mkdirs();
                outputDir.mkdirs();

                File generatedObj = new File(outputDir, "ai_solid_proportioned_pot.obj");
                if (generatedObj.exists()) {
                    generatedObj.delete();
                }

                File imagePath = new File(inputDir, "shard.png");
                ImageExporter.savePng(sectionImage, imagePath);

                String osName = System.getProperty("os.name").toLowerCase();
                String exeName = osName.contains("win") ? "AI.exe" : "AI";

                File exeFile = new File(aiDir, "dist" + File.separator + "AI" + File.separator + exeName);
                ProcessBuilder aiProcessBuilder;

                if (exeFile.exists()) {
                    aiProcessBuilder = new ProcessBuilder(
                            exeFile.getAbsolutePath(),
                            "--input", inputDir.getAbsolutePath(),
                            "--output", outputDir.getAbsolutePath()
                    );
                } else {
                    String pythonCall = osName.contains("win") ? "python" : "python3";
                    aiProcessBuilder = new ProcessBuilder(
                            pythonCall, "rotate.py",
                            "--input", inputDir.getAbsolutePath(),
                            "--output", outputDir.getAbsolutePath()
                    );
                }

                aiProcessBuilder.redirectErrorStream(true);
                aiProcessBuilder.directory(aiDir);

                Process aiProcess = aiProcessBuilder.start();

                String pythonOutput = new String(aiProcess.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                int aiExitCode = aiProcess.waitFor();

                Platform.runLater(infoAlert::close);

                if (aiExitCode == 0) {
                    if (generatedObj.exists()) {
                        Platform.runLater(() -> openRestoredVaseWindow(generatedObj.getAbsolutePath()));
                    }
                } else {
                    boolean isMissingLibrary = pythonOutput.contains("ModuleNotFound") || pythonOutput.contains("ImportError");

                    Platform.runLater(() -> {
                        if (isMissingLibrary) {
                            if (installedFile.exists()) {
                                installedFile.delete();
                            }
                            simulateAIRestoration(objectIds);
                        } else {
                            Alert err = new Alert(Alert.AlertType.ERROR);
                            err.setTitle("Eroare Script Python");
                            err.setHeaderText("AI-ul a returnat o eroare!");
                            err.setContentText("Extinde sectiunea de mai jos pentru a vedea detalii");

                            TextArea textArea = new TextArea(pythonOutput);
                            textArea.setEditable(false);
                            textArea.setWrapText(true);
                            textArea.setMaxWidth(Double.MAX_VALUE);
                            textArea.setMaxHeight(Double.MAX_VALUE);
                            err.getDialogPane().setExpandableContent(textArea);
                            err.getDialogPane().setExpanded(false);

                            err.showAndWait();
                        }
                    });
                }

            } catch (Exception e) {
                e.printStackTrace();
                Platform.runLater(() -> {
                    infoAlert.close();
                    Alert err = new Alert(Alert.AlertType.ERROR);
                    err.setTitle("Eroare de Executie");
                    err.setHeaderText("Nu s-a putut rula AI-ul");
                    err.setContentText("A apărut o eroare la lansarea executabilului sau scriptului Python. Asigură-te că ai permisiunile necesare și că fișierele sunt la locul lor.");
                    err.showAndWait();
                });
            }
        }).start();
    }

    private void openRestoredVaseWindow(String modelPath) {
        int viewSize = 600;
        WritableImage frameBufferImage = new WritableImage(viewSize, viewSize);
        ImageView imageView = new ImageView(frameBufferImage);
        imageView.setFitWidth(viewSize);
        imageView.setFitHeight(viewSize);
        imageView.setPreserveRatio(true);

        SingleObjectRenderer vaseRenderer = new SingleObjectRenderer(modelPath, null, frameBufferImage, viewSize, viewSize, true);

        double[] lastX = {0};
        double[] lastY = {0};
        imageView.setOnMousePressed(event -> {
            lastX[0] = event.getX();
            lastY[0] = event.getY();
        });
        imageView.setOnMouseDragged(event -> {
            vaseRenderer.rotate((float) (event.getX() - lastX[0]), (float) (event.getY() - lastY[0]));
            lastX[0] = event.getX();
            lastY[0] = event.getY();
        });
        imageView.setOnScroll(event -> vaseRenderer.scale((float) event.getDeltaY() * 0.005f));

        VBox root = new VBox(imageView);
        root.setStyle("-fx-background-color: #2b2b2b;");

        Thread renderThread = new Thread(vaseRenderer);
        renderThread.setDaemon(true);
        renderThread.start();

        Stage vaseStage = new Stage();
        vaseStage.setTitle("Rezultat Reconstructie AI");
        vaseStage.setScene(new Scene(root, viewSize, viewSize));
        vaseStage.setOnCloseRequest(e -> renderThread.interrupt());
        vaseStage.show();
    }

    private void openGhostPreviewWindow(SceneObject target, CurvatureClassifier.VesselAxisEstimate axisEstimate) {
        try {
            File tempObjFile = File.createTempFile("ghost_preview_", ".obj");
            GhostVesselGenerator.generateGhostOnly(target, axisEstimate, tempObjFile);

            int viewSize = 600;
            WritableImage frameBufferImage = new WritableImage(viewSize, viewSize);
            ImageView imageView = new ImageView(frameBufferImage);
            imageView.setFitWidth(viewSize);
            imageView.setFitHeight(viewSize);
            imageView.setPreserveRatio(true);

            SingleObjectRenderer previewRenderer = new SingleObjectRenderer(target.getSourcePath(), tempObjFile.getAbsolutePath(), frameBufferImage, viewSize, viewSize, true);

            double[] lastX = {0};
            double[] lastY = {0};
            imageView.setOnMousePressed(event -> {
                lastX[0] = event.getX();
                lastY[0] = event.getY();
            });
            imageView.setOnMouseDragged(event -> {
                previewRenderer.rotate((float) (event.getX() - lastX[0]), (float) (event.getY() - lastY[0]));
                lastX[0] = event.getX();
                lastY[0] = event.getY();
            });
            imageView.setOnScroll(event -> previewRenderer.scale((float) event.getDeltaY() * 0.005f));

            Button saveBtn = new Button("Salveaza Modelul pe Disc (.obj)");
            saveBtn.setStyle("-fx-background-color: #28a745; -fx-text-fill: white; -fx-font-weight: bold; -fx-cursor: hand;");
            saveBtn.setMaxWidth(Double.MAX_VALUE);

            Stage previewStage = new Stage();

            saveBtn.setOnAction(e -> {
                FileChooser fc = new FileChooser();
                fc.setTitle("Salveaza Vas Fantoma");
                fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Obiect 3D", "*.obj"));
                fc.setInitialFileName("vas_fantoma.obj");
                File dest = fc.showSaveDialog(previewStage);
                if (dest != null) {
                    try {
                        GhostVesselGenerator.generateAndExport(target, axisEstimate, dest);
                        Alert a = new Alert(Alert.AlertType.INFORMATION, "Modelul a fost exportat cu succes!");
                        a.showAndWait();
                    } catch (Exception ex) {
                        ex.printStackTrace();
                        new Alert(Alert.AlertType.ERROR, "Eroare la salvare: " + ex.getMessage()).showAndWait();
                    }
                }
            });

            VBox root = new VBox(10, imageView, saveBtn);
            root.setStyle("-fx-background-color: #2b2b2b; -fx-padding: 10;");
            root.setAlignment(Pos.CENTER);

            Thread renderThread = new Thread(previewRenderer);
            renderThread.setDaemon(true);
            renderThread.start();

            previewStage.setTitle("Previzualizare Vas Fantoma");
            previewStage.setScene(new Scene(root, viewSize, viewSize + 60));
            previewStage.setOnCloseRequest(e -> {
                renderThread.interrupt();
                tempObjFile.delete();
            });
            previewStage.show();

        } catch (Exception e) {
            e.printStackTrace();
            new Alert(Alert.AlertType.ERROR, "Eroare la generare: " + e.getMessage()).showAndWait();
        }
    }

    private static String planeEquationText(Vector3f normal, Vector3f point) {
        float d = normal.dot(point);
        return String.format("%.3fx %+.3fy %+.3fz = %.3f", normal.x, normal.y, normal.z, d);
    }

    private static String categoryLabel(SherdShapeClassifier.Category category) {
        switch (category) {
            case FUND: return "Fund de vas";
            case BUZA: return "Buza de vas";
            case MARGINE_LATERALA: return "Margine laterala";
            default: return "Necunoscut";
        }
    }

    /** Formateaza un SherdAnalysisMerger.MergedResult - folosit atat pt rezultatul provizoriu
     * (SherdAnalysisMerger.pendingFromJavaOnly, afisat instant la click) cat si pt cel final
     * (SherdAnalysisMerger.merge, dupa ce sherdtool.py raspunde). Acelasi format in ambele
     * faze e intentionat: doar campurile placeholder ("se calculeaza...") se schimba vizibil
     * cand Python raspunde, nu tot raportul - vezi memoria proiectului pt regulile de combinare. */
    private String formatMergedClassification(SherdAnalysisMerger.MergedResult r) {
        StringBuilder sb = new StringBuilder();
        sb.append("Forma: ").append(categoryLabel(r.category))
                .append(String.format(Locale.ROOT, " (incredere %.0f%%)%n", r.categoryConfidence * 100));
        sb.append(String.format(Locale.ROOT, "Calitate fit Python: %s%n", r.pythonQuality));
        sb.append(String.format(Locale.ROOT, "Vertecsi / fete: %d / %d%n", r.nVertices, r.nFaces));
        sb.append(String.format(Locale.ROOT, "Unitate: %s%n", r.unit));
        sb.append(String.format(Locale.ROOT, "Inaltime pastrata: %.3f%n", r.preservedHeight));
        sb.append(String.format(Locale.ROOT, "Diametru buza: %.3f%n", r.rimDiameter));
        sb.append(String.format(Locale.ROOT, "Diametru maxim: %.3f%n", r.maxDiameter));
        sb.append(String.format(Locale.ROOT, "Raport forma (inaltime/raza buza): %.3f%n", r.formAspectRatio));
        sb.append(String.format(Locale.ROOT, "Arc pastrat: %.1f°%n", r.preservedArcDeg));
        sb.append(String.format(Locale.ROOT, "Axa: %.3f,%.3f,%.3f @ %.3f,%.3f,%.3f%n",
                r.axisDirection.x, r.axisDirection.y, r.axisDirection.z,
                r.axisPoint.x, r.axisPoint.y, r.axisPoint.z));
        sb.append("Extent bbox: ").append(r.bboxExtent);
        if (r.pythonNotes != null && !r.pythonNotes.isBlank()) {
            sb.append("\nNote Python: ").append(r.pythonNotes);
        }
        return sb.toString();
    }

    private void updateProfileChart(LineChart<Number, Number> chart, Label statusLabel,
                                     float[] heights, float[] radii, boolean hasProfile) {
        if (hasProfile && heights.length >= 2 && heights.length == radii.length) {
            chart.getData().clear();
            XYChart.Series<Number, Number> outerSide = new XYChart.Series<>();
            XYChart.Series<Number, Number> innerSide = new XYChart.Series<>();
            for (int i = 0; i < heights.length; i++) {
                outerSide.getData().add(new XYChart.Data<>(radii[i], heights[i]));
                innerSide.getData().add(new XYChart.Data<>(-radii[i], heights[i]));
            }
            chart.getData().addAll(outerSide, innerSide);
            chart.setVisible(true);
            chart.setManaged(true);
            statusLabel.setVisible(false);
            statusLabel.setManaged(false);
        } else {
            chart.setVisible(false);
            chart.setManaged(false);
            statusLabel.setText("Profil indisponibil pentru acest ciob.");
            statusLabel.setVisible(true);
            statusLabel.setManaged(true);
        }
    }

    private void openObjectWindow(int objectId) {
        Stage existing = openObjectWindows.get(objectId);
        if (existing != null) {
            existing.toFront();
            existing.requestFocus();
            return;
        }

        SceneObject target = null;
        for (SceneObject obj : currentRenderer.objects) {
            if (obj.getId() == objectId) {
                target = obj;
                break;
            }
        }
        if (target == null) return;
        final SceneObject finalTarget = target;
        final String meshSourcePath = target.getSourcePath();

        int viewSize = 500;
        WritableImage frameBufferImage = new WritableImage(viewSize, viewSize);
        ImageView imageView = new ImageView(frameBufferImage);
        imageView.setFitWidth(viewSize);
        imageView.setFitHeight(viewSize);
        imageView.setPreserveRatio(true);

        SingleObjectRenderer objectRenderer = new SingleObjectRenderer(target.getSourcePath(), null, frameBufferImage, viewSize, viewSize, false);

        objectRenderer.setOnTopViewCaptured(image -> openTopViewPreview(objectId, image));

        double[] lastX = {0};
        double[] lastY = {0};
        imageView.setOnMousePressed(event -> {
            lastX[0] = event.getX();
            lastY[0] = event.getY();
        });

        imageView.setOnMouseDragged(event -> {
            objectRenderer.rotate((float) (event.getX() - lastX[0]), (float) (event.getY() - lastY[0]));
            lastX[0] = event.getX();
            lastY[0] = event.getY();
        });
        imageView.setOnScroll(event -> objectRenderer.scale((float) event.getDeltaY() * 0.005f));

        Label fileLabel = new Label("Fisier: " + new File(target.getSourcePath()).getName());
        fileLabel.setStyle("-fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 14px;");

        Label posLabel = new Label(String.format("Pozitie  —  X: %.2f  |  Y: %.2f  |  Z: %.2f",
                target.position.x, target.position.y, target.position.z));
        posLabel.setStyle("-fx-text-fill: #cccccc;");

        Label rotLabel = new Label(String.format("Rotatie  —  X: %.1f°  |  Y: %.1f°  |  Z: %.1f°",
                Math.toDegrees(target.rotation.x), Math.toDegrees(target.rotation.y), Math.toDegrees(target.rotation.z)));
        rotLabel.setStyle("-fx-text-fill: #cccccc;");

        VBox infoBox = new VBox(6, fileLabel, posLabel, rotLabel);
        infoBox.setStyle("-fx-padding: 15; -fx-background-color: #383838; -fx-background-radius: 5;");

        Button computeButton = new Button("Calculeaza curbura");
        computeButton.setStyle("-fx-background-color: #0078D7; -fx-text-fill: white; -fx-font-weight: bold; -fx-cursor: hand;");
        computeButton.setMaxWidth(Double.MAX_VALUE);

        Button previewGhostBtn = new Button("Previzualizare Vas Fantoma");
        previewGhostBtn.setStyle("-fx-background-color: #17a2b8; -fx-text-fill: white; -fx-font-weight: bold; -fx-cursor: hand;");
        previewGhostBtn.setMaxWidth(Double.MAX_VALUE);
        previewGhostBtn.setDisable(true);

        Button arheoAxisButton = new Button("Arheo-Axis (analiza avansata, extern)");
        arheoAxisButton.setStyle("-fx-background-color: #6f42c1; -fx-text-fill: white; -fx-font-weight: bold; -fx-cursor: hand;");
        arheoAxisButton.setMaxWidth(Double.MAX_VALUE);

        CheckBox extCheck = new CheckBox("Curbura exterioara");
        extCheck.setDisable(true);
        extCheck.setStyle("-fx-text-fill: white;");
        extCheck.setOnAction(e -> objectRenderer.setShowExterior(extCheck.isSelected()));

        CheckBox intCheck = new CheckBox("Curbura interioara");
        intCheck.setDisable(true);
        intCheck.setStyle("-fx-text-fill: white;");
        intCheck.setOnAction(e -> objectRenderer.setShowInterior(intCheck.isSelected()));

        Label widthLabel = new Label("Latime estimata: Nedeterminat");
        widthLabel.setStyle("-fx-text-fill: #ffcc00; -fx-font-weight: bold;");

        Label extEquationLabel = new Label("Ecuatie suprafata exterior (grad 2): Nedeterminat");
        extEquationLabel.setStyle("-fx-text-fill: #ff6666; -fx-font-family: monospace;");
        extEquationLabel.setWrapText(true);

        Label intEquationLabel = new Label("Ecuatie suprafata interior (grad 2): Nedeterminat");
        intEquationLabel.setStyle("-fx-text-fill: #66aaff; -fx-font-family: monospace;");
        intEquationLabel.setWrapText(true);

        Label spreadLabel = new Label("Distantare stanga/dreapta");
        spreadLabel.setStyle("-fx-text-fill: white;");
        Slider spreadSlider = new Slider(0, 0.6, 0.15);
        spreadSlider.valueProperty().addListener((obs, oldVal, newVal) ->
                objectRenderer.setCurvatureSpread(newVal.floatValue()));

        computeButton.setOnAction(e -> {
            computeButton.setText("Se calculeaza...");
            computeButton.setDisable(true);
            objectRenderer.requestComputeCurvature();
        });

        final CurvatureClassifier.Result[] lastCurvatureResult = new CurvatureClassifier.Result[1];
        final float[] lastThickness = {0f};

        objectRenderer.setOnCurvatureComputed(result -> {
            lastCurvatureResult[0] = result;
            computeButton.setText("Curbura calculata");
            extCheck.setDisable(false);
            intCheck.setDisable(false);
            previewGhostBtn.setDisable(false);

            float distance = result.exteriorPlanePoint.distance(result.interiorPlanePoint);
            lastThickness[0] = distance;
            widthLabel.setText(String.format("Latime estimata: %.3f unitati", distance));

            extEquationLabel.setText("Exterior: " + result.exteriorQuadric.toEquationText());
            intEquationLabel.setText("Interior: " + result.interiorQuadric.toEquationText());
        });

        previewGhostBtn.setOnAction(e -> {
            if (lastCurvatureResult[0] == null) return;
            openGhostPreviewWindow(finalTarget, lastCurvatureResult[0].exteriorAxisEstimate);
        });

        arheoAxisButton.setOnAction(e -> {
            // Grosimea e doar cosmetica pt tool-ul lor (ingroasa peretele "fantoma" din
            // loft-ul gri) - nu afecteaza fitul axei. Folosim grosimea deja calculata daca
            // exista ("Calculeaza curbura" a rulat), altfel un default rezonabil.
            float thickness = lastThickness[0] > 0f ? lastThickness[0] : 0.5f;
            boolean thicknessIsDefault = lastCurvatureResult[0] == null;

            arheoAxisButton.setText("Se porneste...");
            arheoAxisButton.setDisable(true);

            // launch() poate astepta pana la cateva secunde ca sa detecteze un esec rapid
            // (dependinta Python lipsa, fisier lipsa) - nu blocam thread-ul FX pt asta.
            Task<Void> launchTask = new Task<>() {
                @Override
                protected Void call() throws Exception {
                    ArheoAxisLauncher.launch(java.nio.file.Path.of(meshSourcePath), thickness);
                    return null;
                }
            };
            launchTask.setOnSucceeded(ev -> {
                arheoAxisButton.setText("Arheo-Axis (analiza avansata, extern)");
                arheoAxisButton.setDisable(false);
                Alert info = new Alert(Alert.AlertType.INFORMATION);
                info.setTitle("Arheo-Axis lansat");
                info.setHeaderText(null);
                info.setContentText("Scriptul ruleaza intr-o fereastra separata, externa. Poate dura "
                        + "CATEVA MINUTE pana apare fereastra (RANSAC pe sectiuni reale + optimizare "
                        + "neliniara globala) - aplicatia principala ramane utilizabila intre timp.\n\n"
                        + "Grosime folosita: " + String.format(Locale.ROOT, "%.3f", thickness)
                        + (thicknessIsDefault ? " (default, calculeaza curbura mai intai pt una reala)" : ""));
                info.showAndWait();
            });
            launchTask.setOnFailed(ev -> {
                arheoAxisButton.setText("Arheo-Axis (analiza avansata, extern)");
                arheoAxisButton.setDisable(false);
                Throwable ex = launchTask.getException();
                Alert err = new Alert(Alert.AlertType.ERROR);
                err.setTitle("Eroare Arheo-Axis");
                err.setHeaderText("Nu am putut porni scriptul ai/axis.py");
                err.setContentText(ex != null ? ex.getMessage() : "eroare necunoscuta");
                err.getDialogPane().setMinHeight(Region.USE_PREF_SIZE);
                err.showAndWait();
            });

            Thread launchThread = new Thread(launchTask);
            launchThread.setDaemon(true);
            launchThread.start();
        });

        Slider yawSlider = new Slider(0, 360, 0);
        Slider pitchSlider = new Slider(-89, 89, 0);
        Slider offsetSlider = new Slider(-2.5, 2.5, 0);

        yawSlider.valueProperty().addListener((obs, oldVal, newVal) ->
                objectRenderer.setCrossSectionYaw((float) Math.toRadians(newVal.doubleValue())));
        pitchSlider.valueProperty().addListener((obs, oldVal, newVal) ->
                objectRenderer.setCrossSectionPitch((float) Math.toRadians(newVal.doubleValue())));
        offsetSlider.valueProperty().addListener((obs, oldVal, newVal) ->
                objectRenderer.setCrossSectionOffset(newVal.floatValue()));

        Button cutButton = new Button("Decupeaza si Previzualizeaza");
        cutButton.setStyle("-fx-background-color: #28a745; -fx-text-fill: white; -fx-font-weight: bold; -fx-cursor: hand;");
        cutButton.setMaxWidth(Double.MAX_VALUE);
        cutButton.setOnAction(e -> {
            objectRenderer.requestComputeCrossSection();
            objectRenderer.requestTopViewCapture();
        });

        Label l1 = new Label("Sectiune 2D orizontala"); l1.setStyle("-fx-text-fill: white;");
        Label l2 = new Label("Sectiune verticala"); l2.setStyle("-fx-text-fill: white;");
        Label l3 = new Label("Pozitie plan"); l3.setStyle("-fx-text-fill: white;");

        VBox curvatureControls = new VBox(8, computeButton, previewGhostBtn, arheoAxisButton, extCheck, intCheck, widthLabel,
                extEquationLabel, intEquationLabel, spreadLabel, spreadSlider);
        curvatureControls.setStyle("-fx-padding: 15; -fx-background-color: #383838; -fx-background-radius: 5;");

        VBox crossSectionControls = new VBox(8,
                l1, yawSlider,
                l2, pitchSlider,
                l3, offsetSlider,
                cutButton);
        crossSectionControls.setStyle("-fx-padding: 15; -fx-background-color: #383838; -fx-background-radius: 5;");

        Button aiButton = new Button("Genereaza Vas (AI)");
        aiButton.setStyle("-fx-background-color: #8a2be2; -fx-text-fill: white; -fx-font-weight: bold; -fx-cursor: hand;");
        aiButton.setMaxWidth(Double.MAX_VALUE);
        aiButton.setOnAction(e -> {
            if (!SessionDatabase.hasSection(objectId)) {
                Alert err = new Alert(Alert.AlertType.ERROR);
                err.setTitle("Sectiune lipsa");
                err.setHeaderText("Nu ai salvat nicio sectiune!");
                err.setContentText("Te rog sa decupezi si sa salvezi sectiunea inainte de a genera vasul.");
                err.showAndWait();
                return;
            }
            simulateAIRestoration(Set.of(objectId));
        });

        VBox aiControls = new VBox(8, aiButton);
        aiControls.setStyle("-fx-padding: 15; -fx-background-color: #383838; -fx-background-radius: 5;");

        Button classifyButton = new Button("Clasifica ciob (Python)");
        classifyButton.setStyle("-fx-background-color: #e07b00; -fx-text-fill: white; -fx-font-weight: bold; -fx-cursor: hand;");
        classifyButton.setMaxWidth(Double.MAX_VALUE);

        TextArea classifyResultArea = new TextArea("Apasa butonul pentru a rula analiza sherdtool.py pe acest ciob.");
        classifyResultArea.setEditable(false);
        classifyResultArea.setWrapText(true);
        classifyResultArea.setPrefRowCount(9);
        classifyResultArea.setStyle("-fx-control-inner-background: #2b2b2b; -fx-text-fill: #dddddd; -fx-font-family: monospace;");

        NumberAxis profileXAxis = new NumberAxis();
        profileXAxis.setLabel("Raza (r)");
        NumberAxis profileYAxis = new NumberAxis();
        profileYAxis.setLabel("Inaltime (h)");
        LineChart<Number, Number> profileChart = new LineChart<>(profileXAxis, profileYAxis);
        profileChart.setTitle("Profil ciob (sectiune r-h a peretelui)");
        profileChart.setCreateSymbols(false);
        profileChart.setLegendVisible(false);
        profileChart.setPrefHeight(220);
        profileChart.setAnimated(false);
        profileChart.setVisible(false);
        profileChart.setManaged(false);

        Label profileStatusLabel = new Label();
        profileStatusLabel.setStyle("-fx-text-fill: #aaaaaa; -fx-font-style: italic;");
        profileStatusLabel.setWrapText(true);
        profileStatusLabel.setVisible(false);
        profileStatusLabel.setManaged(false);

        classifyButton.setOnAction(e -> {
            classifyButton.setDisable(true);

            // Faza 1 (instant): clasificare 100% Java, fara sa astepte Python.
            float[] positions = finalTarget.getMesh().getPositions();
            int[] indices = finalTarget.getMesh().getIndices();
            CurvatureClassifier.Result curvatureResult = CurvatureClassifier.classify(positions, indices);
            CurvatureClassifier.VesselAxisEstimate javaAxis = curvatureResult.exteriorAxisEstimate;
            GhostVesselGenerator.VesselProfile javaProfile = GhostVesselGenerator.extractProfile(finalTarget, javaAxis);
            SherdShapeClassifier.Result shape = SherdShapeClassifier.classify(positions, indices, javaAxis, javaProfile);

            classifyButton.setText("Se completeaza cu Python...");
            SherdAnalysisMerger.MergedResult provisional = SherdAnalysisMerger.pendingFromJavaOnly(
                    new File(meshSourcePath).getName(), positions.length / 3, indices.length / 3, javaAxis, shape, javaProfile);
            classifyResultArea.setText(formatMergedClassification(provisional)
                    + "\n\n(se ruleaza sherdtool.py pentru date suplimentare, poate dura cateva zeci de secunde...)");
            updateProfileChart(profileChart, profileStatusLabel, javaProfile.heights, javaProfile.radii, true);

            // Faza 2 (in fundal): sherdtool.py, apoi combinam cu ce a calculat deja Java.
            Task<SherdPythonAnalyzer.SherdAnalysisResult> classifyTask = new Task<>() {
                @Override
                protected SherdPythonAnalyzer.SherdAnalysisResult call() throws Exception {
                    return SherdPythonAnalyzer.analyze(java.nio.file.Path.of(meshSourcePath));
                }
            };
            classifyTask.setOnSucceeded(ev -> {
                classifyButton.setText("Clasifica ciob (Python)");
                classifyButton.setDisable(false);
                SherdPythonAnalyzer.SherdAnalysisResult pythonResult = classifyTask.getValue();
                SherdAnalysisMerger.MergedResult merged =
                        SherdAnalysisMerger.merge(pythonResult, javaAxis, shape, javaProfile);
                classifyResultArea.setText(formatMergedClassification(merged));
                updateProfileChart(profileChart, profileStatusLabel, merged.profileHeights, merged.profileRadii, merged.hasProfile());
            });
            classifyTask.setOnFailed(ev -> {
                classifyButton.setText("Clasifica ciob (Python)");
                classifyButton.setDisable(false);
                Throwable ex = classifyTask.getException();
                classifyResultArea.setText(formatMergedClassification(provisional)
                        + "\n\n(sherdtool.py a esuat, se afiseaza doar analiza locala Java: "
                        + (ex != null ? ex.getMessage() : "eroare necunoscuta") + ")");
                // profilul Java ramane deja afisat din faza 1 - nu il stergem la esecul Python.
            });

            Thread classifyThread = new Thread(classifyTask);
            classifyThread.setDaemon(true);
            classifyThread.start();
        });

        VBox classifyControls = new VBox(8, classifyButton, classifyResultArea, profileChart, profileStatusLabel);
        classifyControls.setStyle("-fx-padding: 15; -fx-background-color: #383838; -fx-background-radius: 5;");

        VBox controls = new VBox(15, infoBox, curvatureControls, crossSectionControls, aiControls, classifyControls);
        controls.setStyle("-fx-padding: 15; -fx-background-color: #2b2b2b;");

        ScrollPane scrollPane = new ScrollPane(controls);
        scrollPane.setFitToWidth(true);
        scrollPane.setStyle("-fx-background: #2b2b2b; -fx-border-color: #2b2b2b;");

        VBox root = new VBox(imageView, scrollPane);
        root.setStyle("-fx-background-color: #2b2b2b;");

        Thread objectRenderThread = new Thread(objectRenderer);
        objectRenderThread.setDaemon(true);
        objectRenderThread.start();

        Stage objectStage = new Stage();
        objectStage.setTitle("Analiza Obiect #" + objectId);

        Scene scene = new Scene(root, viewSize, 850);
        objectStage.setScene(scene);

        Rectangle2D screenBounds = Screen.getPrimary().getVisualBounds();
        if (850 > screenBounds.getHeight()) {
            objectStage.setHeight(screenBounds.getHeight() - 40);
        }

        objectStage.setOnCloseRequest(e -> {
            objectRenderThread.interrupt();
            openObjectWindows.remove(objectId);
            if (currentRenderer != null) {
                currentRenderer.clearSelection();
            }
        });
        objectStage.show();

        openObjectWindows.put(objectId, objectStage);
    }

    private void openTopViewPreview(int objectId, WritableImage image) {
        ImageView previewView = new ImageView(image);
        previewView.setFitWidth(400);
        previewView.setFitHeight(400);
        previewView.setPreserveRatio(true);

        Stage previewStage = new Stage();
        previewStage.setTitle("Previzualizare Sectiune");

        Button saveToDbButton = new Button("Salveaza pentru AI");
        saveToDbButton.setStyle("-fx-background-color: #0078D7; -fx-text-fill: white; -fx-font-weight: bold;");
        saveToDbButton.setOnAction(e -> {
            SessionDatabase.saveSection(objectId, image);
            previewStage.close();
        });

        Button saveButton = new Button("Salveaza pe disc");
        saveButton.setStyle("-fx-background-color: #28a745; -fx-text-fill: white; -fx-font-weight: bold; -fx-cursor: hand;");
        saveButton.setOnAction(e -> {
            FileChooser fileChooser = new FileChooser();
            fileChooser.setTitle("Salveaza imaginea sectiunii");
            fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Imagine PNG", "*.png"));
            fileChooser.setInitialFileName("sectiune.png");
            File file = fileChooser.showSaveDialog(previewStage);
            if (file != null) {
                try {
                    ImageExporter.savePng(image, file);
                    previewStage.close();
                } catch (IOException ex) {
                    ex.printStackTrace();
                }
            }
        });

        Button discardButton = new Button("Renunta");
        discardButton.setStyle("-fx-background-color: #d9534f; -fx-text-fill: white; -fx-font-weight: bold; -fx-cursor: hand;");
        discardButton.setOnAction(e -> previewStage.close());

        HBox buttons = new HBox(10, saveToDbButton, discardButton);
        buttons.setAlignment(Pos.CENTER);
        buttons.setStyle("-fx-padding: 10;");

        VBox root = new VBox(previewView, buttons);
        root.setStyle("-fx-background-color: #2b2b2b;");
        previewStage.setScene(new Scene(root, 420, 460));
        previewStage.show();
    }
}