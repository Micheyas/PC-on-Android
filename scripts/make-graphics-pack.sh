#!/usr/bin/env bash
# Builds graphics-pack.tgz — the X server layer for PC on Android:
# Xvfb + x11vnc and their full arm64 dependency closure from Ubuntu
# noble (ports), extracted raw (no dpkg, no postinst) so the guest
# needs NO network to gain graphics. The list below is the closure of
# {xvfb, x11vnc, xfonts-base, x11-xkb-utils} minus the packages the
# Ubuntu base rootfs already ships, resolved from the noble arm64
# Packages indexes on 2026-10-20.
#
# Pack paths are relative to the Ubuntu rootfs root (usr/bin/Xvfb,
# usr/bin/x11vnc, usr/lib/aarch64-linux-gnu/..., usr/share/...).
# Output: dist/graphics-pack.tgz — published on the
# "graphics-pack-2" release; the app downloads it on demand.
# (graphics-pack-1 was retired: it carried a real ./lib directory that
# broke the rootfs merged-usr lib symlink on extraction.)
set -euo pipefail

cd "$(dirname "$0")/.."
mkdir -p dist
WORK="$(mktemp -d "$PWD/dist/gwork.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT
POOL="http://ports.ubuntu.com/ubuntu-ports"

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

DEBS=(
  "pool/main/f/fontconfig/fontconfig-config_2.15.0-1.1ubuntu2_arm64.deb"
  "pool/main/f/fonts-dejavu/fonts-dejavu-core_2.37-8_all.deb"
  "pool/main/f/fonts-dejavu/fonts-dejavu-mono_2.37-8_all.deb"
  "pool/main/a/avahi/libavahi-client3_0.8-13ubuntu6_arm64.deb"
  "pool/main/a/avahi/libavahi-common-data_0.8-13ubuntu6_arm64.deb"
  "pool/main/a/avahi/libavahi-common3_0.8-13ubuntu6_arm64.deb"
  "pool/main/b/brotli/libbrotli1_1.1.0-2build2_arm64.deb"
  "pool/main/libb/libbsd/libbsd0_0.12.1-1build1_arm64.deb"
  "pool/main/d/dbus/libdbus-1-3_1.14.10-4ubuntu4_arm64.deb"
  "pool/main/libd/libdrm/libdrm-amdgpu1_2.4.120-2build1_arm64.deb"
  "pool/main/libd/libdrm/libdrm-common_2.4.120-2build1_all.deb"
  "pool/main/libd/libdrm/libdrm-nouveau2_2.4.120-2build1_arm64.deb"
  "pool/main/libd/libdrm/libdrm-radeon1_2.4.120-2build1_arm64.deb"
  "pool/main/libd/libdrm/libdrm2_2.4.120-2build1_arm64.deb"
  "pool/main/libe/libedit/libedit2_3.1-20230828-1build1_arm64.deb"
  "pool/main/e/elfutils/libelf1t64_0.190-1.1build4_arm64.deb"
  "pool/main/e/expat/libexpat1_2.6.1-2build1_arm64.deb"
  "pool/main/f/fontconfig/libfontconfig1_2.15.0-1.1ubuntu2_arm64.deb"
  "pool/main/libf/libfontenc/libfontenc1_1.1.8-1build1_arm64.deb"
  "pool/main/f/freetype/libfreetype6_2.13.2+dfsg-1build3_arm64.deb"
  "pool/main/libg/libglvnd/libgl1_1.7.0-1build1_arm64.deb"
  "pool/main/m/mesa/libgl1-mesa-dri_24.0.5-1ubuntu1_arm64.deb"
  "pool/main/m/mesa/libglapi-mesa_24.0.5-1ubuntu1_arm64.deb"
  "pool/main/libg/libglvnd/libglvnd0_1.7.0-1build1_arm64.deb"
  "pool/main/m/mesa/libglx-mesa0_24.0.5-1ubuntu1_arm64.deb"
  "pool/main/libg/libglvnd/libglx0_1.7.0-1build1_arm64.deb"
  "pool/main/libi/libice/libice6_1.0.10-1build3_arm64.deb"
  "pool/main/i/icu/libicu74_74.2-1ubuntu3_arm64.deb"
  "pool/main/libj/libjpeg-turbo/libjpeg-turbo8_2.1.5-2ubuntu2_arm64.deb"
  "pool/main/libj/libjpeg8-empty/libjpeg8_8c-2ubuntu11_arm64.deb"
  "pool/main/l/llvm-toolchain-17/libllvm17t64_17.0.6-9ubuntu1_arm64.deb"
  "pool/main/l/lzo2/liblzo2-2_2.10-2build4_arm64.deb"
  "pool/main/p/pixman/libpixman-1-0_0.42.2-1build1_arm64.deb"
  "pool/main/libp/libpng1.6/libpng16-16t64_1.6.43-5build1_arm64.deb"
  "pool/main/c/cyrus-sasl2/libsasl2-2_2.1.28+dfsg1-5ubuntu3_arm64.deb"
  "pool/main/c/cyrus-sasl2/libsasl2-modules-db_2.1.28+dfsg1-5ubuntu3_arm64.deb"
  "pool/main/l/lm-sensors/libsensors-config_3.6.0-9build1_all.deb"
  "pool/main/l/lm-sensors/libsensors5_3.6.0-9build1_arm64.deb"
  "pool/main/libs/libsm/libsm6_1.2.3-1build3_arm64.deb"
  "pool/main/t/tcl8.6/libtcl8.6_8.6.14+dfsg-1build1_arm64.deb"
  "pool/main/t/tk8.6/libtk8.6_8.6.14-1build1_arm64.deb"
  "pool/main/libu/libunwind/libunwind8_1.6.2-3build1_arm64.deb"
  "pool/main/libv/libvncserver/libvncclient1_0.9.14+dfsg-1build2_arm64.deb"
  "pool/main/libv/libvncserver/libvncserver1_0.9.14+dfsg-1build2_arm64.deb"
  "pool/main/v/vulkan-loader/libvulkan1_1.3.275.0-1build1_arm64.deb"
  "pool/main/libx/libx11/libx11-6_1.8.7-1build1_arm64.deb"
  "pool/main/libx/libx11/libx11-data_1.8.7-1build1_all.deb"
  "pool/main/libx/libx11/libx11-xcb1_1.8.7-1build1_arm64.deb"
  "pool/main/libx/libxau/libxau6_1.0.9-1build6_arm64.deb"
  "pool/main/libx/libxaw/libxaw7_1.0.14-1build2_arm64.deb"
  "pool/main/libx/libxcb/libxcb-dri2-0_1.15-1ubuntu2_arm64.deb"
  "pool/main/libx/libxcb/libxcb-dri3-0_1.15-1ubuntu2_arm64.deb"
  "pool/main/libx/libxcb/libxcb-glx0_1.15-1ubuntu2_arm64.deb"
  "pool/main/libx/libxcb/libxcb-present0_1.15-1ubuntu2_arm64.deb"
  "pool/main/libx/libxcb/libxcb-randr0_1.15-1ubuntu2_arm64.deb"
  "pool/main/libx/libxcb/libxcb-shm0_1.15-1ubuntu2_arm64.deb"
  "pool/main/libx/libxcb/libxcb-sync1_1.15-1ubuntu2_arm64.deb"
  "pool/main/libx/libxcb/libxcb-xfixes0_1.15-1ubuntu2_arm64.deb"
  "pool/main/libx/libxcb/libxcb1_1.15-1ubuntu2_arm64.deb"
  "pool/main/libx/libxdamage/libxdamage1_1.1.6-1build1_arm64.deb"
  "pool/main/libx/libxdmcp/libxdmcp6_1.1.3-0ubuntu6_arm64.deb"
  "pool/main/libx/libxext/libxext6_1.3.4-1build2_arm64.deb"
  "pool/main/libx/libxfixes/libxfixes3_6.0.0-2build1_arm64.deb"
  "pool/main/libx/libxfont/libxfont2_2.0.6-1build1_arm64.deb"
  "pool/main/x/xft/libxft2_2.3.6-1build1_arm64.deb"
  "pool/main/libx/libxi/libxi6_1.8.1-1build1_arm64.deb"
  "pool/main/libx/libxinerama/libxinerama1_1.1.4-3build1_arm64.deb"
  "pool/main/libx/libxkbfile/libxkbfile1_1.1.0-1build4_arm64.deb"
  "pool/main/libx/libxml2/libxml2_2.9.14+dfsg-1.3ubuntu3_arm64.deb"
  "pool/main/libx/libxmu/libxmu6_1.1.3-3build2_arm64.deb"
  "pool/main/libx/libxmu/libxmuu1_1.1.3-3build2_arm64.deb"
  "pool/main/libx/libxpm/libxpm4_3.5.17-1build2_arm64.deb"
  "pool/main/libx/libxrandr/libxrandr2_1.5.2-2build1_arm64.deb"
  "pool/main/libx/libxrender/libxrender1_0.9.10-1.1build1_arm64.deb"
  "pool/main/libx/libxshmfence/libxshmfence1_1.3-1build5_arm64.deb"
  "pool/main/libx/libxss/libxss1_1.2.3-1build3_arm64.deb"
  "pool/main/libx/libxt/libxt6t64_1.2.1-1.2build1_arm64.deb"
  "pool/main/libx/libxtst/libxtst6_1.2.3-1.1build1_arm64.deb"
  "pool/main/libx/libxxf86vm/libxxf86vm1_1.1.4-1build4_arm64.deb"
  "pool/main/l/lsb/lsb-base_11.6_all.deb"
  "pool/main/o/openssl/openssl_3.0.13-0ubuntu3_arm64.deb"
  "pool/main/t/tcltk-defaults/tcl_8.6.14build1_arm64.deb"
  "pool/main/t/tcl8.6/tcl8.6_8.6.14+dfsg-1build1_arm64.deb"
  "pool/main/t/tcltk-defaults/tk_8.6.14build1_arm64.deb"
  "pool/main/t/tk8.6/tk8.6_8.6.14-1build1_arm64.deb"
  "pool/main/t/tzdata/tzdata_2024a-2ubuntu1_all.deb"
  "pool/main/x/xorg/x11-common_7.7+23ubuntu3_all.deb"
  "pool/main/x/x11-xkb-utils/x11-xkb-utils_7.7+8build2_arm64.deb"
  "pool/universe/x/x11vnc/x11vnc_0.9.16-10_arm64.deb"
  "pool/main/x/xauth/xauth_1.1.2-1build1_arm64.deb"
  "pool/main/x/xfonts-base/xfonts-base_1.0.5+nmu1_all.deb"
  "pool/main/x/xfonts-encodings/xfonts-encodings_1.0.5-0ubuntu2_all.deb"
  "pool/main/x/xfonts-utils/xfonts-utils_7.7+6build3_arm64.deb"
  "pool/main/x/xkeyboard-config/xkb-data_2.41-2ubuntu1_all.deb"
  "pool/main/x/xorg-server/xserver-common_21.1.12-1ubuntu1_all.deb"
  "pool/universe/x/xorg-server/xvfb_21.1.12-1ubuntu1_arm64.deb"
)

PACK="$WORK/pack"
mkdir -p "$PACK"
echo "Downloading ${#DEBS[@]} arm64 .debs (X server closure)…"
for path in "${DEBS[@]}"; do
  f="$WORK/$(basename "$path")"
  download "$POOL/$path" "$f"
  if command -v dpkg-deb >/dev/null 2>&1; then
    dpkg-deb -x "$f" "$PACK"
  else
    (cd "$PACK" && ar x "$f" && tar --no-same-owner -xf data.tar.* && rm -f data.tar.* debian-binary control.tar.*)
  fi
  rm -f "$f"
done

# Merged-usr normalization: some debs (e.g. liblzo2-2) ship files under
# /lib instead of /usr/lib. The target rootfs is merged-usr — its lib is
# a SYMLINK to usr/lib — so a pack carrying a real ./lib directory makes
# the extractor replace that symlink with a plain directory, orphaning
# /lib/ld-linux-aarch64.so.1 and killing every dynamic binary in the
# guest (seen on-device 2026-10-21). Fold any top-level lib/bin/sbin
# content into usr/ so the pack never carries those directories.
for d in lib bin sbin; do
  if [ -d "$PACK/$d" ] && [ ! -L "$PACK/$d" ]; then
    mkdir -p "$PACK/usr/$d"
    cp -a "$PACK/$d/." "$PACK/usr/$d/"
    rm -rf "$PACK/$d"
    echo "merged-usr: folded ./$d into ./usr/$d"
  fi
done

echo "Pack contents (key files):"
ls -lh "$PACK/usr/bin/Xvfb" "$PACK/usr/bin/x11vnc" "$PACK/usr/bin/xkbcomp" "$PACK/usr/bin/mkfontdir"
du -sh "$PACK"

tar -czf "$WORK/graphics-pack.tgz" -C "$PACK" .
gzip -t "$WORK/graphics-pack.tgz"
mv "$WORK/graphics-pack.tgz" dist/graphics-pack.tgz
ls -lh dist/graphics-pack.tgz
