# Third-party provenance (vendored, never modified)

- `preloader-android/` — https://github.com/LiteLDev/preloader-android, tag **0.2.3**,
  commit `92a5b2d49724ff76e66378b7b753373054fd7301`. Sparse checkout: `include/` +
  root `CMakeLists.txt` only. Nested `.git` removed after checkout to keep one repo;
  re-vendor with: `git clone --depth 1 --branch <tag> --filter=blob:none --sparse …`
  then `git sparse-checkout set include`, then delete `.git`.
- License: see `docs/LICENSES.md`. Headers are used as an external SDK dependency
  (include path only); no Xykell file copies preloader code.
