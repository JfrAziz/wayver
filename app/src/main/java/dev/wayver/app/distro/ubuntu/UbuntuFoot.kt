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
            prepare = { context, rootfsDir ->
                AssetCopy.copyAssetTree(context, "distros/ubuntu/foot", File(rootfsDir, "root/.config"))
            },
            launch =
            "[ -x /usr/bin/foot ] || ($aptPrefix update && " +
                "$aptPrefix install -y --no-install-recommends foot fonts-dejavu-core); exec foot"
        )
}
