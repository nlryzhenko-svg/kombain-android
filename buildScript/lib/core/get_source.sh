#!/bin/bash
set -e

source "buildScript/init/env.sh"
ENV_NB4A=1
source "buildScript/lib/core/get_source_env.sh"
pushd ..

####

if [ ! -d "sing-box" ]; then
  git clone --no-checkout https://github.com/MatsuriDayo/sing-box.git
fi
pushd sing-box
git checkout -f "$COMMIT_SING_BOX"
# Kombain: AmneziaWG obfuscation (jc/jmin/jmax/s1-s4/h1-h4/i1-i5)
git apply --whitespace=nowarn "$SRC_ROOT/buildScript/lib/core/patches/sing-box-amneziawg.patch"
popd

####

if [ ! -d "wireguard-go-awg" ]; then
  git clone --no-checkout https://github.com/Leadaxe/wireguard-go.git wireguard-go-awg
fi
pushd wireguard-go-awg
git fetch origin "$COMMIT_WIREGUARD_AWG" 2>/dev/null || true
git checkout -f "$COMMIT_WIREGUARD_AWG"
popd

####

if [ ! -d "libneko" ]; then
  git clone --no-checkout https://github.com/MatsuriDayo/libneko.git
fi
pushd libneko
git checkout "$COMMIT_LIBNEKO"
popd

####

popd
