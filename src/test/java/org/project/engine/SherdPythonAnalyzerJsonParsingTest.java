package org.project.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

// Testeaza SherdPythonAnalyzer.parseJsonFloatArray - extragerea profile_h/profile_r din
// <nume>_fit.json scris de sherdtool.py. Deterministic, fara subproces Python: profilul
// e adesea gol pe cioburi reale (fitul de axa "auto" esueaza des - vezi hasProfile()),
// deci un test bazat pe un JSON literal e mai de incredere decat unul care asteapta ca
// un mesh anume sa produca profil nevid.
class SherdPythonAnalyzerJsonParsingTest
{
    @Test
    void parsesMultiElementArrayFormattedWithIndentAndNewlines()
    {
        // Formatul exact scris de json.dump(..., indent=2): un numar pe linie.
        String json = "{\n  \"profile_h\": [\n    -5.156449715025822,\n    -4.744693554508581,\n    6.7844789399741785\n  ],\n  \"profile_r\": [\n    4.758482074424721,\n    4.880360719345992,\n    5.11638885440863\n  ]\n}";

        float[] h = SherdPythonAnalyzer.parseJsonFloatArray(json, "profile_h");
        float[] r = SherdPythonAnalyzer.parseJsonFloatArray(json, "profile_r");

        assertArrayEquals(new float[]{-5.156449715025822f, -4.744693554508581f, 6.7844789399741785f}, h, 1e-4f);
        assertArrayEquals(new float[]{4.758482074424721f, 4.880360719345992f, 5.11638885440863f}, r, 1e-4f);
    }

    @Test
    void emptyArrayReturnsEmptyFloatArray()
    {
        String json = "{\"profile_h\": [], \"profile_r\": []}";

        assertEquals(0, SherdPythonAnalyzer.parseJsonFloatArray(json, "profile_h").length);
        assertEquals(0, SherdPythonAnalyzer.parseJsonFloatArray(json, "profile_r").length);
    }

    @Test
    void missingKeyReturnsEmptyFloatArray()
    {
        String json = "{\"axis_dir\": [0.0, 1.0, 0.0]}";

        assertEquals(0, SherdPythonAnalyzer.parseJsonFloatArray(json, "profile_h").length);
    }

    @Test
    void singleElementArrayParsesCorrectly()
    {
        String json = "{\"profile_h\": [3.5]}";

        assertArrayEquals(new float[]{3.5f}, SherdPythonAnalyzer.parseJsonFloatArray(json, "profile_h"), 1e-6f);
    }

    @Test
    void windowsCrLfLineEndingsInsideTheArrayAreHandled()
    {
        // Python scrie fisierul cu open(path, "w") - mod text, deci pe Windows fiecare \n
        // din json.dump(indent=2) devine \r\n (traducere automata de newline-uri). Acelasi
        // fel de bug ca la CSV (vezi SherdPythonAnalyzer.parseCsv) - trebuie verificat separat.
        String json = "{\r\n  \"profile_h\": [\r\n    1.5,\r\n    2.5,\r\n    3.5\r\n  ]\r\n}";

        assertArrayEquals(new float[]{1.5f, 2.5f, 3.5f}, SherdPythonAnalyzer.parseJsonFloatArray(json, "profile_h"), 1e-6f);
    }

    @Test
    void scientificNotationNumbersParseCorrectly()
    {
        // json.dump foloseste repr() pt float-uri Python - numere foarte mici/mari ies in
        // notatie stiintifica (1e-05, -4.2e+10 etc.), nu doar zecimala simpla.
        String json = "{\"profile_h\": [1.23e-05, -4.2e+10, 6E-3]}";

        assertArrayEquals(new float[]{1.23e-05f, -4.2e10f, 6e-3f}, SherdPythonAnalyzer.parseJsonFloatArray(json, "profile_h"), 1e-6f);
    }

    @Test
    void compactJsonWithoutIndentOrSpacesStillParses()
    {
        // Formatul nu depinde de indent=2 - json.dump fara spatii intre chei/valori (sau un
        // fisier editat manual) trebuie sa mearga la fel.
        String json = "{\"profile_h\":[1.0,2.0,3.0],\"profile_r\":[4.0,5.0,6.0]}";

        assertArrayEquals(new float[]{1.0f, 2.0f, 3.0f}, SherdPythonAnalyzer.parseJsonFloatArray(json, "profile_h"), 1e-6f);
        assertArrayEquals(new float[]{4.0f, 5.0f, 6.0f}, SherdPythonAnalyzer.parseJsonFloatArray(json, "profile_r"), 1e-6f);
    }

    @Test
    void similarlyPrefixedKeyDoesNotFalsePositiveMatch()
    {
        // Cheia ceruta e "profile_h" - un camp viitor "profile_height" (cu acelasi prefix)
        // nu trebuie confundat cu ea, altfel am extrage array-ul gresit. Regexul cere
        // ghilimeaua de inchidere imediat dupa cheie, deci "profile_h" nu se potriveste
        // in interiorul lui "profile_height".
        String json = "{\"profile_height\": [99.0, 98.0]}";

        assertEquals(0, SherdPythonAnalyzer.parseJsonFloatArray(json, "profile_h").length);
    }

    @Test
    void nonFiniteTokensParseAsNanOrInfinityWithoutCrashing()
    {
        // json.dump nu foloseste allow_nan=False in sherdtool.py, deci daca prof_h/prof_r
        // ar contine vreodata NaN/Infinity (fit degenerat pe o felie), Python le scrie ca
        // token-uri literale NaN/Infinity/-Infinity (JSON invalid strict, dar valid pt
        // modulul json din Python). Float.parseFloat din Java le accepta pe toate trei -
        // verificam ca nu crapa si ca rezultatul e exact ce ne asteptam (NaN/Infinity),
        // nu 0 din fallback-ul de eroare.
        String json = "{\"profile_h\": [NaN, Infinity, -Infinity, 1.0]}";

        float[] result = SherdPythonAnalyzer.parseJsonFloatArray(json, "profile_h");

        assertEquals(4, result.length);
        assertEquals(true, Float.isNaN(result[0]));
        assertEquals(Float.POSITIVE_INFINITY, result[1]);
        assertEquals(Float.NEGATIVE_INFINITY, result[2]);
        assertEquals(1.0f, result[3]);
    }

    @Test
    void trailingCommaSilentlyDropsTheEmptyTrailingSlotInsteadOfCrashing()
    {
        // JSON valid nu are virgula finala, dar daca fisierul ar fi trunchiat/corupt, apare
        // un "," inainte de "]". String.split(",") fara limita explicita elimina automat
        // token-urile goale FINALE (comportamentul default din Java), deci rezultatul are
        // 2 elemente, nu 3 cu un 0f in plus - documentam explicit asta, ca sa nu presupunem
        // gresit ca exista un fallback la 0 pt sloturi goale finale.
        String json = "{\"profile_h\": [1.0, 2.0,]}";

        float[] result = SherdPythonAnalyzer.parseJsonFloatArray(json, "profile_h");

        assertArrayEquals(new float[]{1.0f, 2.0f}, result, 1e-6f);
    }

    @Test
    void internalEmptyTokenBetweenTwoCommasFallsBackToZero()
    {
        // Spre deosebire de virgula FINALA (eliminata de split), un token gol la MIJLOC
        // (doua virgule consecutive) chiar produce un slot - acolo se vede fallback-ul 0f
        // din catch-ul NumberFormatException.
        String json = "{\"profile_h\": [1.0,,3.0]}";

        float[] result = SherdPythonAnalyzer.parseJsonFloatArray(json, "profile_h");

        assertArrayEquals(new float[]{1.0f, 0.0f, 3.0f}, result, 1e-6f);
    }

    @Test
    void firstOccurrenceWinsWhenKeyAppearsMoreThanOnce()
    {
        // Documenteaza comportamentul curent (nu neaparat "corect" din punct de vedere JSON,
        // dar previzibil): Matcher.find() ia prima potrivire. sherdtool.py nu scrie niciodata
        // aceeasi cheie de doua ori, deci nu e un caz real, dar merita fixat printr-un test
        // explicit ca sa nu se schimbe tacit la un refactor al regexului.
        String json = "{\"profile_h\": [1.0, 2.0], \"other\": 5, \"profile_h\": [9.0]}";

        assertArrayEquals(new float[]{1.0f, 2.0f}, SherdPythonAnalyzer.parseJsonFloatArray(json, "profile_h"), 1e-6f);
    }

    @Test
    void whitespaceOnlyArrayContentIsTreatedAsEmpty()
    {
        // Un array "gol" dar cu spatii/linii goale intre paranteze (posibil daca cineva
        // editeaza manual fisierul, sau un pretty-printer diferit) - inner.trim() trebuie
        // sa reduca asta la string gol, nu la un token unic care pica la parseFloat.
        String json = "{\"profile_h\": [   \n   \n  ]}";

        assertEquals(0, SherdPythonAnalyzer.parseJsonFloatArray(json, "profile_h").length);
    }

    @Test
    void tabsBetweenElementsAreTrimmedLikeSpaces()
    {
        String json = "{\"profile_h\": [1.0,\t2.0,\t\t3.0]}";

        assertArrayEquals(new float[]{1.0f, 2.0f, 3.0f}, SherdPythonAnalyzer.parseJsonFloatArray(json, "profile_h"), 1e-6f);
    }

    @Test
    void emptyJsonObjectReturnsEmptyArrayWithoutCrashing()
    {
        assertEquals(0, SherdPythonAnalyzer.parseJsonFloatArray("{}", "profile_h").length);
    }

    @Test
    void nullValueInsteadOfArrayReturnsEmptyArray()
    {
        // Daca prof_h ar fi None in Python si json.dump l-ar scrie ca "profile_h": null
        // (in loc de []) - nu e formatul actual din sherdtool.py (care foloseste mereu
        // .tolist() sau []), dar codul trebuie sa nu crape daca schema se schimba usor:
        // regexul cere "[" imediat dupa cheie, deci "null" pur si simplu nu se potriveste.
        String json = "{\"profile_h\": null}";

        assertEquals(0, SherdPythonAnalyzer.parseJsonFloatArray(json, "profile_h").length);
    }

    @Test
    void siblingArrayValuesDoNotBleedBetweenKeys()
    {
        // profile_r apare INAINTE de profile_h in text (ordine inversa fata de fisierele
        // reale) - verificam ca fiecare cheie isi ia strict propriul array, nu vecinul.
        String json = "{\"profile_r\": [10.0, 20.0], \"profile_h\": [1.0, 2.0, 3.0]}";

        assertArrayEquals(new float[]{1.0f, 2.0f, 3.0f}, SherdPythonAnalyzer.parseJsonFloatArray(json, "profile_h"), 1e-6f);
        assertArrayEquals(new float[]{10.0f, 20.0f}, SherdPythonAnalyzer.parseJsonFloatArray(json, "profile_r"), 1e-6f);
    }

    @Test
    void sameKeyNestedInsideAnotherObjectIsMatchedTextuallyNotStructurally()
    {
        // Limitare cunoscuta si documentata intentionat: parserul e un regex, nu un parser
        // JSON real, deci nu intelege scope/nesting. Daca acelasi nume de cheie ar aparea
        // intr-un sub-obiect ÎNAINTE de cheia de top-level (nu e cazul in schema actuala,
        // fixa, a lui sherdtool.py), s-ar potrivi gresit cu ce gaseste primul in text.
        // Testul fixeaza acest comportament ca sa nu surprinda pe cineva la un refactor.
        String json = "{\"meta\": {\"profile_h\": [999.0]}, \"profile_h\": [1.0, 2.0]}";

        assertArrayEquals(new float[]{999.0f}, SherdPythonAnalyzer.parseJsonFloatArray(json, "profile_h"), 1e-6f);
    }

    @Test
    void largeArrayParsesFullyAndQuickly()
    {
        // sherdtool.py foloseste implicit n_slices=30, deci profilele reale sunt mici -
        // dar merita verificat ca nu exista vreo limita ascunsa (ex. un split cu limita de
        // capacitate) daca cineva mareste rezolutia feliilor.
        StringBuilder sb = new StringBuilder("{\"profile_h\": [");
        int n = 5000;
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(',');
            sb.append(i).append(".5");
        }
        sb.append("]}");

        float[] result = SherdPythonAnalyzer.parseJsonFloatArray(sb.toString(), "profile_h");

        assertEquals(n, result.length);
        assertEquals(0.5f, result[0]);
        assertEquals(4999.5f, result[n - 1]);
    }

    @Test
    void hasProfileIsFalseForMismatchedOrShortArraysAndTrueOtherwise()
    {
        // hasProfile() e contractul pe care se bazeaza UI-ul ca sa decida daca deseneaza
        // graficul sau afiseaza "profil indisponibil" - verificam direct pragurile lui.
        var empty = new SherdPythonAnalyzer.SherdAnalysisResult(
                "n", "cm", 0, 0, "", "", "", 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, "good", 0.0, "unknown", 0.0, 0.0, "",
                new float[0], new float[0]);
        var mismatched = new SherdPythonAnalyzer.SherdAnalysisResult(
                "n", "cm", 0, 0, "", "", "", 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, "good", 0.0, "unknown", 0.0, 0.0, "",
                new float[]{1f, 2f, 3f}, new float[]{1f, 2f});
        var valid = new SherdPythonAnalyzer.SherdAnalysisResult(
                "n", "cm", 0, 0, "", "", "", 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, "good", 0.0, "unknown", 0.0, 0.0, "",
                new float[]{1f, 2f, 3f}, new float[]{4f, 5f, 6f});

        assertEquals(false, empty.hasProfile());
        assertEquals(false, mismatched.hasProfile());
        assertEquals(true, valid.hasProfile());
    }

    @Test
    void hasProfileBoundaryIsExactlyTwoPoints()
    {
        // Pragul e ">=2", nu ">2" - un singur punct nu poate desena o linie, doua da.
        // Verificam exact granita, nu doar cazuri clar peste/sub ea.
        var onePoint = new SherdPythonAnalyzer.SherdAnalysisResult(
                "n", "cm", 0, 0, "", "", "", 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, "good", 0.0, "unknown", 0.0, 0.0, "",
                new float[]{1f}, new float[]{1f});
        var twoPoints = new SherdPythonAnalyzer.SherdAnalysisResult(
                "n", "cm", 0, 0, "", "", "", 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, "good", 0.0, "unknown", 0.0, 0.0, "",
                new float[]{1f, 2f}, new float[]{1f, 2f});

        assertEquals(false, onePoint.hasProfile());
        assertEquals(true, twoPoints.hasProfile());
    }
}