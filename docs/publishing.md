# Publishing

Everything below is driven by one tag: `git tag v1.2.3 && git push origin v1.2.3`
runs `.github/workflows/release.yml`, which builds and attaches to the GitHub
release: the CLI installers and archives for macOS / Windows / Linux, the
Homebrew formula and Scoop manifest (checksums filled in), the IntelliJ plugin
zip, and the VS Code `.vsix`. The same tag then uploads the plugin and the
extension to their stores, for every store whose token is set as a repository
secret (`stores` job); a missing token skips that store with a warning rather
than failing the release.

## Store tokens (repository secrets)

Set each once with the GitHub CLI from the repository directory. `gh secret set`
reads the value from standard input, so the token never lands in shell history:

```
gh secret set JETBRAINS_MARKETPLACE_TOKEN   # paste the token, Enter, Ctrl-D
gh secret set VSCE_PAT                      # VS Code Marketplace
gh secret set OVSX_PAT                      # Open VSX, optional
```

`gh secret list` confirms what is set. Each store also needs its first upload
done by hand (below); the workflow handles updates.

## Before the first release

- The repository is MIT-licensed (`LICENSE`); the VS Code extension carries a copy.
- **Code signing** (optional, but without it the `.pkg` and `.msi` show a
  Gatekeeper / SmartScreen warning): an Apple Developer ID certificate and a
  Windows code-signing certificate. The script, Homebrew and Scoop routes don't
  need it.

## JetBrains Marketplace

1. First upload by hand: https://plugins.jetbrains.com → *Upload plugin* →
   `intellij-plugin/build/distributions/ai-relay-intellij-<version>.zip`.
   JetBrains reviews a new plugin (typically a couple of business days).
2. Later releases: the tag does it, with `JETBRAINS_MARKETPLACE_TOKEN` set as a
   repository secret (a token from https://plugins.jetbrains.com/author/me/tokens).
   By hand: `JETBRAINS_MARKETPLACE_TOKEN=… ./gradlew publishPlugin
   -PreleaseVersion=<version>` from `intellij-plugin/`.
3. `./gradlew verifyPlugin` runs JetBrains' compatibility checks locally; the
   release workflow runs it too.

## VS Code Marketplace

1. Create the publisher once: https://marketplace.visualstudio.com/manage →
   *Create publisher*, id **chelayel** (it must match `publisher` in
   `vscode-extension/package.json`).
2. A Personal Access Token from Azure DevOps, which is what the VS Code
   Marketplace authenticates with:
   1. Sign in at https://dev.azure.com with the same Microsoft account as the
      publisher. Create an organization if it offers to; any name works.
   2. User settings (top right) → *Personal access tokens* → *New Token*.
   3. Name it, set **Organization** to *All accessible organizations*, pick an
      expiry (a year is the maximum; note the date), and under *Scopes* choose
      *Custom defined* → *Show all scopes* → **Marketplace: Manage**.
   4. Copy the token; it is shown once.
   The first upload of a new extension can be done from the publisher page
   with the `.vsix` from the GitHub release, or with `npx vsce publish -p <token>`
   from `vscode-extension/`.
3. Later releases: the tag does it, with `VSCE_PAT` set as a repository secret.
4. Open VSX (for VSCodium and other forks), optional: an account at
   https://open-vsx.org, an access token from its user settings, a namespace
   named **chelayel** created with `npx ovsx create-namespace chelayel -p <token>`,
   then `OVSX_PAT` as a repository secret.

## Homebrew and Scoop

Both read the manifests the release workflow attaches:

- Homebrew: create a repository named `homebrew-tap` under the GitHub account,
  copy `airelay.rb` from the release into `Formula/airelay.rb`, commit. Users
  then `brew install chelayel/tap/airelay`. Update the file on each release.
- Scoop: no repository needed — users install straight from the release asset:
  `scoop install https://github.com/Chelayel/ai-relay/releases/latest/download/airelay.json`.
  A `scoop-bucket` repository can carry the same file for a nicer name.
