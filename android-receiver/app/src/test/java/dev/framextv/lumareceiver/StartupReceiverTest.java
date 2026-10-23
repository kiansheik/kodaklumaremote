package dev.framextv.lumareceiver;

import android.content.Intent;
import org.junit.Test;
import static org.junit.Assert.*;

public final class StartupReceiverTest {
    @Test public void bootAndReplacementBringReceiverToFront() {
        assertTrue(StartupReceiver.showOnStartup(Intent.ACTION_BOOT_COMPLETED));
        assertTrue(StartupReceiver.showOnStartup(Intent.ACTION_MY_PACKAGE_REPLACED));
        assertFalse(StartupReceiver.showOnStartup(Intent.ACTION_SHUTDOWN));
        assertFalse(StartupReceiver.showOnStartup(null));
    }
}
