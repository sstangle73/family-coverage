# Publishing

For maintainers. Three channels, one codebase:

| Channel | Build | Signed by | Donation link |
|---|---|---|---|
| GitHub Releases | `full` release APK | StorieDev's Family Coverage key | README, About |
| F-Droid (main repository) | `full`, built by F-Droid from a tag | F-Droid's key | F-Droid's Donate field, About |
| Google Play | `play` release bundle (AAB) | Play App Signing (upload key: StorieDev's) | none, anywhere |

## Before the first release

- [ ] **Make the repository public** (GitHub `sstangle73/family-coverage`; the GitLab mirror can stay private).
- [ ] **GitHub Pages:** Settings → Pages → deploy from `main`, folder `/docs`. That publishes the help pages and the
      report page at `https://sstangle73.github.io/family-coverage/`, which the app links to.
- [ ] **Privacy policy:** add a Family Coverage section to `storiedev.com/privacy` (the storiedev-web repository),
      from [docs/privacy.md](docs/privacy.md). The app's About links there, and so will the Play listing.
- [ ] **Signing key**, after the repository is public (GitHub's free plan has an environment's approval step only on
      public repositories). Run `pwsh -NoProfile -File tool\New-SigningKey.ps1`: it makes the key (or reuses it) in
      Bitwarden, sets up the `release` environment (your approval, `v*` tags only) with the key as its secrets, and
      pins the certificate's SHA-256 in `.github/workflows/release.yml`. Commit the pin. There's no fallback to the
      debug key: a release signed with it couldn't be updated.
- [x] **Screenshots** in `fastlane/metadata/android/en-US/images/` (phone shots, one tablet shot of the report, and
      `featureGraphic.png`), for F-Droid and the Play listing. A made-up household, never real places.
      `tool/store_shots.py` makes them on the emulator, and Play's background-location video too;
      `store/feature-graphic.html` is the feature graphic's source.
- [ ] Test on at least one Pixel and one Samsung, dual-SIM, for a week: [TESTING.md](TESTING.md).

## Each release

1. Bump `fc.versionCode` (by one) and `fc.versionName` in `gradle.properties`.
2. Add `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt` (under 500 characters).
3. Commit, tag `v<versionName>`, and push the tag to both remotes.
4. The **Release** workflow waits for your approval (GitHub's Actions tab, or its app). It then checks the tag against
   `gradle.properties`, runs the tests, signs, refuses any signer but the pinned one, and drafts a GitHub release with
   the `full` APK and `SHA256SUMS`. Read the draft, then publish it.
5. Google Play: the signed `play` bundle is the run's `play-bundle` artifact. Upload it to the testing track first.

## F-Droid

F-Droid builds the app itself from the tag. Submit a merge request to
[fdroiddata](https://gitlab.com/fdroid/fdroiddata) adding `metadata/com.storiedev.familycoverage.yml`; a draft is in
[docs/fdroid/com.storiedev.familycoverage.yml](docs/fdroid/com.storiedev.familycoverage.yml). After the first build,
F-Droid follows new tags on its own (`UpdateCheckMode: Tags`).

Things a reviewer may raise, and the answers:
- **NonFreeNet?** Speed tests and data checks use Cloudflare's public endpoints, but both can be switched off, and
  the app's core (signal logging, test texts, the server test against the household's own server) needs no
  third-party service.
- **SMS permissions:** send and receive only (no READ_SMS), used for the test texts the user turns on; receivers are
  disabled until then and drop anything that isn't the app's own tag from the partner's numbers.
- **Tracking?** None. Location stays on the device unless the household sets up its own server.

## Google Play

Play allows SMS permissions only to default texting apps and nine listed uses; network testing isn't one, so the
`play` flavor has none, and test texts open Messages instead. Claiming an exception we don't fit (such as "device
automation") risks the developer account.

The Play build also drops `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (limited to listed uses) and opens Android's battery
list instead, and carries no donation link (Play's payments policy has been enforced against in-app donation links,
including links to project pages that mention donating).

The Console's forms (listing, app content, Data safety, the location declarations) are answered in
[store/PLAY-CONSOLE.md](store/PLAY-CONSOLE.md), with the judgment calls marked. In short:
- **Data safety:** nothing goes to StorieDev. Only a household's own server, if it sets one up, receives data, so
  the collection is optional and not shared.
- **Background location:** the agreement text and the *Location in the background* dialog are the prominent
  disclosure. The declaration needs a short video.
- **Testing:** a personal developer account opened after November 2023 needs a closed test with at least 12 testers
  for 14 days before production.

## The GitLab mirror

`origin` pushes to GitHub and GitLab together (`git remote -v`). The GitLab project has shared runners turned off, so
a pipeline file there would run only on a project runner of our own. Builds run on GitHub Actions for now.
