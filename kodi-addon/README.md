# LUMA Remote Metadata for Kodi

This source-only companion add-on lets LUMA Remote attach canonical movie or
episode metadata when it starts playback on Kodi. LUMA Remote still falls back
to ordinary playback when the add-on is not installed.

For each app release, download the `script.lumaremote.metadata-<version>.zip`
asset from the same GitHub release as the APK. In Kodi, enable installation from
unknown sources if needed, then choose **Settings > Add-ons > Install from zip
file** and select that ZIP. Do not extract or rename its contents.

The ZIP contains only the checked-in Python/XML files beneath
`script.lumaremote.metadata/`. It contains no media, credentials, or active
playback URLs.
