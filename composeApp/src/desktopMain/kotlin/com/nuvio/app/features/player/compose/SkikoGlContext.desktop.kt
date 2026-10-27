package com.nuvio.app.features.player.compose

import java.awt.Container
import java.awt.Window
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import org.jetbrains.skia.DirectContext
import org.jetbrains.skiko.GraphicsApi
import org.jetbrains.skiko.SkiaLayer

internal class SkikoGlContext private constructor(
    private val layer: SkiaLayer,
) {
    val renderApi: GraphicsApi get() = layer.renderApi

    val directContext: DirectContext?
        get() = renderer()?.let { renderer -> readField(renderer, "glContext") as? DirectContext }

    val contextHandle: Long
        get() = renderer()?.let { renderer -> readField(renderer, "context") as? Long } ?: 0L

    val deviceHandle: Long
        get() = renderer()?.let { renderer -> readField(renderer, "device") as? Long } ?: 0L

    fun makeCurrent(): Boolean {
        if (renderApi != GraphicsApi.OPENGL) return false
        val renderer = renderer() ?: return false
        val method = findMethod(renderer.javaClass, "makeCurrent\$org_jetbrains_skiko_skiko") ?: return false
        return runCatching { method.invoke(renderer) }.isSuccess
    }

    private fun renderer(): Any? = runCatching {
        val frameDriver = findMethod(layer.javaClass, "getFrameDriver\$org_jetbrains_skiko_skiko")?.invoke(layer)
            ?: return@runCatching null
        val producer = readField(frameDriver, "producer") ?: return@runCatching null
        findMethod(producer.javaClass, "getRenderer\$org_jetbrains_skiko_skiko")?.invoke(producer)
    }.getOrNull()

    companion object {
        fun find(window: Window?): SkikoGlContext? {
            val root = window ?: return null
            return findSkiaLayer(root)?.let(::SkikoGlContext)
        }

        private fun findSkiaLayer(container: Container): SkiaLayer? {
            container.components.forEach { component ->
                if (component is SkiaLayer) return component
                if (component is Container) findSkiaLayer(component)?.let { return it }
            }
            return null
        }
    }
}

private val fieldCache = ConcurrentHashMap<Pair<Class<*>, String>, Field>()
private val methodCache = ConcurrentHashMap<Pair<Class<*>, String>, Method>()

private fun findField(type: Class<*>, name: String): Field? = fieldCache[type to name] ?: run {
    var current: Class<*>? = type
    while (current != null) {
        current.declaredFields.firstOrNull { it.name == name }?.let { field ->
            field.isAccessible = true
            fieldCache[type to name] = field
            return field
        }
        current = current.superclass
    }
    null
}

private fun findMethod(type: Class<*>, name: String): Method? = methodCache[type to name] ?: run {
    var current: Class<*>? = type
    while (current != null) {
        current.declaredMethods.firstOrNull { it.name == name && it.parameterCount == 0 }?.let { method ->
            method.isAccessible = true
            methodCache[type to name] = method
            return method
        }
        current = current.superclass
    }
    null
}

private fun readField(target: Any, name: String): Any? =
    runCatching { findField(target.javaClass, name)?.get(target) }.getOrNull()
