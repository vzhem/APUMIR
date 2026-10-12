# Release handoff — 2026-10-12

## Fixed branch and restored line

Continue only on `arena/4f0b31c5-apumir`; do not switch to PR #43's `arena/7ab0e8d6-apumir`.
The recovered implementation commit is `b8e6dcf` (tag `v11.74.337` points to it). Documentation
status updates after release are being committed on the fixed branch.

Root cause: v11.74.330 forked from v11.74.206 instead of v11.74.329. Comparing v206→v329 gives
276 changed paths, 12,919 insertions, 2,823 deletions; old PR #43 head `8f6ce09` compared with
v206 had 13 paths, 1,186 insertions, 4 deletions. This matches the reported ~13k→~1k missing
change-set; it is not the total source size of the repository. Merge `73e40eb` restored v329 while
retaining .330–.336. Old tags/assets were not moved.

## Published release

- Owner authorized a public release for all users and approved the exact bilingual body in
  `docs/RELEASE_NOTES_v11.74.337.md`.
- Tag `v11.74.337` points to `b8e6dcf`. Release workflow run `38246638460` succeeded on the
  recovered tree: online-helper/direct-transport Rust suites; three native ABIs; UniFFI; story
  photo/JVM tests; story/community/helper Android tests; `assembleRelease`; rotated-key signing;
  checksum and release assets.
- `v11.74.337` is now public Latest: https://github.com/vzhem/APUMIR/releases/tag/v11.74.337
- API asset metadata: `app-release.apk` 95,123,319 bytes,
  `sha256:b51f09815a0e87612bfe5db3866497c52c9798bcd598b76e4fb59fc22bde1536`;
  `SHA256SUMS.txt` 82 bytes,
  `sha256:5dcf54a5901a0209092d901cfbdd7b50c0fff4e6968b7f7c4c9751b53cdccdf1`.
  APK/checksum were not downloaded for independent local verification.
- `main` remains at v11.74.206; the CI generated-bindings back-push did not move it. Work and pushes
  remain on the fixed Arena branch.

## Phone/helper scope and unverified work

- Published online-helper stages 1–2 (.326–.329) are restored: direct-first routing, bounded
  helper race/fallback, local helper ranking, participation/load controls, signed decline and
  immediate fallback. Do not promise connectivity through arbitrary NAT or measured phone capacity.
- `SWARM_MOBILE.md` describes a separate UDP hole-punching path for file-transfer packets. It was
  already present in v11.74.206; it is not ordinary-message helper routing. No real-phone/NAT
  test was performed in this session.
- Story source wiring is present (`ChatListViewModel` → `StoryStore` → `ChatRepository`), and the
  merged tree passed CI. This is still not a manual device/UI test.
- Stage 3 remains partial: content-addressed chunks currently serve personal photo stories; group/
  channel swarm sharing and physical-device tests remain. Stage 4 is not active: `SwarmContributionStats` has bounded pure-Kotlin mechanics, but
  no call sites; its counters/guard do not measure or limit real traffic. See
  `docs/SWARM_HELPER_ROADMAP.md` and `docs/PHONE_TEST_CHECKLIST.md`.

## Next work for future releases

1. Run the phone checklist on at least two Android devices across Wi-Fi/mobile/NAT; record story
   delivery, helper fallback, N-1 behavior, battery, traffic, latency and thermal results.
2. Before calling Stage 3 complete, define authorized group/channel content-key distribution,
   revocation/expiry, custody consent, persistence/migration and quotas; add tests and phone evidence.
3. The bounded Stage 4 scaffold and JVM tests are now in the working branch after the `.337` tag;
   no CI has run for them yet and no production call sites exist. Wire it only to cryptographically
   verified helper delivery/refusal events, prevent double counting, then test before anti-abuse/UI.
   Do not claim global honesty, unique users or capacity.
4. Release any newly completed Stage 3/4 work in a later version with a fresh successful tag CI.

Local Java/Gradle and Rust/Cargo are absent in this sandbox, so no local build claim is available.
