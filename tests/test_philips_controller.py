"""Offline protocol fixtures only: no TV, phone, discovery or public requests."""
import contextlib
import hashlib
import http.server
import importlib.util
import io
import json
import os
from pathlib import Path
import stat
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from unittest import mock
import urllib.request

SPEC = importlib.util.spec_from_file_location("philips_controller", Path(__file__).resolve().parents[1] / "scripts/philips-controller.py")
philips = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(philips)
AUTH_KEY = "fixture-auth-key-only"
PIN = "0017"
ADDRESS = "10.20.30.40"


class Handler(http.server.BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def reply(self, value, status=200, headers=None):
        raw = value if isinstance(value, bytes) else json.dumps(value).encode()
        self.send_response(status)
        for name, text in (headers or {}).items(): self.send_header(name, text)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def authenticated(self):
        header = self.headers.get("Authorization", "")
        try:
            fields = urllib.request.parse_keqv_list(urllib.request.parse_http_list(header.removeprefix("Digest ")))
            digest = lambda text: hashlib.md5(text.encode()).hexdigest()
            ha1 = digest(f'{self.server.device_id}:fixture-realm:{AUTH_KEY}')
            ha2 = digest(f'{self.command}:{self.path}')
            expected = digest(f'{ha1}:fixture-nonce:{fields["nc"]}:{fields["cnonce"]}:auth:{ha2}')
            if header.startswith("Digest ") and fields["username"] == self.server.device_id and fields["response"] == expected:
                return True
        except (KeyError, ValueError, AttributeError):
            pass
        self.reply({}, 401, {"WWW-Authenticate": 'Digest realm="fixture-realm", nonce="fixture-nonce", algorithm=MD5, qop="auth"'})
        return False

    def do_GET(self):
        self.server.events.append(("GET", self.path, None))
        if self.path == "/6/system":
            self.reply({"api_version": {"Major": 6, "Minor": 1, "Patch": 0}, "serialnumber_encrypted": "fixture-private-id",
                        "featuring": {"jsonfeatures": {"activities": ["browser"], "inputkey": ["key", "unicode"], "textentry": ["context"]}}})
        elif self.server.redirect:
            self.reply({}, 302, {"Location": "http://127.0.0.1:1/must-not-follow"})
        elif self.authenticated():
            self.reply({"level1": "App", "level2": "Browser", "level3": "https://private.example/secret", "activity": {"id": "private"}, "encrypted_id": "private-id"})

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", "0"))))
        self.server.events.append(("POST", self.path, body))
        if self.path == "/6/pair/request":
            self.server.device_id = body["device"]["id"]
            self.reply({"error_id": "SUCCESS", "timestamp": 123456, "auth_key": AUTH_KEY, "timeout": 60})
        elif self.authenticated():
            if self.path == "/6/pair/grant":
                auth = body["auth"]
                self.server.grant_verified = auth == {"auth_appId": "1", "auth_timestamp": 123456,
                    "auth_signature": philips.signature(123456, PIN), "pin": PIN}
                self.reply({"error_id": "DENIED" if self.server.deny else "SUCCESS", "error_text": "fixture-private-detail"})
            elif self.server.deny:
                self.reply({"error_id": "DENIED", "error_text": "fixture-private-detail"})
            else:
                self.reply(b"<html><head><title>Ok</title></head><body>Ok</body></html>")


class PhilipsTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.directory = Path(self.temporary.name)
        self.store = philips.PrivateStore(self.directory / "private")
        self.server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.server.events, self.server.redirect, self.server.deny = [], False, False
        self.server.device_id, self.server.grant_verified = None, False
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        transport = philips.JsonTransport(f"http://127.0.0.1:{self.server.server_port}/6/")
        self.client = philips.PhilipsController(ADDRESS, self.store, transport)

    def tearDown(self):
        self.server.shutdown(); self.server.server_close(); self.thread.join()
        self.temporary.cleanup()

    def paired(self):
        self.client.pair_request()
        self.assertEqual(self.client.pair_grant(PIN), {"paired": True})

    def test_request_payload_and_private_pending(self):
        result = self.client.pair_request()
        self.assertEqual(result, {"pairing": "PIN_REQUIRED", "expiresInSeconds": 60})
        request = next(body for method, path, body in self.server.events if path == "/6/pair/request")
        self.assertEqual(request["access"]["scope"], ["read", "write", "control"])
        self.assertIn("featuring", request["access"])
        self.assertRegex(request["device"]["id"], r"^[a-f0-9]{32}$")
        self.assertEqual(request["device"]["type"], "native")
        pending = self.store.read("pending.json")
        self.assertEqual(pending["auth_key"], AUTH_KEY)
        self.assertNotIn("pin", pending)
        self.assertEqual(stat.S_IMODE((self.store.directory / "pending.json").stat().st_mode), 0o600)
        self.assertEqual(stat.S_IMODE(self.store.directory.stat().st_mode), 0o700)
        self.assertFalse((self.store.directory / "credentials.json").exists())

    def test_real_digest_grant_preserves_leading_zero_and_device(self):
        self.paired()
        self.assertTrue(self.server.grant_verified)
        self.assertFalse((self.store.directory / "pending.json").exists())
        credentials = self.store.read("credentials.json")
        self.assertEqual(credentials["id"], self.server.device_id)
        self.assertEqual(credentials["auth_key"], AUTH_KEY)
        self.assertNotIn("pin", credentials)
        bodies = [body for method, path, body in self.server.events if path == "/6/pair/grant"]
        self.assertEqual(len(bodies), 2)  # Initial challenge plus authenticated replay.
        self.assertEqual(bodies[0], bodies[1])

    def test_hmac_known_vector(self):
        # Independent calculation: raw SHA1 HMAC, then Base64 (never hex).
        import base64, hmac
        expected = base64.b64encode(hmac.new(philips.PUBLIC_VENDOR_KEY, b"1234560017", hashlib.sha1).digest()).decode()
        self.assertEqual(philips.signature(123456, PIN), expected)

    def test_rejected_grant_preserves_credentials_and_pending(self):
        self.client.pair_request()
        old = {"host": ADDRESS, "id": "a" * 32, "auth_key": "old-fixture-key"}
        self.store.write("credentials.json", old)
        self.server.deny = True
        with self.assertRaises(philips.PhilipsError) as caught:
            self.client.pair_grant(PIN)
        self.assertEqual(caught.exception.code, "PAIRING_REJECTED")
        self.assertEqual(self.store.read("credentials.json"), old)
        self.assertIsNotNone(self.store.read("pending.json"))

    def test_pending_and_expiry_do_not_send_another_prompt_or_pin(self):
        self.client.pair_request()
        before = len(self.server.events)
        with self.assertRaises(philips.PhilipsError): self.client.pair_request()
        pending = self.store.read("pending.json"); pending["expiresAt"] = time.time() - 1
        self.store.write("pending.json", pending)
        with self.assertRaises(philips.PhilipsError) as caught: self.client.pair_grant(PIN)
        self.assertEqual(caught.exception.code, "PAIRING_EXPIRED")
        self.assertEqual(len(self.server.events), before)

    def test_info_and_context_do_not_expose_identifiers_or_urls(self):
        info = self.client.info()
        self.assertEqual(info["api"], {"major": 6, "minor": 1, "patch": 0})
        self.assertTrue(info["features"]["browser"])
        self.paired()
        context = self.client.context()
        self.assertEqual(context, {"contextObserved": True, "focus": {"level1": "App", "level2": "Browser"}})
        for private in (ADDRESS, AUTH_KEY, "private-id", "https://private.example", PIN):
            self.assertNotIn(private, json.dumps([info, context]))

    def test_browser_and_keys_use_exact_authenticated_payloads(self):
        self.paired()
        url = "https://media.example/fixture?token=not-a-real-token"
        self.assertEqual(self.client.open_url(url), {"accepted": True, "pageLoadVerified": False})
        self.assertEqual(self.client.key("enter"), {"accepted": True})
        self.assertEqual(self.server.events[-1][2], {"key": "Confirm"})
        self.assertEqual([body for method, path, body in self.server.events if path == "/6/activities/browser"][-1], {"url": url})
        before = len(self.server.events)
        with self.assertRaises(philips.PhilipsError): self.client.key("power")
        self.assertEqual(len(self.server.events), before)

    def test_rejected_write_is_not_reported_as_accepted(self):
        self.paired(); self.server.deny = True
        with self.assertRaises(philips.PhilipsError) as caught:
            self.client.open_url("https://example.com/")
        self.assertEqual(caught.exception.code, "TV_REJECTED")

    def test_unicode_typing_preserves_spaces_and_uses_authenticated_key_payload(self):
        self.paired()
        text = "  Português: ação 🎬  "
        result = self.client.type_text(text)
        self.assertEqual(result, {"accepted": True, "textAppliedVerified": False})
        self.assertEqual(self.server.events[-1], ("POST", "/6/input/key", {"unicode": text}))
        self.assertNotIn(text, json.dumps(result))
        self.assertNotIn(text, (self.store.directory / "credentials.json").read_text())
        self.server.deny = True
        with self.assertRaises(philips.PhilipsError) as caught: self.client.type_text("fixture")
        self.assertEqual(caught.exception.code, "TV_REJECTED")

    def test_invalid_typing_never_sends_a_request(self):
        for value in ("", "line\nbreak", "\x00", "tab\there", "\x7f", "\u0085", "\ud800", "x" * 2049, None):
            with self.subTest(text="redacted"), self.assertRaises(philips.PhilipsError) as caught:
                self.client.type_text(value)
            self.assertEqual(caught.exception.code, "INVALID_TEXT")
        self.assertEqual(self.server.events, [])

    def test_private_text_input_preserves_spaces_and_refuses_echo_fallback(self):
        with mock.patch.object(philips.sys, "stdin", io.StringIO("  ação  \r\n")):
            self.assertEqual(philips.read_private_text(), "  ação  ")
        with mock.patch.object(philips.sys, "stdin", io.StringIO("x" * 2048 + "\nmore")):
            with self.assertRaises(philips.PhilipsError): self.client.type_text(philips.read_private_text())
        def unsafe_prompt(prompt):
            import warnings
            warnings.warn("fixture echo unavailable", philips.getpass.GetPassWarning)
            raise AssertionError("Echoing fallback must never run")
        with mock.patch.object(philips.getpass, "getpass", unsafe_prompt):
            with self.assertRaises(philips.PhilipsError) as caught: philips.hidden_input("Text: ")
        self.assertEqual(caught.exception.code, "PRIVATE_INPUT_UNAVAILABLE")
        self.assertEqual(self.server.events, [])

    def test_redirect_cannot_forward_credentials(self):
        self.paired(); self.server.redirect = True
        before = len(self.server.events)
        with self.assertRaises(philips.PhilipsError) as caught: self.client.context()
        self.assertEqual(caught.exception.code, "REDIRECT_REJECTED")
        self.assertEqual(len(self.server.events), before + 1)

    def test_url_validation_and_private_hint_boundaries(self):
        for url in ("http://192.168.1.20:4178/receiver", "https://example.com/player"):
            self.assertEqual(philips.validate_url(url), url)
        for url in ("file:///etc/passwd", "javascript:alert(1)", "http://user:pass@example.com/", "https://example.com/#secret",
                    "http://127.0.0.1/", "http://127.1/", "http://2130706433/", "http://169.254.1.1/", "http://localhost/", "https://example.com/\nprivate"):
            with self.subTest(url="redacted"), self.assertRaises(philips.PhilipsError): philips.validate_url(url)
        hints = self.directory / "hints.json"
        hints.write_text(json.dumps([{"ip": ADDRESS, "port": 9999}, {"ip": "fe80::1", "port": 9999}]))
        self.assertEqual(philips.address_from_hints(hints), ADDRESS)
        hints.write_text(json.dumps([{"ip": ADDRESS}, {"ip": "192.168.1.2"}]))
        with self.assertRaises(philips.PhilipsError): philips.address_from_hints(hints)

    def test_storage_rejects_public_files_and_symlinks(self):
        self.store.write("credentials.json", {"fixture": True})
        (self.store.directory / "credentials.json").chmod(0o644)
        with self.assertRaises(philips.PhilipsError): self.store.read("credentials.json")
        link = self.store.directory / "pending.json"; link.symlink_to(self.directory / "outside")
        with self.assertRaises(philips.PhilipsError): self.store.write("pending.json", {"fixture": True})

    def test_cli_rejects_secret_arguments_without_echo(self):
        for command, secret in (("pair-grant", PIN), ("type", "fixture-private-text")):
            completed = subprocess.run([sys.executable, str(Path(philips.__file__)), command, secret], capture_output=True, text=True)
            self.assertEqual(completed.returncode, 2)
            self.assertEqual(completed.stderr, '{"error":"INVALID_ARGUMENTS"}\n')
            self.assertNotIn(secret, completed.stdout + completed.stderr)


if __name__ == "__main__":
    unittest.main()
