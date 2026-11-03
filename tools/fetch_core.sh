#!/usr/bin/env bash
# Scarica il motore di EDOPro (ocgcore) e Lua alle versioni collaudate con l'app.
set -euo pipefail
DEST=app/src/main/cpp/third_party/ocgcore
rm -rf "$DEST"
git clone -q https://github.com/edo9300/ygopro-core.git "$DEST"
git -C "$DEST" checkout -q c2e0b697e031d3560c077c9cd2cc76474969dedf
rm -rf "$DEST/lua/src"
git clone -q https://github.com/lua/lua.git "$DEST/lua/src"
git -C "$DEST/lua/src" checkout -q 6e22fedb74cf0c9b6656e9fce8b7331db847c605
echo "motore pronto in $DEST"
