package gg.gnomeheist;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GnomeHeist implements ModInitializer {
    public static final String MOD_ID = "gnomeheist";
    public static final Logger LOG = LoggerFactory.getLogger("Gnome Heist");

    @Override
    public void onInitialize() {
        HeistEvents.register();
        if (SelfTest.ENABLED) SelfTest.register();
        // Melty's first-start check waits for this line in latest.log.
        LOG.info("Gnome Heist loaded");
    }
}
