package org.project.engine;

import javafx.scene.image.WritableImage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionDatabaseTest
{
    @AfterEach
    void clearDatabase()
    {
        SessionDatabase.clear();
    }

    @Test
    void freshObjectHasNoSectionByDefault()
    {
        assertFalse(SessionDatabase.hasSection(1));
        assertNull(SessionDatabase.getSection(1));
    }

    @Test
    void savingSectionMakesHasSectionTrue()
    {
        WritableImage image = new WritableImage(4, 4);

        SessionDatabase.saveSection(1, image);

        assertTrue(SessionDatabase.hasSection(1));
    }

    @Test
    void getSectionReturnsExactImageThatWasSaved()
    {
        WritableImage image = new WritableImage(4, 4);

        SessionDatabase.saveSection(1, image);

        assertSame(image, SessionDatabase.getSection(1));
    }

    @Test
    void getSectionForUnknownIdReturnsNullWithoutThrowing()
    {
        SessionDatabase.saveSection(1, new WritableImage(2, 2));

        assertNull(SessionDatabase.getSection(999));
    }

    @Test
    void savingSectionOverwritesPreviousImageForSameId()
    {
        WritableImage firstImage = new WritableImage(4, 4);
        WritableImage secondImage = new WritableImage(8, 8);

        SessionDatabase.saveSection(1, firstImage);
        SessionDatabase.saveSection(1, secondImage);

        assertSame(secondImage, SessionDatabase.getSection(1));
    }

    @Test
    void sectionsForDifferentObjectsAreTrackedIndependently()
    {
        WritableImage imageForFirst = new WritableImage(4, 4);

        SessionDatabase.saveSection(1, imageForFirst);

        assertTrue(SessionDatabase.hasSection(1));
        assertFalse(SessionDatabase.hasSection(2));
        assertNull(SessionDatabase.getSection(2));
    }

    @Test
    void clearRemovesAllSavedSections()
    {
        SessionDatabase.saveSection(1, new WritableImage(4, 4));
        SessionDatabase.saveSection(2, new WritableImage(4, 4));

        SessionDatabase.clear();

        assertFalse(SessionDatabase.hasSection(1));
        assertFalse(SessionDatabase.hasSection(2));
        assertNull(SessionDatabase.getSection(1));
        assertNull(SessionDatabase.getSection(2));
    }

    @Test
    void clearOnAlreadyEmptyDatabaseDoesNotThrow()
    {
        SessionDatabase.clear();
        SessionDatabase.clear();

        assertFalse(SessionDatabase.hasSection(1));
    }

    @Test
    void concurrentSavesForDistinctIdsAreAllRetained() throws InterruptedException
    {
        int fragmentCount = 50;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch latch = new CountDownLatch(fragmentCount);

        for (int i = 0; i < fragmentCount; i++) {
            int objectId = i;
            pool.submit(() -> {
                try {
                    SessionDatabase.saveSection(objectId, new WritableImage(2, 2));
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        pool.shutdown();

        for (int i = 0; i < fragmentCount; i++) {
            assertTrue(SessionDatabase.hasSection(i), "Fragment #" + i + " ar trebui sa aiba sectiune salvata");
        }
    }

    @Test
    void generateVasFlowOnlyAllowsSelectionWhereEveryFragmentHasASection()
    {
        List<String> fragmentPaths = List.of(
                "GB_001.obj", "GB_002.obj", "GB_003.obj", "GB_004.obj", "GB_005.obj");
        int firstFragmentId = 1;

        for (int i = 0; i < fragmentPaths.size(); i++) {
            int objectId = firstFragmentId + i;
            SceneObject fragment = new SceneObject(objectId, null, fragmentPaths.get(i));
            assertEquals(objectId, fragment.getId());

            // toate fragmentele in afara de ultimul primesc o sectiune salvata
            if (i < fragmentPaths.size() - 1) {
                SessionDatabase.saveSection(objectId, new WritableImage(4, 4));
            }
        }

        int lastFragmentId = firstFragmentId + fragmentPaths.size() - 1;

        List<Integer> missingSections = missingSectionsAmong(firstFragmentId, lastFragmentId);

        assertEquals(List.of(lastFragmentId), missingSections);

        SessionDatabase.saveSection(lastFragmentId, new WritableImage(4, 4));

        assertTrue(missingSectionsAmong(firstFragmentId, lastFragmentId).isEmpty());
    }

    private static List<Integer> missingSectionsAmong(int firstId, int lastId)
    {
        return java.util.stream.IntStream.rangeClosed(firstId, lastId)
                .filter(id -> !SessionDatabase.hasSection(id))
                .boxed()
                .toList();
    }

    @Test
    void savingNullImageThrowsInsteadOfSilentlyCorruptingState()
    {
        // SessionDatabase e sustinut de un ConcurrentHashMap, care nu accepta
        // valori null - o chemare accidentala cu o imagine null
        // (ex. capturePreviewImage ar intoarce null pe un fail silentios) trebuie
        // sa explodeze zgomotos aici, nu sa lase o intrare "fantoma" greu de
        // depanat mai tarziu la getSection().
        assertThrows(NullPointerException.class, () -> SessionDatabase.saveSection(1, null));
        assertFalse(SessionDatabase.hasSection(1));
    }

    @Test
    void deletingAnObjectFromTheSceneAlsoRemovesItsSavedSection()
    {
        // OpenGLRenderer.processPendingDeletions() cheama SessionDatabase.removeSection()
        // pentru id-ul sters, exact ca sa evite scurgerea documentata anterior:
        // o sectiune 2D salvata pentru un fragment ramas fara obiect in scena.
        OpenGLRenderer renderer = new OpenGLRenderer(null);
        SceneObject fragment = new SceneObject(1, null, "GB_001.obj");
        renderer.objects.add(fragment);
        SessionDatabase.saveSection(1, new WritableImage(4, 4));

        renderer.queueDeleteObject(1);
        renderer.processPendingDeletions();

        assertTrue(renderer.objects.isEmpty());
        assertFalse(SessionDatabase.hasSection(1));
        assertNull(SessionDatabase.getSection(1));
    }

    @Test
    void deletingAnObjectWithNoSavedSectionDoesNotThrow()
    {
        OpenGLRenderer renderer = new OpenGLRenderer(null);
        SceneObject fragment = new SceneObject(1, null, "GB_002.obj");
        renderer.objects.add(fragment);

        renderer.queueDeleteObject(1);

        assertDoesNotThrow(renderer::processPendingDeletions);
        assertFalse(SessionDatabase.hasSection(1));
    }

    @Test
    void removeSectionForAnIdThatWasNeverSavedIsANoOp()
    {
        assertDoesNotThrow(() -> SessionDatabase.removeSection(42));
        assertFalse(SessionDatabase.hasSection(42));
    }

    @Test
    void deletingOneFragmentDoesNotAffectAnotherFragmentsSavedSection()
    {
        OpenGLRenderer renderer = new OpenGLRenderer(null);
        renderer.objects.add(new SceneObject(1, null, "GB_001.obj"));
        renderer.objects.add(new SceneObject(2, null, "GB_002.obj"));
        WritableImage keptImage = new WritableImage(4, 4);
        SessionDatabase.saveSection(1, new WritableImage(4, 4));
        SessionDatabase.saveSection(2, keptImage);

        renderer.queueDeleteObject(1);
        renderer.processPendingDeletions();

        assertFalse(SessionDatabase.hasSection(1));
        assertTrue(SessionDatabase.hasSection(2));
        assertSame(keptImage, SessionDatabase.getSection(2));
    }
}
