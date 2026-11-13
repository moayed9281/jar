package com.smpcore;

import io.papermc.paper.plugin.bootstrap.BootstrapContext;
import io.papermc.paper.plugin.bootstrap.PluginBootstrap;

/** Runs before the server starts: registers the Esc (pause) menu dialog on Paper 1.21.6+. */
public final class DcBootstrap implements PluginBootstrap {
    @Override
    public void bootstrap(BootstrapContext context) {
        try {
            Class.forName("io.papermc.paper.registry.data.dialog.DialogBase", false, DcBootstrap.class.getClassLoader());
            EscMenu.register(context);
        } catch (Throwable t) {
            context.getLogger().warn("Esc menu not available on this version: " + t);
        }
    }
}
