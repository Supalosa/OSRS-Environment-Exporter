package models.scene

import cache.LocationType

interface SceneOverrider {
    fun overrideTile(regionId: Int, x: Int, y: Int, z: Int, objId: Int, orientation: Int, type: Int): SceneOverride?
}

data class SceneOverride(val objId: Int? = null, val orientation: Int? = null, val type: Int? = null)

class InfernoSceneOverrider: SceneOverrider {
    override fun overrideTile(
        regionId: Int,
        x: Int,
        y: Int,
        z: Int,
        objId: Int,
        orientation: Int,
        type: Int
    ): SceneOverride? {
        if (regionId != 9043) {
            return null;
        }

        // 30340 - 2x1 corner left
        if (x == 27 && y == 52) {
            return SceneOverride(objId = 30340, orientation = 1) // good
        }
        // 30330 - corner 1x1
        if (x == 27 && y == 55) {
            return SceneOverride(objId = 30327, orientation = 2) // good
        }
        // 30342 - 2x1 corner
        if (x == 27 && y == 54) {
            return SceneOverride(objId = 30342, orientation = 1) // good
        }

        if (x == 27 && y == 56) {
            return SceneOverride(objId = 30328, orientation = 2) // good
        }
        // 30339 - 2x1 corner right

        if (x == 35 && y == 52) {
            return SceneOverride(objId = 30339, orientation = 3)
        }
        // 30342 - 2x1 corner
        if (x == 35 && y == 54) {
            return SceneOverride(objId = 30341, orientation = 3) // good
        }
        // 30327 - 1x1 vertical wall

        // 30345 zuk rocks
        if (x == 28 && y == 52) {
            return SceneOverride(objId = 30345, orientation = 3) // good
        }
        if (x == 33 && y == 52) {
            return SceneOverride(objId = 30345, orientation = 3) // good
        }


        // 30291 - lava
        if (x >= 26 && x <= 33 && y >= 50 && y <= 54) {
            return SceneOverride(objId = 30291)
        }

        // removing floor decorations cos ugly
        if (x >= 17 && y <= 45 && x <= 45 && y >= 17) {
            return SceneOverride(objId = -1)
        }


        return null
    }

}