# Philips browser internals: observed surface and upstream implementation reference

Research date: 2026-10-20. This is source analysis, not a firmware disassembly or
a TV capability acceptance test. It supports the
[firmware/server report](philips-firmware-implementation.md) and
[app-execution investigation](philips-app-execution.md).

## Evidence boundary

The TV's one-time HTTP-page report exposes `fetch`, Promise, WebAssembly,
WebSocket, MediaSource, WebGL/WebGL2 constructors, localStorage and IndexedDB.
EME, VideoDecoder, serviceWorker and crypto.subtle were not exposed in that
**insecure, non-cross-origin-isolated page context**. The code only inspected
presence and fixed media type declarations; it did not construct graphics or
codec objects, request permissions, open databases or start media.

Parsed browser tokens were Chrome `112.0.4147.62`, AppleWebKit `537.36`,
Opera `46.0.2207.0`, OMI `4.24`, TitanOS `3.0`, NETTV `4.6.0.7` and
SmartTvA `5.0.0`. Reported `Linux x86_64` is a browser platform string, not
proof of the TV's physical CPU architecture. Raw UA/model strings are not retained
by this report. The ignored snapshot is `.local/tv-hello/browser-compatibility.json`.

The reference below is the official Chromium mirror at **tag `112.0.5615.49`**.
It is deliberately not described as this Philips firmware's source revision:
the observed token differs, vendor backports/configuration are unknown, and UA
strings alone do not identify an exact implementation. That upstream tag's
[DEPS](https://github.com/chromium/chromium/blob/112.0.5615.49/DEPS#L273-L296)
pins V8 `96fed67922e5f54a027aed80259e5083769e33e2`, ANGLE
`293db5ce4d0766cb3ba7711057a00f0a5bddb00d`, and BoringSSL
`ca1690e221677cea3fb946f324eb89d846ec53f2`. These are **reference revisions**,
not recovered TV component versions.

An official current-build firmware container is now available to the main
investigation, but no browser executable or linked libraries have been extracted.
Consequently no TV-specific ELF imports, V8 flags, GPU backend, sandbox policy,
certificate roots, storage directories or socket-service bindings are established.

## Can a TV page host a server like Kodi?

A later fixed presence-only TV sample found `TCPServerSocket`, `TCPSocket`,
`UDPSocket` and `WebTransport` **not exposed**, while `WebSocket` and
`RTCPeerConnection` were exposed. Secure context and cross-origin isolation
remained false. Matching ready/heartbeat reports had no schema rejections.
Evidence: `.local/tv-hello/firmware-deep/socket-profile.json`. No constructor,
permission request, bind/listen/accept, ICE gathering or connection was attempted.
This characterizes the current Internet-page context; privileged DevView/vendor
app contexts remain unknown.

The observed browser features support a TV-side application, computation and
outbound control connections. They do not by themselves establish an incoming
HTTP/TCP listener. The exact missing capability is an authorized host interface
that binds a TV network address/port and accepts connections.

| Route | What the source establishes | What remains unproved on this TV |
| --- | --- | --- |
| Browser WebSocket | Client constructor takes a remote URL; the public interface has send/close/events, with no bind/listen/accept operation. | Successful outbound connection and lifecycle/background behavior. |
| WebAssembly | A module can run compiled computation and call supplied imports. It does not acquire OS socket calls merely by compiling C/C++ or providing a WASI import name. | Instantiation/compute performance; an approved host import providing a real socket service. |
| WebGL | Shader source and draws travel through graphics validation/command interfaces. This grants graphics operations, not an OS socket interface. | Context creation, real GPU/backend, limits, performance. |
| Privileged Chromium Direct Sockets | The reference contains a real `TCPServerSocket`, behind specific context/runtime/policy gates; its service reaches the network process's TCP-server creation. | Exposure and permission in any Philips app context; the ordinary HTTP-page result cannot establish that. |
| Native/vendor helper | A separately authorized installed process or a vendor bridge could supply a listener and accept commands. | An actual supported package/bridge interface with those permissions. |

### Browser WebSocket is a client path

Chromium's public [websocket.idl](https://github.com/chromium/chromium/blob/112.0.5615.49/third_party/blink/renderer/modules/websockets/websocket.idl#L36-L67)
is implemented by `DOMWebSocket`. The concrete path is:

1. `DOMWebSocket::Create` / `Connect` in
   [dom_websocket.cc](https://github.com/chromium/chromium/blob/112.0.5615.49/third_party/blink/renderer/modules/websockets/dom_websocket.cc#L201-L265).
2. `WebSocketChannelImpl::Connect` obtains a `WebSocketConnector` through the
   BrowserInterfaceBroker and sends `connector->Connect(url, ...)` over Mojo in
   [websocket_channel_impl.cc](https://github.com/chromium/chromium/blob/112.0.5615.49/third_party/blink/renderer/modules/websockets/websocket_channel_impl.cc#L272-L359).
3. Browser `WebSocketConnectorImpl::Connect` supplies origin/frame/storage-partition
   context to `NetworkContext::CreateWebSocket` in
   [websocket_connector_impl.cc](https://github.com/chromium/chromium/blob/112.0.5615.49/content/browser/websockets/websocket_connector_impl.cc#L72-L113).
4. Network-service `WebSocket::AddChannel` constructs `net::WebSocketChannel` and
   calls `SendAddChannelRequest` in
   [websocket.cc](https://github.com/chromium/chromium/blob/112.0.5615.49/services/network/websocket.cc#L601-L635).

That is an outgoing handshake to an existing server. A JavaScript library named
`WebSocketServer` cannot add a listener to this public interface.

### WebAssembly imports do not create host privileges

V8 installs `WebAssembly.compile`, `validate` and `instantiate` in
[wasm-js.cc](https://github.com/v8/v8/blob/96fed67922e5f54a027aed80259e5083769e33e2/src/wasm/wasm-js.cc#L2945-L2947).
`WebAssemblyInstantiate` passes the provided imports to the engine;
[`WasmEngine::SyncInstantiate` / `AsyncCompile`](https://github.com/v8/v8/blob/96fed67922e5f54a027aed80259e5083769e33e2/src/wasm/wasm-engine.cc#L598-L691)
coordinate instantiation/compilation.

The decisive host boundary is
[`InstanceBuilder::LookupImport`, `SanitizeImports`, `ProcessImportedFunction`](https://github.com/v8/v8/blob/96fed67922e5f54a027aed80259e5083769e33e2/src/wasm/module-instantiate.cc#L909-L1165):
imports are looked up on the supplied imports object and imported functions must
be callable with the appropriate signature. An import named `sock_bind`,
`listen` or a WASI module name still needs a real host implementation. It does
not make V8 resolve a libc symbol or give the module a syscall instruction.
This is consistent with the specification's
[no-ambient-access rule](https://webassembly.github.io/spec/core/intro/introduction.html#security-considerations).
The current specification is architectural corroboration, not proof that this
TV implements all features of that specification version.

Emscripten does not silently remove that boundary. At fixed toolchain reference
**3.1.34**, [`SOCKFS.websocket_sock_ops.listen`](https://github.com/emscripten-core/emscripten/blob/3.1.34/src/library_sockfs.js#L499-L517)
returns `EOPNOTSUPP` outside Node; its Node branch uses `require('ws').Server`.
[`emscripten_websocket_new`](https://github.com/emscripten-core/emscripten/blob/3.1.34/src/library_websocket.js#L285-L313)
constructs the browser's outgoing `WebSocket`. The official
[networking guide](https://emscripten.org/docs/porting/networking.html#full-posix-sockets-over-websocket-proxy-server)
also describes a separate native proxy that performs POSIX operations on behalf
of the page. In that architecture the proxy host owns the actual listening
socket. It would be TV-hosted only if an authorized native proxy already ran
on the TV. A browser build of a Kodi-like C++ component therefore still needs
an explicit host capability for serving incoming LAN requests.

### WebGL compiles graphics work, not a socket service

In [webgl_rendering_context_base.cc](https://github.com/chromium/chromium/blob/112.0.5615.49/third_party/blink/renderer/modules/webgl/webgl_rendering_context_base.cc),
`shaderSource` (around line 5002) validates/normalizes source before
`ContextGL()->ShaderSource`; `compileShader` (2457) checks the shader object then
calls `ContextGL()->CompileShader`; `drawArrays` (2925) validates draw state before
`ContextGL()->DrawArrays`.

The reference renderer's
[`GLES2Implementation::DrawArrays`](https://github.com/chromium/chromium/blob/112.0.5615.49/gpu/command_buffer/client/gles2_implementation.cc#L5403-L5428)
validates parameters and submits command-buffer work. A validating GPU-service
path uses [`GLES2DecoderImpl::DoCompileShader`](https://github.com/chromium/chromium/blob/112.0.5615.49/gpu/command_buffer/service/gles2_cmd_decoder.cc#L12199-L12214),
which requests shader compilation with the configured translator. The pinned
ANGLE [`Shader::compile`](https://github.com/google/angle/blob/293db5ce4d0766cb3ba7711057a00f0a5bddb00d/src/libANGLE/Shader.cpp#L365-L470)
sets compilation options and invokes a renderer implementation. Upstream has
multiple graphics configurations; this does not establish which validating or
passthrough path, driver, hardware or software renderer the TV uses.

Compiling a shader can produce GPU machine instructions through that graphics
stack. The specified shader/command APIs do not expose POSIX bind/listen or
arbitrary host syscalls. No shader, graphics context or GPU workload was run
on the TV during this investigation.

### Direct Sockets is an explicit privileged exception

At the same Chromium tag, [`tcp_server_socket.idl`](https://github.com/chromium/chromium/blob/112.0.5615.49/third_party/blink/renderer/modules/direct_sockets/tcp_server_socket.idl)
requires **SecureContext**, **IsolatedContext** and runtime feature
**DirectSocketsExperimental**. `TCPSocket` has the corresponding
[`DirectSockets` gate](https://github.com/chromium/chromium/blob/112.0.5615.49/third_party/blink/renderer/modules/direct_sockets/tcp_socket.idl).
The server path is concrete:

- [`TCPServerSocket::Create` / `Open`](https://github.com/chromium/chromium/blob/112.0.5615.49/third_party/blink/renderer/modules/direct_sockets/tcp_server_socket.cc#L72-L112)
  checks context/permissions and invokes Mojo `OpenTCPServerSocket`.
- [`Socket::CheckContextAndPermissions`](https://github.com/chromium/chromium/blob/112.0.5615.49/third_party/blink/renderer/modules/direct_sockets/socket.cc#L91-L109)
  checks a live script context and the `direct-sockets` permissions-policy feature.
- Browser [`DirectSocketsServiceImpl::CreateForFrame`](https://github.com/chromium/chromium/blob/112.0.5615.49/content/browser/direct_sockets/direct_sockets_service_impl.cc#L126-L140)
  checks policy and sufficient isolation. Its
  [`OpenTCPServerSocket`](https://github.com/chromium/chromium/blob/112.0.5615.49/content/browser/direct_sockets/direct_sockets_service_impl.cc#L264-L278)
  calls `NetworkContext::CreateTCPServerSocket` with a local address/backlog.
- [`IsFrameSufficientlyIsolated`](https://github.com/chromium/chromium/blob/112.0.5615.49/content/browser/renderer_host/isolated_context_util.cc#L14-L28)
  accepts an isolation level at least `kMaybeIsolatedApplication` **or** an
  embedder `IsIsolatedContextAllowedForUrl` decision. It is not a test of the
  page's `crossOriginIsolated` JavaScript boolean alone.

Thus it would be inaccurate to say that Chromium source contains no listening
API. It would also be inaccurate to infer that HTTPS headers, WASM or WebGL grant
this TV page that privileged context. A vendor-approved app context could be
investigated as a supported route if exposed; neither exposure nor successful
binding has been established here.

### Useful control alternatives preserve the distinction

An outbound TV WebSocket or existing short/long polling can receive commands
from another host while the page is alive. A reachable rendezvous host remains
part of that architecture. WebRTC data channels could provide negotiated peer
messages if supported, using signaling/ICE rather than a general HTTP server;
[`RTCPeerConnection.createDataChannel`](https://github.com/chromium/chromium/blob/112.0.5615.49/third_party/blink/renderer/modules/peerconnection/rtc_peer_connection.idl#L160)
and [`RTCDataChannel.send`](https://github.com/chromium/chromium/blob/112.0.5615.49/third_party/blink/renderer/modules/peerconnection/rtc_data_channel.idl#L59-L62)
are the reference interfaces. No WebRTC connection, listener or network permission
request was performed. Page survival in the background or after app exit remains
a separate lifecycle question.

## Other observed APIs: concrete implementation paths

| Surface | Verified upstream path and functions | Practical boundary |
| --- | --- | --- |
| `fetch()` | [`GlobalFetch::fetch`](https://github.com/chromium/chromium/blob/112.0.5615.49/third_party/blink/renderer/core/fetch/global_fetch.cc#L130-L154) → [`FetchManager::Fetch` / `Loader::PerformHTTPFetch`](https://github.com/chromium/chromium/blob/112.0.5615.49/third_party/blink/renderer/core/fetch/fetch_manager.cc#L764-L862) → [`ThreadableLoader::Start`](https://github.com/chromium/chromium/blob/112.0.5615.49/third_party/blink/renderer/core/loader/threadable_loader.cc#L115-L175) / RawResource → ResourceLoader / URLLoader → [`ResourceRequestSender::SendAsync`](https://github.com/chromium/chromium/blob/112.0.5615.49/third_party/blink/renderer/platform/loader/fetch/url_loader/resource_request_sender.cc#L212-L269), `ThrottlingURLLoader::CreateLoaderAndStart`, URLLoaderFactory/Mojo → network service. | Browser-controlled request plumbing. It is not a raw-socket escape or arbitrary header/credential authority. |
| CORS | [`CorsURLLoader::StartRequest`](https://github.com/chromium/chromium/blob/112.0.5615.49/services/network/cors/cors_url_loader.cc#L715-L800) determines preflight; `OnReceiveResponse` checks access. [`network::URLLoader`](https://github.com/chromium/chromium/blob/112.0.5615.49/services/network/url_loader.cc#L474-L595) creates the `net::URLRequest`. | An HTTP response can arrive yet remain unreadable to script. A player/vendor backend may have different request plumbing; that is unverified. |
| TLS | [`SSLClientSocketImpl::Connect`, `Init`, `DoHandshake`, `VerifyCert`](https://github.com/chromium/chromium/blob/112.0.5615.49/net/socket/ssl_client_socket_impl.cc) configure the BoringSSL connection, call `SSL_do_handshake` (line 965), and delegate certificate verification (around 1133 onward). | The TV's trust store, TLS build/options and current certificate interoperability have not been inspected. This source is not evidence of an actual TV TLS handshake. |
| Promise and event loop | V8 [`PromiseConstructor`](https://github.com/v8/v8/blob/96fed67922e5f54a027aed80259e5083769e33e2/src/builtins/promise-constructor.tq#L48), [`PerformPromiseThenImpl` / `TriggerPromiseReactions`](https://github.com/v8/v8/blob/96fed67922e5f54a027aed80259e5083769e33e2/src/builtins/promise-abstract-operations.tq) enqueue reaction microtasks. Blink [`EventLoop::PerformMicrotaskCheckpoint`](https://github.com/chromium/chromium/blob/112.0.5615.49/third_party/blink/renderer/platform/scheduler/common/event_loop.cc#L92-L103) enters V8's [`MicrotaskQueue::RunMicrotasks`](https://github.com/v8/v8/blob/96fed67922e5f54a027aed80259e5083769e33e2/src/execution/microtask-queue.cc#L113-L190). [`ScriptPromiseResolver::ResolveOrRejectImmediately`](https://github.com/chromium/chromium/blob/112.0.5615.49/third_party/blink/renderer/bindings/core/v8/script_promise_resolver.cc#L139-L150) connects native completion to script promises. | Asynchrony and scheduling do not grant daemon lifetime or network-server permissions. |
| localStorage | Blink [`StorageArea::getItem` / `setItem`](https://github.com/chromium/chromium/blob/112.0.5615.49/third_party/blink/renderer/modules/storage/storage_area.cc#L102-L129) checks access; [`CachedStorageArea::GetItem` / `SetItem`](https://github.com/chromium/chromium/blob/112.0.5615.49/third_party/blink/renderer/modules/storage/cached_storage_area.cc#L75-L115) uses a renderer cache and Mojo `remote_area_->Put`; service [`StorageAreaImpl::CommitChanges`](https://github.com/chromium/chromium/blob/112.0.5615.49/components/services/storage/dom_storage/storage_area_impl.cc#L714-L817) creates database writes. [`LocalStorageImpl`](https://github.com/chromium/chromium/blob/112.0.5615.49/components/services/storage/dom_storage/local_storage_impl.cc#L530-L560) chooses disk-backed LevelDB or in-memory storage from embedder configuration. | Property presence proves neither persistence across restart nor a usable quota. No TV storage read/write was made. |
| IndexedDB | [`IDBFactory::open` / `OpenInternalImpl`](https://github.com/chromium/chromium/blob/112.0.5615.49/third_party/blink/renderer/modules/indexeddb/idb_factory.cc#L362-L441) creates an async request, checks permission and calls Mojo `factory->Open`. Browser [`IndexedDBFactory::OpenAndVerifyIndexedDBBackingStore`](https://github.com/chromium/chromium/blob/112.0.5615.49/content/browser/indexed_db/indexed_db_factory.cc#L813-L949) opens transactional LevelDB and selects on-disk/in-memory backing. | Origin/storage policy, durability, eviction and quotas are not TV-tested. |

## Why the four absent APIs may be context-gated

[`SecurityContext::SetSecurityOrigin`](https://github.com/chromium/chromium/blob/112.0.5615.49/third_party/blink/renderer/core/execution_context/security_context.cc#L94-L117)
classifies trust and insecure ancestors. The following upstream IDL exposure
rules explain why an ordinary LAN HTTP page can lack otherwise compiled APIs;
they do not prove those APIs are available over HTTPS on this TV.

| API | Exact upstream gate | Implementation boundary inspected |
| --- | --- | --- |
| EME | [`Navigator.requestMediaKeySystemAccess` is `SecureContext`](https://github.com/chromium/chromium/blob/112.0.5615.49/third_party/blink/renderer/modules/encryptedmedia/navigator_request_media_key_system_access.idl#L10). | [`NavigatorRequestMediaKeySystemAccess::requestMediaKeySystemAccess`](https://github.com/chromium/chromium/blob/112.0.5615.49/third_party/blink/renderer/modules/encryptedmedia/navigator_request_media_key_system_access.cc#L122-L201) checks encrypted-media policy and delegates to the encrypted-media client. Key-system/CDM/license support is not proved. |
| WebCodecs VideoDecoder | [`SecureContext`, `RuntimeEnabled=WebCodecs`](https://github.com/chromium/chromium/blob/112.0.5615.49/third_party/blink/renderer/modules/webcodecs/video_decoder.idl#L10-L15). | [`VideoDecoderTraits::CreateDecoder`](https://github.com/chromium/chromium/blob/112.0.5615.49/third_party/blink/renderer/modules/webcodecs/video_decoder.cc#L208-L215) creates `VideoDecoderBroker`. One upstream downstream option is [`MojoVideoDecoder::InitializeRemoteDecoder` / `Decode`](https://github.com/chromium/chromium/blob/112.0.5615.49/media/mojo/clients/mojo_video_decoder.cc#L191-L254). That does not establish the TV's chosen backend. |
| Service worker | [`Navigator.serviceWorker` is `SecureContext`](https://github.com/chromium/chromium/blob/112.0.5615.49/third_party/blink/renderer/modules/service_worker/navigator_service_worker.idl#L9). | [`ServiceWorkerContainer::registerServiceWorker`](https://github.com/chromium/chromium/blob/112.0.5615.49/third_party/blink/renderer/modules/service_worker/service_worker_container.cc#L218) validates origin/scope/script and delegates registration. A service worker handles scoped browser requests; it is not an incoming TCP listener or permanent daemon. |
| crypto.subtle | Both [`Crypto.subtle`](https://github.com/chromium/chromium/blob/112.0.5615.49/third_party/blink/renderer/modules/crypto/crypto.idl#L38) and [`SubtleCrypto`](https://github.com/chromium/chromium/blob/112.0.5615.49/third_party/blink/renderer/modules/crypto/subtle_crypto.idl#L37-L40) require `SecureContext`. | [`SubtleCrypto`](https://github.com/chromium/chromium/blob/112.0.5615.49/third_party/blink/renderer/modules/crypto/subtle_crypto.cc) normalizes algorithms/keys then calls `Platform::Current()->Crypto()`. [`WebCryptoImpl`](https://github.com/chromium/chromium/blob/112.0.5615.49/components/webcrypto/webcrypto_impl.cc) dispatches asynchronous crypto work and completion. This differs from the network stack's automatic TLS and grants no socket privilege. |

## Reproduction and unresolved work

The ignored evidence root is `.local/tv-hello/firmware-deep/browser/`.
`manifest.json` records source URL, fixed tag/hash, bytes and SHA-256 for each
bounded individual download; namespace folders preserve source paths.
`fetch_sources.py` limits file size to 2 MiB and concurrent downloads to six.
No Chromium checkout/build, browser source modification, firmware upload,
WASM/GL execution or listener test was performed for this source review.

Source acquisition initially failed through Python's local CA bundle and the
Gitiles endpoint; system curl with TLS verification and the official GitHub
mirror succeeded. Two guessed historical paths were unavailable and were
replaced by the verified `indexed_db_factory.cc` and
`components/webcrypto/webcrypto_impl.cc` paths. These acquisition failures do
not establish anything about the TV.

To identify actual firmware implementation, next obtain an extracted matching
browser binary/libraries and a reproducible component map or vendor source
release. To establish a supported TV-hosted server, inspect documented app
permissions/privileged socket exposure and test only an explicitly selected
supported interface. Preserve the distinction between exposure, permitted
construction, real bind/accept, background lifetime and ordinary media playback.
