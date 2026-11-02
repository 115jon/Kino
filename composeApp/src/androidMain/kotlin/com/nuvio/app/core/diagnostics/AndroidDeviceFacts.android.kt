package com.nuvio.app.core.diagnostics

import android.app.ActivityManager
import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
import android.os.Build
import android.os.StatFs
import android.view.Display
import java.util.Locale
import java.util.TimeZone

internal object AndroidDeviceFacts {
    private const val MB = 1024L * 1024L

    val gpu: String? by lazy { queryGlRenderer() }

    fun isTelevision(context: Context): Boolean {
        val uiMode = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        return uiMode?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    }

    fun collect(context: Context?): ReportDevice {
        val memory = context?.let(::memoryInfo)
        return ReportDevice(
            os = "Android",
            osVersion = Build.VERSION.RELEASE,
            arch = Build.SUPPORTED_ABIS.firstOrNull() ?: System.getProperty("os.arch"),
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            cpu = cpuName(),
            cpuCores = Runtime.getRuntime().availableProcessors(),
            memoryTotalMb = memory?.totalMem?.div(MB),
            memoryFreeMb = memory?.availMem?.div(MB),
            storageFreeMb = context?.let { runCatching { StatFs(it.filesDir.path).availableBytes / MB }.getOrNull() },
            gpu = gpu,
            displays = context?.let(::displays).orEmpty(),
            locale = Locale.getDefault().toLanguageTag(),
            timezone = TimeZone.getDefault().id,
        )
    }

    private fun cpuName(): String {
        val soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Build.SOC_MANUFACTURER, Build.SOC_MODEL)
                .filter { it.isNotBlank() && it != Build.UNKNOWN }
                .joinToString(" ")
        } else {
            ""
        }
        return soc.ifBlank { Build.HARDWARE }
    }

    private fun memoryInfo(context: Context): ActivityManager.MemoryInfo? = runCatching {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        ActivityManager.MemoryInfo().also(manager::getMemoryInfo)
    }.getOrNull()

    private fun displays(context: Context): List<ReportDisplay> = runCatching {
        val manager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val density = context.resources.displayMetrics.density.toDouble()
        manager.displays.map { display ->
            val mode = display.mode
            ReportDisplay(
                width = mode.physicalWidth,
                height = mode.physicalHeight,
                scale = density.takeIf { display.displayId == Display.DEFAULT_DISPLAY },
                refreshHz = mode.refreshRate.toDouble(),
            )
        }
    }.getOrDefault(emptyList())

    private fun queryGlRenderer(): String? = runCatching {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1))
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        val configAttributes = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE,
        )
        check(EGL14.eglChooseConfig(display, configAttributes, 0, configs, 0, 1, count, 0) && count[0] > 0)
        val config = configs[0]
        val context = EGL14.eglCreateContext(
            display,
            config,
            EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
            0,
        )
        val surface = EGL14.eglCreatePbufferSurface(
            display,
            config,
            intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE),
            0,
        )
        try {
            check(EGL14.eglMakeCurrent(display, surface, surface, context))
            listOfNotNull(GLES20.glGetString(GLES20.GL_VENDOR), GLES20.glGetString(GLES20.GL_RENDERER))
                .joinToString(" ")
                .ifBlank { null }
        } finally {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, surface)
            EGL14.eglDestroyContext(display, context)
        }
    }.getOrNull()
}
