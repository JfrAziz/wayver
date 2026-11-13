package dev.wayver.app.distro.alpine

import dev.wayver.app.distro.DesktopSpec
import dev.wayver.app.utils.AssetCopy
import java.io.File

object AlpineFoot {
    val spec =
        DesktopSpec(
            id = "foot",
            name = "Foot Terminal",
            prepare = { context, rootfsDir ->
                AssetCopy.copyAssetTree(context, "distros/alpine/foot", File(rootfsDir, "root/.config"))
            },
            launch = "[ -x /usr/bin/foot ] || (apk update && apk add --no-cache foot font-dejavu); exec foot"
        )
}
