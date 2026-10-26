# Agent rules and conventions — Tensura Xaero Integration

**Repo:** `HexaHexes/tensura-xaero-integration` · **Mod ID:** `tensura_xaero_integration` · **Branch:** `main`
**Platform:** Minecraft 1.21.1 / NeoForge 21.1.259 / JDK 21 · **Jar:** `build/libs/tensura_xaero_integration-<version>.jar`

Shows Tensura: Reincarnated's ambient chunk magicule as a translucent overlay on
Xaero's Minimap and Xaero's World Map, and reports the hovered chunk's
concentration on the world map. Server-authoritative, mapped chunks only, no
simulation of its own. Read `docs/RECONNAISSANCE.md` before touching any
third-party API — it records what those jars actually expose and what they do not.

**Dependencies**
- Required: Minecraft, NeoForge.
- Optional: `tensura` (2.0.1.3+, the only concentration source), `xaerominimap`
  (26.6.0+), `xaeroworldmap` (1.47.0+) — all `compileOnly` from CurseMaven, never
  bundled. Gating: `tensura/TensuraBridge` and `client/WorldMapBridge` /
  `client/MinimapBridge` check `ModList` first, and `TensuraAccess` /
  `Xaero*Access` are the only classes importing those APIs. Do not import a
  third-party type anywhere else.

**Test:** `./gradlew runGameTestServer` — `ConcentrationGameTests` (6 tests).
`expectedTests = 6` in `build.gradle` must match the `@GameTest` count. JUnit:
`./gradlew test` (129 tests, no Minecraft needed).

**Gotchas**
- **Two mixins are load-bearing.** `mixin/worldmap/WorldMapHighlighterRegistryMixin`
  and `mixin/minimap/MinimapHighlighterRegistryMixin` inject at `HEAD` of each
  map's `HighlighterRegistry.end()` because neither Xaero mod exposes a
  third-party registration call. Verified only against the pinned Xaero versions;
  re-verify on any bump, and keep each mixin config's `requiredMods` pointing at
  exactly one map mod so it never applies when that map is absent.
- **`compileOnly` versions and the `*_mod_version` ranges in
  `src/main/templates/META-INF/neoforge.mods.toml` are one decision.** Bump the
  file id and the version together in `gradle.properties`.
- **ManasCore's modules are FML nested jars.** `extractManascoreModules` explodes
  them onto the compile classpath; without it Tensura's public types do not
  compile. Keep that input coming from CurseMaven.
- **Never substitute `AreaMagiculeHelper.getMagicule` for a chunk query** — it
  returns the baseline when the chunk is not loaded, which turns "no data" into a
  fake value.
- **Privacy rule:** a chunk that is not mapped must never be rendered, tooltipped,
  cached or servery. `ClientConcentrationStore.offer` refuses unmapped writes and
  `ConcentrationServer` only answers subscribed chunks — do not add a path around
  either gate.
- **Last-known values are still real observations.** Persistence only ever writes
  what the mapped-only ingress already accepted, and a replayed value is always
  `historical`, so it is drawn dimmer and hover says "(last known)". Never store a
  value for an unmapped or never-visited chunk, and never let a replayed value
  compare equal to a live one (the cache treats `historical` as part of the
  observation for exactly that reason).

---

## Branch sync (do this first)

Before starting any new development in this repo:

1. `git status` — confirm what uncommitted work exists. Never disturb or sweep it.
2. `git pull --ff-only` — sync with the remote. A fast-forward is the normal case,
   because we push straight to the shared branch.
3. If the pull would **not** fast-forward (the branch has diverged), or if it would
   collide with local uncommitted work, **stop and ask** rather than merging, rebasing
   or stashing on your own.

## Scope doctrine

- **One mod + bundled datapack.** No KubeJS scripts.
- **Data-driven over code** — prefer JSON/datapack content to Java when both work.
- **Public API only, no mixins** in the colony-gear-style addon mods. Sanctioned
  exceptions: `ds-tensura-fix` (tensura-dragon-skin-rebalance), a mixin fix mod by
  design, and this repo, whose two client mixins are the only way to register a
  map overlay (see the gotchas above). No other mixin is to be added here.

## Verification

- Verify with the in-repo GameTest run (`./gradlew runGameTestServer`). Repos without
  GameTests verify with `./gradlew build`. Never verify against a live CurseForge
  instance.
- Keep `expectedTests` in `build.gradle` in sync with the `@GameTest` methods.
- **Client-render features cannot be proven headless.** The honest scope is
  "build green + the user's in-game check" - say so rather than overclaiming.

## Build environment

- JDK 21 (`JAVA_HOME` -> JDK 21); the toolchain targets Java 21.
- The Gradle wrapper is committed. `./gradlew build` compiles,
  `./gradlew runGameTestServer` runs the GameTest server.
- **Dependencies resolve through Maven, never local folders.** Compile-only mods use
  CurseMaven coordinates (`curse.maven:<slug>-<projectId>:<fileId>`, repository
  `https://cursemaven.com`); Modrinth-exclusive mods use the official Modrinth maven
  (`maven.modrinth:<project-slug>:<version>`, repository
  `https://api.modrinth.com/maven`). Runtime mod closures for GameTest runs live in a
  dedicated Gradle configuration synced into the run's `mods/` folder. Gitignored
  `libs/` and `testmods/` staging folders are retired: CI must resolve every
  dependency from a clean checkout. Pin the exact version the repo was verified
  against; bump pins deliberately, never implicitly.
- **Org-owned libraries publish to GitHub Packages** (`https://maven.pkg.github.com/HexaHexes/<repo>`,
  e.g. `infinite-dungeon-api`) and follow the same rule - never fall back to a
  gitignored local jar. Watch three things here:
  - **Reads always require auth, and GitHub answers 404 (not 401) when it is missing**,
    so an unauthenticated consumer fails with a message that looks like a mistyped
    artifact coordinate rather than a credential problem.
  - **The workflow `GITHUB_TOKEN` can publish a package owned by its own repo but
    cannot read a package owned by a different repository.** Consumers use the org
    secret `PACKAGE_READ_TOKEN` (classic token, `read:packages`). Declare the
    repository in `build.gradle` with credentials reading `System.getenv('GITHUB_ACTOR')`
    / `System.getenv('GITHUB_TOKEN')`, falling back to the `gpr.user` / `gpr.key`
    Gradle properties for local builds.
  - Inject the secret **scoped to the Gradle step only**
    (`env: GITHUB_TOKEN: ${{ secrets.PACKAGE_READ_TOKEN }}`), never at job level -
    job-level injection silently changes what `actions/checkout` and any `gh` CLI
    step authenticates as.
- On `run/` file-lock errors, kill any stale `java` process and retry.

## PowerShell quirks (this org is developed on Windows)

- A `git push` "error" in PowerShell is usually stderr passthrough — success is the
  `hash..hash  branch -> branch` line, not the absence of red text.
- `New-Object Type(args)` breaks on arithmetic — use `::new()` or a parenthesized
  argument.
- **Never write files with `Set-Content -Encoding UTF8`** — PowerShell 5.1 writes a
  UTF-8 BOM that breaks Gradle's parser and YAML. Use
  `[System.IO.File]::WriteAllText($path, $text)` (no BOM). Re-check for BOMs after
  any script-written edit.

## Git & release

- Remote is SSH: `git@github.com:HexaHexes/<name>.git`.
- Push only after tests are green **and** the user has given the go.
- Never sweep the user's uncommitted work into a commit — commit only what is intended.
- Commit subjects: concise, imperative.
- **Version bump on finish:** always bump the version when a new feature or bugfix
  is finished.
- **One feature or bugfix per branch:** develop each on its own feature branch and
  merge it into main/master through a pull request with a **squash merge**. The PR
  body must detail the work done, following `.github/pull_request_template.md`.

## House style

- **No icons** unless the user explicitly asks.
- READMEs are **player-facing only**: pitch, mechanics in plain terms, in-game usage,
  required/optional dependencies, credits, license. No build internals, no class names.
- This repo is **LGPL v2.1**, copyright Bitwise Madness. `NOTICE` / attribution files
  are mandatory and must never be deleted.
- Deploy to the Tensura RPG instance **only** when the user asks.

## Naming

- **Repo and folder:** lowercase, hyphenated (e.g. `tensura-colony-gear`).
- **`mod_id`:** lowercase with underscores (e.g. `tensura_colony_gear`). Minecraft
  requires `[a-z][a-z0-9_]{1,63}`.
- **Jar / `archivesName`:** follows `mod_id` by default.
- **Java base package:** `org.bitwisemadness.<project-name>` - repo name lowercased,
  separators stripped (e.g. `ds-tensura-fix` -> `org.bitwisemadness.dstensurafix`).
  The Maven `group` in `build.gradle` matches (`org.bitwisemadness`).
- Keep repo name, folder name, `mod_id`, and the Java package aligned; the rename
  checklist in `README.md` walks the whole set.
- **This template is canonical for org-wide rules.** When a rule is added or changed
  in any repo's `agents/AGENTS.md`, mirror the same change into the template so new
  repos inherit it.

## CI & release model

- `ci.yml` runs three checks that back the branch protection rulesets: **junit**
  (build + the JUnit suite, asserted non-vacuous), **gametest** (`runGameTestServer`,
  graded on the `All N required tests passed` log line - the exit code is not a
  reliable signal) and **version-bump** (a beta or stable bump must change the
  version file and nothing else; an `-alpha` bump may bundle work; a PR labelled
  `breaking` forces the next clean bump to raise the major or minor version).
- **Branch protection:** the default branch is guarded by two repository rulesets -
  `protect-main` (PR-only, squash merge only, strict `junit`/`gametest`/`version-bump`
  status checks, no force-pushes, no branch deletion) and
  `require-review-from-write-access` (one approving review; the listed org owners
  bypass). Direct pushes to the default branch are rejected by GitHub.
- `version-bump.yml` opens an isolated version-bump PR (only the version file changes).
- `release.yml` runs on every push to `main`: it builds, re-asserts the JUnit and
  GameTest results, then tags and publishes. Anything without a version bump, or
  with an `-alpha` suffix, is an **alpha** and only publishes when the
  `ALPHA_RELEASES` repository variable is `true`; alpha tags carry the commit
  (`vX.Y.Z+g<sha>`) and the jar asset is stamped with it. Betas and stables are
  gated on an isolated version bump. No CurseForge dispatch here: this repo has
  no CurseForge listing.
- CurseForge publishing (`curseforge.yml`) is **deferred** until the mod has a CurseForge
  listing and the `CURSEFORGE_PROJECT_ID` / `CURSEFORGE_API_KEY` secrets. Do not add it
  speculatively.
