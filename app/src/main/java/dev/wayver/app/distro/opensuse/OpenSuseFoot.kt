package dev.wayver.app.distro.opensuse

import dev.wayver.app.distro.DesktopSpec
import dev.wayver.app.utils.AssetCopy
import java.io.File

object OpenSuseFoot {
    private val zypperCmd =
        "zypper --gpg-auto-import-keys --non-interactive refresh && zypper --non-interactive install --no-recommends"

    val spec =
        DesktopSpec(
            id = "foot",
            name = "Foot Terminal",
            prepare = { ctx ->
                AssetCopy.copyAssetTree(ctx.context, "distros/opensuse/foot", File(ctx.rootfsDir, "root/.config"))
                ctx.runInProot("$zypperCmd foot dejavu-fonts") == 0
            },
            launch = "exec foot"
        )
}
