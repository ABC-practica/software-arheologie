package org.project.engine;

import javafx.scene.image.WritableImage;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class SessionDatabase
{
    private static final Map<Integer, WritableImage> crossSections = new ConcurrentHashMap<>();

    public static void saveSection(int objectId, WritableImage image) {
        crossSections.put(objectId, image);
    }

    public static boolean hasSection(int objectId) {
        return crossSections.containsKey(objectId);
    }

    public static WritableImage getSection(int objectId) {
        return crossSections.get(objectId);
    }

    public static void removeSection(int objectId) {
        crossSections.remove(objectId);
    }

    public static void clear() {
        crossSections.clear();
    }
}