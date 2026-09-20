package com.gorillajumping.game

import com.gorillajumping.gltf.Bounds
import com.gorillajumping.gltf.Glb
import com.gorillajumping.gltf.MeshData
import com.gorillajumping.math.Mat4
import com.gorillajumping.math.Vec3
import com.gorillajumping.render.Batcher
import com.gorillajumping.render.CpuPart

enum class Slot(val title: String) { HAT("Шляпа"), FACE("Лицо"), BADGE("Значок") }

/**
 * Косметика из all_gorilla_tag_season_cosmetics.glb. В файле вещи разложены витриной,
 * а не надеты на голову, поэтому каждую сажаем на гориллу по её габаритам и слоту.
 * [lift] — сдвиг по высоте (м), [scale] — если вещь в наборе другого масштаба.
 */
class CosmeticDef(
    val title: String,
    val slot: Slot,
    val nodes: List<String>,
    val lift: Float = 0f,
    val scale: Float = 1f,
    val sink: Float = -1f
)

object CosmeticCatalog {
    val all = listOf(
        // Шляпы
        CosmeticDef("Пляжная шляпа", Slot.HAT, listOf("BeachHat_28")),
        CosmeticDef("Кепка с пропеллером", Slot.HAT, listOf("PropellerCap_145")),
        CosmeticDef("Шляпа Сэма", Slot.HAT, listOf("SamHat_147")),
        CosmeticDef("Пробковый шлем", Slot.HAT, listOf("SunHelmet_161")),
        CosmeticDef("Козырёк", Slot.HAT, listOf("Visor_163")),
        CosmeticDef("Шляпа-осьминог", Slot.HAT, listOf("OctopusHat_66")),
        CosmeticDef("Акула", Slot.HAT, listOf("SharkHead_74"), sink = 0.2f),
        CosmeticDef("Яйцо", Slot.HAT, listOf("egghat_188")),
        CosmeticDef("Шляпа лепрекона", Slot.HAT, listOf("leprechaun_hat_190")),
        CosmeticDef("Уши кролика", Slot.HAT, listOf("bunnyearsbrown_174"), sink = 0.04f),
        CosmeticDef("Белые уши кролика", Slot.HAT, listOf("bunnyearswhite_176"), sink = 0.04f),
        CosmeticDef("Цветочная шляпа", Slot.HAT, listOf("upsidedownflowerhatblue_198")),
        CosmeticDef("Фиолетовая цветочная", Slot.HAT, listOf("upsidedownflowerhatpurple_200")),
        CosmeticDef("Шляпа-муха", Slot.HAT, listOf("FlyHat_227")),
        CosmeticDef("Швабра", Slot.HAT, listOf("MopHat_235")),
        CosmeticDef("Мышиные уши", Slot.HAT, listOf("MouseEars_237"), sink = 0.05f),
        CosmeticDef("Шляпа пугала", Slot.HAT, listOf("ScarecrowHat_334")),
        CosmeticDef("Злой Санта", Slot.HAT, listOf("EvilSantaHat_Wardrobe_358")),
        CosmeticDef("Колпак Санты", Slot.HAT, listOf("SantaHat_Wardrobe_400")),
        CosmeticDef("Рога оленя", Slot.HAT, listOf("ReindeerAntlers_LHADD_Wardrobe_391"), sink = 0.04f),
        CosmeticDef("Шляпа снеговика", Slot.HAT, listOf("SnowmanHat_415")),
        CosmeticDef("Чулок", Slot.HAT, listOf("StockingHat_Wardrobe_421")),
        CosmeticDef("Золотой подарок", Slot.HAT, listOf("GiftHatGold_449")),
        CosmeticDef("Каска инженера", Slot.HAT, listOf("HatEngineer_455")),
        CosmeticDef("Гнездо совы", Slot.HAT, listOf("OwlNestHat_467")),
        CosmeticDef("Шляпа принцессы", Slot.HAT, listOf("princesshat_575")),
        CosmeticDef("Тыква", Slot.HAT, listOf("pumpkinhat_577")),
        CosmeticDef("Уши волка", Slot.HAT, listOf("wolfears_599"), sink = 0.05f),
        CosmeticDef("Парик клоуна", Slot.HAT, listOf("clownwig_552"), sink = 0.12f),
        CosmeticDef("Кепка клоуна", Slot.HAT, listOf("ClownCap_616")),
        CosmeticDef("Шляпа шерифа", Slot.HAT, listOf("SheriffHat_652"), scale = 0.55f),
        CosmeticDef("Корона единорога", Slot.HAT, listOf("UnicornCrown_674")),
        CosmeticDef("Франкенштейн", Slot.HAT, listOf("HatFrankenHead_701")),
        CosmeticDef("Шляпа-скелет", Slot.HAT, listOf("SkeletonHat_721")),
        CosmeticDef("Парик вампира", Slot.HAT, listOf("vampirewig_583"), sink = 0.12f),

        // Лицо
        CosmeticDef("Солнечные очки", Slot.FACE, listOf("SunGlasses_157", "SunGlassesLens_159")),
        CosmeticDef("Синие очки", Slot.FACE, listOf("Summer_Shades_B_13")),
        CosmeticDef("Красные очки", Slot.FACE, listOf("Summer_Shades_R_19")),
        CosmeticDef("Зелёные очки", Slot.FACE, listOf("Summer_Shades_G_17")),
        CosmeticDef("Очки-пальмы", Slot.FACE, listOf("PalmTreeGlasses_68", "PalmTreeGlassesLens_70")),
        CosmeticDef("Очки 2023", Slot.FACE, listOf("2023Glasses_LFABX_Wardrobe_350")),
        CosmeticDef("Праздничные очки", Slot.FACE, listOf("GlassesHoliday_451")),
        CosmeticDef("Бинокль", Slot.FACE, listOf("CardboardBinoculars_95")),
        CosmeticDef("Усы", Slot.FACE, listOf("Mustache_143"), lift = -0.09f),
        CosmeticDef("Усы шерифа", Slot.FACE, listOf("SheriffMustache_654"), lift = -0.09f),
        CosmeticDef("Нос Рудольфа", Slot.FACE, listOf("RudolphNose_LFABY_Wardrobe_393"), lift = -0.05f),
        CosmeticDef("Нос клоуна", Slot.FACE, listOf("clownnose_550"), lift = -0.05f),
        CosmeticDef("Борода Санты", Slot.FACE, listOf("SantaBeard_Wardrobe_395"), lift = -0.13f),
        CosmeticDef("Глаза навыкате", Slot.FACE, listOf("BulgingGooglyEyes_608")),
        CosmeticDef("Маска дьявола", Slot.FACE, listOf("MaskDevil.001_705"), lift = -0.02f),
        CosmeticDef("Маска клоуна", Slot.FACE, listOf("MaskClown.001_703"), lift = -0.02f),
        CosmeticDef("Клыки вампира", Slot.FACE, listOf("vampirefangs_581"), lift = -0.1f),

        // Значки на грудь
        CosmeticDef("Звезда шерифа", Slot.BADGE, listOf("SheriffBadge_650"), scale = 0.6f),
        CosmeticDef("Ромашка", Slot.BADGE, listOf("DaisyBadge_219"), scale = 0.7f),
        CosmeticDef("Снежинка", Slot.BADGE, listOf("BadgeSnowFlake_437")),
        CosmeticDef("Значок принцессы", Slot.BADGE, listOf("princessbadge_571"), scale = 0.7f),
        CosmeticDef("Клевер", Slot.BADGE, listOf("_4leafclover_168"), scale = 0.5f),
    )

    fun of(slot: Slot) = all.filter { it.slot == slot }
}

/** Где на модели гориллы голова, лицо и грудь (в локальных координатах модели). */
class GorillaAnchors(val headTop: Float, val faceFront: Float, val faceCenterY: Float, val chestFront: Float, val chestY: Float) {
    val headCenterZ get() = faceFront - 0.13f
}

/** Собирает одну вещь из glb и сажает её на гориллу. Возвращает части в координатах модели гориллы. */
fun buildCosmetic(glb: Glb, def: CosmeticDef, anchors: GorillaAnchors): List<CpuPart>? {
    val meshes = ArrayList<MeshData>()
    for (name in def.nodes) {
        val node = glb.findNode(name)
        if (node < 0) continue
        glb.collect(node, Mat4.identity()) { meshes += it }
    }
    if (meshes.isEmpty()) return null
    val bounds = Bounds()
    meshes.forEach { bounds.add(it.positions) }
    val size = bounds.size * def.scale
    val c = bounds.center
    // Сначала переносим центр вещи в ноль и масштабируем, затем ставим на место.
    val target = when (def.slot) {
        Slot.HAT -> {
            val sink = if (def.sink >= 0f) def.sink else (size.y * 0.3f).coerceIn(0.03f, 0.1f)
            Vec3(0f, anchors.headTop - sink + size.y / 2 + def.lift, anchors.headCenterZ)
        }
        Slot.FACE -> Vec3(0f, anchors.faceCenterY + 0.035f + def.lift, anchors.faceFront + 0.035f - size.z / 2)
        Slot.BADGE -> Vec3(-0.08f, anchors.chestY + def.lift, anchors.chestFront + 0.01f - size.z / 2)
    }
    val m = Mat4.translation(target) * Mat4.scale(Vec3(def.scale, def.scale, def.scale)) * Mat4.translation(-c)
    val batcher = Batcher()
    for (mesh in meshes) {
        val pos = FloatArray(mesh.positions.size)
        for (i in 0 until mesh.vertexCount) {
            val p = m.transformPoint(Vec3(mesh.positions[i * 3], mesh.positions[i * 3 + 1], mesh.positions[i * 3 + 2]))
            pos[i * 3] = p.x; pos[i * 3 + 1] = p.y; pos[i * 3 + 2] = p.z
        }
        batcher.add(MeshData(pos, mesh.normals, mesh.uvs, mesh.indices, mesh.material))
    }
    return batcher.build()
}
