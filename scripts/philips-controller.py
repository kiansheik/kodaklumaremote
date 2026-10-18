#!/usr/bin/env python3
"""Small, local Philips JointSpace v6 client. No discovery or Matter changes."""
import argparse
import base64
import getpass
import hashlib
import hmac
import http.client
import ipaddress
import json
import os
from pathlib import Path
import re
import secrets
import socket
import ssl
import stat
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
import warnings

ROOT = Path(__file__).resolve().parents[1]
PUBLIC_VENDOR_KEY = base64.b64decode(
    "ZmVay1EQVFOaZhwQ4Kv81ypLAZNczV9sG4KkseXWn1NEk6cXmPKO/MCa9sryslvLCFMnNe4Z4CPXzToowvhHvA=="
)
KEYS = {"home": "Home", "back": "Back", "up": "CursorUp", "down": "CursorDown",
        "left": "CursorLeft", "right": "CursorRight", "enter": "Confirm", "options": "Options"}
FOCUS_VALUES = {"Home", "WatchTv", "WatchSatellite", "App", "Apps", "Application", "Internet",
                "Browser", "Media Station X", "MediaStationX", "MSX", "Android", "Launcher",
                "TV", "SmartTV", "YouTube", "On", "Off", "on", "off"}
MAX_RESPONSE = 1024 * 1024


class PhilipsError(Exception):
    def __init__(self, code, uncertain=False):
        super().__init__(code)
        self.code, self.uncertain = code, uncertain


class SafeParser(argparse.ArgumentParser):
    def error(self, message):
        self.exit(2, '{"error":"INVALID_ARGUMENTS"}\n')


def private_ipv4(value):
    try:
        address = ipaddress.IPv4Address(value)
        if any(address in ipaddress.IPv4Network(network) for network in
               ("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16")):
            return str(address)
    except (ValueError, TypeError):
        pass
    raise PhilipsError("ADDRESS_HINT_INVALID")


def address_from_hints(path):
    try:
        if path.stat().st_size > 32768:
            raise PhilipsError("ADDRESS_HINT_INVALID")
        hints = json.loads(path.read_text())
        if not isinstance(hints, list):
            raise PhilipsError("ADDRESS_HINT_INVALID")
        addresses = set()
        for hint in hints:
            if not isinstance(hint, dict):
                continue
            try:
                addresses.add(private_ipv4(hint.get("ip")))
            except PhilipsError:
                pass
        if len(addresses) != 1:
            raise PhilipsError("ADDRESS_HINT_AMBIGUOUS" if addresses else "ADDRESS_HINT_INVALID")
        return addresses.pop()
    except (OSError, ValueError, TypeError):
        raise PhilipsError("ADDRESS_HINT_INVALID") from None


class PrivateStore:
    def __init__(self, directory):
        self.directory = Path(directory)
        if self.directory.is_symlink():
            raise PhilipsError("PRIVATE_STORAGE")
        self.directory.mkdir(mode=0o700, parents=True, exist_ok=True)
        if not self.directory.is_dir() or self.directory.stat().st_uid != os.getuid():
            raise PhilipsError("PRIVATE_STORAGE")
        self.directory.chmod(0o700)

    def read(self, name, required=False):
        path = self.directory / name
        try:
            flags = os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0)
            with os.fdopen(os.open(path, flags), "r") as stream:
                metadata = os.fstat(stream.fileno())
                if (not stat.S_ISREG(metadata.st_mode) or metadata.st_uid != os.getuid()
                        or metadata.st_mode & 0o077 or metadata.st_size > 65536):
                    raise PhilipsError("PRIVATE_STORAGE")
                value = json.load(stream)
                if not isinstance(value, dict):
                    raise PhilipsError("PRIVATE_STORAGE")
                return value
        except FileNotFoundError:
            if required:
                raise PhilipsError("PAIRING_REQUIRED" if name == "credentials.json" else "PAIRING_NOT_PENDING") from None
            return None
        except (OSError, ValueError, TypeError):
            raise PhilipsError("PRIVATE_STORAGE") from None

    def write(self, name, value):
        path = self.directory / name
        if path.is_symlink():
            raise PhilipsError("PRIVATE_STORAGE")
        descriptor, temporary = tempfile.mkstemp(prefix=".pending-", dir=self.directory)
        try:
            with os.fdopen(descriptor, "w") as stream:
                os.fchmod(stream.fileno(), 0o600)
                json.dump(value, stream, separators=(",", ":"))
                stream.flush()
                os.fsync(stream.fileno())
            os.replace(temporary, path)
        finally:
            if os.path.exists(temporary):
                os.unlink(temporary)

    def delete(self, name):
        try:
            (self.directory / name).unlink()
        except FileNotFoundError:
            pass


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, message, headers, new_url):
        raise PhilipsError("REDIRECT_REJECTED")


class JsonTransport:
    def __init__(self, base_url):
        self.base_url = base_url.rstrip("/") + "/"

    def request(self, path, payload=None, credentials=None, json_required=True):
        # This context belongs only to this one fixed LAN endpoint. No trust-store
        # changes, environment proxies, redirects or automatic write retries.
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
        context.check_hostname = False
        context.verify_mode = ssl.CERT_NONE
        handlers = [urllib.request.ProxyHandler({}), NoRedirect(), urllib.request.HTTPSHandler(context=context)]
        if credentials:
            passwords = urllib.request.HTTPPasswordMgrWithDefaultRealm()
            passwords.add_password(None, self.base_url, credentials[0], credentials[1])
            handlers.append(urllib.request.HTTPDigestAuthHandler(passwords))
        opener = urllib.request.build_opener(*handlers)
        body = None if payload is None else json.dumps(payload, separators=(",", ":")).encode()
        request = urllib.request.Request(self.base_url + path, data=body,
                                         headers={"Accept": "application/json", "Content-Type": "application/json"})
        try:
            with opener.open(request, timeout=8) as response:
                if response.status != 200:
                    raise PhilipsError("TV_REJECTED")
                raw = response.read(MAX_RESPONSE + 1)
                if len(raw) > MAX_RESPONSE:
                    raise PhilipsError("INVALID_RESPONSE", payload is not None)
                if not json_required:
                    try:
                        acknowledgment = json.loads(raw)
                    except (ValueError, UnicodeError):
                        return {}
                    if isinstance(acknowledgment, dict) and acknowledgment.get("error_id", "SUCCESS") != "SUCCESS":
                        raise PhilipsError("TV_REJECTED")
                    return {}
                value = json.loads(raw)
                if not isinstance(value, dict):
                    raise PhilipsError("INVALID_RESPONSE")
                return value
        except urllib.error.HTTPError as error:
            raise PhilipsError("AUTH_REQUIRED" if error.code == 401 else "TV_REJECTED") from None
        except (TimeoutError, socket.timeout):
            raise PhilipsError("REQUEST_TIMEOUT", payload is not None) from None
        except urllib.error.URLError:
            raise PhilipsError("TV_UNREACHABLE", payload is not None) from None
        except (OSError, http.client.HTTPException):
            raise PhilipsError("TV_UNREACHABLE", payload is not None) from None
        except (ValueError, UnicodeError):
            raise PhilipsError("INVALID_RESPONSE", payload is not None) from None


def signature(timestamp, pin):
    return base64.b64encode(hmac.new(PUBLIC_VENDOR_KEY, (str(timestamp) + pin).encode(), hashlib.sha1).digest()).decode()


def validate_url(value):
    if not isinstance(value, str) or not value or len(value) > 8192 or any(ord(ch) < 33 or ord(ch) == 127 for ch in value):
        raise PhilipsError("INVALID_URL")
    try:
        url = urllib.parse.urlsplit(value)
        if (url.scheme not in ("http", "https") or not url.hostname or url.username is not None
                or url.password is not None or url.fragment or "\\" in value or url.port == 0):
            raise PhilipsError("INVALID_URL")
        host = url.hostname.lower()
        if host == "localhost" or host.endswith(".localhost"):
            raise PhilipsError("INVALID_URL")
        try:
            address = ipaddress.ip_address(host)
            if address.is_loopback or address.is_unspecified or address.is_multicast or address.is_link_local:
                raise PhilipsError("INVALID_URL")
        except ValueError:
            if (re.fullmatch(r"(?:0x[0-9a-f]+|[0-9.]+)", host)
                    or not re.fullmatch(r"[a-z0-9](?:[a-z0-9.-]{0,251}[a-z0-9])?", host)):
                raise PhilipsError("INVALID_URL")
        return value
    except (ValueError, TypeError):
        raise PhilipsError("INVALID_URL") from None


class PhilipsController:
    def __init__(self, address, store, transport=None):
        self.address = private_ipv4(address)
        self.store = store
        self.transport = transport or JsonTransport(f"https://{self.address}:1926/6/")

    def credentials(self):
        state = self.store.read("credentials.json", True)
        if (state.get("host") != self.address or not re.fullmatch(r"[a-f0-9]{32}", state.get("id", ""))
                or not isinstance(state.get("auth_key"), str) or not 1 <= len(state["auth_key"]) <= 512):
            raise PhilipsError("PRIVATE_STORAGE")
        return state["id"], state["auth_key"]

    def info(self):
        system = self.transport.request("system")
        version = system.get("api_version", {})
        features = system.get("featuring", {}).get("jsonfeatures", {})
        return {"api": {name.lower(): value for name, value in version.items()
                        if name.lower() in ("major", "minor", "patch") and type(value) is int},
                "features": {"browser": "browser" in features.get("activities", []),
                             "unicode": "unicode" in features.get("inputkey", []),
                             "textEntry": "textentry" in features,
                             "applications": "applications" in features}}

    def context(self):
        value = self.transport.request("context", credentials=self.credentials())
        return {"contextObserved": True, "focus": {name: value[name] for name in
                ("level1", "level2", "level3", "activity", "mode", "screenstate")
                if isinstance(value.get(name), str) and value[name] in FOCUS_VALUES}}

    def pair_request(self):
        if self.store.read("credentials.json"):
            raise PhilipsError("ALREADY_PAIRED")
        pending = self.store.read("pending.json")
        if pending and pending.get("expiresAt", 0) > time.time():
            raise PhilipsError("PAIRING_PENDING")
        system = self.transport.request("system")
        device = {"device_name": "LUMA local controller", "device_os": "Python", "type": "native",
                  "id": secrets.token_hex(16), "app_id": "dev.framextv.lumaremote", "app_name": "LUMA Remote"}
        access = {"scope": ["read", "write", "control"]}
        if isinstance(system.get("featuring"), dict):
            access["featuring"] = system["featuring"]
        result = self.transport.request("pair/request", {"access": access, "device": device})
        if result.get("error_id") != "SUCCESS":
            raise PhilipsError("PAIRING_REJECTED")
        timestamp, auth_key = result.get("timestamp"), result.get("auth_key")
        if (not isinstance(timestamp, (int, str)) or isinstance(timestamp, bool) or not re.fullmatch(r"[0-9]{1,20}", str(timestamp))
                or not isinstance(auth_key, str) or not 1 <= len(auth_key) <= 512 or any(ord(ch) < 32 for ch in auth_key)):
            raise PhilipsError("INVALID_RESPONSE", True)
        timeout = result.get("timeout", 60)
        timeout = timeout if type(timeout) is int and 1 <= timeout <= 600 else 60
        self.store.write("pending.json", {"host": self.address, "device": device, "timestamp": timestamp,
                                         "auth_key": auth_key, "expiresAt": time.time() + timeout})
        return {"pairing": "PIN_REQUIRED", "expiresInSeconds": timeout}

    def pair_grant(self, pin):
        if not isinstance(pin, str) or not re.fullmatch(r"[0-9]{4}", pin):
            raise PhilipsError("INVALID_PIN")
        pending = self.store.read("pending.json", True)
        if pending.get("host") != self.address:
            raise PhilipsError("PRIVATE_STORAGE")
        if pending.get("expiresAt", 0) <= time.time():
            raise PhilipsError("PAIRING_EXPIRED")
        try:
            device, timestamp, auth_key = pending["device"], pending["timestamp"], pending["auth_key"]
            if not re.fullmatch(r"[a-f0-9]{32}", device["id"]) or not isinstance(auth_key, str):
                raise PhilipsError("PRIVATE_STORAGE")
        except (KeyError, TypeError):
            raise PhilipsError("PRIVATE_STORAGE") from None
        result = self.transport.request("pair/grant", {"auth": {"auth_appId": "1", "auth_timestamp": timestamp,
                                         "auth_signature": signature(timestamp, pin), "pin": pin}, "device": device},
                                        (device["id"], auth_key))
        if result.get("error_id") != "SUCCESS":
            raise PhilipsError("PAIRING_REJECTED")
        self.store.write("credentials.json", {"host": self.address, "id": device["id"], "auth_key": auth_key})
        self.store.delete("pending.json")
        return {"paired": True}

    def open_url(self, url):
        url = validate_url(url)
        self.transport.request("activities/browser", {"url": url}, self.credentials(), json_required=False)
        return {"accepted": True, "pageLoadVerified": False}

    def key(self, name):
        if name not in KEYS:
            raise PhilipsError("INVALID_KEY")
        self.transport.request("input/key", {"key": KEYS[name]}, self.credentials(), json_required=False)
        return {"accepted": True}

    def type_text(self, text):
        if (not isinstance(text, str) or not 1 <= len(text) <= 2048
                or any(ord(ch) < 32 or 127 <= ord(ch) <= 159 or 0xd800 <= ord(ch) <= 0xdfff for ch in text)):
            raise PhilipsError("INVALID_TEXT")
        self.transport.request("input/key", {"unicode": text}, self.credentials(), json_required=False)
        return {"accepted": True, "textAppliedVerified": False}


def read_private_input(prompt):
    value = hidden_input(prompt) if sys.stdin.isatty() else sys.stdin.read(8193)
    return value.strip()


def hidden_input(prompt):
    with warnings.catch_warnings():
        warnings.simplefilter("error", getpass.GetPassWarning)
        try:
            return getpass.getpass(prompt)
        except getpass.GetPassWarning:
            raise PhilipsError("PRIVATE_INPUT_UNAVAILABLE") from None


def read_private_text():
    if sys.stdin.isatty():
        return hidden_input("Text: ")
    # Preserve intentional spaces; tolerate one pipeline line ending only.
    return sys.stdin.read(2051).removesuffix("\n").removesuffix("\r")


def main():
    parser = SafeParser(description=__doc__)
    parser.add_argument("--state-dir", type=Path, default=ROOT / ".local/philips-controller")
    parser.add_argument("--address-hints", type=Path, default=ROOT / ".local/matter-address-hints.json")
    commands = parser.add_subparsers(dest="command", required=True)
    for name in ("info", "context", "pair-request", "pair-grant", "open-url", "type"):
        commands.add_parser(name)
    commands.add_parser("key").add_argument("name", choices=sorted(KEYS))
    args = parser.parse_args()
    try:
        client = PhilipsController(address_from_hints(args.address_hints), PrivateStore(args.state_dir))
        if args.command == "info": result = client.info()
        elif args.command == "context": result = client.context()
        elif args.command == "pair-request": result = client.pair_request()
        elif args.command == "pair-grant": result = client.pair_grant(read_private_input("TV PIN: "))
        elif args.command == "key": result = client.key(args.name)
        elif args.command == "type": result = client.type_text(read_private_text())
        else:
            config = client.store.read("config.json")
            url = config.get("url") if config else read_private_input("Browser URL: ")
            result = client.open_url(url)
        print(json.dumps(result, separators=(",", ":")))
        return 0
    except PhilipsError as error:
        print(json.dumps({"error": error.code, "outcome": "unknown" if error.uncertain else "not_confirmed"}), file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        print('{"error":"CANCELLED","outcome":"not_confirmed"}', file=sys.stderr)
        return 130
    except Exception:
        print('{"error":"LOCAL_OPERATION_FAILED","outcome":"not_confirmed"}', file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
