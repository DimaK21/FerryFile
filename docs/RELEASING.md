# Releasing

Two long-lived branches:

- `develop` — the integration branch. Every change lands here first.
- `main` — released code only. Each release is a merge from `develop`, tagged `vX.Y.Z`.

Nobody commits to `develop` or `main` directly; changes arrive through branches.

## Features

1. Cut a branch from `develop`:

   ```bash
   git switch -c feature/<name> develop
   ```

2. Commit on the branch and keep `./gradlew :app:testDebugUnitTest` green.
3. Merge it into `develop` with a merge commit, then delete the branch:

   ```bash
   git switch develop
   git merge --no-ff feature/<name> -m "Merge feature/<name> into develop"
   git branch -d feature/<name>
   git push origin develop
   git push origin --delete feature/<name>   # only if the branch was pushed
   ```

   If the merge goes through a pull request, use "Create a merge commit" and tick "Delete branch".

## Releasing a batch of changes

Release when enough changes have accumulated on `develop`. Freeze `develop` while doing it: no new
merges between the version bump and the release merge.

1. Check that `develop` is green:

   ```bash
   ./gradlew :app:testDebugUnitTest :app:lintDebug
   ```

2. Bump the version on a short-lived branch. Edit `APP_VERSION_MAJOR`, `APP_VERSION_MINOR`,
   `APP_VERSION_PATCH` (see [VERSIONING.md](VERSIONING.md)) and increase `APP_VERSION_CODE` by one
   in `gradle.properties`:

   ```bash
   git switch -c release/X.Y.Z develop
   # edit gradle.properties
   git commit -am "Bump version to X.Y.Z"
   git switch develop
   git merge --no-ff release/X.Y.Z -m "Merge release/X.Y.Z into develop"
   git branch -d release/X.Y.Z
   ```

3. Merge `develop` into `main` and tag the merge commit. Tags are annotated and follow the
   existing form `Release X.Y.Z (versionCode N)`:

   ```bash
   git switch main
   git merge --no-ff develop -m "Release X.Y.Z"
   git tag -a vX.Y.Z -m "Release X.Y.Z (versionCode N)"
   ```

   A prerelease uses the suffix in both places, e.g. `0.2.0-beta` and `v0.2.0-beta`.

4. Bring `develop` level with `main` so it contains the release commit and tag (a fast-forward,
   not a new commit):

   ```bash
   git switch develop
   git merge --ff-only main
   ```

5. Build the signed bundle from the tag and upload it to Google Play
   (see [RELEASE_SIGNING.md](RELEASE_SIGNING.md)):

   ```bash
   git switch --detach vX.Y.Z
   ./gradlew :app:bundleRelease
   ```

6. Push everything:

   ```bash
   git push origin main develop vX.Y.Z
   ```

A fix for an already released version follows the same path: a `feature/<name>` branch from
`develop`, merged into `develop`, then released as the next patch version.
