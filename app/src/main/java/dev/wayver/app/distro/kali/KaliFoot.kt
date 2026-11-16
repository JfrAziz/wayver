package dev.wayver.app.distro.kali

import dev.wayver.app.distro.DesktopSpec
import dev.wayver.app.utils.AssetCopy
import java.io.File

object KaliFoot {
    private val aptPrefix = "DEBIAN_FRONTEND=noninteractive apt-get -o APT::Sandbox::User=root"

    val spec =
        DesktopSpec(
            id = "foot",
            name = "Foot Terminal",
            prepare = { ctx ->
                AssetCopy.copyAssetTree(ctx.context, "distros/kali/foot", File(ctx.rootfsDir, "root/.config"))
                ctx.runInProot(
                    "p=$(ls -1 /usr/bin/perl5.*.* 2>/dev/null | head -n 1); " +
                        "[ -e /usr/bin/perl ] || [ -z \"\$p\" ] || ln -sf \"\$p\" /usr/bin/perl; " +
                        "DEBIAN_FRONTEND=noninteractive dpkg --configure -a && " +
                        "$aptPrefix update && $aptPrefix install -y --no-install-recommends foot fonts-dejavu-core"
                ) == 0
            },
            launch = "exec foot"
        )
}
