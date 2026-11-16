package dev.wayver.app.distro.arch

import dev.wayver.app.distro.DesktopSpec
import dev.wayver.app.utils.AssetCopy
import java.io.File

object ArchFoot {
    val spec =
        DesktopSpec(
            id = "foot",
            name = "Foot Terminal",
            prepare = { ctx ->
                AssetCopy.copyAssetTree(ctx.context, "distros/arch/foot", File(ctx.rootfsDir, "root/.config"))
                ctx.runInProot("${Arch.pacmanPrep} && pacman -Syu --noconfirm --needed foot ttf-dejavu") == 0
            },
            launch = "exec foot"
        )
}
