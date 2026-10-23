import sys

import xbmc
import xbmcgui

from resources.lib.luma_metadata import PayloadError, decode_payload, play_payload


def main(argv):
    try:
        if len(argv) != 2:
            raise PayloadError("one payload argument required")
        play_payload(decode_payload(argv[1]), xbmc, xbmcgui)
    except PayloadError:
        # Deliberately fixed text: payloads contain short-lived media URLs.
        xbmc.log("LUMA metadata payload rejected", xbmc.LOGWARNING)
    except Exception:
        xbmc.log("LUMA metadata handoff failed", xbmc.LOGERROR)


if __name__ == "__main__":
    main(sys.argv)
