#!/usr/bin/env bash
# Generate upload key sekali untuk MusPlay (lihat AGENTS.md §CI/CD, keystore
# & release). Butuh `keytool` di PATH (bagian dari JDK apa pun).
#
#   ./scripts/generate-keystore.sh
#
# Meminta alias & password secara interaktif, lalu menulis upload-keystore.jks
# di root repo. File hasil JANGAN di-commit (sudah digitignore) — isinya
# dipindah ke GitHub Secrets sesuai petunjuk yang script ini cetak di akhir.
set -euo pipefail

cd "$(dirname "$0")/.."
OUT="upload-keystore.jks"

if [ -f "$OUT" ]; then
  echo "ERROR: $OUT sudah ada. Keystore yang dipakai rilis sebelumnya tidak boleh ditimpa." >&2
  exit 1
fi

read -r -p "Alias (enter = bitchord): " ALIAS
ALIAS=${ALIAS:-bitchord}

while true; do
  read -r -s -p "Password keystore (min 6 karakter): " PASS1; echo
  read -r -s -p "Ulangi password: " PASS2; echo
  [ "$PASS1" = "$PASS2" ] && [ "${#PASS1}" -ge 6 ] && break
  echo "Password kosong/terlalu pendek/tidak sama — ulangi."
done

keytool -genkeypair -v \
  -keystore "$OUT" -alias "$ALIAS" \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass "$PASS1" -keypass "$PASS1" \
  -dname "CN=MusPlay, OU=Personal, O=MusPlay, C=ID"

cat <<EOF

Selesai: $OUT (alias: $ALIAS)

Langkah berikutnya — pindahkan ke GitHub Secrets (jangan commit file ini):
  base64 -w0 $OUT > keystore.b64
  gh secret set KEYSTORE_BASE64 < keystore.b64
  gh secret set KEYSTORE_PASSWORD -b '$PASS1'
  gh secret set KEY_ALIAS -b '$ALIAS'
  gh secret set KEY_PASSWORD -b '$PASS1'

Simpan salinan $OUT + password di tempat aman: hilang = user existing
tidak bisa update app (lihat AGENTS.md).
EOF
