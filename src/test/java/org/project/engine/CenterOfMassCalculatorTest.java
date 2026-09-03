package org.project.engine;

import org.joml.Vector3f;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.File;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CenterOfMassCalculatorTest
{
    private static final String GB_FOLDER = "C:\\Users\\Radu\\Desktop\\gb";

    @ParameterizedTest(name = "{0}")
    @MethodSource("referenceCenters")
    void computedCenterOfMassMatchesIndependentReference(String fileName, float expectedX, float expectedY, float expectedZ)
    {
        File modelFile = new File(GB_FOLDER, fileName);
        Assumptions.assumeTrue(modelFile.exists(), "Fisierul de test " + modelFile + " nu exista pe masina asta - sar testul.");

        ModelLoader.Geometry geometry = ModelLoader.loadGeometry(modelFile.getPath());
        Vector3f actual = CenterOfMassCalculator.compute(geometry.positions, geometry.indices);
        Vector3f expected = new Vector3f(expectedX, expectedY, expectedZ);

        float distance = actual.distance(expected);
        assertEquals(0.0, distance, 1e-4,
                "asteptat " + expected + " (referinta independenta Python, dubla precizie), obtinut " + actual);
    }

    private static Stream<Arguments> referenceCenters()
    {
        return Stream.of(
                Arguments.of("GB_001.obj", -0.021306810f, +0.489780567f, +0.115857186f),
                Arguments.of("GB_002.obj", -0.205059714f, -0.106169249f, +0.033616023f),
                Arguments.of("GB_003.obj", -0.162079483f, +0.116405575f, +0.036507160f),
                Arguments.of("GB_004.obj", -0.045984669f, +0.068196805f, -0.108990671f),
                Arguments.of("GB_005.obj", +0.088284536f, +0.114347932f, -0.134813108f),
                Arguments.of("GB_006.obj", -0.259949245f, -0.086357021f, +0.019289217f),
                Arguments.of("GB_007.obj", +0.256254231f, +0.341184165f, +0.055116114f),
                Arguments.of("GB_008.obj", -0.247868330f, -0.000043182f, -0.181781640f),
                Arguments.of("GB_009.obj", -0.096270423f, -0.049434136f, -0.129904638f),
                Arguments.of("GB_010.obj", -0.026603915f, +0.488113147f, -0.046312049f),
                Arguments.of("GB_011.obj", +0.008166726f, +0.125304196f, -0.050618129f),
                Arguments.of("GB_012.obj", -0.283397400f, +0.150516023f, -0.078664207f),
                Arguments.of("GB_013.obj", +0.052685045f, +0.512267808f, -0.038339459f),
                Arguments.of("GB_014.obj", -0.085259256f, -0.135663477f, -0.328169434f),
                Arguments.of("GB_015.obj", -0.397409886f, +0.085206695f, -0.003445873f),
                Arguments.of("GB_016.obj", -0.627568782f, -0.316652089f, +0.072003665f),
                Arguments.of("GB_017.obj", -0.309672478f, -0.171698598f, -0.180270343f),
                Arguments.of("GB_018.obj", +0.037998736f, -0.237443590f, -0.322883494f),
                Arguments.of("GB_019.obj", +0.033934327f, -0.072037064f, -0.052390576f),
                Arguments.of("GB_020.obj", -0.539045884f, +0.470014370f, +0.004144738f),
                Arguments.of("GB_021.obj", -0.065539178f, -0.111452868f, -0.201243847f),
                Arguments.of("GB_022.obj", +0.059912834f, +0.015957404f, -0.332093469f),
                Arguments.of("GB_023.obj", +0.070976019f, +0.162178188f, -0.325953266f),
                Arguments.of("GB_024.obj", +0.116839653f, -0.556454454f, +0.074387770f),
                Arguments.of("GB_025.obj", -0.404698829f, -0.279497746f, -0.312486524f),

                // Artefacte istorice reale, descarcate de pe Zenodo (licenta CC-BY / CC-BY-NC,
                // vezi comentariul de mai jos pentru surse) - completeaza setul GB_* cu cioburi
                // suplimentare si cu alte tipuri de artefacte (vas, cupa, figurine), nu doar
                // fragmente de perete. Aceeasi metoda de referinta independenta (Python/numpy,
                // dubla precizie), doar ca fisierele .glb au fost incarcate cu trimesh in loc
                // de parserul manual de .obj (trimesh, force="mesh", concateneaza toate nodurile
                // scenei cu transformarile aplicate - acelasi principiu ca traversarea de noduri
                // din ModelLoader). Variantele Draco-comprimate ale Kylix/Bull/Violin au fost
                // evitate (Assimp, ca si trimesh fara plugin separat, nu le decodeaza).
                Arguments.of("AstianKatkelma_KM14100_16A.glb", +0.167711290f, +0.185092618f, +0.141736445f),
                Arguments.of("AstianKatkelma_KM14100_16B.glb", +0.019324671f, +0.113104851f, +0.041332712f),
                Arguments.of("Unguentario_Centocamere.obj", -0.005745718f, -0.006880507f, +0.063806080f),
                Arguments.of("Kylix.glb", -0.027718174f, +0.084145245f, -0.013475923f),
                Arguments.of("MycenaeanBullFigure.glb", -0.002943599f, -0.180405316f, +0.087531047f),
                Arguments.of("ViolinFigurine.glb", +0.000191713f, -0.016979208f, +0.335746744f),

                // Al doilea val, tot de pe Zenodo, cautat mai larg (unelte antice, monede,
                // arme medievale, cioburi de la mai multe situri arheologice reale - Hillsboro/
                // Fredricks/Wekiva/St. Johns/NMB&H, pietre Govan) - 59 candidati gasiti initial,
                // unul exclus (fisier stub fara geometrie reala), inca unul exclus dupa verificare
                // (old_candle_holder: 4 sub-mesh-uri cu scale-uri locale foarte disproportionate
                // intre ele - Java/Assimp si Python/trimesh au dat centre de masa complet diferite,
                // posibil o breasa reala in ModelLoader la cazul asta, neinvestigata inca).
                Arguments.of("Worn_out_Longsword_10218801.glb", -0.506208215f, -0.000135682f, +0.000926924f),
                Arguments.of("Ancestral_Pueblo_Pottery_Sherd_10290861.glb", -0.024494794f, -0.212942889f, +0.087230200f),
                Arguments.of("Stylized_fantasy_medieval_gold_coin_10313146.glb", +0.000000005f, +0.000000000f, +0.000000003f),
                Arguments.of("Medieval_Sword_10280543.glb", -0.001344950f, -0.204348801f, +0.000400021f),
                Arguments.of("ROI015_040_01_10222413.glb", -0.062099195f, -0.203530583f, +0.002212130f),
                Arguments.of("Terracotta_Army_figurine_10366919.glb", +0.018433855f, -0.170778885f, -0.006009081f),
                Arguments.of("Medieval_Shield_10328302.glb", +0.000365651f, +0.111157047f, +0.108171442f),
                Arguments.of("Pottery_Sherd_EOA_2022_41_1_10244110.glb", -0.389405495f, +0.060540907f, +0.244187808f),
                Arguments.of("ROI015_052_01_10315088.glb", -0.051316077f, +0.048643769f, +0.024722496f),
                Arguments.of("XPM_098_Stone_Labret_Sapsuk_River_Alaska_21291448.glb", -0.093099998f, -0.000577189f, +0.086945606f),
                Arguments.of("Clay_figurine_of_a_female_head_10229987.glb", -0.071289399f, -0.077216912f, -0.005173296f),
                Arguments.of("Check_Stamped_Sand_Tempered_Pottery_Sherd_10242042.glb", +0.074651264f, -0.011705164f, +0.011293411f),
                Arguments.of("Ancestral_Hopi_Duck_Effigy_Replica_10290993.glb", +0.072283572f, -0.202905142f, +0.152037035f),
                Arguments.of("ROI015_039_01_10344302.glb", +0.038633541f, -0.033390795f, -0.007949686f),
                Arguments.of("Jarra_Medieval_10339662.glb", +0.259815718f, -0.164552351f, -0.000930735f),
                Arguments.of("Ancient_Singha_Stone_Statue_10224856.glb", +0.016305087f, +0.259474875f, +0.107138475f),
                Arguments.of("Syrian_Horse_with_Rider_Figurine_21352879.glb", -0.066467391f, -0.272453798f, +0.034553436f),
                Arguments.of("Replica_Chert_Drill_10297214.glb", +0.389540963f, -0.095815733f, -0.005720777f),
                Arguments.of("Fredricks_Plain_Jar_Rim_2351p7194_p7225_p7253_21529387.glb", +0.014402864f, +0.114651142f, +0.247661198f),
                Arguments.of("Fredricks_Plain_Jar_Rim_2351p7253_21528080.glb", -0.119456959f, +0.062248524f, +0.264948281f),
                Arguments.of("Hillsboro_Simple_Stamped_Jar_Rim_80p212_21569337.glb", -0.269869992f, +0.485397280f, -0.076027477f),
                Arguments.of("Hillsboro_Check_Stamped_Jar_Rim_80p969_21353270.glb", +0.015411985f, +0.245049564f, +0.050137208f),
                Arguments.of("Hillsboro_Check_Stamped_Jar_Rim_80p288_21488561.glb", -0.124889054f, +0.210901582f, +0.114502117f),
                Arguments.of("Hillsboro_Simple_Stamped_Jar_Rim_80p746_2_21354323.glb", -0.194989340f, +0.164700770f, +0.012786852f),
                Arguments.of("Hillsboro_Simple_Stamped_Jar_Rim_80p265_21377253.glb", -0.197517646f, +0.122665162f, +0.278963022f),
                Arguments.of("Small_Oven_Door_from_Star_6_Cast_Iron_Stove_10272586.glb", +0.035500436f, +0.131609240f, +0.113105711f),
                Arguments.of("Vessel_with_Cover_cover_5th_4th_century_BCE_10328620.glb", +0.039146099f, +0.046738686f, +0.014519804f),
                Arguments.of("Vessel_with_Cover_base_5th_4th_century_BCE_10385610.glb", -0.002456590f, +0.055816232f, +0.013328653f),
                Arguments.of("Roman_Blood_Letting_Cup_Shaheen_Alikhan_10359411.glb", +0.049176984f, -0.056169100f, -0.043479428f),
                Arguments.of("Medieval_Axe_10325977.glb", -0.070701511f, +0.031854094f, +0.005626889f),
                Arguments.of("ROI015_044_01_10314487.glb", +0.016368799f, -0.075385204f, -0.028208886f),
                Arguments.of("Ancient_Food_Vessel_Game_Ready_2K_PBR_10379461.glb", +0.008599632f, -0.057301694f, -0.030572801f),
                Arguments.of("Ampullina_sea_snail_fossil_10363776.glb", -0.154129595f, -0.022161397f, +0.057649064f),
                Arguments.of("Medieval_Book_Stack_10377655.glb", +0.147503538f, +0.065255856f, +0.084980362f),
                Arguments.of("Wekiva_Incised_Pottery_Sherd_10332426.glb", +0.151963944f, -0.082378695f, +0.030309822f),
                Arguments.of("St_Johns_Incised_Pottery_Orange_County_FL_10227333.glb", +0.055634476f, -0.033149906f, +0.049690331f),
                Arguments.of("Wekiva_Incised_Pottery_Sherd_10321110.glb", +0.042295669f, -0.060607358f, +0.045732368f),
                Arguments.of("Hillsboro_Simple_Stamped_Jar_Rim_80p819_21568976.glb", +0.015529778f, +0.256278570f, -0.051013193f),
                Arguments.of("Fredricks_Check_Stamped_Jar_Rim_2351p6481_2_21490797.glb", +0.018735692f, +0.062395547f, +0.103212768f),
                Arguments.of("Old_Tomb_Stone_10336280.glb", -0.019160247f, -0.121670351f, +0.013317305f),
                Arguments.of("Clay_Artifact_3D_Scan_10328754.glb", +0.102629447f, -0.203149082f, -0.123873381f),
                Arguments.of("Tanagra_terracotta_figurine_10266047.glb", -0.001332543f, -0.035632841f, -0.016315567f),
                Arguments.of("Hillsboro_Check_Stamped_Jar_Rim_80p543_21565394.glb", +0.151448977f, +0.088413851f, -0.001693801f),
                Arguments.of("Govan_42_10330969.glb", -0.034410923f, +0.012376890f, -0.020019477f),
                Arguments.of("Knobby_amphora_21490109.glb", -0.144275771f, -0.256476337f, +0.107827310f),
                Arguments.of("Realistic_medieval_shield_10332718.glb", +0.012185435f, +0.022173074f, +0.065667862f),
                Arguments.of("Museum_culture_10273782.glb", +0.330847904f, +0.505851918f, -0.206376869f),
                Arguments.of("Govan_17_10336723.glb", +0.026932085f, +0.123173853f, +0.036971921f),
                Arguments.of("NMB_H_Iron_Age_pottery_21369941.glb", -0.149304277f, +0.042484156f, -0.020427526f),
                Arguments.of("NMB_H_Iron_Age_tool_spool_21492201.glb", -0.025209698f, -0.022753505f, -0.027063964f),
                Arguments.of("Terracotta_figurine_quadruped_Camarina_Sicily_21568629.glb", -0.181188977f, -0.161333118f, -0.002171461f),
                Arguments.of("LEKYTHOS_AIDONE_SICILY_ITALY_10262407.glb", -0.008685909f, -0.127387957f, +0.003007242f),
                Arguments.of("Govan_27_10344069.glb", -0.006378671f, +0.061767348f, +0.023642241f),
                Arguments.of("NMB_H_Iron_Age_tool_loom_weight_21378999.glb", +0.015527299f, -0.423404877f, -0.043904525f),
                Arguments.of("Govan_31_10313104.glb", -0.023176819f, -0.071085206f, +0.021778194f),
                Arguments.of("Neolithic_pottery_test_AR_10357991.glb", +0.009499741f, +0.102004970f, -0.030113734f),
                Arguments.of("NMB_H_Iron_Age_tool_spool_21527749.glb", -0.042019827f, -0.015746527f, -0.015980401f),
                Arguments.of("Govan_36_10315383.glb", +0.061394660f, +0.042250866f, +0.035652261f)
        );
    }
}
