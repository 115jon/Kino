# Windows VRR Compatibility

Windows G-SYNC can lower the display refresh rate and cause whole-window flicker while a Compose Desktop window is focused. Skiko's DirectX/OpenGL path can avoid that behavior when a transparent `1x1` Swing panel is rendered above the `SkiaLayer`.

The integration lives in `composeApp/src/desktopMain/kotlin/com/nuvio/app/WindowsVrrCompatibility.desktop.kt` and is installed from `DesktopApp.kt`. Keep these invariants when updating Compose or Skiko:

- Install the sentinel only for `DIRECT3D` and `OPENGL` renderers.
- Keep it opaque with an alpha-zero background so Java2D participates in window composition.
- Track `SkiaLayer.PropertyKind.Renderer` so fallback to software or ANGLE removes it.
- Remove it with the Compose window lifecycle.

The Windows PR job compiles desktop sources and runs `desktopTest`. CI does not provide a G-SYNC display, so hardware validation remains a Windows desktop smoke test after renderer or windowing changes.
