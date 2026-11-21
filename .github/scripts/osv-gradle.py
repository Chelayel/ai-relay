"""Check Gradle-resolved dependencies against the OSV vulnerability database.

Usage: python3 osv-gradle.py <gradle-dependencies-output>...

Each argument is the text printed by
`./gradlew -q dependencies --configuration runtimeClasspath`. The version Gradle
actually resolved is used (the right-hand side of `a -> b`), not the one a
library asked for, so constraints and BOM upgrades are honoured.

Prints a Markdown report (also appended to $GITHUB_STEP_SUMMARY when set) and
exits 1 when any resolved dependency has a known advisory.
"""

import json
import os
import re
import sys
import urllib.request

COORD = re.compile(r"([\w.\-]+):([\w.\-]+):([\w.\-]+)(?: -> ([\w.\-]+))?")
OSV_BATCH = "https://api.osv.dev/v1/querybatch"


def resolved_deps(paths):
    deps = set()
    for path in paths:
        with open(path, encoding="utf-8") as fh:
            for line in fh:
                if "project " in line:
                    continue
                m = COORD.search(line)
                if m:
                    deps.add((f"{m[1]}:{m[2]}", m[4] or m[3]))
    return sorted(deps)


def osv_batch(deps):
    body = {
        "queries": [
            {"package": {"ecosystem": "Maven", "name": name}, "version": version}
            for name, version in deps
        ]
    }
    req = urllib.request.Request(
        OSV_BATCH,
        data=json.dumps(body).encode(),
        headers={"Content-Type": "application/json"},
    )
    with urllib.request.urlopen(req, timeout=60) as resp:
        return json.load(resp)["results"]


def main(paths):
    deps = resolved_deps(paths)
    hits = []
    if deps:
        for (name, version), result in zip(deps, osv_batch(deps)):
            ids = [v["id"] for v in result.get("vulns", [])]
            if ids:
                hits.append((name, version, ids))

    lines = [f"### Gradle dependencies: {len(deps)} checked against OSV", ""]
    if hits:
        lines += ["| Dependency | Resolved | Advisories |", "| --- | --- | --- |"]
        for name, version, ids in hits:
            links = ", ".join(f"[{i}](https://osv.dev/vulnerability/{i})" for i in ids)
            lines.append(f"| `{name}` | {version} | {links} |")
    else:
        lines.append("No known vulnerabilities.")
    report = "\n".join(lines) + "\n"

    print(report)
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as fh:
            fh.write(report)
    return 1 if hits else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
