# Publishing

Each piece has its own version and ships when that version moves:

| Piece | Version lives in | Ships when |
| --- | --- | --- |
| CLI | the tag (`git tag v1.2.3 && git push origin v1.2.3`) | the tag is pushed: installers and archives on the GitHub release, Homebrew tap, Scoop manifest |
| IntelliJ plugin | the tag on a tag run (it bundles the CLI); otherwise `pluginVersion` in `intellij-plugin/gradle.properties` | every tag, at the tag's version, when the JetBrains Marketplace does not have it yet |
| VS Code extension | the tag on a tag run (it bundles the CLI); otherwise `version` in `vscode-extension/package.json` | the same, against the Visual Studio Marketplace |

Only a tag publishes, and a tag ships all three at the tag's version: the
plugin and the extension bundle the CLI, so a CLI release is a release of
theirs too, and their versions follow the tag on that run without a commit.
The store job uploads only what is newer than the store, so re-running a tag
is safe. A push to `main` that changes
`intellij-plugin/`, `vscode-extension/` or `ide/chat/` builds them as a check
and uploads nothing: publishing from `main` too raced the tag's run for the
same version, and a plugin built off `main` bundled the CLI at the build file's
fallback version. A missing token skips that store with a warning; the built
files are in the run's `ide` artifact.

## Store tokens (repository secrets)

Set each once with the GitHub CLI from the repository directory. `gh secret set`
reads the value from standard input, so the token never lands in shell history:

```
gh secret set JETBRAINS_MARKETPLACE_TOKEN   # paste the token, Enter, Ctrl-D
gh secret set AZURE_CLIENT_ID               # VS Code Marketplace, managed identity
gh secret set AZURE_TENANT_ID
gh secret set VSCE_PAT                      # VS Code Marketplace, PAT fallback
gh secret set OVSX_PAT                      # Open VSX, optional
gh secret set TAP_DEPLOY_KEY < tap_key      # Homebrew tap: the private half of a write deploy key on Chelayel/homebrew-tap
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
   `vscode-extension/package.json`). The first upload of a new extension is
   done there by hand with the `.vsix` from the run's `ide` artifact (Actions →
   the release run → Artifacts) or a local `npx vsce package`.
2. Automation signs in one of two ways. The release workflow uses the managed
   identity when `AZURE_CLIENT_ID` is set, else the PAT, else skips the store.

### Managed identity (no stored secret; the route that outlives PATs)

Microsoft retires global Azure DevOps PATs on 2026-12-01, and the Marketplace
accepts no other kind. `vsce publish --oidc` exists but the Marketplace never
shipped the policy page it needs, so it fails. What works is a user-assigned
managed identity in Azure, trusted through GitHub's OIDC token:

1. **Azure**: a subscription (the free tier is enough; the identity is a
   resource that needs a home). Portal → *Managed Identities* → *Create*:
   any resource group, region and name. Note its **Client ID** and
   **Tenant ID** from *Properties*. It must be a managed identity, not an app
   registration — an app registration signs in but publishing fails with
   `InvalidAccessException`.
2. **Federated credential**: on the identity, *Settings → Federated
   credentials → Add credential*. Scenario *GitHub Actions deploying Azure
   resources*; organization `Chelayel`, repository `ai-relay`; entity type
   **Environment**, name `marketplace-publish`. (Branch or tag entity types
   break on the next release; the environment is what the release workflow's
   `stores` job runs in.)
3. **GitHub**: `gh secret set AZURE_CLIENT_ID` and `gh secret set AZURE_TENANT_ID`
   with the two values from step 1.
4. **The id the Marketplace knows the identity by**: run the
   `marketplace-identity` workflow (*Actions → marketplace-identity → Run
   workflow*). It signs in as the identity and prints an id in a notice. This
   is an Azure DevOps profile id, not the client, tenant, object or resource
   id, and the Members page accepts only this one.
5. **Publisher**: https://marketplace.visualstudio.com/manage → the publisher →
   *Members* → *Add*, paste that id, role **Contributor**.
6. Delete `VSCE_PAT` (`gh secret delete VSCE_PAT`) once a release has published
   through the identity, so the fallback cannot outlive its purpose.

### Personal access token (works until 2026-12-01)

An Azure DevOps PAT: sign in at https://dev.azure.com with the publisher's
Microsoft account, *User settings → Personal access tokens → New Token*,
organization **All accessible organizations** (a single organization gives
403), scope *Custom defined → Show all scopes →* **Marketplace: Manage**,
expiry no later than 2026-11-30. Then `gh secret set VSCE_PAT`.

### Open VSX (optional)

For VSCodium and other forks: an account at https://open-vsx.org, an access
token from its user settings, a namespace named **chelayel** created with
`npx ovsx create-namespace chelayel -p <token>`, then `OVSX_PAT` as a
repository secret.

## Homebrew and Scoop

Both read the manifests the release workflow attaches:

- Homebrew: the repository `Chelayel/homebrew-tap` holds `Formula/airelay.rb`;
  users `brew install chelayel/tap/airelay`. The release workflow commits the
  new formula there on every tag through a **deploy key** on the tap: the
  public half is registered under the tap's *Settings → Deploy keys* with
  *Allow write access*, the private half is the `TAP_DEPLOY_KEY` secret. To
  rotate it:

  ```
  ssh-keygen -t ed25519 -N "" -C "ai-relay release workflow" -f tap_key
  gh api -X POST repos/Chelayel/homebrew-tap/keys -f title="ai-relay release workflow" -f key="$(cat tap_key.pub)" -F read_only=false
  gh secret set TAP_DEPLOY_KEY < tap_key
  rm tap_key tap_key.pub
  ```

  (A fine-grained personal token was tried first and GitHub kept answering
  "Resource not accessible by personal access token" from the workflow even
  with Contents read/write; a deploy key is scoped to the one repository and
  does not expire.) Without the secret the formula is only attached to the
  release and `brew upgrade` keeps offering the old version.
- Scoop: no repository needed — users install straight from the release asset:
  `scoop install https://github.com/Chelayel/ai-relay/releases/latest/download/airelay.json`.
  A `scoop-bucket` repository can carry the same file for a nicer name.
