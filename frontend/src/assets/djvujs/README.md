# DjVu.js (vendored)

Used by the DJVU reader (`src/app/features/readers/djvu-reader`). Loaded lazily with
`<script>` tags only when a DjVu book is opened; see `djvujs-viewer.engine.ts`.

| File             | Component            | Version | License                                  |
|------------------|----------------------|---------|------------------------------------------|
| `djvu.js`        | DjVu.js Library      | 0.5.4   | GNU GPL v2 or (at your option) any later |
| `djvu_viewer.js` | DjVu.js Viewer       | 0.10.1  | The Unlicense                            |

- Project: https://djvu.js.org/ — source: https://github.com/RussCoder/djvujs
- Release: https://github.com/RussCoder/djvujs/releases/tag/L.0.5.4_V.0.10.1
- License details: https://github.com/RussCoder/djvujs/blob/master/LICENSE.md

The files are the unmodified release assets:

```
10a831d62cc7ced39c30c7350a11410402d082a0f0a5b073a88d3d7ca662add3  djvu.js
95f65308ad6159fcd5a1f34397bbd8e071321c48bce4d62375ce3af471e77764  djvu_viewer.js
```

## Updating

1. Download `djvu.js` and `djvu_viewer.js` from the new release on GitHub.
2. Replace both files here and update the versions and checksums above
   (`sha256sum djvu.js djvu_viewer.js`).
3. Check that the viewer API used by `djvujs-viewer.engine.ts` is unchanged
   (`DjVu.Viewer`, `loadDocument`, `configure`, `Events.PAGE_NUMBER_CHANGED`, `get.pagesQuantity`).
