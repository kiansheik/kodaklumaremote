import json
import sys


sys.path.insert(0, sys.argv[1])
from luma_metadata import PayloadError, decode_payload, play_payload  # noqa: E402


class Tag:
    def __init__(self):
        self.values = {}

    def setTitle(self, value):
        self.values["title"] = value

    def setMediaType(self, value):
        self.values["type"] = value

    def setTvShowTitle(self, value):
        self.values["showTitle"] = value

    def setSeason(self, value):
        self.values["season"] = value

    def setEpisode(self, value):
        self.values["episode"] = value

    def setUniqueID(self, value, kind, default):
        self.values["uniqueId"] = value
        self.values["uniqueType"] = kind
        self.values["uniqueDefault"] = default


class Item:
    def __init__(self, label, path):
        self.label = label
        self.path = path
        self.tag = Tag()
        self.lookup = None

    def setContentLookup(self, value):
        self.lookup = value

    def setMimeType(self, value):
        self.mime_type = value

    def getVideoInfoTag(self):
        return self.tag


class Gui:
    ListItem = Item


class Player:
    def play(self, source, item):
        state["sourceExact"] = source == item.path
        state["labelExact"] = item.label == item.tag.values.get("title")
        state["lookupDisabled"] = item.lookup is False
        state["mimeType"] = item.mime_type
        state["played"] = True
        state["tag"] = item.tag.values


class Xbmc:
    @staticmethod
    def Player():
        return Player()


state = {"played": False}
try:
    payload = decode_payload(sys.argv[2])
    play_payload(payload, Xbmc, Gui)
    print(json.dumps(state, separators=(",", ":"), sort_keys=True))
except PayloadError:
    print('{"rejected":true}')
