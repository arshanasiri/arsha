#!/usr/bin/env bash
# ارسال مدارک تست (عکس‌ها و لاگ‌ها) به یک Release از نوع prerelease
set -u
TAG="ci-$1"
shopt -s nullglob
cd ci-out 2>/dev/null || exit 0
( cd reports 2>/dev/null && zip -rq ../reports.zip . ) || true
FILES=( shots/*.png summary.txt logcat-*.txt reports.zip *.log services-*.txt )
[ ${#FILES[@]} -eq 0 ] && exit 0
cd ..
ARGS=()
for f in "${FILES[@]}"; do ARGS+=("ci-out/$f"); done
gh release create "$TAG" --prerelease --title "CI evidence $1" --notes "commit $GITHUB_SHA" "${ARGS[@]}" || true
