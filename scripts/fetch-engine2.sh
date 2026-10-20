#!/usr/bin/env bash
# Engine layer 2 staging (run by CI before the Gradle build, and locally
# when developing): downloads the glibc rootfs + Box64 + an x86_64 test
# program and places them in app/src/main/assets/.
#
# The big binaries are deliberately NOT committed to git — this script
# fetches them from their official sources on every build:
#   - Ubuntu 24.04 base rootfs (arm64, glibc)   cdimage.ubuntu.com
#   - Box64 0.3.8 arm64 .deb                    archive.ubuntu.com pool
#     (0.4.3 from the same pool is built for GLIBC_2.43 and REFUSES to
#      run on 24.04's glibc 2.39 — the v0.7 on-phone failure. 0.3.8
#      needs at most GLIBC_2.38. The guard below keeps this pairing
#      honest if the versions are ever bumped.)
#   - busybox x86_64 (static, musl) test file   busybox.net
# The .deb is unpacked here and only the runnable pieces are re-packed
# into engine2-overlay.tgz, which the app extracts over the Ubuntu rootfs.
set -euo pipefail

cd "$(dirname "$0")/.."
ASSETS="app/src/main/assets"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

UBUNTU_URL="https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.5-base-arm64.tar.gz"
BOX64_DEB_URL="https://archive.ubuntu.com/ubuntu/pool/universe/b/box64/box64_0.3.8+dfsg-1_arm64.deb"
ROOTFS_GLIBC="2.39"   # glibc shipped in the Ubuntu 24.04 base rootfs
BUSYBOX_X64_URL="https://busybox.net/downloads/binaries/1.35.0-x86_64-linux-musl/busybox"

# Download with resume: some networks cut long transfers mid-file.
# Retrying with -C - continues where the previous attempt stopped.
# Downloads go to a temp path; callers move the finished file into
# place, so a partial file can never pose as a staged asset.
download() {
  local url="$1" dest="$2" attempt
  rm -f "$dest"
  for attempt in 1 2 3 4 5 6 7 8 9 10 11 12; do
    if curl -fL -C - -o "$dest" "$url"; then
      return 0
    fi
    echo "download interrupted (attempt $attempt), resuming…"
    sleep 2
  done
  echo "FAILED to download $url" >&2
  return 1
}

# 1) Ubuntu rootfs -> assets/ubuntu.tgz (".tgz" so aapt leaves it alone,
#    the lesson from the rootfs.tar.gz incident in v0.4)
if [ ! -s "$ASSETS/ubuntu.tgz" ]; then
  echo "Downloading Ubuntu 24.04 base arm64 rootfs…"
  download "$UBUNTU_URL" "$WORK/ubuntu.tgz"
  gzip -t "$WORK/ubuntu.tgz"
  mv "$WORK/ubuntu.tgz" "$ASSETS/ubuntu.tgz"
fi

# 2) Box64 overlay: the box64 binary + the x86_64 test program
if [ ! -s "$ASSETS/engine2-overlay.tgz" ]; then
  echo "Downloading Box64 .deb…"
  download "$BOX64_DEB_URL" "$WORK/box64.deb"
  mkdir -p "$WORK/deb"
  if command -v dpkg-deb >/dev/null 2>&1; then
    dpkg-deb -x "$WORK/box64.deb" "$WORK/deb"
  else
    (cd "$WORK/deb" && ar x "$WORK/box64.deb" && tar -xf data.tar.*)
  fi

  # Guard: Box64's required glibc must not exceed the rootfs glibc,
  # or the loader rejects it on the phone (the v0.7 failure). Fail the
  # build here instead of shipping a broken engine.
  if command -v readelf >/dev/null 2>&1; then
    NEED="$(readelf -V "$WORK/deb/usr/bin/box64" | grep -oE 'GLIBC_[0-9.]+' | sort -Vu | tail -1 | sed 's/GLIBC_//')"
    if [ "$(printf '%s\n%s\n' "$ROOTFS_GLIBC" "$NEED" | sort -V | head -1)" != "$NEED" ] && [ "$NEED" != "$ROOTFS_GLIBC" ]; then
      echo "Box64 needs GLIBC_$NEED but the rootfs has $ROOTFS_GLIBC — refusing to stage a broken pair" >&2
      exit 1
    fi
    echo "Box64 glibc check: needs GLIBC_$NEED, rootfs has $ROOTFS_GLIBC — OK"
  fi

  OVERLAY="$WORK/overlay"
  mkdir -p "$OVERLAY/usr/local/bin" "$OVERLAY/opt/tests"
  cp "$WORK/deb/usr/bin/box64" "$OVERLAY/usr/local/bin/box64"
  # Box64's bundled x86_64 wrapper libraries, when the package ships them
  if [ -d "$WORK/deb/usr/lib/box64" ]; then
    mkdir -p "$OVERLAY/usr/local/lib"
    cp -r "$WORK/deb/usr/lib/box64" "$OVERLAY/usr/local/lib/box64"
  fi
  if [ -d "$WORK/deb/usr/share/box64" ]; then
    mkdir -p "$OVERLAY/usr/local/share"
    cp -r "$WORK/deb/usr/share/box64" "$OVERLAY/usr/local/share/box64"
  fi
  if [ -f "$WORK/deb/etc/box64.box64rc" ]; then
    mkdir -p "$OVERLAY/etc"
    cp "$WORK/deb/etc/box64.box64rc" "$OVERLAY/etc/box64.box64rc"
  fi
  chmod 755 "$OVERLAY/usr/local/bin/box64"

  echo "Downloading x86_64 test program (static busybox)…"
  download "$BUSYBOX_X64_URL" "$OVERLAY/opt/tests/busybox-x86_64"
  chmod 755 "$OVERLAY/opt/tests/busybox-x86_64"

  tar -czf "$WORK/engine2-overlay.tgz" -C "$OVERLAY" .
  gzip -t "$WORK/engine2-overlay.tgz"
  mv "$WORK/engine2-overlay.tgz" "$ASSETS/engine2-overlay.tgz"
fi

echo "Engine 2 assets staged:"
ls -lh "$ASSETS/ubuntu.tgz" "$ASSETS/engine2-overlay.tgz"
if [ -f "$WORK/overlay/usr/local/bin/box64" ]; then
  file "$WORK/overlay/usr/local/bin/box64" || true
  file "$WORK/overlay/opt/tests/busybox-x86_64" || true
fi
