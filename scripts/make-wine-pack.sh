#!/usr/bin/env bash
# Builds wine-pack.tgz — the downloadable Wine engine pack the app
# fetches on demand (kept OUT of the APK so the APK stays small).
#
# Pack contents (paths are relative to the Ubuntu rootfs root):
#   opt/wine/                          Kron4ek Wine amd64-wow64 ($WINE_VERSION)
#   usr/lib/box64-x86_64-linux-gnu/    Box64's bundled x86_64 libraries
#   usr/lib/x86_64-linux-gnu/          x86_64 glibc, libgcc, libstdc++,
#                                      zlib (Ubuntu noble amd64 .debs)
#   lib64/ld-linux-x86-64.so.2         the x86_64 dynamic loader
#
# Run locally or by .github/workflows/wine-pack.yml. Output:
# dist/wine-pack.tgz
set -euo pipefail

cd "$(dirname "$0")/.."
mkdir -p dist
# Work under dist/ (the caller's big filesystem), not /tmp — the
# unpacked Wine tree is ~1 GB and some sandboxes give /tmp only 512 MB.
WORK="$(mktemp -d "$PWD/dist/work.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT
# Optional local cache: point WINE_TARBALL / BUNDLE_TARBALL at
# pre-downloaded files to skip the big downloads while developing.
CACHE_WINE="${WINE_TARBALL:-dist/cache/wine-${WINE_VERSION:-10.0}.tar.xz}"
CACHE_BUNDLE="${BUNDLE_TARBALL:-dist/cache/bundle.tar.gz}"

# Wine version to pack. 11.20's ntdll aborts under Box64+PRoot with
# "set_mem_area_state: Assertion '!(size & granularity_mask)' failed"
# during the first real program run (seen on-device 2026-10-20), so
# the pack defaults to the 10.0 generation the Android Wine scene
# (Winlator / MiceWine) standardized on.
WINE_VERSION="${WINE_VERSION:-10.0}"
WINE_URL="https://github.com/Kron4ek/Wine-Builds/releases/download/${WINE_VERSION}/wine-${WINE_VERSION}-amd64-wow64.tar.xz"
BUNDLE_URL="https://github.com/ptitSeb/box64/releases/download/v0.3.8/box64-bundle-x86-libs-0.38.0.tar.gz"
POOL="https://archive.ubuntu.com/ubuntu/pool"
LIBC_DEB="$POOL/main/g/glibc/libc6_2.39-0ubuntu8.9_amd64.deb"
LIBGCC_DEB="$POOL/main/g/gcc-14/libgcc-s1_14.2.0-4ubuntu2~24.04.1_amd64.deb"
LIBSTDCXX_DEB="$POOL/main/g/gcc-14/libstdc++6_14.2.0-4ubuntu2~24.04.1_amd64.deb"
ZLIB_DEB="$POOL/main/z/zlib/zlib1g_1.3.dfsg-3.1ubuntu2.2_amd64.deb"
# X11 + font libraries Wine's graphical side needs inside Box64
# (winex11.drv, FreeType/Fontconfig for text). Filenames resolved from
# the noble main Packages index 2026-10-20.
X_DEBS="
$POOL/main/libx/libx11/libx11-6_1.8.7-1build1_amd64.deb
$POOL/main/libx/libxcb/libxcb1_1.15-1ubuntu2_amd64.deb
$POOL/main/libx/libxau/libxau6_1.0.9-1build6_amd64.deb
$POOL/main/libx/libxdmcp/libxdmcp6_1.1.3-0ubuntu6_amd64.deb
$POOL/main/libb/libbsd/libbsd0_0.12.1-1build1_amd64.deb
$POOL/main/libm/libmd/libmd0_1.1.0-2build1_amd64.deb
$POOL/main/libx/libxext/libxext6_1.3.4-1build2_amd64.deb
$POOL/main/libx/libxrender/libxrender1_0.9.10-1.1build1_amd64.deb
$POOL/main/libx/libxrandr/libxrandr2_1.5.2-2build1_amd64.deb
$POOL/main/libx/libxfixes/libxfixes3_6.0.0-2build1_amd64.deb
$POOL/main/libx/libxcursor/libxcursor1_1.2.1-1build1_amd64.deb
$POOL/main/libx/libxi/libxi6_1.8.1-1build1_amd64.deb
$POOL/main/libx/libxinerama/libxinerama1_1.1.4-3build1_amd64.deb
$POOL/main/libx/libxxf86vm/libxxf86vm1_1.1.4-1build4_amd64.deb
$POOL/main/libx/libxcomposite/libxcomposite1_0.4.5-1build3_amd64.deb
$POOL/main/libx/libxdamage/libxdamage1_1.1.6-1build1_amd64.deb
$POOL/main/f/freetype/libfreetype6_2.13.2+dfsg-1build3_amd64.deb
$POOL/main/f/fontconfig/libfontconfig1_2.15.0-1.1ubuntu2_amd64.deb
$POOL/main/libp/libpng1.6/libpng16-16t64_1.6.43-5build1_amd64.deb
$POOL/main/b/brotli/libbrotli1_1.1.0-2build2_amd64.deb
$POOL/main/e/expat/libexpat1_2.6.1-2build1_amd64.deb
$POOL/main/libx/libxkbcommon/libxkbcommon0_1.6.0-1build1_amd64.deb
"

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

PACK="$WORK/pack"
mkdir -p "$PACK/opt" "$PACK/usr/lib" "$PACK/lib64"

echo "Getting Wine ${WINE_VERSION} (amd64 wow64)…"
if [ -s "$CACHE_WINE" ]; then cp "$CACHE_WINE" "$WORK/wine.tar.xz"; else download "$WINE_URL" "$WORK/wine.tar.xz"; fi
tar --no-same-owner -xJf "$WORK/wine.tar.xz" -C "$WORK"
WINE_SRC="$(find "$WORK" -maxdepth 3 -type f -path "*/bin/wine" | head -1 | xargs dirname | xargs dirname)"
echo "Wine tree: $WINE_SRC"
cp -a --no-preserve=ownership "$WINE_SRC" "$PACK/opt/wine"
# Drop the pieces a runtime never needs (headers, static libs, man pages)
rm -rf "$PACK/opt/wine/include" "$PACK/opt/wine/share/man"
find "$PACK/opt/wine/lib" -name "*.a" -delete 2>/dev/null || true

echo "Getting Box64 x86_64 library bundle…"
if [ -s "$CACHE_BUNDLE" ]; then cp "$CACHE_BUNDLE" "$WORK/bundle.tar.gz"; else download "$BUNDLE_URL" "$WORK/bundle.tar.gz"; fi
mkdir -p "$WORK/bundle"
tar --no-same-owner -xzf "$WORK/bundle.tar.gz" -C "$WORK/bundle"
cp -a --no-preserve=ownership "$WORK/bundle/usr/lib/box64-x86_64-linux-gnu" "$PACK/usr/lib/"

echo "Downloading x86_64 system libraries (amd64 .debs)…"
for deb_url in "$LIBC_DEB" "$LIBGCC_DEB" "$LIBSTDCXX_DEB" "$ZLIB_DEB" $X_DEBS; do
  f="$WORK/$(basename "$deb_url")"
  download "$deb_url" "$f"
  mkdir -p "$WORK/debx"
  if command -v dpkg-deb >/dev/null 2>&1; then
    dpkg-deb -x "$f" "$WORK/debx"
  else
    (cd "$WORK/debx" && ar x "$f" && tar --no-same-owner -xf data.tar.* && rm -f data.tar.* debian-binary control.tar.*)
  fi
done
# The debs ship merged-usr paths (usr/lib/x86_64-linux-gnu) plus, for
# libc6, the loader under lib64/. Copy both into place.
if [ -d "$WORK/debx/usr/lib/x86_64-linux-gnu" ]; then
  mkdir -p "$PACK/usr/lib/x86_64-linux-gnu"
  cp -a --no-preserve=ownership "$WORK/debx/usr/lib/x86_64-linux-gnu/." "$PACK/usr/lib/x86_64-linux-gnu/"
fi
if [ -d "$WORK/debx/lib/x86_64-linux-gnu" ]; then
  mkdir -p "$PACK/usr/lib/x86_64-linux-gnu"
  cp -a --no-preserve=ownership "$WORK/debx/lib/x86_64-linux-gnu/." "$PACK/usr/lib/x86_64-linux-gnu/"
fi
cp -a --no-preserve=ownership "$WORK/debx/lib64/." "$PACK/lib64/" 2>/dev/null || true
# Make sure the loader exists at BOTH well-known spots.
LOADER="$(find "$WORK/debx" -name "ld-linux-x86-64.so.2" | head -1)"
if [ -n "$LOADER" ]; then
  cp -aL --no-preserve=ownership "$LOADER" "$PACK/lib64/ld-linux-x86-64.so.2"
  cp -aL --no-preserve=ownership "$LOADER" "$PACK/usr/lib/x86_64-linux-gnu/ld-linux-x86-64.so.2"
fi
chmod 755 "$PACK/lib64/ld-linux-x86-64.so.2" "$PACK/opt/wine/bin/wine" || true

echo "Pack contents (key files):"
ls -lh "$PACK/opt/wine/bin/wine" "$PACK/lib64/ld-linux-x86-64.so.2"
ls "$PACK/usr/lib/x86_64-linux-gnu/" | grep -E "^libc\.so|^libstdc|^libgcc|^libz\.so" || true

tar -czf "$WORK/wine-pack.tgz" -C "$PACK" .
gzip -t "$WORK/wine-pack.tgz"
mv "$WORK/wine-pack.tgz" dist/wine-pack.tgz
ls -lh dist/wine-pack.tgz
