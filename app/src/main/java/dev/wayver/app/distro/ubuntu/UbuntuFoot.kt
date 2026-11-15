package dev.wayver.app.distro.ubuntu

import dev.wayver.app.distro.DesktopSpec
import dev.wayver.app.utils.AssetCopy
import java.io.File

object UbuntuFoot {
    private val aptPrefix = "DEBIAN_FRONTEND=noninteractive apt-get -o APT::Sandbox::User=root"

    val spec =
        DesktopSpec(
            id = "foot",
            name = "Foot Terminal",
            prepare = { ctx ->
                AssetCopy.copyAssetTree(ctx.context, "distros/ubuntu/foot", File(ctx.rootfsDir, "root/.config"))
                ctx.runInProot(
                    "$aptPrefix update && $aptPrefix install -y --no-install-recommends foot fonts-dejavu-core"
                ) == 0
            },
            launch = "exec foot"
        )
}
