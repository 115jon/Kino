# Desktop UI audit

Date: 2026-10-27. Branch: `t3/desktop-settings-ui-audit`, forked from `perf/optimize`.

Scope: the Windows desktop build at 1280×800 and 1920×1040, driven through a sandboxed install with an anonymous local profile and the public Cinemeta addon. Android and iOS keep their layouts; every change below is behind `isDesktopPlatform` or lives in a desktop-only composable, except the branding removals, which apply everywhere.

## 1. Settings

### Before

- Three columns: nav rail, a 280 dp category list (Account, General, About, Advanced) under a `displayLarge` "Settings" title, and a content pane. The pane started 112 dp down (a tablet status-bar offset), so a quarter of the window was empty.
- The General category showed a mobile drill-down list (Layout, Content & Discovery, Playback, Integrations, Notifications). Reaching a real control took one more click.
- Rows were phone-sized: 42 dp icon tiles, 16 dp vertical padding, 16 sp titles, no hover cursor, no chevron, no keyboard escape.
- The content pane stretched to the full window width, so at 1920 px a single "Install Addon" button was 1300 px wide.
- The footer read "Made with ❤️ by Tapframe and friends" and the About category linked to `nuvio.tv/privacy-policy`.

### After

- `DesktopSettingsLayout.kt` (new, fork-owned) replaces the category sidebar on desktop with a flat 224 dp section list: Account, Appearance, Content & Discovery, Playback, Integrations, Tracking, Notifications, Advanced, About, plus Watch party and Switch profile at the bottom when available. Items are `NuvioRailItem`s, so they share the nav rail's pill shape, hover and pressed layers, hand cursor and focus ring.
- Clicking a section opens its real page directly. The old Root page is only used for About (licences, update channel, check for updates, version). Settings lands on Account.
- Sub-pages (Homescreen, Streams, Addons, …) keep the back button; top-level pages do not. Esc goes back one level while a sub-page is open. Esc goes through Compose's own `BackHandler` (`org.jetbrains.compose.ui:ui-backhandler`, newly added to commonMain), because Compose Desktop already maps the Escape key to a back event and consumes it before any `onKeyEvent` modifier or window handler sees it. The handler is registered only on desktop and only while a sub-page is open, so Esc on a top-level page does nothing and an open dialog closes without also navigating back. Kino's `PlatformBackHandler` stays a no-op on desktop on purpose: wiring it would make Esc on Home open the exit confirmation.
- Content is left-aligned with a 880 dp maximum width, 32 dp side padding and a 28 dp top offset. The page title is 22 sp instead of 26 sp.
- `SettingsRowMetrics.kt` (new, fork-owned) gives `SettingsNavigationRow`, `SettingsSwitchRow`, `SettingsChipRow`, the group divider and the card shape a desktop density: 32 dp icon tiles, 10 dp vertical padding, 14 sp titles, 13 sp descriptions, a trailing chevron on navigation rows, a hand cursor, a 16 dp divider inset and the 12 dp card radius. Tablet and phone values are unchanged.
- The whole desktop app now provides `LocalMinimumInteractiveComponentSize = 40.dp` (Material default is 48 dp), so switches, icon buttons and checkboxes stop padding rows to touch height.
- Theme mode row: the Light/Dark/System agent should place its composable at the top of the Display group in `appearanceSettingsContent` (`AppearanceSettingsPage.kt`), directly after the theme picker section. The desktop sidebar needs no change for it.

## 2. Nuvio and Tapframe leftovers

Removed from the UI:

- "Made with ❤️ by Tapframe and friends" footer (`SettingsRootPage.kt`). The wordmark and version line stay.
- Privacy Policy row linking to `nuvio.tv/privacy-policy` (`SettingsRootPage.kt`).
- Terms acknowledgement under the sign-up form linking to `nuvio.tv/terms` (`AuthScreen.kt`).

Left in place on purpose:

- `compose_auth_link_open` ("Open nuvio.tv/link") and the `server_error_official_*` strings describe the official backend's device-link page and server URL. They are functional endpoints, not branding.
- The Licenses page's GPL source link to `github.com/115jon/Kino`.
- Unused `compose_about_made_with` and `compose_auth_terms_*` strings stay in the resource files to avoid touching every locale file in an upstream-owned directory.

## 3. General desktop audit

### Fixed

| Pattern | Where | Fix |
|---|---|---|
| Bottom sheets for every picker and action list | `core/ui/BottomSheet.kt` → `NuvioModalBottomSheet` | On desktop the sheet renders as a centered dialog (`DesktopSheetDialog.kt`, new): 520 dp max width, 80 % max height, dialog radius, same surface and edge tokens. `dismissNuvioBottomSheet` already short-circuits when the sheet state was never shown, so callers are unchanged. |
| Dropdown chips (Search filters, Library sort/filter, trailer type) opened a bottom sheet | `core/ui/DropdownChip.kt` | On desktop the chip opens the existing `Menu`/`MenuItem` popover anchored to the chip. |
| Profile switcher only opened on long-press drag; a click went to Settings | `ProfileSwitcherTab.kt` (new `expandOnClick` parameter), `DesktopAppNavigationRail.kt` | On desktop a click on the avatar opens the switcher popup; the rail label still opens Settings. |
| 48 dp touch targets around switches and icon buttons | `DesktopApp.kt` | Desktop-wide 40 dp minimum interactive size. |
| Search input spanned the full content width (1630 px at 1920) and ignored Esc | `features/search/SearchScreen.kt` (two-line hunk), `SearchDesktopModifiers.kt` (new) | 720 dp maximum width on desktop; Esc clears the query while the field is focused. |
| Settings density, hierarchy and width | see section 1 | |

### Next

Ranked by impact. None of these are started.

1. **Home hero height.** `homeHeroLayout` (`HomeHeroSection.kt`) clamps the hero to 460–580 dp, which is most of an 800 px window. A desktop branch should cap it at about 55 % of the viewport height.
2. **Poster hover states and row scroll arrows.** No `hoverable` on poster cards; the row arrow exists but cards give no feedback. A desktop lift or outline on hover plus a hand cursor would do.
3. **Full-width primary buttons.** `NuvioPrimaryButton` is `fillMaxWidth()` with a 52 dp height. On desktop it should cap at about 360 dp outside dialogs (Account sign-out, Addons install, profile editor "Create Profile").
4. **Dialog buttons.** `DialogButtons` splits buttons evenly across the full dialog width (the "Addon Installed" dialog has a 520 px "Done"). Desktop convention is right-aligned, intrinsic-width buttons.
5. **Tooltips.** No `TooltipBox` anywhere. Icon-only controls (Library grid/download icons, details back button, addon refresh/delete) need plain tooltips on hover.
6. **Search keyboard flow.** Enter should move focus into the results grid, and arrow keys should walk it.
7. **Settings search on desktop.** The pull-to-reveal search is unreachable now that the Root page is hidden. A search field at the top of the sidebar that renders `settingsSearchRootContent` in the pane would restore it.
8. **Details back button and Esc.** A bare 24 dp arrow at the top-left of a full-bleed hero, and no Esc to close. Give it the same 36 dp surfaced button used by the settings header and a desktop `BackHandler` like the one in settings.
9. **Long-press as the only route to secondary actions** on posters and the Play button. Right-click maps through `nuvioSecondaryClick` on desktop but nothing advertises it; a visible overflow button on hover would.

## 4. Upstream-owned files touched

Authorship from `git log --format=%an`; the fork author is Jonathan Martinez.

| File | Change |
|---|---|
| `features/settings/SettingsScreen.kt` | 7 small hunks in `TabletSettingsScreen`: desktop sidebar branch, `displayedPage`, pane modifier and padding, header back rule, Esc handler call. |
| `features/settings/SettingsComponents.kt` | Row metrics in `SettingsCard`, `SettingsGroupDivider`, `TabletPageHeader`, `SettingsNavigationRow`, `SettingsSwitchRow`, `SettingsChipRow`. |
| `features/settings/SettingsRootPage.kt` | Removed privacy policy row and made-with footer. |
| `features/auth/AuthScreen.kt` | Removed terms acknowledgement. |
| `core/ui/BottomSheet.kt` | Desktop dialog branch at the top of `NuvioModalBottomSheet`. |
| `core/ui/DropdownChip.kt` | Desktop menu branch. |
| `features/profiles/ProfileSwitcherTab.kt` | `expandOnClick` parameter. |

| `features/search/SearchScreen.kt` | Width cap and Esc modifiers on the input box. |

| `composeApp/build.gradle.kts`, `gradle/libs.versions.toml` | One dependency line each for `ui-backhandler`. |

Fork-owned files touched: `DesktopApp.kt`, `DesktopAppNavigationRail.kt`. New fork-owned files: `features/settings/DesktopSettingsLayout.kt`, `features/settings/SettingsRowMetrics.kt`, `core/ui/DesktopSheetDialog.kt`, `features/search/SearchDesktopModifiers.kt`.

## 5. How the screenshots were taken

The session had no interactive foreground window, so real mouse input never reached the app. A small launcher class on the classpath (`kui.Agent`, kept outside the repo in `%TEMP%\kui`) starts a loopback command server and then calls `DesktopAppKt.main`; commands dispatch synthetic AWT mouse and key events to the Compose window. The app ran from a separate `LOCALAPPDATA`, home and temp directory with an in-memory `java.util.prefs` factory, so the real login was never touched. Screenshots use `PrintWindow`. Before/after images are under `%TEMP%\kui\shots`.
