# Publishing

Everything below is driven by one tag: `git tag v1.2.3 && git push origin v1.2.3`
runs `.github/workflows/release.yml`, which builds and attaches to the GitHub
release: the CLI installers and archives for macOS / Windows / Linux, the
Homebrew formula and Scoop manifest (checksums filled in), the IntelliJ plugin
zip, and the VS Code `.vsix`. The stores are a manual step each, once per release.

## Before the first release

- **A `LICENSE` file at the repo root.** Both marketplaces want one, `vsce`
  warns without it, and the two existing Relay plugins describe themselves as
  MIT — but the choice is the author's, so it is not in the repository yet.
- **Code signing** (optional, but without it the `.pkg` and `.msi` show a
  Gatekeeper / SmartScreen warning): an Apple Developer ID certificate and a
  Windows code-signing certificate. The script, Homebrew and Scoop routes don't
  need it.

## JetBrains Marketplace

1. First upload by hand: https://plugins.jetbrains.com → *Upload plugin* →
   `intellij-plugin/build/distributions/ai-relay-intellij-<version>.zip`.
   JetBrains reviews a new plugin (typically a couple of business days).
2. Later releases: `JETBRAINS_MARKETPLACE_TOKEN=… ./gradlew publishPlugin
   -PreleaseVersion=<version>` from `intellij-plugin/`, or upload the zip from
   the GitHub release.
3. `./gradlew verifyPlugin` runs JetBrains' compatibility checks locally; the
   release workflow runs it too.

## VS Code Marketplace

1. Create the publisher once: https://marketplace.visualstudio.com/manage →
   *Create publisher*, id **chelayel** (it must match `publisher` in
   `vscode-extension/package.json`).
2. A Personal Access Token from Azure DevOps with the *Marketplace → Manage*
   scope (https://code.visualstudio.com/api/working-with-extensions/publishing-extension#get-a-personal-access-token).
3. `cd vscode-extension && npx vsce publish -p <token>` — or upload the `.vsix`
   from the GitHub release on the publisher page.
4. Open VSX (for VSCodium and others): `npx ovsx publish airelay-vscode-<version>.vsix -p <open-vsx-token>`.

## Homebrew and Scoop

Both read the manifests the release workflow attaches:

- Homebrew: create a repository named `homebrew-tap` under the GitHub account,
  copy `airelay.rb` from the release into `Formula/airelay.rb`, commit. Users
  then `brew install chelayel/tap/airelay`. Update the file on each release.
- Scoop: no repository needed — users install straight from the release asset:
  `scoop install https://github.com/Chelayel/ai-relay/releases/latest/download/airelay.json`.
  A `scoop-bucket` repository can carry the same file for a nicer name.
