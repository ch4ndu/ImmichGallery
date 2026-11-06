#!/bin/bash
set -euo pipefail

# jpackage's Finder script can silently skip the installation alias in headless builds.
# Add a normal filesystem shortcut without requiring Finder automation permission.
image=$1
work_root=$2
test -f "$image"
mkdir -p "$work_root"
work=$(mktemp -d "$work_root/applications-link.XXXXXX")
mount_point="$work/volume"
mkdir "$mount_point"
mounted=false

cleanup() {
    if "$mounted"; then
        if hdiutil detach "$mount_point"; then
            mounted=false
        else
            echo "Could not detach packaging volume; retained staging directory: $work" >&2
        fi
    fi
    # Never recursively remove a directory while its volume may still be mounted.
    if ! "$mounted"; then
        rm -rf "$work"
    fi
}
trap cleanup EXIT

hdiutil convert "$image" -format UDRW -o "$work/writable.dmg" -quiet
mounted=true
hdiutil attach "$work/writable.dmg" -readwrite -nobrowse -mountpoint "$mount_point" -quiet
if [[ ! -e "$mount_point/Applications" && ! -L "$mount_point/Applications" ]]; then
    ln -s /Applications "$mount_point/Applications"
fi
test -e "$mount_point/Applications"
hdiutil detach "$mount_point" -quiet
mounted=false
hdiutil convert "$work/writable.dmg" -format UDZO -o "$work/complete.dmg" -quiet
# Keep the original image intact until the replacement is complete.
mv -f "$work/complete.dmg" "$image"
