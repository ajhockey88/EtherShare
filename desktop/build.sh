#!/usr/bin/env bash
# Builds EtherShare for PC for Windows, macOS and Linux into desktop/dist/. Needs Go
# (https://go.dev/dl/); the version comes from the Android app's manifest.
set -euo pipefail
cd "$(dirname "$0")"
version=$(grep -o 'versionName="[^"]*"' ../app/src/main/AndroidManifest.xml | cut -d'"' -f2)
rm -rf dist && mkdir -p dist
build() { # GOOS GOARCH output
    CGO_ENABLED=0 GOOS=$1 GOARCH=$2 go build -trimpath -ldflags "-s -w -X main.version=$version" -o "dist/$3" .
}
build windows amd64 EtherShare-Windows.exe
for target in "darwin arm64 Mac-AppleSilicon" "darwin amd64 Mac-Intel" "linux amd64 Linux"; do
    set -- $target
    build "$1" "$2" EtherShare
    # Zipped, so the program keeps its "can run" permission when downloaded.
    (cd dist && zip -q "EtherShare-$3.zip" EtherShare && rm EtherShare)
done
ls -lh dist
