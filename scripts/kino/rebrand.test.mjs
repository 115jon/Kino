import assert from "node:assert/strict";
import test from "node:test";

import { STRING_FILES, rebrandXml } from "./rebrand.mjs";

test("rewrites user-visible product names in string values", () => {
  const { output, hits } = rebrandXml(
    '<resources>\r\n    <string name="trakt_library_source_nuvio">Nuvio Library</string>\r\n</resources>\r\n',
  );
  assert.equal(
    output,
    '<resources>\r\n    <string name="trakt_library_source_nuvio">Kino Library</string>\r\n</resources>\r\n',
  );
  assert.equal(hits.length, 1);
  assert.equal(hits[0].line, 2);
});

test("keeps urls, domains, schemes and resource names", () => {
  const source = [
    '<string name="compose_auth_link_open">Open nuvio.tv/link</string>',
    '<string name="server_error_official_active">api.nuvio.tv is the official server.</string>',
    '<string name="deep_link">Use nuvio://auth/simkl or https://nuvio.tv/Nuvio</string>',
  ].join("\n");
  const { output, hits } = rebrandXml(source);
  assert.equal(output, source);
  assert.equal(hits.length, 0);
});

test("handles suffixes, scripts, plurals and the licenses title", () => {
  const { output } = rebrandXml(
    [
      '<string name="a">Nuvio\'nun Nuvioライブラリ</string>',
      '<string name="settings_licenses_attributions_nuvio_title">Nuvio Mobile</string>',
      '<plurals name="b"><item quantity="one">%d Nuvio item</item></plurals>',
    ].join("\n"),
  );
  assert.equal(
    output,
    [
      '<string name="a">Kino\'nun Kinoライブラリ</string>',
      '<string name="settings_licenses_attributions_nuvio_title">Kino</string>',
      '<plurals name="b"><item quantity="one">%d Kino item</item></plurals>',
    ].join("\n"),
  );
});

test("leaves allowlisted upstream programme strings alone", () => {
  const source = '<string name="community_membership_title">Nuvio Supporter Membership</string>';
  assert.equal(rebrandXml(source).output, source);
});

test("is idempotent", () => {
  const once = rebrandXml('<string name="x">Make Nuvio yours.</string>').output;
  assert.equal(rebrandXml(once).output, once);
  assert.equal(rebrandXml(once).hits.length, 0);
});

test("targets compose and android string resources only", () => {
  assert.ok(STRING_FILES.test("composeApp/src/commonMain/composeResources/values-tr/strings.xml"));
  assert.ok(STRING_FILES.test("composeApp/src/androidMain/res/values/strings.xml"));
  assert.ok(STRING_FILES.test("androidApp/src/debug/res/values/strings.xml"));
  assert.ok(STRING_FILES.test("composeApp/src/commonMain/composeResources/values-de/strings_pip.xml"));
  assert.ok(STRING_FILES.test("composeApp/src/commonMain/composeResources/values/autosync_strings.xml"));
  assert.ok(!STRING_FILES.test("composeApp/src/androidMain/res/values/themes.xml"));
  assert.ok(!STRING_FILES.test("composeApp/src/commonMain/kotlin/App.kt"));
  assert.ok(!STRING_FILES.test("iosApp/iosApp/Info.plist"));
});
