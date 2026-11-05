package dev.framextv.lumareceiver;

import android.content.Intent;
import android.view.KeyEvent;
import org.junit.Test;
import static org.junit.Assert.*;

public final class StartupReceiverTest {
    @Test public void bootAndReplacementBringReceiverToFront() {
        assertTrue(StartupReceiver.showOnStartup(Intent.ACTION_BOOT_COMPLETED));
        assertTrue(StartupReceiver.showOnStartup(Intent.ACTION_MY_PACKAGE_REPLACED));
        assertFalse(StartupReceiver.showOnStartup(Intent.ACTION_SHUTDOWN));
        assertFalse(StartupReceiver.showOnStartup(null));
    }

    @Test public void okDiagnosticsCountOnlyInitialCenterOrEnterDown() {
        assertTrue(MainActivity.isInitialOkPress(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, 0));
        assertTrue(MainActivity.isInitialOkPress(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 0));
        assertFalse(MainActivity.isInitialOkPress(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, 1));
        assertFalse(MainActivity.isInitialOkPress(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER, 0));
        assertFalse(MainActivity.isInitialOkPress(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0));
    }
}
