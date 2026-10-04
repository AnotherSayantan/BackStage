# BackStage

BackStage is an encrypted workspace lifecycle manager designed for local developer environments, sensitive projects, and disk space reclamation.

It provides deterministic state management for directory trees, transitioning them between active working directories on the local filesystem and encrypted, authenticated archives in cold storage.

---

## Overview

Software projects accumulate significant local data—source code, uncommitted changes, dependency trees (`node_modules`, `.gradle`, virtualenvs), build artifacts, and credentials. Storing dormant projects unencrypted on local disks consumes storage and exposes sensitive data at rest.

BackStage replaces manual archiving and directory deletion with a managed lifecycle model:

- **Active**: The directory is extracted on the local filesystem and available for development.
- **Staged**: An encrypted archive is prefetched locally with cached metadata for rapid unlocking.
- **Vaulted**: The project is encrypted, compressed, verified, and stored on cold or cloud storage. The local directory is cleanly purged.

---

## Key Features

### Cryptographic Security
- **Key Derivation**: Argon2id with high-memory parameters for password-based key generation.
- **Authenticated Encryption**: AES-256-GCM or ChaCha20-Poly1305 with per-chunk initialization vectors.
- **Integrity Verification**: BLAKE3 streaming hashes and keyed message authentication codes (MAC) across archive headers and variable metadata tables.
- **Key Zeroization**: Sensitive key material is zeroized in memory immediately after cryptographic operations.
- **Device Authentication**: Native platform biometric integration (Windows Hello, Android Biometrics).

### Safe Lifecycle Operations
- **Open Handle Detection**: Queries OS process handles (Windows Restart Manager, Linux `/proc/*/fd`) to prevent archiving or evicting workspaces actively in use by IDEs, editors, or terminals.
- **Capacity Validation**: Estimates disk requirements prior to extraction and identifies cross-volume storage deficits before disk exhaustion occurs.
- **Atomic Operations**: All archive writes use temporary staging files and atomic filesystem renames to prevent partial or corrupted archives on system interruption.
- **Integrity Validation**: Computes pre- and post-extraction snapshot diffs to detect filesystem modifications or extraction anomalies.

### Storage Backends
- **Local Filesystem**: Direct local path storage.
- **Mounted Cloud Drives**: Native detection for Google Drive, Microsoft OneDrive, Dropbox, and iCloud Drive.
- **Removable Media**: External USB drives and portable media.
- **Remote Services**: S3-compatible object storage and WebDAV endpoints.

### Archive Formats
- **BackStage Native (`.bstage`)**: High-performance chunked format supporting parallel compression (Zstandard) and random-access verification.
- **Plain Compressed (`.bvault`)**: Zstandard-compressed archive without encryption for trusted, non-sensitive environments.
- **Portable (`.7z`)**: AES-256 encrypted 7-Zip archives compatible with standard third-party tools.

---

## Installation & Requirements

### Desktop Application
Pre-built binaries are available on the [Releases](https://github.com/AnotherSayantan/BackStage/releases) page:
- **Windows**: MSI installer (`.msi`) or standalone portable package (`.zip`).
- **Runtime**: Bundled JetBrains Runtime (JBR 17). No external Java installation required.

### CLI Tool (`bstage`)
A command-line tool is available for terminal automation and scripting.

```bash
# Example CLI commands
bstage list
bstage load <workspace-id>
bstage evict <workspace-id>
bstage status
```

---

## Architecture & Build

BackStage is written in Kotlin Multiplatform and structured into three primary modules:

- **`:core`**: Pure Kotlin multiplatform business logic, cryptographic primitives, filesystem arbiters, and backend drivers.
- **`:composeApp`**: Desktop and Android user interface built with Compose Multiplatform.
- **`:cli`**: Headless command-line utility built with Clikt.

### Building from Source

Prerequisites:
- JDK 17 or higher
- Git

```bash
# Clone the repository
git clone https://github.com/AnotherSayantan/BackStage.git
cd BackStage

# Run the desktop application
./gradlew :composeApp:run

# Build the CLI application
./gradlew :cli:shadowJar

# Package desktop installer (Windows MSI)
./gradlew :composeApp:packageMsi

# Create standalone distributable
./gradlew :composeApp:createDistributable
```

---

## License

This project is licensed under the MIT License. See [LICENSE](LICENSE) for details.
