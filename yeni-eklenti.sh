#!/usr/bin/env bash
# Sablon eklentisini kopyalayıp yeni bir site eklentisi oluşturur.
# Kullanım: ./yeni-eklenti.sh SiteAdi https://siteadi.com
set -euo pipefail

if [ $# -ne 2 ]; then
  echo "Kullanım: $0 SiteAdi https://siteadi.com" >&2
  exit 1
fi

NAME="$1"
URL="${2%/}"
DOMAIN="$(echo "$URL" | sed -E 's#^https?://##; s#/.*$##')"
ROOT="$(cd "$(dirname "$0")" && pwd)"

if ! [[ "$NAME" =~ ^[A-Z][A-Za-z0-9]*$ ]]; then
  echo "SiteAdi büyük harfle başlamalı ve sadece harf/rakam içermeli (ör. DiziSitesi)." >&2
  exit 1
fi
if [ -e "$ROOT/$NAME" ]; then
  echo "$NAME zaten var." >&2
  exit 1
fi

SRC="$ROOT/$NAME/src/main/kotlin/com/kendirepo"
cp -R "$ROOT/Sablon" "$ROOT/$NAME"
rm -rf "$ROOT/$NAME/build"
mv "$SRC/Sablon.kt" "$SRC/$NAME.kt"
mv "$SRC/SablonPlugin.kt" "$SRC/${NAME}Plugin.kt"

sed -i.bak \
  -e "s#class Sablon : MainAPI#class $NAME : MainAPI#" \
  -e "s#override var mainUrl = \"https://example.com\"#override var mainUrl = \"$URL\"#" \
  -e "s#override var name = \"Sablon\"#override var name = \"$NAME\"#" \
  "$SRC/$NAME.kt"
sed -i.bak \
  -e "s#SablonPlugin#${NAME}Plugin#" \
  -e "s#registerMainAPI(Sablon())#registerMainAPI($NAME())#" \
  "$SRC/${NAME}Plugin.kt"
sed -i.bak \
  -e "s#description = \".*\"#description = \"$DOMAIN\"#" \
  -e "s#domain=example.com#domain=$DOMAIN#" \
  "$ROOT/$NAME/build.gradle.kts"
find "$ROOT/$NAME" -name '*.bak' -delete

echo "Oluşturuldu: $NAME/"
echo "Şimdi $SRC/$NAME.kt içindeki SEÇİCİ yorumlu yerleri siteye göre düzenle."
echo "Derlemek için: ./gradlew $NAME:make"
