#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
command -v git >/dev/null || { echo "Instale o git antes."; exit 1; }
vendor='vendor/llama.cpp'
patch_dir="$(pwd -P)/patches"
base_patch="$patch_dir/llama-android.patch"
asset_patch="$patch_dir/llama-android-asset.patch"
build_patch="$patch_dir/llama-android-build.patch"
patches=("$base_patch" "$asset_patch" "$build_patch")
revision="$(tr -d '\r\n' < gradle/llama-cpp.commit)"
if [[ ! "$revision" =~ ^[a-f0-9]{40}$ ]]; then
    echo 'Revisão inválida. Confira gradle/llama-cpp.commit.' >&2
    exit 1
fi
for patch in "${patches[@]}"; do
    if [ ! -f "$patch" ]; then
        echo "Patch Android ausente: $patch" >&2
        exit 1
    fi
done
if [ ! -e "$vendor/.git" ]; then
    git init -q "$vendor"
    git -C "$vendor" config core.autocrlf false
    git -C "$vendor" config core.longpaths true
fi
head_revision="$(git -C "$vendor" rev-parse --verify HEAD 2>/dev/null || true)"
if [ "$head_revision" != "$revision" ]; then
    echo 'Obtendo a revisão fixada do llama.cpp.'
    git -C "$vendor" fetch --depth 1 --no-tags https://github.com/ggml-org/llama.cpp.git "$revision"
    git -C "$vendor" checkout --detach "$revision"
fi
apply_patch() {
    local patch="$1"
    if ! git -C "$vendor" apply --reverse --ignore-space-change --check "$patch" 2>/dev/null; then
        git -C "$vendor" apply --ignore-space-change "$patch"
    fi
}
# O patch de assets altera linhas do patch base. Confira a ponta da pilha primeiro.
if ! git -C "$vendor" apply --reverse --ignore-space-change --check "$asset_patch" 2>/dev/null; then
    apply_patch "$base_patch"
    apply_patch "$asset_patch"
fi
apply_patch "$build_patch"
git -C "$vendor" rev-parse --verify HEAD > LLAMA_CPP_COMMIT_USADO.txt
echo 'Pronto. Para gerar o APK offline execute bash gradlew :app:assembleOfflineDebug.'
