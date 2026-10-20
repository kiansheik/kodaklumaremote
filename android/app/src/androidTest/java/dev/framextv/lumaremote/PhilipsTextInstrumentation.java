package dev.framextv.lumaremote;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Build;
import android.os.Bundle;
import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.net.ssl.HttpsURLConnection;

/** Owned protocol/clock/store fixtures only: no TV calls, discovery or PIN prompts. */
public final class PhilipsTextInstrumentation {
    private static final String HOST = "192.168.50.4", START = "http://192.168.50.2:4178";
    private static final String PIN = "a".repeat(64);
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private interface Action { void run() throws Exception; }
    private static void rejects(String code, Action action) throws Exception {
        try { action.run(); throw new AssertionError("Expected fixed failure " + code); }
        catch (PhilipsTextController.TextException failure) { require(code.equals(failure.code), "Unexpected fixed text error"); }
    }
    private static JSONObject object(Object... fields) throws Exception { JSONObject value = new JSONObject(); for (int i = 0; i < fields.length; i += 2) value.put((String) fields[i], fields[i + 1]); return value; }
    private static final class Clock implements PhilipsTextController.Clock {
        long time = 10000; final List<Long> sleeps = new ArrayList<>(); Runnable onSleep;
        public long now() { return time; }
        public void sleep(long millis) { sleeps.add(millis); time += millis; if (onSleep != null) { Runnable callback = onSleep; onSleep = null; callback.run(); } }
    }
    private static final class Store implements PhilipsTextController.Store {
        JSONObject saved;
        public JSONObject read() { return saved; }
        public void save(JSONObject value) throws PhilipsTextController.TextException { try { saved = new JSONObject(value.toString()); } catch (Exception invalid) { throw new PhilipsTextController.TextException("TEXT_STORAGE"); } }
    }
    private static final class Factory implements PhilipsTextController.Factory {
        final List<JSONObject> calls = new ArrayList<>(); final List<String> pins = new ArrayList<>();
        boolean rejectGrant, unicode = true, browserSupported = true; int inputCount, failInput = -1, browserCount; long inputDelay; final Clock clock;
        String browserError; Runnable onSystem;
        Factory(Clock clock) { this.clock = clock; }
        public PhilipsTextController.Transport create(String host, String pin) {
            require(HOST.equals(host), "Text transport changed its explicit peer"); pins.add(pin);
            return new PhilipsTextController.Transport() {
                boolean closed;
                public String certificatePin() { return PIN; }
                public void close() { closed = true; }
                public JSONObject request(String path, JSONObject body, String user, String password, boolean json, long deadline) throws PhilipsTextController.TextException {
                    require(!closed, "Closed transport reused");
                    try {
                        calls.add(object("path", path, "body", body == null ? JSONObject.NULL : new JSONObject(body.toString()), "user", user == null ? JSONObject.NULL : user, "deadline", deadline));
                        if ("system".equals(path)) {
                            if (onSystem != null) { Runnable callback = onSystem; onSystem = null; callback.run(); }
                            return object("featuring", object("jsonfeatures", object("inputkey", new JSONArray().put(unicode ? "unicode" : "key"), "activities", browserSupported ? new JSONArray().put("browser") : new JSONArray())));
                        }
                        if ("pair/request".equals(path)) return object("error_id", "SUCCESS", "timestamp", 12345, "auth_key", "fixture-auth-key", "timeout", 3);
                        if ("pair/grant".equals(path)) {
                            require(!user.isEmpty() && "fixture-auth-key".equals(password) && PIN.equals(pin), "Grant lost pending credentials or certificate binding");
                            return object("error_id", rejectGrant ? "DENIED" : "SUCCESS");
                        }
                        if ("activities/browser".equals(path)) {
                            browserCount++;
                            require(!json && user.matches("[a-f0-9]{32}") && "fixture-auth-key".equals(password) && PIN.equals(pin)
                                    && body.length() == 1 && PhilipsTextController.fixedBrowserSetupUrl(body.getString("url")), "Browser launch changed fixed URL, saved authorization or certificate binding");
                            if (browserError != null) throw new PhilipsTextController.TextException(browserError, true);
                            return new JSONObject();
                        }
                        require("input/key".equals(path) && !json && user.matches("[a-f0-9]{32}") && "fixture-auth-key".equals(password) && PIN.equals(pin), "Typing changed endpoint, authorization or certificate binding");
                        inputCount++; clock.time += inputDelay;
                        if (inputCount == failInput) throw new PhilipsTextController.TextException("TEXT_TIMEOUT", true);
                        require(body.length() == 1 && body.getString("unicode").codePointCount(0, body.getString("unicode").length()) == 1, "Typing sent more than one Unicode key or a submit key");
                        return new JSONObject();
                    } catch (PhilipsTextController.TextException fixed) { throw fixed; }
                    catch (Exception invalid) { throw new AssertionError("Invalid owned protocol fixture", invalid); }
                }
            };
        }
    }
    private static PhilipsTextController paired(Store store, Factory factory, Clock clock) throws Exception {
        PhilipsTextController controller = new PhilipsTextController(store, factory, clock);
        controller.pairRequest(HOST); controller.pairGrant(HOST, "0123"); return controller;
    }
    private static void passed(Instrumentation runner, int count, String name) { Bundle status = new Bundle(); status.putInt("current", count); status.putString("test", name); runner.sendStatus(0, status); }
    public static void run(Instrumentation runner) {
        Bundle result = new Bundle(); int checks = 0;
        try {
            require("dev.framextv.lumaremote.receiverfixture".equals(runner.getTargetContext().getPackageName()), "Text fixtures require the isolated application");
            require(Build.FINGERPRINT.startsWith("generic") || Build.FINGERPRINT.contains("emulator") || Build.MODEL.contains("sdk_gphone"), "Text fixtures require an emulator");
            Map<String, String> digest = PhilipsTextController.parseChallenge("Digest realm=\"testrealm@host.com\", qop=\"auth,auth-int\", nonce=\"dcd98b7102dd2f0e8b11d0f600bfb0c093\", opaque=\"5ccc069c403ebaf9f0171e9517f40e41\"");
            String authorization = PhilipsTextController.digestAuthorization(digest, "Mufasa", "Circle Of Life", "GET", "/dir/index.html", "0a4f113b", 1);
            require(authorization.contains("response=\"6629fae49393a05397450978507c4ef1\"") && authorization.contains("nc=00000001") && authorization.contains("qop=auth"), "RFC Digest known answer failed");
            require("ShAVnLeilQQ00rvqN1kn5BJnIbQ=".equals(PhilipsTextController.signature("12345", "0123")), "Vendor HMAC or leading-zero PIN changed");
            rejects("TEXT_AUTH_REQUIRED", () -> PhilipsTextController.parseChallenge("Basic realm=\"fixture\""));
            rejects("TEXT_AUTH_REQUIRED", () -> PhilipsTextController.parseChallenge("Digest realm=\"one\",realm=\"two\",nonce=\"fixture\""));
            Map<String, String> unsupported = PhilipsTextController.parseChallenge("Digest realm=\"fixture\",nonce=\"fixture\",qop=\"auth-int\"");
            rejects("TEXT_AUTH_REQUIRED", () -> PhilipsTextController.digestAuthorization(unsupported, "fixture", "fixture", "POST", "/6/input/key", "fixture", 1));
            passed(runner, ++checks, "Digest RFC vector, bounded challenges and vendor HMAC preserve leading-zero PIN");

            Clock clock = new Clock(); Store store = new Store(); Factory factory = new Factory(clock);
            PhilipsTextController controller = new PhilipsTextController(store, factory, clock);
            JSONObject pending = controller.pairRequest(HOST);
            require(pending.getBoolean("pairing") && pending.getInt("expiresInSeconds") == 3 && store.saved == null, "Pair request persisted unapproved credentials");
            require(!controller.status(HOST).getBoolean("textPermissionSaved"), "Pending PIN was presented as saved permission");
            rejects("TEXT_PAIRING_PENDING", () -> controller.pairRequest(HOST));
            require(factory.pins.size() == 1, "Duplicate pair request prompted the TV again");
            rejects("TEXT_INVALID_PIN", () -> controller.pairGrant(HOST, "123"));
            rejects("TEXT_ADDRESS_UNAVAILABLE", () -> controller.pairGrant("192.168.50.9", "0123"));
            factory.rejectGrant = true; rejects("TEXT_REJECTED", () -> controller.pairGrant(HOST, "0123"));
            require(store.saved == null && controller.status(HOST).getBoolean("textPairing"), "Failed PIN grant persisted credentials or discarded pending pairing");
            factory.rejectGrant = false; controller.pairGrant(HOST, "0123");
            require(controller.status(HOST).getBoolean("typingAvailable") && !controller.status("192.168.50.9").getBoolean("typingAvailable"), "Typing readiness ignored paired peer");
            require(controller.status("").getBoolean("textPermissionSaved") && !controller.status("").getBoolean("typingAvailable"), "Unresolved address erased saved permission");
            Factory restoredFactory = new Factory(clock);
            PhilipsTextController restored = new PhilipsTextController(store, restoredFactory, clock);
            require(restored.status("").getBoolean("textPermissionSaved") && !restored.status("").getBoolean("typingAvailable"), "Restored permission looked unpaired before address resolution");
            require(restored.status(HOST).getBoolean("textPermissionSaved") && restored.status(HOST).getBoolean("typingAvailable") && !restored.status("192.168.50.9").getBoolean("typingAvailable"), "Restored readiness lost its peer binding");
            require(restoredFactory.calls.isEmpty(), "Permission/readiness status made a TV request"); restored.close();
            rejects("TEXT_ALREADY_PAIRED", () -> controller.pairRequest(HOST));
            JSONObject grant = factory.calls.get(factory.calls.size() - 1).getJSONObject("body");
            require("0123".equals(grant.getJSONObject("auth").getString("pin")) && "ShAVnLeilQQ00rvqN1kn5BJnIbQ=".equals(grant.getJSONObject("auth").getString("auth_signature")), "Grant changed PIN or signature");
            controller.close();
            Clock expiredClock = new Clock(); Factory expiredFactory = new Factory(expiredClock); PhilipsTextController expired = new PhilipsTextController(new Store(), expiredFactory, expiredClock);
            expired.pairRequest(HOST); expiredClock.time += 3001;
            JSONObject expiredStatus = expired.status(HOST);
            require(!expiredStatus.getBoolean("textPairing") && !expiredStatus.getBoolean("textPermissionSaved") && "TEXT_PAIRING_EXPIRED".equals(expiredStatus.getString("textError")), "Expired pending PIN disappeared without an explicit expiry state");
            require(expiredFactory.calls.size() == 2, "Expiry status made a TV request");
            rejects("TEXT_PAIRING_EXPIRED", () -> expired.pairGrant(HOST, "0123")); expired.close();
            Clock unsupportedClock = new Clock(); Factory unsupportedFactory = new Factory(unsupportedClock); unsupportedFactory.unicode = false;
            PhilipsTextController unsupportedController = new PhilipsTextController(new Store(), unsupportedFactory, unsupportedClock);
            rejects("TEXT_UNSUPPORTED", () -> unsupportedController.pairRequest(HOST));
            require(unsupportedFactory.calls.size() == 1 && "system".equals(unsupportedFactory.calls.get(0).getString("path")), "Unsupported TV still received a PIN prompt"); unsupportedController.close();
            passed(runner, ++checks, "Pairing is pending-only until successful grant, peer-bound, nonduplicated and expiring");

            Clock typeClock = new Clock(); Factory typeFactory = new Factory(typeClock); PhilipsTextController typing = paired(new Store(), typeFactory, typeClock);
            JSONObject typed = typing.typeAddress(HOST, START); String expected = "192.168.50.2:4178";
            StringBuilder actual = new StringBuilder();
            for (JSONObject call : typeFactory.calls) if ("input/key".equals(call.getString("path"))) actual.append(call.getJSONObject("body").getString("unicode"));
            require(expected.contentEquals(actual) && typed.getInt("acknowledgedChars") == expected.length() && !typed.getBoolean("textAppliedVerified"), "Typed input was not exactly native phone host:port");
            require("msx".equals(typed.getString("textEntryMode")) && typed.getInt("totalChars") == expected.length(), "MSX address receipt lost its fixed mode or total");
            require(typeClock.sleeps.size() == expected.length() - 1 && typeClock.sleeps.stream().allMatch(delay -> delay == 150), "Unicode pacing changed");
            for (String unsafe : new String[] {"https://192.168.50.2:4178", "http://127.0.0.1:4178", START + "/secret", START + "?target=other", "http://user@192.168.50.2:4178"}) rejects("TEXT_INVALID_INPUT", () -> typing.typeAddress(HOST, unsafe));
            typing.close();
            Clock partialClock = new Clock(); Factory partialFactory = new Factory(partialClock); PhilipsTextController partial = paired(new Store(), partialFactory, partialClock);
            partialFactory.failInput = 3; rejects("TEXT_TIMEOUT", () -> partial.typeAddress(HOST, START));
            JSONObject receipt = partial.status(HOST).getJSONObject("lastTyping");
            require(partialFactory.inputCount == 3 && receipt.getInt("acknowledgedChars") == 2 && receipt.getBoolean("partialInput") && !receipt.getBoolean("textAppliedVerified") && !partial.status(HOST).getBoolean("typingBusy"), "Uncertain Unicode key retried or partial receipt lost");
            partial.close();
            Clock slowClock = new Clock(); Factory slowFactory = new Factory(slowClock); PhilipsTextController slow = paired(new Store(), slowFactory, slowClock);
            slowFactory.inputDelay = 33000; rejects("TEXT_TIMEOUT", () -> slow.typeAddress(HOST, START));
            require(slowFactory.inputCount == 2 && slow.status(HOST).getJSONObject("lastTyping").getBoolean("partialInput"), "Whole typing deadline did not bound further writes"); slow.close();
            Clock cancelClock = new Clock(); Factory cancelFactory = new Factory(cancelClock); PhilipsTextController cancel = paired(new Store(), cancelFactory, cancelClock);
            cancelClock.onSleep = cancel::cancel; rejects("TEXT_CANCELLED", () -> cancel.typeAddress(HOST, START));
            JSONObject canceled = cancel.status(HOST).getJSONObject("lastTyping");
            require(cancelFactory.inputCount == 1 && canceled.getInt("acknowledgedChars") == 1 && canceled.getBoolean("partialInput") && "TEXT_CANCELLED".equals(canceled.getString("error")), "Cancel during pacing wrote another key or lost the partial receipt");
            cancelClock.onSleep = cancel::close; rejects("TEXT_CANCELLED", () -> cancel.typeAddress(HOST, START));
            require(cancelFactory.inputCount == 2 && cancel.status(HOST).getJSONObject("lastTyping").getBoolean("partialInput"), "Close during pacing misreported partial typing");
            String browserExpected = "http://192.168.50.2:4178/msx/connect.html";
            require(browserExpected.equals(PhilipsTextController.browserSetupUrl(START)), "Browser entry changed fixed local target");
            require("http://192.168.50.2:49153/msx/connect.html".equals(PhilipsTextController.browserSetupUrl("http://192.168.50.2:49153")), "Browser entry reused an old server port");
            Clock browserClock = new Clock(); Store browserStore = new Store(); Factory firstBrowserFactory = new Factory(browserClock);
            PhilipsTextController firstBrowser = paired(browserStore, firstBrowserFactory, browserClock); firstBrowser.close();
            Factory browserFactory = new Factory(browserClock); browserFactory.inputDelay = 1800;
            PhilipsTextController browser = new PhilipsTextController(browserStore, browserFactory, browserClock);
            long browserStarted = browserClock.now(); JSONObject browserTyped = browser.typeBrowserAddress(HOST, START); StringBuilder browserActual = new StringBuilder();
            for (JSONObject call : browserFactory.calls) {
                require("input/key".equals(call.getString("path")), "Browser entry requested another PIN instead of reusing saved permission");
                browserActual.append(call.getJSONObject("body").getString("unicode"));
                require(call.getLong("deadline") == browserStarted + 90000, "Browser entry did not use one bounded 90-second deadline");
            }
            require(browserExpected.contentEquals(browserActual) && browserTyped.getInt("acknowledgedChars") == browserExpected.length()
                    && browserTyped.getInt("totalChars") == browserExpected.length() && "browser".equals(browserTyped.getString("textEntryMode"))
                    && !browserTyped.getBoolean("textAppliedVerified"), "Browser entry was not the exact fixed URL with an honest receipt");
            require(browserClock.now() - browserStarted > 65000 && browserClock.now() - browserStarted < 90000
                    && browserClock.sleeps.size() == browserExpected.length() - 1 && browserClock.sleeps.stream().allMatch(delay -> delay == 150), "Browser entry budget or Unicode pacing changed");
            for (String unsafe : new String[] {"https://httpbin.org/redirect-to?url=http://elsewhere.test", "https://192.168.50.2:4178", "http://127.0.0.1:4178", START + "/msx/connect.html", START + "?url=other", "http://user@192.168.50.2:4178"}) rejects("TEXT_INVALID_INPUT", () -> browser.typeBrowserAddress(HOST, unsafe));
            require(browserFactory.inputCount == browserExpected.length(), "Invalid browser entry wrote caller-provided text"); browser.close();
            Clock browserCancelClock = new Clock(); Factory browserCancelFactory = new Factory(browserCancelClock);
            PhilipsTextController browserCancel = new PhilipsTextController(browserStore, browserCancelFactory, browserCancelClock);
            browserCancelClock.onSleep = browserCancel::cancel; rejects("TEXT_CANCELLED", () -> browserCancel.typeBrowserAddress(HOST, START));
            JSONObject browserCanceled = browserCancel.status(HOST).getJSONObject("lastTyping");
            require(browserCancelFactory.inputCount == 1 && browserCanceled.getInt("acknowledgedChars") == 1 && browserCanceled.getBoolean("partialInput")
                    && browserCanceled.getInt("totalChars") == browserExpected.length() && "browser".equals(browserCanceled.getString("textEntryMode")), "Browser cancellation lost its partial receipt or sent a second key"); browserCancel.close();
            Clock browserPartialClock = new Clock(); Factory browserPartialFactory = new Factory(browserPartialClock); browserPartialFactory.failInput = 3;
            PhilipsTextController browserPartial = new PhilipsTextController(browserStore, browserPartialFactory, browserPartialClock);
            rejects("TEXT_TIMEOUT", () -> browserPartial.typeBrowserAddress(HOST, START)); JSONObject browserReceipt = browserPartial.status(HOST).getJSONObject("lastTyping");
            require(browserPartialFactory.inputCount == 3 && browserReceipt.getInt("acknowledgedChars") == 2 && browserReceipt.getBoolean("partialInput")
                    && "browser".equals(browserReceipt.getString("textEntryMode")) && !browserReceipt.getBoolean("textAppliedVerified"), "Uncertain browser character retried or partial receipt lost"); browserPartial.close();
            passed(runner, ++checks, "Fixed MSX/browser entries reuse saved permission, pace one codepoint and preserve bounded cancellation/partial receipts without submitting");

            Clock launchClock = new Clock(); Factory launchFactory = new Factory(launchClock);
            PhilipsTextController launch = new PhilipsTextController(browserStore, launchFactory, launchClock);
            long launchStarted = launchClock.now(); JSONObject launched = launch.launchBrowser(HOST, START);
            require(launched.getBoolean("accepted") && !launched.getBoolean("pageLoadVerified") && launchFactory.calls.size() == 2
                    && "system".equals(launchFactory.calls.get(0).getString("path")) && "activities/browser".equals(launchFactory.calls.get(1).getString("path"))
                    && browserExpected.equals(launchFactory.calls.get(1).getJSONObject("body").getString("url")), "Browser launch skipped fresh support or mistook acceptance for loading");
            require(launchFactory.calls.get(0).getLong("deadline") == launchStarted + 15000 && launchFactory.calls.get(1).getLong("deadline") == launchStarted + 30000
                    && launchFactory.inputCount == 0 && launchClock.sleeps.isEmpty() && launchFactory.pins.size() == 1, "Browser launch changed its bounded budget, reused no saved pin, or typed/submitted keys");
            for (String unsafe : new String[] {"https://elsewhere.test", START + "/msx/connect.html", START + "?url=other"}) rejects("TEXT_INVALID_INPUT", () -> launch.launchBrowser(HOST, unsafe));
            require(launchFactory.browserCount == 1 && launchFactory.calls.size() == 2, "Invalid launch input reached a transport"); launch.close();
            Clock unsupportedBrowserClock = new Clock(); Factory unsupportedBrowserFactory = new Factory(unsupportedBrowserClock); unsupportedBrowserFactory.browserSupported = false;
            PhilipsTextController unsupportedBrowser = new PhilipsTextController(browserStore, unsupportedBrowserFactory, unsupportedBrowserClock);
            rejects("TEXT_BROWSER_UNSUPPORTED", () -> unsupportedBrowser.launchBrowser(HOST, START));
            require(unsupportedBrowserFactory.calls.size() == 1 && unsupportedBrowserFactory.browserCount == 0, "Unadvertised browser still received a launch POST"); unsupportedBrowser.close();
            Clock cancelledLaunchClock = new Clock(); Factory cancelledLaunchFactory = new Factory(cancelledLaunchClock);
            PhilipsTextController cancelledLaunch = new PhilipsTextController(browserStore, cancelledLaunchFactory, cancelledLaunchClock); cancelledLaunchFactory.onSystem = cancelledLaunch::cancel;
            rejects("TEXT_CANCELLED", () -> cancelledLaunch.launchBrowser(HOST, START));
            require(cancelledLaunchFactory.calls.size() == 1 && cancelledLaunchFactory.browserCount == 0, "Cancelled launch sent a POST after capability lookup"); cancelledLaunch.close();
            for (String launchError : new String[] {"TEXT_TIMEOUT", "TEXT_REJECTED"}) {
                Clock rejectedLaunchClock = new Clock(); Factory rejectedLaunchFactory = new Factory(rejectedLaunchClock); rejectedLaunchFactory.browserError = launchError;
                PhilipsTextController rejectedLaunch = new PhilipsTextController(browserStore, rejectedLaunchFactory, rejectedLaunchClock);
                rejects(launchError, () -> rejectedLaunch.launchBrowser(HOST, START));
                require(rejectedLaunchFactory.browserCount == 1 && rejectedLaunchFactory.calls.size() == 2 && !rejectedLaunch.status(HOST).getBoolean("typingBusy"), "Failed browser launch retried or remained busy"); rejectedLaunch.close();
            }
            require(PhilipsTextController.fixedBrowserSetupUrl(browserExpected), "Fixed browser URL was not admitted");
            for (String unsafe : new String[] {"https://httpbin.org/redirect-to?url=http%3A%2F%2F192.168.50.2%3A4178%2Fmsx%2Fconnect.html", "http://127.0.0.1:4178/msx/connect.html", "https://192.168.50.2:4178/msx/connect.html", "http://8.8.8.8:4178/msx/connect.html", "http://192.168.50.2:80/msx/connect.html", "http://user@192.168.50.2:4178/msx/connect.html", browserExpected + "?extra=1", browserExpected + "#fragment", browserExpected.replace("connect.html", "other.html")}) require(!PhilipsTextController.fixedBrowserSetupUrl(unsafe), "Browser transport accepted an arbitrary URL or route");
            passed(runner, ++checks, "Browser shortcut checks fresh advertised support, reuses saved permission and reports acceptance without load proof or automatic retries");

            Object previousFactory = HttpsURLConnection.getDefaultSSLSocketFactory(), previousVerifier = HttpsURLConnection.getDefaultHostnameVerifier();
            PhilipsTextController.HttpTransport scoped = new PhilipsTextController.HttpTransport(HOST, PIN); scoped.close();
            rejects("TEXT_INVALID_INPUT", () -> scoped.request("activities/browser", object("url", "https://elsewhere.test"), "fixture", "fixture", false, 0));
            require(previousFactory == HttpsURLConnection.getDefaultSSLSocketFactory() && previousVerifier == HttpsURLConnection.getDefaultHostnameVerifier(), "TV TLS settings changed global network defaults");
            byte[] first = "owned certificate A".getBytes(StandardCharsets.UTF_8), changed = "owned certificate B".getBytes(StandardCharsets.UTF_8);
            String fingerprint = PhilipsTextController.pinnedCertificateHash(first, "");
            require(fingerprint.equals(PhilipsTextController.pinnedCertificateHash(first, fingerprint)), "Pinned certificate was not accepted");
            try { PhilipsTextController.pinnedCertificateHash(changed, fingerprint); throw new AssertionError("Changed TV certificate accepted"); } catch (java.security.cert.CertificateException expectedFailure) { }
            rejects("TEXT_ADDRESS_UNAVAILABLE", () -> new PhilipsTextController.HttpTransport("8.8.8.8", PIN));
            runner.getTargetContext().getSharedPreferences("philips-text", 0).edit().clear().commit();
            PhilipsTextController.EncryptedStore encrypted = new PhilipsTextController.EncryptedStore(runner.getTargetContext());
            encrypted.save(store.saved);
            require(encrypted.read().getString("auth_key").equals("fixture-auth-key"), "Encrypted credentials did not round-trip");
            String envelope = runner.getTargetContext().getSharedPreferences("philips-text", 0).getString("credentials", "");
            require(!envelope.contains("fixture-auth-key") && !envelope.contains(HOST) && !envelope.contains(store.saved.getString("id")), "App preferences stored plaintext TV credentials");
            runner.getTargetContext().getSharedPreferences("philips-text", 0).edit().clear().commit();
            passed(runner, ++checks, "Per-TV certificate pin, private target and Keystore credentials leave global TLS unchanged");
            result.putInt("passed", checks); result.putInt("failures", 0); runner.finish(Activity.RESULT_OK, result);
        } catch (Throwable failure) { result.putInt("passed", checks); result.putInt("failures", 1); result.putString("failure", failure.getClass().getSimpleName() + ": " + String.valueOf(failure.getMessage())); runner.finish(Activity.RESULT_CANCELED, result); }
    }
}
