# Changelog

## [0.3.0](https://github.com/115jon/Kino/compare/desktop-v0.2.24...desktop-v0.3.0) (2026-07-24)


### ⚠ BREAKING CHANGES

* **release:** Releases now require android-vX.Y.Z or desktop-vX.Y.Z tags; legacy v* tags no longer trigger platform release workflows. Version metadata is now sourced from release/versions/.

### Features

* **android-playback:** ✨ restore PiP and media controls ([febe06d](https://github.com/115jon/Kino/commit/febe06d1a680e6abca43dadc6d90d3582793b914))
* **desktop:** ✨ restore desktop navigation and player controls ([3c7935e](https://github.com/115jon/Kino/commit/3c7935ec9f836b872c03cffbf7d1729ee22cf895))
* **details:** ✨ optimize desktop detail layout ([2980830](https://github.com/115jon/Kino/commit/298083071c4bcc6c30b9e74b5f5151d3441ec9cc))
* **release:** ✨ add platform-specific release tracks ([e43ac17](https://github.com/115jon/Kino/commit/e43ac17c4451ad199577acd74a9e019a6a410a68))
* **release:** ✨ package Android APK and Windows installer ([c6bd22b](https://github.com/115jon/Kino/commit/c6bd22b89810927cffed8db814692238152c4fe2))
* **theme:** 💄 apply Kino visual identity ([524882c](https://github.com/115jon/Kino/commit/524882c81e9b5a111fcaa53e23a3249e91590f99))
* **updater:** ✨ restore in-app update banner ([c97deb9](https://github.com/115jon/Kino/commit/c97deb9c80cdd6160ee269883886ec0ad048bdb6))
* **windows-player:** ✨ integrate Windows media controls ([9ca7f28](https://github.com/115jon/Kino/commit/9ca7f284b2276d6277f869d94a5e901e8fb89942))
* **windows:** ✨ follow system title bar theme ([e94fceb](https://github.com/115jon/Kino/commit/e94fceb56e60efde0a8644dee9a2726d39687f5d))


### Bug Fixes

* **desktop:** 🐛 apply MPV subtitle styles correctly ([e7ed22e](https://github.com/115jon/Kino/commit/e7ed22ed58776e04cc9850a0bd4a9a5c09307419))
* **desktop:** 🐛 hide controls on player exit ([cbaea06](https://github.com/115jon/Kino/commit/cbaea0666f22d231d8805854637bd324a387fd7f))
* **desktop:** 🐛 stabilize fullscreen focus and player overlays ([1969552](https://github.com/115jon/Kino/commit/1969552cff7061bc305a83ff921cfc27f2ca59ee))
* **desktop:** 🐛 wire subtitles and installer path ([53172b3](https://github.com/115jon/Kino/commit/53172b346ec5abcfe20ae926d18e374b46863af5))
* **mobile-auth:** 🐛 restore Supabase config fallback ([8db04fb](https://github.com/115jon/Kino/commit/8db04fb02417923b32e01d7c126d6c499e557f84))
* **player:** 🐛 add quality-aware startup fallback ([f62da6c](https://github.com/115jon/Kino/commit/f62da6ce92c4e95764412490cae50bd1573da253))
* **player:** 🐛 harden desktop audio playback quality ([f461bd5](https://github.com/115jon/Kino/commit/f461bd56eb2151aa940a72328bad7e5ca661f479))
* **player:** 🐛 harden desktop playback lifecycle ([4f73297](https://github.com/115jon/Kino/commit/4f732974958d026980ea77038af7e3ec6ab0e5f4))
* **player:** 🐛 harden Windows playback lifecycle ([80100d0](https://github.com/115jon/Kino/commit/80100d08f0c95e20a124f13e49e8294973562b08))
* **player:** 🐛 make stream state observable to Compose ([dfd32bd](https://github.com/115jon/Kino/commit/dfd32bdf0f948a5f7516b4fe0415ba5c6e27f01f))
* **player:** 🐛 stabilize desktop playback and track metadata ([17d1fb9](https://github.com/115jon/Kino/commit/17d1fb93c6c76bc667b3ead6190a80ef35f3302b))
* **player:** 💄 align desktop player controls ([3012ed5](https://github.com/115jon/Kino/commit/3012ed5fba2c2f2fd159b0b475e136da5ed02e11))
* **profile-state:** 🐛 preserve active profile across background transitions ([4cfb181](https://github.com/115jon/Kino/commit/4cfb181ba800a65daa85f3bec722a521b0cceef1))
* **sync:** 🐛 restore profile sync stability ([1da3061](https://github.com/115jon/Kino/commit/1da30618f85501ccdb7d35e466e6ae353f1cecd4))
* **windows:** 🐛 opt out of OBS Vulkan capture ([90d4863](https://github.com/115jon/Kino/commit/90d4863663c00ac4cf4d3ad0496c0322a80380fd))
* **windows:** 🐛 preserve VRR workaround in CI ([e08b67c](https://github.com/115jon/Kino/commit/e08b67c3fabca899cd226a0ec2ccf7a7175f2692))
* **windows:** 🐛 synchronize Skiko D3D redraws ([4fe48c8](https://github.com/115jon/Kino/commit/4fe48c84184e62b871243c03fb7e82c9d6cc8057))
* **windows:** preserve G-SYNC workaround in CI ([232823c](https://github.com/115jon/Kino/commit/232823c10c47630ed7020aa0cbfe2a7568494bfa))
