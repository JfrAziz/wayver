package dev.wayver.app.distro.voidlinux

import dev.wayver.app.distro.DesktopSpec
import dev.wayver.app.utils.AssetCopy
import java.io.File

object VoidFoot {
    val spec =
        DesktopSpec(
            id = "foot",
            name = "Foot Terminal",
            prepare = { ctx ->
                AssetCopy.copyAssetTree(ctx.context, "distros/void/foot", File(ctx.rootfsDir, "root/.config"))
                ctx.runInProot(
                    "xbps-install -Suy xbps && xbps-install -Suy && xbps-install -Sy foot dejavu-fonts-ttf"
                ) == 0
            },
            launch = "exec foot"
        )
}
