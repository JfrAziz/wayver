package dev.wayver.app.distro.alpine

import dev.wayver.app.distro.DesktopSpec
import dev.wayver.app.utils.AssetCopy
import java.io.File

object AlpineFoot {
    val spec =
        DesktopSpec(
            id = "foot",
            name = "Foot Terminal",
            prepare = { ctx ->
                AssetCopy.copyAssetTree(ctx.context, "distros/alpine/foot", File(ctx.rootfsDir, "root/.config"))
                ctx.runInProot("apk update && apk add --no-cache foot font-dejavu") == 0
            },
            launch = "exec foot"
        )
}
