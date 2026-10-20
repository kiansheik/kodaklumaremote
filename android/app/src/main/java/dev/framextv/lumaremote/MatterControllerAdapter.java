package dev.framextv.lumaremote;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import chip.devicecontroller.ChipClusters;
import chip.devicecontroller.ChipDeviceController;
import chip.devicecontroller.ChipStructs;
import chip.devicecontroller.CommissionParameters;
import chip.devicecontroller.ControllerParams;
import chip.devicecontroller.DeviceAttestation;
import chip.devicecontroller.GetConnectedDeviceCallbackJni.GetConnectedDeviceCallback;
import chip.devicecontroller.ICDDeviceInfo;
import chip.devicecontroller.OpenCommissioningCallback;
import chip.devicecontroller.UnpairDeviceCallback;
import chip.platform.AndroidBleManager;
import chip.platform.AndroidChipPlatform;
import chip.platform.AndroidNfcCommissioningManager;
import chip.platform.ChipMdnsCallbackImpl;
import chip.platform.DiagnosticDataProviderImpl;
import chip.platform.KeyValueStoreManager;
import chip.platform.NsdManagerServiceBrowser;
import chip.platform.NsdManagerServiceResolver;
import chip.platform.PreferencesConfigurationManager;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** A local, independent Matter administrator. No Google Home fabric or account. */
public final class MatterControllerAdapter implements AutoCloseable {
    public interface Listener { void onStateChanged(JSONObject snapshot); }
    public interface Callback { void success(JSONObject result); void failure(String code, String message); }
    interface PowerOnCluster {
        void readAcceptedCommands(ChipClusters.OnOffCluster.AcceptedCommandListAttributeCallback callback);
        void on(ChipClusters.DefaultClusterCallback callback);
        void readOnOff(ChipClusters.BooleanAttributeCallback callback);
    }
    interface PowerOnResult {
        boolean current();
        void capabilities(List<Long> commands);
        void acknowledged();
        void accepted(Boolean onOff);
        void rejected(String code);
    }
    private interface PointerAction { void run(long pointer, Request request); }
    private static final Object PLATFORM_LOCK = new Object();
    private static final ExecutorService SDK_WORKER = Executors.newSingleThreadExecutor();
    private static AndroidChipPlatform sharedPlatform;
    private static ChipDeviceController sharedController;
    private static MatterControllerAdapter controllerOwner;

    private final Context context;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final SharedPreferences metadata;
    private volatile JSONObject published = object("available", false, "paired", false, "connected", false, "pairing", false, "paused", false,
            "name", "Philips TV", "capabilities", object("navigation", false, "power", false, "powerOn", false, "volume", false, "inputs", false), "inputs", new JSONArray());
    private volatile boolean closed;
    private volatile String resolvedPrivateAddress = "";
    private ChipDeviceController controller;
    private AndroidChipPlatform platform;
    private boolean available, connected, pairing, refreshing, paused;
    private boolean navigationSupported, powerSupported, powerOnSupported, volumeSupported, inputsSupported, openWindowSupported;
    private long nodeId, pairingNode, generation, lastRead;
    private int keyEndpoint = -1, powerEndpoint = -1, volumeEndpoint = -1, inputEndpoint = -1;
    private int currentVolume = -1, minVolume = 0, maxVolume = 254, currentInput = -1;
    private String name = "Philips TV", errorCode = "";
    private JSONArray inputs = new JSONArray();
    private final Map<Integer, List<Long>> clusters = new LinkedHashMap<>();
    private final Map<Integer, Boolean> videoEndpoints = new LinkedHashMap<>();
    private final Map<Integer, Boolean> speakerEndpoints = new LinkedHashMap<>();

    public MatterControllerAdapter(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        metadata = this.context.getSharedPreferences("matter-controller", Context.MODE_PRIVATE);
        nodeId = metadata.getLong("node", 0);
        publish();
        submit(this::initialize);
    }

    public JSONObject snapshot() { return copy(published); }

    /** Internal address of this phone's already paired TV; never a caller-selected target. */
    public String currentPrivateAddress() { return resolvedPrivateAddress; }
    public void resolvePrivateAddress(Callback callback) {
        submit(() -> {
            if (closed || !available || controller == null || nodeId == 0 || pairing || paused) {
                main.post(() -> callback.failure("TEXT_ADDRESS_UNAVAILABLE", "Reconnect the paired TV.")); return;
            }
            Request request = new Request(callback, generation, 15000);
            withPointer(request, (pointer, operation) -> {
                if (resolvedPrivateAddress.isEmpty()) { operation.fail("TEXT_ADDRESS_UNAVAILABLE"); return; }
                operation.success(object("host", resolvedPrivateAddress));
            });
        });
    }

    public void request(String method, JSONObject args, Callback callback) {
        if (closed) { main.post(() -> callback.failure("CANCELLED", "Matter controller is closed.")); return; }
        JSONObject input = args == null ? new JSONObject() : copy(args);
        if ("matter.status".equals(method)) {
            if (input.optBoolean("reconnect", false)) submit(() -> {
                paused = false; errorCode = ""; publish();
                main.post(() -> callback.success(snapshot()));
                if (available && nodeId != 0 && !pairing && !refreshing) refresh(null);
            });
            else {
                main.post(() -> callback.success(snapshot()));
                submit(() -> { if (available && nodeId != 0 && !paused && !pairing && !refreshing && android.os.SystemClock.elapsedRealtime() - lastRead > 3000) refresh(null); });
            }
            return;
        }
        submit(() -> {
            if (closed) { main.post(() -> callback.failure("CANCELLED", "Matter controller is closed.")); return; }
            Request request = new Request(callback, generation, "matter.pair".equals(method) ? 10000 : 15000);
            if ("matter.disconnect".equals(method)) {
                resolvedPrivateAddress = "";
                paused = true; connected = false; refreshing = false; generation++;
                cancelPairing();
                publish(); request.success(snapshot()); return;
            }
            if ("matter.forget".equals(method)) {
                if (!Boolean.TRUE.equals(input.opt("confirmed")) || input.has("localOnly") && !(input.opt("localOnly") instanceof Boolean)) { request.fail("INVALID_INPUT"); return; }
                if (Boolean.TRUE.equals(input.opt("localOnly"))) { clearSavedPairing(request, "LOCAL_ONLY", false); return; }
            }
            if (!available || controller == null) { request.fail("MATTER_UNAVAILABLE"); return; }
            if (pairing) { request.fail("BUSY"); return; }
            if (paused && !"matter.pair".equals(method) && !"matter.forget".equals(method)) { request.fail("MATTER_UNAVAILABLE"); return; }
            try {
                switch (method) {
                    case "matter.pair": pair(input, request); break;
                    case "matter.key":
                        int code = keyCode(input.optString("key", ""));
                        if (code < 0) { request.fail("INVALID_INPUT"); break; }
                        if (keyEndpoint < 0 || !navigationSupported) { request.fail("MATTER_UNSUPPORTED"); break; }
                        withPointer(request, (pointer, work) -> new ChipClusters.KeypadInputCluster(pointer, keyEndpoint).sendKey(new ChipClusters.KeypadInputCluster.SendKeyResponseCallback() {
                            public void onSuccess(Integer status) { submit(() -> { if (!guard(work)) return; if (status != null && status == 0) { errorCode = ""; publish(); work.success(snapshot()); } else work.fail("MATTER_REJECTED"); }); }
                            public void onError(Exception error) { work.fail("MATTER_REJECTED"); }
                        }, code));
                        break;
                    case "matter.volume":
                        Object value = input.opt("value");
                        if (!(value instanceof Number) || ((Number) value).doubleValue() != ((Number) value).intValue()
                                || ((Number) value).intValue() < minVolume || ((Number) value).intValue() > maxVolume) { request.fail("INVALID_INPUT"); break; }
                        if (volumeEndpoint < 0 || currentVolume < 0 || !volumeSupported) { request.fail("MATTER_UNSUPPORTED"); break; }
                        int level = ((Number) value).intValue();
                        withPointer(request, (pointer, work) -> new ChipClusters.LevelControlCluster(pointer, volumeEndpoint).moveToLevel(commandCallback(work, () -> readVolume(pointer, work, () -> work.success(snapshot()))), level, 0, 0, 0));
                        break;
                    case "matter.input":
                        if (!(input.opt("index") instanceof Number) || input.optDouble("index") != input.optInt("index")) { request.fail("INVALID_INPUT"); break; }
                        int index = input.optInt("index", -1);
                        if (index < 0 || !containsInput(index)) { request.fail("INVALID_INPUT"); break; }
                        if (inputEndpoint < 0 || !inputsSupported) { request.fail("MATTER_UNSUPPORTED"); break; }
                        withPointer(request, (pointer, work) -> new ChipClusters.MediaInputCluster(pointer, inputEndpoint).selectInput(commandCallback(work, () -> { currentInput = index; publish(); work.success(snapshot()); }), index));
                        break;
                    case "matter.powerOff":
                        if (!Boolean.TRUE.equals(input.opt("confirmed"))) { request.fail("INVALID_INPUT"); break; }
                        if (powerEndpoint < 0 || !powerSupported) { request.fail("MATTER_UNSUPPORTED"); break; }
                        withPointer(request, (pointer, work) -> new ChipClusters.OnOffCluster(pointer, powerEndpoint).off(commandCallback(work, () -> work.success(snapshot()))));
                        break;
                    case "matter.powerOn":
                        if (input.length() != 0) { request.fail("INVALID_INPUT"); break; }
                        if (powerEndpoint < 0 || !powerOnSupported) { request.fail("MATTER_UNSUPPORTED"); break; }
                        withPointer(request, this::powerOn);
                        break;
                    case "matter.openWindow": openWindow(request); break;
                    case "matter.forget": forget(input, request); break;
                    default: request.fail("INVALID_REQUEST");
                }
            } catch (Throwable error) { request.fail("MATTER_REJECTED"); }
        });
    }

    private void initialize() {
        if (closed) return;
        try {
            ChipDeviceController.loadJni();
            synchronized (PLATFORM_LOCK) {
                if (sharedPlatform == null) sharedPlatform = new AndroidChipPlatform(new AndroidBleManager(context), new AndroidNfcCommissioningManager(),
                        new EncryptedStorage(context), new PreferencesConfigurationManager(context),
                        new NsdManagerServiceResolver(context, new NsdManagerServiceResolver.NsdManagerResolverAvailState()),
                        new NsdManagerServiceBrowser(context), new ChipMdnsCallbackImpl(), new DiagnosticDataProviderImpl(context));
                platform = sharedPlatform;
                if (sharedController == null) sharedController = new ChipDeviceController(ControllerParams.newBuilder().setControllerVendorId(0xFFF4).setEnableServerInteractions(true).build());
                controller = sharedController;
                controllerOwner = this;
            }
            final List<byte[]> paas = certificates("matter/paa");
            final List<byte[]> cds = certificates("matter/cd");
            if (paas.isEmpty() || cds.isEmpty()) throw new IllegalStateException("Missing public attestation roots");
            final Map<String, byte[]> roots = new LinkedHashMap<>();
            for (byte[] cert : paas) roots.put(Base64.encodeToString(DeviceAttestation.extractSkidFromPaaCert(cert), Base64.NO_WRAP), cert);
            controller.setAttestationTrustStoreDelegate(skid -> roots.get(Base64.encodeToString(skid, Base64.NO_WRAP)), cds);
            controller.setCompletionListener(completion);
            controller.startDnssd();
            available = true;
            publish();
            if (nodeId != 0) refresh(null);
        } catch (Throwable error) { available = false; connected = false; errorCode = "MATTER_UNAVAILABLE"; publish(); }
    }

    private void pair(JSONObject args, Request request) {
        if (nodeId != 0) { request.fail("MATTER_ALREADY_PAIRED"); return; }
        String code = args.optString("setupCode", "").trim();
        if (!code.startsWith("MT:")) code = code.replace(" ", "").replace("-", "");
        if (!(code.matches("[0-9]{11}|[0-9]{21}") || code.matches("MT:[0-9A-Z.\\-]{10,256}"))) { request.fail("INVALID_INPUT"); return; }
        pairingNode = new SecureRandom().nextLong() & Long.MAX_VALUE;
        if (pairingNode == 0) pairingNode = 1;
        generation++;
        pairing = true; connected = false; paused = false; errorCode = "";
        long attempt = generation;
        publish();
        try {
            controller.pairDeviceWithCode(pairingNode, code, true, true, new CommissionParameters.Builder().build());
            request.success(snapshot());
            main.postDelayed(() -> submit(() -> {
                if (!pairing || generation != attempt) return;
                cancelPairing(); errorCode = "MATTER_TIMEOUT"; publish();
            }), 120000);
        } catch (Throwable error) { pairing = false; errorCode = "MATTER_PAIRING"; publish(); request.fail("MATTER_PAIRING"); }
    }

    private final ChipDeviceController.CompletionListener completion = new ChipDeviceController.CompletionListener() {
        public void onConnectDeviceComplete() {}
        public void onStatusUpdate(int status) {}
        public void onPairingComplete(long code) { if (code != 0) pairFailed(); }
        public void onPairingDeleted(long code) {}
        public void onCommissioningComplete(long pairedNode, long code) { submit(() -> {
            if (!pairing || pairedNode != pairingNode) return;
            pairing = false;
            if (code != 0) { errorCode = "MATTER_PAIRING"; publish(); return; }
            if (!metadata.edit().putLong("node", pairedNode).commit()) { errorCode = "MATTER_STORAGE"; publish(); return; }
            nodeId = pairedNode; errorCode = ""; publish(); refresh(null);
        }); }
        public void onReadCommissioningInfo(int vendor, int product, int wifi, int thread) {}
        public void onCommissioningStatusUpdate(long node, String stage, long code) {}
        public void onCommissioningStageStart(long node, String stage) {}
        public void onNotifyChipConnectionClosed() {}
        public void onCloseBleComplete() {}
        public void onError(Throwable error) { pairFailed(); }
        public void onOpCSRGenerationComplete(byte[] csr) {}
        public void onICDRegistrationInfoRequired() {}
        public void onICDRegistrationComplete(long code, ICDDeviceInfo info) {}
    };

    private void cancelPairing() {
        boolean active = pairing; pairing = false;
        if (active && controller != null) try { controller.stopDevicePairing(pairingNode); } catch (Throwable ignored) { }
        pairingNode = 0;
    }
    private void pairFailed() { submit(() -> { if (pairing) { cancelPairing(); errorCode = "MATTER_PAIRING"; publish(); } }); }

    private void refresh(Request request) {
        if (closed || refreshing || pairing || paused || nodeId == 0) { if (request != null) request.success(snapshot()); return; }
        refreshing = true; powerOnSupported = false; lastRead = android.os.SystemClock.elapsedRealtime();
        Request work = request == null ? new Request(new Callback() { public void success(JSONObject result) {} public void failure(String code, String text) {} }, generation, 15000) : request;
        withPointer(work, (pointer, operation) -> {
            clusters.clear(); videoEndpoints.clear(); speakerEndpoints.clear();
            new ChipClusters.DescriptorCluster(pointer, 0).readPartsListAttribute(new ChipClusters.DescriptorCluster.PartsListAttributeCallback() {
                public void onSuccess(List<Integer> value) { submit(() -> {
                    if (!guard(operation)) return;
                    ArrayList<Integer> endpoints = new ArrayList<>(); endpoints.add(0);
                    for (Integer endpoint : value) if (endpoint != null && endpoint > 0 && endpoint <= 65535 && !endpoints.contains(endpoint) && endpoints.size() < 64) endpoints.add(endpoint);
                    readEndpoint(pointer, endpoints, 0, operation);
                }); }
                public void onError(Exception error) { submit(() -> { if (!guard(operation)) return; refreshing = false; operation.fail("MATTER_UNAVAILABLE"); }); }
            });
        });
    }

    private void readEndpoint(long pointer, List<Integer> endpoints, int index, Request request) {
        if (!guard(request)) return;
        if (index >= endpoints.size()) { finishDiscovery(pointer, request); return; }
        int endpoint = endpoints.get(index);
        ChipClusters.DescriptorCluster descriptor = new ChipClusters.DescriptorCluster(pointer, endpoint);
        descriptor.readServerListAttribute(new ChipClusters.DescriptorCluster.ServerListAttributeCallback() {
            public void onSuccess(List<Long> value) { submit(() -> {
                if (!guard(request)) return;
                clusters.put(endpoint, new ArrayList<>(value));
                descriptor.readDeviceTypeListAttribute(new ChipClusters.DescriptorCluster.DeviceTypeListAttributeCallback() {
                    public void onSuccess(List<ChipStructs.DescriptorClusterDeviceTypeStruct> types) { submit(() -> {
                        if (!guard(request)) return;
                        boolean video = false, speaker = false;
                        for (ChipStructs.DescriptorClusterDeviceTypeStruct type : types) if (type.deviceType != null) {
                            if (type.deviceType == 0x23L || type.deviceType == 0x28L) video = true;
                            if (type.deviceType == 0x22L) speaker = true;
                        }
                        videoEndpoints.put(endpoint, video); speakerEndpoints.put(endpoint, speaker); readEndpoint(pointer, endpoints, index + 1, request);
                    }); }
                    public void onError(Exception error) { submit(() -> readEndpoint(pointer, endpoints, index + 1, request)); }
                });
            }); }
            public void onError(Exception error) { submit(() -> readEndpoint(pointer, endpoints, index + 1, request)); }
        });
    }

    private void finishDiscovery(long pointer, Request request) {
        if (!guard(request)) return;
        keyEndpoint = powerEndpoint = volumeEndpoint = inputEndpoint = -1;
        navigationSupported = powerSupported = powerOnSupported = volumeSupported = inputsSupported = openWindowSupported = false;
        List<Integer> speakerLevels = new ArrayList<>(), videoLevels = new ArrayList<>();
        for (Map.Entry<Integer, List<Long>> entry : clusters.entrySet()) {
            int endpoint = entry.getKey(); List<Long> servers = entry.getValue();
            boolean video = Boolean.TRUE.equals(videoEndpoints.get(endpoint)) || servers.contains(1289L) || servers.contains(1287L) || servers.contains(1286L);
            if (keyEndpoint < 0 && servers.contains(1289L)) keyEndpoint = endpoint;
            if (inputEndpoint < 0 && servers.contains(1287L)) inputEndpoint = endpoint;
            if (powerEndpoint < 0 && video && servers.contains(6L)) powerEndpoint = endpoint;
            if (servers.contains(8L)) {
                if (Boolean.TRUE.equals(speakerEndpoints.get(endpoint))) speakerLevels.add(endpoint);
                else if (video) videoLevels.add(endpoint);
            }
        }
        if (speakerLevels.size() == 1) volumeEndpoint = speakerLevels.get(0);
        else if (speakerLevels.isEmpty() && videoLevels.size() == 1) volumeEndpoint = videoLevels.get(0);
        connected = true; errorCode = "";
        new ChipClusters.BasicInformationCluster(pointer, 0).readProductNameAttribute(new ChipClusters.CharStringAttributeCallback() {
            public void onSuccess(String value) { submit(() -> { if (request.revision != generation || closed) return; if (value != null && !value.trim().isEmpty()) name = safeName(value); }); }
            public void onError(Exception error) {}
        });
        Runnable done = () -> { refreshing = false; connected = true; errorCode = ""; publish(); request.success(snapshot()); };
        probeCommands(pointer, request, 0, () -> readInputs(pointer, request, done));
    }

    private void readInputs(long pointer, Request request, Runnable done) {
        if (!guard(request)) return;
        if (inputEndpoint >= 0 && inputsSupported) {
            new ChipClusters.MediaInputCluster(pointer, inputEndpoint).readInputListAttribute(new ChipClusters.MediaInputCluster.InputListAttributeCallback() {
                public void onSuccess(List<ChipStructs.MediaInputClusterInputInfoStruct> value) { submit(() -> {
                    if (!guard(request)) return;
                    JSONArray observed = new JSONArray();
                    for (ChipStructs.MediaInputClusterInputInfoStruct input : value) if (input.index != null && input.index >= 0 && input.index <= 255 && observed.length() < 64) observed.put(object("index", input.index, "name", safeName(input.name)));
                    inputs = observed;
                    new ChipClusters.MediaInputCluster(pointer, inputEndpoint).readCurrentInputAttribute(new ChipClusters.IntegerAttributeCallback() {
                        public void onSuccess(int value) { submit(() -> { if (!guard(request)) return; currentInput = value; readVolume(pointer, request, done); }); }
                        public void onError(Exception error) { submit(() -> readVolume(pointer, request, done)); }
                    });
                }); }
                public void onError(Exception error) { submit(() -> { if (!guard(request)) return; inputs = new JSONArray(); readVolume(pointer, request, done); }); }
            });
        } else readVolume(pointer, request, done);
    }

    private void probeCommands(long pointer, Request request, int step, Runnable done) {
        if (!guard(request)) return;
        Runnable next = () -> probeCommands(pointer, request, step + 1, done);
        if (step == 0 && keyEndpoint >= 0) {
            new ChipClusters.KeypadInputCluster(pointer, keyEndpoint).readAcceptedCommandListAttribute(new ChipClusters.KeypadInputCluster.AcceptedCommandListAttributeCallback() {
                public void onSuccess(List<Long> value) { submit(() -> { if (!guard(request)) return; navigationSupported = value.contains(0L); next.run(); }); }
                public void onError(Exception error) { submit(next); }
            });
        } else if (step == 1 && powerEndpoint >= 0) {
            new ChipClusters.OnOffCluster(pointer, powerEndpoint).readAcceptedCommandListAttribute(new ChipClusters.OnOffCluster.AcceptedCommandListAttributeCallback() {
                public void onSuccess(List<Long> value) { submit(() -> { if (!guard(request)) return; powerSupported = value.contains(0L); powerOnSupported = value.contains(1L); next.run(); }); }
                public void onError(Exception error) { submit(next); }
            });
        } else if (step == 2 && volumeEndpoint >= 0) {
            new ChipClusters.LevelControlCluster(pointer, volumeEndpoint).readAcceptedCommandListAttribute(new ChipClusters.LevelControlCluster.AcceptedCommandListAttributeCallback() {
                public void onSuccess(List<Long> value) { submit(() -> { if (!guard(request)) return; volumeSupported = value.contains(0L); next.run(); }); }
                public void onError(Exception error) { submit(next); }
            });
        } else if (step == 3 && inputEndpoint >= 0) {
            new ChipClusters.MediaInputCluster(pointer, inputEndpoint).readAcceptedCommandListAttribute(new ChipClusters.MediaInputCluster.AcceptedCommandListAttributeCallback() {
                public void onSuccess(List<Long> value) { submit(() -> { if (!guard(request)) return; inputsSupported = value.contains(0L); next.run(); }); }
                public void onError(Exception error) { submit(next); }
            });
        } else if (step == 4 && clusters.containsKey(0) && clusters.get(0).contains(60L)) {
            new ChipClusters.AdministratorCommissioningCluster(pointer, 0).readAcceptedCommandListAttribute(new ChipClusters.AdministratorCommissioningCluster.AcceptedCommandListAttributeCallback() {
                public void onSuccess(List<Long> value) { submit(() -> { if (!guard(request)) return; openWindowSupported = value.contains(0L); next.run(); }); }
                public void onError(Exception error) { submit(next); }
            });
        } else if (step >= 5) done.run();
        else next.run();
    }

    private void readVolume(long pointer, Request request, Runnable done) {
        if (!guard(request)) return;
        if (volumeEndpoint < 0 || !volumeSupported) { done.run(); return; }
        ChipClusters.LevelControlCluster cluster = new ChipClusters.LevelControlCluster(pointer, volumeEndpoint);
        cluster.readMinLevelAttribute(new ChipClusters.IntegerAttributeCallback() {
            public void onSuccess(int value) { submit(() -> { if (!guard(request)) return; minVolume = value; readMax(); }); }
            public void onError(Exception error) { submit(this::readMax); }
            private void readMax() { if (!guard(request)) return; cluster.readMaxLevelAttribute(new ChipClusters.IntegerAttributeCallback() {
                public void onSuccess(int value) { submit(() -> { if (!guard(request)) return; maxVolume = value; readCurrent(); }); }
                public void onError(Exception error) { submit(this::readCurrent); }
                private void readCurrent() { if (!guard(request)) return; cluster.readCurrentLevelAttribute(new ChipClusters.LevelControlCluster.CurrentLevelAttributeCallback() {
                    public void onSuccess(Integer value) { submit(() -> { if (!guard(request)) return; currentVolume = value == null ? -1 : value; publish(); done.run(); }); }
                    public void onError(Exception error) { submit(() -> { if (!guard(request)) return; currentVolume = -1; publish(); done.run(); }); }
                }); }
            }); }
        });
    }

    private void withPointer(Request request, PointerAction action) {
        if (nodeId == 0) { request.fail("MATTER_NOT_PAIRED"); return; }
        controller.getConnectedDevicePointer(nodeId, new GetConnectedDeviceCallback() {
            public void onDeviceConnected(long pointer) { submit(() -> {
                if (!request.current()) { controller.releaseConnectedDevicePointer(pointer); return; }
                request.pointer = pointer;
                connected = true;
                try {
                    String resolved = controller.getIpAddress(nodeId);
                    resolvedPrivateAddress = MsxServer.privateAddress(resolved) ? resolved : "";
                } catch (Throwable unavailable) { resolvedPrivateAddress = ""; }
                try { action.run(pointer, request); } catch (Throwable error) { request.fail("MATTER_REJECTED"); }
            }); }
            public void onConnectionFailure(long node, Exception error) { submit(() -> { if (!guard(request)) return; resolvedPrivateAddress = ""; connected = false; refreshing = false; errorCode = "MATTER_NOT_CONNECTED"; publish(); request.fail("MATTER_NOT_CONNECTED"); }); }
        });
    }

    private ChipClusters.DefaultClusterCallback commandCallback(Request request, Runnable success) {
        return new ChipClusters.DefaultClusterCallback() {
            public void onSuccess() { submit(() -> { if (guard(request)) { errorCode = ""; publish(); success.run(); } }); }
            public void onError(Exception error) { request.fail("MATTER_REJECTED"); }
        };
    }
    private void powerOn(long pointer, Request request) {
        ChipClusters.OnOffCluster cluster = new ChipClusters.OnOffCluster(pointer, powerEndpoint);
        requestPowerOn(new PowerOnCluster() {
            public void readAcceptedCommands(ChipClusters.OnOffCluster.AcceptedCommandListAttributeCallback callback) {
                cluster.readAcceptedCommandListAttribute(new ChipClusters.OnOffCluster.AcceptedCommandListAttributeCallback() {
                    public void onSuccess(List<Long> value) { submit(() -> callback.onSuccess(value)); }
                    public void onError(Exception error) { submit(() -> callback.onError(error)); }
                });
            }
            public void on(ChipClusters.DefaultClusterCallback callback) {
                cluster.on(new ChipClusters.DefaultClusterCallback() {
                    public void onSuccess() { submit(callback::onSuccess); }
                    public void onError(Exception error) { submit(() -> callback.onError(error)); }
                });
            }
            public void readOnOff(ChipClusters.BooleanAttributeCallback callback) {
                cluster.readOnOffAttribute(new ChipClusters.BooleanAttributeCallback() {
                    public void onSuccess(boolean value) { submit(() -> callback.onSuccess(value)); }
                    public void onError(Exception error) { submit(() -> callback.onError(error)); }
                });
            }
        }, new PowerOnResult() {
            public boolean current() { return guard(request); }
            public void capabilities(List<Long> commands) { powerSupported = commands.contains(0L); powerOnSupported = commands.contains(1L); publish(); }
            public void acknowledged() { request.powerOnAcknowledged = true; }
            public void accepted(Boolean onOff) {
                errorCode = ""; publish(); request.success(powerOnReceipt(onOff));
            }
            public void rejected(String code) { request.fail(code); }
        });
    }
    private JSONObject powerOnReceipt(Boolean onOff) {
        JSONObject result = snapshot();
        put(result, "accepted", true); put(result, "powerStateVerified", Boolean.TRUE.equals(onOff)); put(result, "onOff", onOff == null ? JSONObject.NULL : onOff);
        return result;
    }
    /** Fresh capability gate and one discrete On, followed by optional observed state. */
    static void requestPowerOn(PowerOnCluster cluster, PowerOnResult result) {
        AtomicBoolean invoked = new AtomicBoolean(), acknowledged = new AtomicBoolean(), finished = new AtomicBoolean();
        ChipClusters.BooleanAttributeCallback readback = new ChipClusters.BooleanAttributeCallback() {
            public void onSuccess(boolean value) { if (result.current() && finished.compareAndSet(false, true)) result.accepted(value); }
            public void onError(Exception error) { if (result.current() && finished.compareAndSet(false, true)) result.accepted(null); }
        };
        ChipClusters.DefaultClusterCallback command = new ChipClusters.DefaultClusterCallback() {
            public void onSuccess() {
                if (!result.current() || !acknowledged.compareAndSet(false, true) || finished.get()) return;
                result.acknowledged();
                try { cluster.readOnOff(readback); } catch (RuntimeException unavailable) { readback.onError(unavailable); }
            }
            public void onError(Exception error) { if (result.current() && !acknowledged.get() && finished.compareAndSet(false, true)) result.rejected("MATTER_REJECTED"); }
        };
        ChipClusters.OnOffCluster.AcceptedCommandListAttributeCallback supported = new ChipClusters.OnOffCluster.AcceptedCommandListAttributeCallback() {
            public void onSuccess(List<Long> commands) {
                if (!result.current() || !invoked.compareAndSet(false, true)) return;
                List<Long> known = commands == null ? new ArrayList<>() : commands;
                result.capabilities(known);
                if (!known.contains(1L)) { finished.set(true); result.rejected("MATTER_UNSUPPORTED"); return; }
                try { cluster.on(command); } catch (RuntimeException unavailable) { command.onError(unavailable); }
            }
            public void onError(Exception error) {
                if (result.current() && invoked.compareAndSet(false, true)) { finished.set(true); result.capabilities(new ArrayList<>()); result.rejected("MATTER_UNAVAILABLE"); }
            }
        };
        try { cluster.readAcceptedCommands(supported); } catch (RuntimeException unavailable) { supported.onError(unavailable); }
    }
    private boolean guard(Request request) { if (request.current()) return true; request.release(); return false; }

    private void openWindow(Request request) {
        if (!openWindowSupported) { request.fail("MATTER_UNSUPPORTED"); return; }
        withPointer(request, (pointer, operation) -> {
            int discriminator = new SecureRandom().nextInt(4096);
            if (!controller.openPairingWindowWithPINCallback(pointer, 180, 1000, discriminator, null, new OpenCommissioningCallback() {
                public void onSuccess(long node, String manual, String qr) { submit(() -> {
                    if (!guard(operation)) return;
                    try { operation.success(object("manualCode", manual, "qrPayload", qr, "qrPng", qrPng(qr), "expiresAt", System.currentTimeMillis() + 180000)); }
                    catch (Exception error) { operation.fail("MATTER_REJECTED"); }
                }); }
                public void onError(int status, long node) { operation.fail("MATTER_REJECTED"); }
            })) operation.fail("MATTER_REJECTED");
        });
    }

    private void forget(JSONObject args, Request request) {
        if (!Boolean.TRUE.equals(args.opt("confirmed"))) { request.fail("INVALID_INPUT"); return; }
        if (nodeId == 0) { request.success(snapshot()); return; }
        controller.unpairDeviceCallback(nodeId, new UnpairDeviceCallback() {
            public void onSuccess(long node) { submit(() -> {
                if (node != nodeId || closed) return;
                clearSavedPairing(request, "TV_REMOVED", true);
            }); }
            public void onError(int status, long node) { request.fail("MATTER_REJECTED"); }
        });
    }

    private void clearSavedPairing(Request request, String reason, boolean tvFabricRemoved) {
        if (!metadata.edit().remove("node").commit()) { request.fail("MATTER_STORAGE"); return; }
        generation++; cancelPairing();
        nodeId = 0; resolvedPrivateAddress = ""; connected = refreshing = paused = false; errorCode = "";
        keyEndpoint = powerEndpoint = volumeEndpoint = inputEndpoint = -1; currentVolume = currentInput = -1;
        inputs = new JSONArray(); clusters.clear(); videoEndpoints.clear(); speakerEndpoints.clear();
        navigationSupported = powerSupported = powerOnSupported = volumeSupported = inputsSupported = openWindowSupported = false;
        publish(); JSONObject result = snapshot(); put(result, "resetReason", reason); put(result, "tvFabricRemoved", tvFabricRemoved); request.success(result);
    }

    private void publish() {
        JSONObject state = object("available", available, "paired", nodeId != 0, "connected", connected, "pairing", pairing, "paused", paused,
                "name", name, "capabilities", object("navigation", navigationSupported, "power", powerSupported, "powerOn", powerOnSupported,
                        "volume", volumeSupported && currentVolume >= 0, "inputs", inputsSupported && inputs.length() > 0, "openWindow", openWindowSupported), "inputs", inputs);
        put(state, "volume", currentVolume >= 0 ? object("current", currentVolume, "min", minVolume, "max", maxVolume) : JSONObject.NULL);
        put(state, "currentInput", currentInput >= 0 ? currentInput : JSONObject.NULL);
        put(state, "errorCode", errorCode.isEmpty() ? JSONObject.NULL : errorCode);
        published = copy(state);
        if (!closed && listener != null) main.post(() -> { if (!closed) listener.onStateChanged(snapshot()); });
    }

    private boolean containsInput(int index) { for (int i = 0; i < inputs.length(); i++) if (inputs.optJSONObject(i).optInt("index", -1) == index) return true; return false; }
    static int keyCode(String key) {
        switch (key) { case "enter": return 0; case "up": return 1; case "down": return 2; case "left": return 3; case "right": return 4; case "home": return 9; case "back": return 13; default: return -1; }
    }
    private List<byte[]> certificates(String path) throws Exception {
        List<byte[]> result = new ArrayList<>();
        String[] files = context.getAssets().list(path);
        if (files == null) return result;
        for (String file : files) if (file.endsWith(".der")) try (InputStream stream = context.getAssets().open(path + "/" + file)) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(); byte[] buffer = new byte[1024]; int length;
            while ((length = stream.read(buffer)) != -1) { if (bytes.size() + length > 8192) throw new IllegalStateException("Invalid trust root"); bytes.write(buffer, 0, length); }
            result.add(bytes.toByteArray());
        }
        return result;
    }
    static String qrPng(String payload) throws Exception {
        BitMatrix matrix = new MultiFormatWriter().encode(payload, BarcodeFormat.QR_CODE, 360, 360);
        Bitmap image = Bitmap.createBitmap(360, 360, Bitmap.Config.ARGB_8888);
        for (int y = 0; y < 360; y++) for (int x = 0; x < 360; x++) image.setPixel(x, y, matrix.get(x, y) ? 0xff000000 : 0xffffffff);
        ByteArrayOutputStream png = new ByteArrayOutputStream(); image.compress(Bitmap.CompressFormat.PNG, 100, png); image.recycle();
        return "data:image/png;base64," + Base64.encodeToString(png.toByteArray(), Base64.NO_WRAP);
    }
    private static String safeName(String text) { if (text == null) return "TV input"; String safe = text.replaceAll("[\\p{Cntrl}]", ""); return safe.substring(0, Math.min(safe.length(), 120)); }
    private void submit(Runnable work) { try { SDK_WORKER.execute(work); } catch (RejectedExecutionException ignored) {} }
    private static JSONObject object(Object... pairs) { JSONObject result = new JSONObject(); for (int i = 0; i < pairs.length; i += 2) put(result, (String) pairs[i], pairs[i + 1]); return result; }
    private static void put(JSONObject object, String key, Object value) { try { object.put(key, value); } catch (Exception ignored) {} }
    private static JSONObject copy(JSONObject value) { try { return new JSONObject(value.toString()); } catch (Exception error) { return new JSONObject(); } }

    private final class Request {
        private final Callback callback;
        private final long revision;
        private final AtomicBoolean done = new AtomicBoolean();
        private volatile long pointer;
        private volatile boolean powerOnAcknowledged;
        Request(Callback callback, long revision, long timeout) { this.callback = callback; this.revision = revision; main.postDelayed(() -> submit(() -> {
            if (!done.compareAndSet(false, true)) return;
            if (!closed && revision == generation && powerOnAcknowledged) {
                // On is known accepted even if the optional read stays silent.
                // Keep its SDK pointer until a late callback can safely release it.
                JSONObject receipt = powerOnReceipt(null);
                main.post(() -> callback.success(copy(receipt))); return;
            }
            if (revision == generation) { resolvedPrivateAddress = ""; refreshing = false; connected = false; errorCode = "MATTER_TIMEOUT"; publish(); }
            // A late SDK callback releases the pointer. Releasing it while its
            // native read/invoke is outstanding risks a use-after-free.
            main.post(() -> callback.failure(revision == generation ? "MATTER_TIMEOUT" : "CANCELLED", "Matter request timed out."));
        }), timeout); }
        boolean current() { return !done.get() && !closed && revision == generation; }
        void success(JSONObject value) { release(); if (done.compareAndSet(false, true)) main.post(() -> callback.success(copy(value))); }
        void fail(String code) { release(); if (done.compareAndSet(false, true)) main.post(() -> callback.failure(code, "Matter request could not be completed.")); }
        private synchronized void release() { long held = pointer; pointer = 0; if (held != 0 && controller != null) submit(() -> controller.releaseConnectedDevicePointer(held)); }
    }

    @Override public void close() {
        if (closed) return;
        resolvedPrivateAddress = "";
        closed = true;
        submit(() -> {
            generation++;
            if (controller != null && controllerOwner == this) {
                cancelPairing();
                controller.shutdownSubscriptions(); controller.stopDnssd();
                controllerOwner = null;
            }
        });
    }

    /** Fabric secrets are encrypted with a non-exportable Android Keystore key. */
    static final class EncryptedStorage implements KeyValueStoreManager {
        private static final String ALIAS = "luma-matter-fabric-v1";
        private final SharedPreferences preferences;
        private final SecretKey key;
        EncryptedStorage(Context context) throws Exception {
            preferences = context.getSharedPreferences("matter-secure-fabric", Context.MODE_PRIVATE);
            KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
            if (!store.containsAlias(ALIAS)) {
                if (!preferences.getAll().isEmpty()) throw new IllegalStateException("Fabric key unavailable");
                KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
                generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
                generator.generateKey();
            }
            key = (SecretKey) store.getKey(ALIAS, null);
            // The native issuer treats any read error as an absent CA key.
            // Authenticate existing values before it can generate replacements.
            for (Map.Entry<String, ?> entry : preferences.getAll().entrySet()) {
                if (!(entry.getValue() instanceof String)) throw new IllegalStateException("Fabric storage unavailable");
                get(entry.getKey());
            }
        }
        public synchronized String get(String name) {
            String encrypted = preferences.getString(name, null); if (encrypted == null) return null;
            try {
                String[] fields = encrypted.split(":", 2); if (fields.length != 2) throw new IllegalStateException();
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, Base64.decode(fields[0], Base64.NO_WRAP)));
                cipher.updateAAD(name.getBytes(StandardCharsets.UTF_8));
                return new String(cipher.doFinal(Base64.decode(fields[1], Base64.NO_WRAP)), StandardCharsets.UTF_8);
            } catch (Exception error) { throw new IllegalStateException("Fabric storage unavailable"); }
        }
        public synchronized void set(String name, String value) {
            try {
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key); cipher.updateAAD(name.getBytes(StandardCharsets.UTF_8));
                String encrypted = Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + ":" + Base64.encodeToString(cipher.doFinal(value.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
                if (!preferences.edit().putString(name, encrypted).commit()) throw new IllegalStateException();
            } catch (Exception error) { throw new IllegalStateException("Fabric storage unavailable"); }
        }
        public synchronized void delete(String name) { if (!preferences.edit().remove(name).commit()) throw new IllegalStateException("Fabric storage unavailable"); }
    }
}
