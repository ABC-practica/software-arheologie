package org.project.engine;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

// Scenariul care ne intereseaza aici: fereastra dedicata a unui fragment ramane
// deschisa, dar intre timp fragmentul e sters din scena principala (buton
// "Delete Object" / "Sterge Selectia" din popup-ul de selectie, pe alt fir -
// FX thread - fata de firul de randare care proceseaza coada de stergeri).
// Vrem sa fim siguri ca acest flux nu arunca exceptii "degeaba" si ca starea
// (objects, selectedObjectIds) ramane consistenta indiferent de ordine.
//
// processPendingDeletions() a fost extras din run() special pentru asta - restul
// din run() are nevoie de context OpenGL real (fereastra GLFW ascunsa), pe cand
// stergerea in sine e logica pura pe liste/seturi, deci testabila direct.
//
// Testele NU inregistreaza un onSelectionChanged callback: in productie acel
// callback declanseaza Platform.runLater, care are nevoie de FX toolkit
// initializat. O fereastra deschisa dar fara nimeni ascultand notificari nu ar
// trebui sa se comporte diferit fata de starea interna (objects/selectedObjectIds) -
// asta verificam.
class OpenGLRendererDeletionTest
{
    @Test
    void deletingNonExistentIdIsSilentNoOp()
    {
        OpenGLRenderer renderer = new OpenGLRenderer(null);
        SceneObject object = new SceneObject(1, null, "test");
        renderer.objects.add(object);

        renderer.queueDeleteObject(999);

        assertDoesNotThrow(renderer::processPendingDeletions);
        assertEquals(1, renderer.objects.size());
    }

    @Test
    void deletingUnselectedObjectRemovesOnlyThatObject()
    {
        OpenGLRenderer renderer = new OpenGLRenderer(null);
        SceneObject first = new SceneObject(1, null, "a");
        SceneObject second = new SceneObject(2, null, "b");
        renderer.objects.add(first);
        renderer.objects.add(second);

        renderer.queueDeleteObject(1);
        renderer.processPendingDeletions();

        assertEquals(1, renderer.objects.size());
        assertEquals(2, renderer.objects.get(0).getId());
    }

    @Test
    void deletingSelectedObjectAlsoRemovesItFromSelection()
    {
        OpenGLRenderer renderer = new OpenGLRenderer(null);
        SceneObject object = new SceneObject(1, null, "test");
        renderer.objects.add(object);
        renderer.getSelectedObjectIds().add(1);

        renderer.queueDeleteObject(1);
        renderer.processPendingDeletions();

        assertTrue(renderer.objects.isEmpty());
        assertFalse(renderer.getSelectedObjectIds().contains(1));
    }

    @Test
    void deletingOneOfSeveralSelectedObjectsLeavesTheRestSelected()
    {
        OpenGLRenderer renderer = new OpenGLRenderer(null);
        SceneObject first = new SceneObject(1, null, "a");
        SceneObject second = new SceneObject(2, null, "b");
        SceneObject third = new SceneObject(3, null, "c");
        renderer.objects.add(first);
        renderer.objects.add(second);
        renderer.objects.add(third);
        renderer.getSelectedObjectIds().add(1);
        renderer.getSelectedObjectIds().add(2);
        renderer.getSelectedObjectIds().add(3);

        renderer.queueDeleteObject(2);
        renderer.processPendingDeletions();

        assertEquals(2, renderer.objects.size());
        assertTrue(renderer.getSelectedObjectIds().contains(1));
        assertTrue(renderer.getSelectedObjectIds().contains(3));
        assertFalse(renderer.getSelectedObjectIds().contains(2));
    }

    @Test
    void deletingTheSameIdTwiceIsIdempotent()
    {
        // Ex: userul apasa "Delete Object" de doua ori repede (dublu-click,
        // sau popup-ul ramane deschis din greseala) - a doua stergere trebuie
        // sa fie un no-op curat, nu o eroare.
        OpenGLRenderer renderer = new OpenGLRenderer(null);
        SceneObject object = new SceneObject(1, null, "test");
        renderer.objects.add(object);

        renderer.queueDeleteObject(1);
        renderer.queueDeleteObject(1);

        assertDoesNotThrow(renderer::processPendingDeletions);
        assertTrue(renderer.objects.isEmpty());
    }

    @Test
    void deletingASectionBoxLeavesOrphanedChildInObjectsListButTransformsSkipIt()
    {
        // Copiii unui sectionBox (fragmentele dintr-o sectiune arheologica) nu
        // sunt sterse in cascada cand sectionBox-ul parinte e sters - raman in
        // objects cu un parinte "zombie" (scos din scena, dar tinut in viata de
        // referinta child.parent). Nu ar trebui sa fie o eroare, dar merita
        // documentat: move/rotate/scale ignora oricum obiectele cu parinte
        // (vezi "if (obj.parent != null) continue;"), deci raman inerte vizual.
        OpenGLRenderer renderer = new OpenGLRenderer(null);
        SceneObject sectionBox = new SceneObject(1, null, "SECTIUNE");
        SceneObject child = new SceneObject(2, null, "GB_001.obj");
        child.parent = sectionBox;
        renderer.objects.add(sectionBox);
        renderer.objects.add(child);
        renderer.getSelectedObjectIds().add(2);

        renderer.queueDeleteObject(1);
        renderer.processPendingDeletions();

        assertEquals(1, renderer.objects.size());
        assertEquals(2, renderer.objects.get(0).getId());
        assertTrue(renderer.getSelectedObjectIds().contains(2),
                "copilul ramane selectat - stergerea parintelui nu il deselecteaza automat");

        assertDoesNotThrow(() -> renderer.moveSelectedObject(10f, 10f));
        assertEquals(0f, child.position.x, "copil cu parinte e ignorat de move/rotate/scale");
    }

    @Test
    void isSectionObjectIsFalseAfterTheObjectIsDeleted()
    {
        OpenGLRenderer renderer = new OpenGLRenderer(null);
        SceneObject sectionBox = new SceneObject(1, null, "SECTIUNE");
        sectionBox.isSectionBox = true;
        renderer.objects.add(sectionBox);
        assertTrue(renderer.isSectionObject(1));

        renderer.queueDeleteObject(1);
        renderer.processPendingDeletions();

        assertFalse(renderer.isSectionObject(1));
    }

    @Test
    void deletingWithoutASelectionCallbackRegisteredNeverThrows()
    {
        // Simuleaza exact "fereastra dedicata deschisa, dar userul nu a
        // interactionat cu popup-ul de selectie de pe scena principala" - adica
        // niciun listener nu asteapta notificari. Codul de productie nu ar
        // trebui sa presupuna ca onSelectionChanged e mereu setat.
        OpenGLRenderer renderer = new OpenGLRenderer(null);
        SceneObject object = new SceneObject(1, null, "test");
        renderer.objects.add(object);
        renderer.getSelectedObjectIds().add(1);

        assertDoesNotThrow(() -> {
            renderer.queueDeleteObject(1);
            renderer.processPendingDeletions();
        });
    }

    @Test
    void deletionsQueuedConcurrentlyFromMultipleThreadsAreAllProcessed()
    {
        // In productie, queueDeleteObject() e apelat de pe FX thread (click pe
        // buton) in timp ce firul de randare dreneaza coada la fiecare frame.
        // pendingDeletions e un ConcurrentLinkedQueue special pentru asta -
        // verificam ca "producatori" multipli concurenti nu pierd/dubleaza id-uri.
        OpenGLRenderer renderer = new OpenGLRenderer(null);
        int objectCount = 100;
        for (int i = 1; i <= objectCount; i++) {
            renderer.objects.add(new SceneObject(i, null, "obj" + i));
        }

        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch latch = new CountDownLatch(objectCount);
        for (int i = 1; i <= objectCount; i++) {
            int id = i;
            pool.submit(() -> {
                renderer.queueDeleteObject(id);
                latch.countDown();
            });
        }

        assertDoesNotThrow(() -> assertTrue(latch.await(5, TimeUnit.SECONDS)));
        pool.shutdown();

        renderer.processPendingDeletions();

        assertTrue(renderer.objects.isEmpty());
    }
}
