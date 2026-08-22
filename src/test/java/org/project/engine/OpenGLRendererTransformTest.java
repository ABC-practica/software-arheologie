package org.project.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

// move/rotate/scaleSelectedObject nu ating deloc GL - doar muta campuri pe
// SceneObject dintr-o lista simpla. Constructorul OpenGLRenderer(WritableImage)
// nu atinge fxImage decat in run(), asa ca putem trece null. SceneObject accepta
// un Mesh null fara probleme (nu-l foloseste in metodele astea) - Mesh real nu
// poate fi construit fara context GL, deci null e singura varianta testabila.
//
// selectedObjectIds nu are setter public (se populeaza doar din picking, in
// run()), dar getSelectedObjectIds() intoarce referinta directa catre Set-ul
// intern, deci il putem popula direct din test fara reflectie sau alt seam nou.
class OpenGLRendererTransformTest
{
    @Test
    void moveSelectedObjectUpdatesPositionScaledBySmallFactor()
    {
        OpenGLRenderer renderer = newRendererWithSelectedObject(1);
        SceneObject object = renderer.objects.get(0);

        renderer.moveSelectedObject(10f, 20f);

        assertEquals(0.1f, object.position.x, 1e-5f);
        assertEquals(0.2f, object.position.y, 1e-5f);
    }

    @Test
    void moveSelectedObjectDoesNothingWhenNoObjectIsSelected()
    {
        OpenGLRenderer renderer = new OpenGLRenderer(null);
        SceneObject object = new SceneObject(1, null, "test");
        renderer.objects.add(object);
        // selectedObjectId ramane -1 (valoarea implicita)

        renderer.moveSelectedObject(100f, 100f);

        assertEquals(0f, object.position.x);
        assertEquals(0f, object.position.y);
    }

    @Test
    void moveSelectedObjectClampsToPanningLimits()
    {
        OpenGLRenderer renderer = newRendererWithSelectedObject(1);
        SceneObject object = renderer.objects.get(0);

        renderer.moveSelectedObject(100000f, 100000f);
        assertEquals(2.7f, object.position.x, 1e-5f);
        assertEquals(2.0f, object.position.y, 1e-5f);

        renderer.moveSelectedObject(-200000f, -200000f);
        assertEquals(-2.7f, object.position.x, 1e-5f);
        assertEquals(-2.0f, object.position.y, 1e-5f);
    }

    @Test
    void rotateSelectedObjectUpdatesRotationWithSwappedNegatedAxes()
    {
        // atentie la mapare: deltaX afecteaza rotation.Y (nu X), deltaY afecteaza
        // rotation.X, si ambele SCAD (nu adauga) - usor de inversat din greseala.
        OpenGLRenderer renderer = newRendererWithSelectedObject(1);
        SceneObject object = renderer.objects.get(0);

        renderer.rotateSelectedObject(10f, 5f);

        assertEquals(-0.1f, object.rotation.y, 1e-5f);
        assertEquals(-0.05f, object.rotation.x, 1e-5f);
    }

    @Test
    void rotateSelectedObjectDoesNothingWhenNoObjectIsSelected()
    {
        OpenGLRenderer renderer = new OpenGLRenderer(null);
        SceneObject object = new SceneObject(1, null, "test");
        renderer.objects.add(object);

        renderer.rotateSelectedObject(10f, 5f);

        assertEquals(0f, object.rotation.x);
        assertEquals(0f, object.rotation.y);
    }

    @Test
    void scaleSelectedObjectAddsDelta()
    {
        OpenGLRenderer renderer = newRendererWithSelectedObject(1);
        SceneObject object = renderer.objects.get(0);
        object.scale = 1.0f;

        renderer.scaleSelectedObject(0.5f);

        assertEquals(1.5f, object.scale, 1e-5f);
    }

    @Test
    void scaleSelectedObjectClampsToMinimum()
    {
        OpenGLRenderer renderer = newRendererWithSelectedObject(1);
        SceneObject object = renderer.objects.get(0);
        object.scale = 0.1f;

        renderer.scaleSelectedObject(-0.5f);

        assertEquals(0.05f, object.scale, 1e-5f);
    }

    @Test
    void scaleSelectedObjectDoesNothingWhenNoObjectIsSelected()
    {
        OpenGLRenderer renderer = new OpenGLRenderer(null);
        SceneObject object = new SceneObject(1, null, "test");
        object.scale = 1.0f;
        renderer.objects.add(object);

        renderer.scaleSelectedObject(0.5f);

        assertEquals(1.0f, object.scale);
    }

    @Test
    void transformsOnlyAffectSelectedObjectAmongSeveral()
    {
        OpenGLRenderer renderer = new OpenGLRenderer(null);
        SceneObject first = new SceneObject(1, null, "a");
        SceneObject second = new SceneObject(2, null, "b");
        SceneObject third = new SceneObject(3, null, "c");
        renderer.objects.add(first);
        renderer.objects.add(second);
        renderer.objects.add(third);
        setSelectedObjectId(renderer, 2);

        renderer.moveSelectedObject(10f, 10f);
        renderer.rotateSelectedObject(10f, 10f);
        renderer.scaleSelectedObject(0.5f);

        assertEquals(0f, first.position.x);
        assertEquals(0f, third.position.x);
        assertEquals(0.1f, second.position.x, 1e-5f);
        assertEquals(1.5f, second.scale, 1e-5f);
    }

    @Test
    void staleSelectionNotPresentInListIsHandledGracefully()
    {
        // selectedObjectId indica un id care nu (mai) exista in lista de obiecte
        // (ex. obiectul a fost sters intre timp) - nu ar trebui sa crape, doar
        // sa nu gaseasca nimic de modificat.
        OpenGLRenderer renderer = new OpenGLRenderer(null);
        SceneObject object = new SceneObject(1, null, "test");
        renderer.objects.add(object);
        setSelectedObjectId(renderer, 999);

        renderer.moveSelectedObject(10f, 10f);
        renderer.rotateSelectedObject(10f, 10f);
        renderer.scaleSelectedObject(0.5f);

        assertEquals(0f, object.position.x);
        assertEquals(0f, object.rotation.x);
    }

    private static OpenGLRenderer newRendererWithSelectedObject(int objectId)
    {
        OpenGLRenderer renderer = new OpenGLRenderer(null);
        SceneObject object = new SceneObject(objectId, null, "test");
        renderer.objects.add(object);
        setSelectedObjectId(renderer, objectId);
        return renderer;
    }

    private static void setSelectedObjectId(OpenGLRenderer renderer, int objectId)
    {
        renderer.getSelectedObjectIds().add(objectId);
    }
}
