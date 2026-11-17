# Publishing SmartTrack on GitHub

This repository contains source, build inputs, migration history, scripts and public documentation. Build outputs and private runtime data stay local. Adding a GitHub remote or publishing a repository does not deploy the backend or upload the ESP32 firmware.

## Before the first commit

1. Review `git status --short` and `git diff --cached --name-status`. Stage only the intended source/documentation changes. Do not use `git add -f` to override the ignore rules for local credentials or output folders.
2. Inspect the content with `git diff --cached` and run a staged-file secret scan. Review code literals as well as filenames; an ignored `.env` cannot protect a password pasted into source. Check screenshots for account details, pairing codes, network addresses and health/location data.
3. Verify `.env.example` still contains only blanks/placeholders. It is a reference, not an automatically loaded configuration file. Keep MySQL passwords, firmware provisioning values and Android signing keys outside Git.
4. Build/test using the README. Automated software tests do not establish sensor accuracy, production security or release signing.
5. Review and approve the exact staged file list **before** committing. Re-scan and review again if that list or its content changes.

The preparation review files are saved locally under ignored `.work/github-preparation/`: `STAGED_FILES.md` lists every proposed path, `STAGED_FILES.txt` is the plain list, and `STAGED_SCAN_REPORT.md` summarizes checks. These private files are not part of the repository. The initial preparation intentionally performs no commit or push.

## Files that stay local

- `.tools/`, `.work/`, `dist/`, Maven `target/`, Gradle `build/` and `.gradle/`, PlatformIO `.pio/`, caches, generated archives and compiled installers/APKs/firmware.
- `.env` and other filled-in environment files, local SDK/IDE configuration, credentials/session JSON, private keys and keystores.
- Database state, backups/dumps and raw bench logs. Keep `database/V*__*.sql` migrations and synthetic contract fixtures.
- Generated `report/` and `presentation/` deliverables, including student details. Source documentation and reviewed screenshots under `docs/` remain included.

Application icons under `design-system/icons/` are required packaging assets; reviewed PNG screenshots are documentation evidence. They are intentionally retained even though APKs, EXEs and other compiled program outputs are excluded.

The text documentation uses placeholders for recorded LAN IP addresses. Desktop screenshots still contain the bench device's non-secret identifier. The emulator's `10.0.2.2` and loopback `127.0.0.1` are development routing addresses, not private deployment credentials. Test-only password literals create isolated `example.test` accounts and must not be reused for real accounts. Raw evidence paths in historical notes refer to private, ignored local records and will not be downloadable from a public checkout.

No licence is added by this preparation. Choose one only after confirming the team's ownership and university requirements; public visibility alone does not grant a software licence.

## Upload commands, after approval

Create an **empty** GitHub repository named `SmartTrack` in your own account. Leave GitHub's automatic README, licence and `.gitignore` options unchecked because those files are already prepared locally. Replace `YOUR_USERNAME` below with that account. Authenticate through Git Credential Manager or an SSH setup; never put a token/password in a remote URL.

From PowerShell in the project root, after approving the staged snapshot:

```powershell
git diff --cached --name-status
git diff --cached --check
git commit -m "Initial SmartTrack source and documentation"
git branch -M main
git remote add origin https://github.com/YOUR_USERNAME/SmartTrack.git
git push -u origin main
```

If an `origin` already exists, inspect it with `git remote -v`; do not blindly replace it or force-push. Use the actual URL returned by your GitHub repository. The initial preparation found no remote and no existing commits. If Git requests an author identity, set your chosen name and email with repository-local `git config user.name` and `git config user.email` before committing; use a GitHub-provided no-reply email if desired.

Do not run `git clean`, delete build folders or alter local databases just to publish source. Ignore rules keep these files on disk. For future binary releases, attach reviewed, signed build outputs to GitHub Releases separately from the source tree.
