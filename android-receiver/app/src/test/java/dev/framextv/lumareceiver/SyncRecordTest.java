package dev.framextv.lumareceiver;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.json.JSONObject;
import org.junit.Test;

public class SyncRecordTest {
    private static SyncRecord record(String key, long at, String origin, String data) {
        return new SyncRecord(key, "progress", at, origin, false, data);
    }

    /** Applies records to a map using the LWW rule, like LibraryStore.merge. */
    private static Map<String, SyncRecord> apply(List<SyncRecord> records) {
        Map<String, SyncRecord> state = new HashMap<>();
        for (SyncRecord record : records) if (SyncRecord.wins(record, state.get(record.key))) state.put(record.key, record);
        return state;
    }

    @Test public void newerWinsThenOriginThenData() {
        assertTrue(SyncRecord.wins(record("k", 2, "phoneAAAA", "{}"), record("k", 1, "phoneZZZZ", "{}")));
        assertFalse(SyncRecord.wins(record("k", 1, "phoneZZZZ", "{}"), record("k", 2, "phoneAAAA", "{}")));
        assertTrue(SyncRecord.wins(record("k", 1, "phoneZZZZ", "{\"b\":1}"), record("k", 1, "phoneAAAA", "{\"a\":1}")));
        assertTrue(SyncRecord.wins(record("k", 1, "phoneAAAA", "{\"b\":1}"), record("k", 1, "phoneAAAA", "{\"a\":1}")));
        assertFalse(SyncRecord.wins(record("k", 1, "phoneAAAA", "{}"), record("k", 1, "phoneAAAA", "{}")));
        assertFalse(SyncRecord.wins(record("k", 1, "phoneZZZZ", "{\"a\":1}"), record("k", 1, "luma-AAAA", "{\"a\":1}")));
    }

    @Test public void convergesForAnyOrderAndDuplicates() {
        Random random = new Random(7);
        List<SyncRecord> updates = new ArrayList<>();
        for (int i = 0; i < 200; i++)
            updates.add(record("k" + random.nextInt(5), random.nextInt(20), "origin" + random.nextInt(3) + "xxxx", "{\"p\":" + random.nextInt(4) + "}"));
        Map<String, SyncRecord> expected = apply(updates);
        for (int round = 0; round < 20; round++) {
            List<SyncRecord> shuffled = new ArrayList<>(updates);
            shuffled.addAll(updates.subList(0, 50));
            Collections.shuffle(shuffled, random);
            Map<String, SyncRecord> actual = apply(shuffled);
            assertEquals(expected.keySet(), actual.keySet());
            for (String key : expected.keySet()) assertTrue(expected.get(key) == actual.get(key) || !SyncRecord.wins(expected.get(key), actual.get(key)) && !SyncRecord.wins(actual.get(key), expected.get(key)));
        }
    }

    @Test public void parseValidatesEnvelope() throws Exception {
        long now = 1_000_000_000_000L;
        SyncRecord ok = SyncRecord.parse(new JSONObject("{\"key\":\"framextv:tv:1:1:2\",\"type\":\"progress\",\"updatedAt\":5,\"origin\":\"luma-abcdefgh\",\"data\":{\"a\":1}}"), now);
        assertEquals("{\"a\":1}", ok.data);
        String[] bad = {
            "{\"key\":\"BAD KEY\",\"type\":\"progress\",\"updatedAt\":5,\"origin\":\"luma-abcdefgh\",\"data\":{}}",
            "{\"key\":\"k\",\"type\":\"other\",\"updatedAt\":5,\"origin\":\"luma-abcdefgh\",\"data\":{}}",
            "{\"key\":\"k\",\"type\":\"progress\",\"updatedAt\":" + (now + 2L * 24 * 3600 * 1000) + ",\"origin\":\"luma-abcdefgh\",\"data\":{}}",
            "{\"key\":\"k\",\"type\":\"progress\",\"updatedAt\":5,\"origin\":\"x\",\"data\":{}}",
            "{\"key\":\"k\",\"type\":\"progress\",\"updatedAt\":5,\"origin\":\"luma-abcdefgh\"}",
        };
        for (String value : bad) {
            try { SyncRecord.parse(new JSONObject(value), now); fail(value); }
            catch (ApiException expected) { assertEquals("INVALID_RECORD", expected.code); }
        }
        assertTrue(SyncRecord.parse(new JSONObject("{\"key\":\"k\",\"type\":\"title\",\"updatedAt\":5,\"origin\":\"luma-abcdefgh\",\"deleted\":true}"), now).deleted);
    }
}
