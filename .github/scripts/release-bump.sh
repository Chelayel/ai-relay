#!/usr/bin/env bash
# Bumps the version for a merged pull request, commits it to main, tags it and
# pushes both atomically. Run by auto-release.yml; see that file for the rules.
#
#   LABELS     comma-separated labels of the pull request
#   PR_NUMBER  the pull request's number, for the commit message
#   PR_TITLE   the pull request's title, for the commit message
#
# Locally, `.github/scripts/release-bump.sh --dry-run` prints the next version
# and applies the bump to the working tree without committing or pushing.
set -euo pipefail

dry_run=false
[ "${1:-}" = "--dry-run" ] && dry_run=true

level="patch"
case ",${LABELS:-}," in
  *,release:major,*) level=major ;;
  *,release:minor,*) level=minor ;;
esac

# The version in the files, which a person may have raised by hand.
file_version() {
  sed -n 's/^pluginVersion=//p' "intellij-plugin/gradle.properties" | tr -d ' \r'
}

# Writes the version into every file that carries it.
apply_version() {
  local v="$1"
  sed -i -E "s/^(version = .*\?: \")[^\"]+(\")/\1$v\2/" build.gradle.kts
  sed -i -E "s/^pluginVersion=.*/pluginVersion=$v/" intellij-plugin/gradle.properties
  set_package_version vscode-extension "$v"
}

# Sets "version" in a package directory's package.json and, when present, the
# two root entries of its package-lock.json, editing only those lines so the
# rest of each file keeps its formatting.
set_package_version() {
  V="$2" node -e '
    const fs = require("fs");
    const dir = process.argv[1], v = process.env.V;
    // package.json has one root "version"; package-lock.json has two (the
    // root and packages[""]), and they come before any dependency entry.
    for (const [file, count] of [["package.json", 1], ["package-lock.json", 2]]) {
      const path = dir + "/" + file;
      if (!fs.existsSync(path)) continue;
      let seen = 0;
      const text = fs.readFileSync(path, "utf8").replace(
        /^( +"version": ")[^"]*(")/gm,
        (line, head, tail) => (seen++ < count ? head + v + tail : line));
      fs.writeFileSync(path, text);
    }
  ' "$1"
}

newest() { printf '%s\n' "$@" | sort -V | tail -1; }

next_version() {
  local cur="$1" major minor patch
  IFS=. read -r major minor patch <<<"$cur"
  case "$level" in
    major) echo "$((major + 1)).0.0" ;;
    minor) echo "$major.$((minor + 1)).0" ;;
    *)     echo "$major.$minor.$((patch + 1))" ;;
  esac
}

git config user.name "github-actions[bot]"
git config user.email "41898282+github-actions[bot]@users.noreply.github.com"

for attempt in 1 2 3 4 5; do
  if ! $dry_run; then
    git fetch --quiet --tags origin main
    git reset --quiet --hard origin/main
  fi

  last_tag=$(git tag -l 'v[0-9]*' | sed 's/^v//' | sort -V | tail -1)
  current=$(newest "${last_tag:-0.0.0}" "$(file_version)")
  # A hand-raised version in the files that has no tag yet is released as is.
  if [ "$(file_version)" != "${last_tag:-}" ] && [ "$(file_version)" = "$current" ] \
     && ! git rev-parse -q --verify "refs/tags/v$current" >/dev/null; then
    version="$current"
  else
    version=$(next_version "$current")
  fi
  echo "Last tag: v${last_tag:-none}; files: $(file_version); releasing v$version ($level)"

  apply_version "$version"
  if $dry_run; then
    git --no-pager diff --stat
    exit 0
  fi

  git add -A
  git commit --quiet -m "Release v$version" -m "From #${PR_NUMBER:-?}: ${PR_TITLE:-}"
  git tag -a "v$version" -m "v$version"
  if git push --atomic origin HEAD:main "refs/tags/v$version"; then
    echo "Pushed main and v$version."
    exit 0
  fi

  # Someone else moved main or took the tag first; start again from theirs.
  echo "Push rejected (attempt $attempt); retrying."
  git tag -d "v$version" >/dev/null
  sleep $((attempt * 5))
done

echo "::error::Could not push the release after 5 attempts."
exit 1
