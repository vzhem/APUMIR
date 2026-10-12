# Release handoff — 2026-10-12

## Branch and recovery

Work only on `arena/4f0b31c5-apumir`; do not switch to PR #43's `arena/7ab0e8d6-apumir`.
At the start of this handoff, `HEAD` is `3986f54`; documentation and the v11.74.337 notes are
being finalized in the working tree.

The lost stable line is recovered: v11.74.329 is an ancestor of the fixed branch. Root cause:
v11.74.330 forked from v11.74.206 instead of v11.74.329. Comparing v206→v329 gives 276 changed
paths, 12,919 insertions, 2,823 deletions; old PR #43 head `8f6ce09` compared with v206 had only
13 changed paths, 1,186 insertions, 4 deletions. This is the concrete match for the reported
~13k→~1k loss (it describes the missing change-set, not the total repository size).

Public v11.74.336 remains Latest and points to the old tree. No prior tag/release/assets were
moved. Merge `73e40eb` restored v329 and retained .330–.336 work.

## Phone/helper feature boundaries

- Published online-helper stages 1–2 (.326–.329) are restored: direct-first routing, bounded
  online-helper race/fallback, local helper ranking, explicit participation controls/load modes,
  and signed decline triggering immediate fallback. No arbitrary-NAT, battery/load, or physical
  phone result is established.
- `SWARM_MOBILE.md` describes a separate UDP hole-punching path for file transfers. Its code was
  already present in v11.74.206 and remains in the recovered tree; it is not ordinary-message
  helper routing and has no current-session real-phone/NAT verification.
- Story source wiring is present after v329 recovery (`ChatListViewModel` → `StoryStore` →
  `ChatRepository`, with incoming packets routed back into `StoryStore`). That is source inspection,
  not device/UI validation.
- Stage 3 is only partial: content-addressed chunks are applied to personal photo stories; group/
  channel swarm delivery, real-phone verification, and combined-tree CI remain. Stage 4 is not
  active: `SwarmContributionStats` has no call sites; its counters/guards do not measure or limit
  real traffic. Details and next engineering tasks are in `docs/SWARM_HELPER_ROADMAP.md` and
  `docs/PHONE_TEST_CHECKLIST.md`.

## v11.74.337 publication

The owner authorized a public release for all users and approved the exact bilingual body in
`docs/RELEASE_NOTES_v11.74.337.md`. The notes describe the broad recovered user-facing changes,
not only localization. The tag does not exist yet. Before publishing, run the tag-triggered release
workflow on the recovered tree, verify all build/test stages and APK/checksum assets, and only then
promote the generated draft to public stable Latest. Do not move or rewrite any old tag/release.

`gh workflow run` previously returned HTTP 403; do not retry the same workflow-dispatch API call.
There is no Java/Gradle or Rust/Cargo toolchain in this sandbox. The prior successful run #790 and
published .336 build predate the recovery merge and do not validate the current combined tree.

## Immediate next actions

1. Review and commit the refreshed helper/photo roadmap, corrected phone checklist, mobile-path
   boundary note, and approved `.337` notes to the fixed branch.
2. Push only `arena/4f0b31c5-apumir`.
3. Create and push annotated tag `v11.74.337` on the fixed branch; monitor its tag-triggered CI.
4. If the full workflow succeeds, verify the draft contains `app-release.apk` and
   `SHA256SUMS.txt`, record the asset digest, then publish as Latest. If it fails, fix the branch,
   use a new tag version as appropriate, and do not publish the failed build.
5. For .338+ continue Stage 3/4 only after the CI/device gates and bounded-statistics design in the
   roadmap; do not claim those stages are complete in .337.
