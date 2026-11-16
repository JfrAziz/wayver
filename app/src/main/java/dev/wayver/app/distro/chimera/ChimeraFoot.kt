package dev.wayver.app.distro.chimera

import dev.wayver.app.distro.DesktopSpec
import dev.wayver.app.utils.AssetCopy
import java.io.File

object ChimeraFoot {
    val spec =
        DesktopSpec(
            id = "foot",
            name = "Foot Terminal",
            prepare = { ctx ->
                AssetCopy.copyAssetTree(ctx.context, "distros/chimera/foot", File(ctx.rootfsDir, "root/.config"))
                ctx.runInProot("apk update && apk add --no-interactive foot fonts-dejavu-ttf") == 0
            },
            launch = "exec foot"
        )
}
