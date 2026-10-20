# Installing, upgrading and removing AI Relay

[← README](../README.md)

## Install

No Java needed — every download below carries its own runtime.

| System | How |
| --- | --- |
| **macOS / Linux** | `curl -fsSL https://raw.githubusercontent.com/Chelayel/ai-relay/main/packaging/install.sh \| sh` |
| **macOS (Homebrew)** | `brew install chelayel/tap/airelay` |
| **macOS (installer)** | `airelay-macos-arm64.pkg` (Apple silicon) or `-x64.pkg` from [Releases](https://github.com/Chelayel/ai-relay/releases) |
| **Windows (installer)** | `airelay-windows-x64.msi` from [Releases](https://github.com/Chelayel/ai-relay/releases), then open **airelay** from the Start menu — it offers to add itself to your PATH |
| **Windows (PowerShell)** | `irm https://raw.githubusercontent.com/Chelayel/ai-relay/main/packaging/install.ps1 \| iex` |
| **Windows (Scoop)** | `scoop install https://github.com/Chelayel/ai-relay/releases/latest/download/airelay.json` |
| **Debian / Ubuntu** | `sudo dpkg -i airelay-linux-x64.deb` |

Then run `airelay` with no arguments and it asks which agent and which folder;
or `airelay demo` to try the prompt with no account at all.

## Which version, and upgrading

`airelay --version` prints the installed version; the banner shows it too.
Once a day the CLI checks the latest release in the background and, when a
newer one exists, the line above the prompt says so with the upgrade command
for the way this copy was installed:

| Installed with | Upgrade with |
| --- | --- |
| `install.sh` | re-run the one-liner: `curl -fsSL https://raw.githubusercontent.com/Chelayel/ai-relay/main/packaging/install.sh \| sh` |
| Homebrew | `brew update && brew upgrade airelay` |
| `install.ps1` | re-run the one-liner: `irm https://raw.githubusercontent.com/Chelayel/ai-relay/main/packaging/install.ps1 \| iex` |
| Scoop | `scoop update airelay` |
| `.pkg` / `.msi` / `.deb` | download the new installer from [Releases](https://github.com/Chelayel/ai-relay/releases/latest) and run it over the old one |

The IDE plugins update through their marketplaces; the IntelliJ one carries
its own copy of the CLI, the VS Code one uses an installed `airelay` when
there is one and its bundled copy otherwise.

## Switching how it's installed

Each route puts `airelay` somewhere different and none of them can remove
another's copy, so **uninstall the old route before (or after) installing the
new one**. Otherwise both stay on `PATH`, the first one wins, and that is
usually the old one: the new install seems to do nothing. `airelay` warns
when it sees this, and the installers do too; `type -a airelay` (or
`where airelay` on Windows) shows every copy.

| Installed with | Remove it with |
| --- | --- |
| `install.sh` | `rm ~/.local/bin/airelay && rm -rf ~/.local/share/airelay` |
| Homebrew | `brew uninstall airelay` |
| `.pkg` | `sudo rm -rf /Applications/airelay.app /usr/local/bin/airelay && sudo pkgutil --forget com.chelayel.airelay` |
| `.msi` | Settings › Apps › Installed apps › airelay › Uninstall |
| `install.ps1` | Delete `%LOCALAPPDATA%\Programs\airelay` and remove it from your user PATH |
| Scoop | `scoop uninstall airelay` |
| `.deb` | `sudo apt remove airelay` |

The installers are **not code-signed**. The script, Homebrew and Scoop routes
are unaffected. A double-clicked `.pkg` is stopped by Gatekeeper (right-click →
Open), and the `.msi` by SmartScreen (More info → Run anyway), until the
project has an Apple Developer ID and a Windows signing certificate.

Releases are cut by pushing a tag — `git tag v1.2.3 && git push origin v1.2.3`
— which runs `.github/workflows/release.yml` on macOS, Windows and Linux.

## Behind a corporate proxy: "PKIX path building failed" / "unable to find valid certification path"

A corporate proxy or firewall is inspecting TLS and signing with its own root
certificate. Your browser trusts it because the OS does; the Java runtime
bundled with `airelay` has its own certificate store and did not. Since 2.0.3
`airelay` also trusts the operating system's store (Windows, macOS) and any
certificate files it finds (since 2.0.3), so this usually just works. If it still fails:

- put the proxy's root certificate (PEM or CRT) in `~/.airelay/certs/`, or
- point `AIRELAY_CA_BUNDLE` (or `SSL_CERT_FILE`) at it.

The banner's `tls` line lists what was added. On Linux the OS store is not
read; use one of the two routes above.


## Build from source

Requires JDK 21. The Gradle wrapper is included.

```bash
./gradlew installDist
# launcher at build/install/airelay/bin/airelay
```

Put it on your PATH:

```bash
export PATH="$PWD/build/install/airelay/bin:$PATH"
```

The launcher finds its own JDK 21+ (and falls back to the one it was built
with), so it still starts inside a repo whose `JAVA_HOME` points at an older
JDK — that repo's `JAVA_HOME` is passed through untouched to anything the agent
runs, such as its `./gradlew`. Set `AIRELAY_JAVA_HOME` to choose the JVM
explicitly.
