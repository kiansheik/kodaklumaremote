import base64
import ipaddress
import json
import re
from urllib.parse import urlsplit


MAX_ARGUMENT = 16384
MEDIA_TYPES = {
    "video/mp4",
    "video/webm",
    "application/vnd.apple.mpegurl",
    "application/x-mpegurl",
    "application/dash+xml",
}
TOP_FIELDS = {"version", "source", "contentType", "title", "showTitle", "content"}
CONTENT_FIELDS = {"key", "kind", "contentId", "season", "episode", "watchUrl"}
FORBIDDEN_BIDI = set(range(0x202A, 0x202F)) | set(range(0x2066, 0x206A))
DOMAIN_LABEL = re.compile(r"^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$")
CONTENT_ID = re.compile(r"^[1-9][0-9]{0,9}$")
URL_IN_TEXT = re.compile(r"(?i)(?:[a-z][a-z0-9+.-]*://|www\.)\S+")


class PayloadError(ValueError):
    pass


def _object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise PayloadError("duplicate field")
        result[key] = value
    return result


def _text(value, allow_empty=False):
    if not isinstance(value, str) or len(value) > 200 or value != value.strip():
        raise PayloadError("invalid text")
    if not value and not allow_empty:
        raise PayloadError("empty text")
    if URL_IN_TEXT.search(value) or any(ord(char) < 32 or 127 <= ord(char) <= 159 or ord(char) in FORBIDDEN_BIDI for char in value):
        raise PayloadError("unsafe text")
    return value


def _public_url(value):
    if not isinstance(value, str) or not value or len(value) > 8192 or any(ord(char) <= 32 or char in "|\\#" or ord(char) == 127 for char in value):
        raise PayloadError("invalid source")
    try:
        parsed = urlsplit(value)
        port = parsed.port
    except ValueError as error:
        raise PayloadError("invalid source") from error
    if parsed.scheme not in ("http", "https") or not parsed.netloc or parsed.fragment or parsed.username is not None or parsed.password is not None:
        raise PayloadError("invalid source")
    if port is not None and not 1 <= port <= 65535:
        raise PayloadError("invalid source")
    host = parsed.hostname
    if not host:
        raise PayloadError("invalid source")
    try:
        address = ipaddress.ip_address(host)
    except ValueError:
        domain = host.lower().rstrip(".")
        if not domain or len(domain) > 253 or "." not in domain or re.fullmatch(r"[0-9.]+", domain):
            raise PayloadError("invalid source")
        if domain.rsplit(".", 1)[1].isdigit() or domain.rsplit(".", 1)[1] in {"localhost", "local", "localdomain", "lan", "home", "internal", "arpa"}:
            raise PayloadError("invalid source")
        if any(not DOMAIN_LABEL.fullmatch(label) for label in domain.split(".")):
            raise PayloadError("invalid source")
    else:
        if not address.is_global:
            raise PayloadError("invalid source")
    return value


def _validate(value):
    if not isinstance(value, dict) or set(value) != TOP_FIELDS or type(value["version"]) is not int or value["version"] != 1:
        raise PayloadError("invalid envelope")
    source = _public_url(value["source"])
    if value["contentType"] not in MEDIA_TYPES:
        raise PayloadError("invalid media type")
    title = _text(value["title"])
    content = value["content"]
    if not isinstance(content, dict) or set(content) != CONTENT_FIELDS:
        raise PayloadError("invalid content")
    kind, content_id = content["kind"], content["contentId"]
    if kind not in ("movie", "tv") or not isinstance(content_id, str) or not CONTENT_ID.fullmatch(content_id):
        raise PayloadError("invalid content identity")
    if kind == "movie":
        if content["season"] is not None or content["episode"] is not None or value["showTitle"] != "":
            raise PayloadError("invalid movie metadata")
        key = "framextv:movie:" + content_id
        watch_url = "https://framextv.cc/watch/" + content_id
    else:
        season, episode = content["season"], content["episode"]
        if type(season) is not int or type(episode) is not int or not 0 <= season <= 9999 or not 1 <= episode <= 9999:
            raise PayloadError("invalid episode metadata")
        show_title = _text(value["showTitle"])
        if title != "%s · S%d E%d" % (show_title, season, episode):
            raise PayloadError("inconsistent episode title")
        key = "framextv:tv:%s:%d:%d" % (content_id, season, episode)
        watch_url = "https://framextv.cc/watch/%s/%d/%d" % (content_id, season, episode)
    if content["key"] != key or content["watchUrl"] != watch_url:
        raise PayloadError("non-canonical content")
    return {
        "source": source,
        "contentType": value["contentType"],
        "title": title,
        "showTitle": value["showTitle"],
        "content": content,
    }


def decode_payload(argument):
    if not isinstance(argument, str) or not 1 <= len(argument) <= MAX_ARGUMENT or not re.fullmatch(r"[A-Za-z0-9_-]+", argument):
        raise PayloadError("invalid encoding")
    try:
        padding = "=" * (-len(argument) % 4)
        raw = base64.b64decode(argument + padding, altchars=b"-_", validate=True)
        if len(raw) > 12288:
            raise PayloadError("payload too large")
        value = json.loads(raw.decode("utf-8", "strict"), object_pairs_hook=_object,
                           parse_constant=lambda _: (_ for _ in ()).throw(PayloadError("invalid number")))
    except PayloadError:
        raise
    except (UnicodeError, ValueError, json.JSONDecodeError) as error:
        raise PayloadError("invalid payload") from error
    return _validate(value)


def play_payload(payload, xbmc, xbmcgui):
    content = payload["content"]
    item = xbmcgui.ListItem(label=payload["title"], path=payload["source"])
    item.setMimeType(payload["contentType"])
    item.setContentLookup(False)
    tag = item.getVideoInfoTag()
    tag.setTitle(payload["title"])
    if content["kind"] == "tv":
        tag.setMediaType("episode")
        tag.setTvShowTitle(payload["showTitle"])
        tag.setSeason(content["season"])
        tag.setEpisode(content["episode"])
    else:
        tag.setMediaType("movie")
    tag.setUniqueID("v1:" + content["key"], "luma", False)
    xbmc.Player().play(payload["source"], item)
