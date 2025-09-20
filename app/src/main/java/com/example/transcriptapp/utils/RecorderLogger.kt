package com.example.transcriptapp.utils

import android.util.Log
import java.util.Locale

/**
 * Utility class for standardized logging throughout the app
 * with "recorder-viva-log" prefix and consistent formatting.
 */
object RecorderLogger {
    // Constants for log levels
    private const val VERBOSE = 1
    private const val DEBUG = 2
    private const val INFO = 3
    private const val WARN = 4
    private const val ERROR = 5
    
    // Default minimum log level (can be changed at runtime)
    private var minLogLevel = VERBOSE
    
    // App-wide tag prefix
    private const val TAG_PREFIX = "recorder-viva-log"
    
    // Enable/disable logging globally
    private var loggingEnabled = true
    
    /**
     * Enable or disable logging globally
     */
    fun setLoggingEnabled(enabled: Boolean) {
        loggingEnabled = enabled
    }
    
    /**
     * Set minimum log level
     * @param level One of VERBOSE, DEBUG, INFO, WARN, ERROR
     */
    fun setMinLogLevel(level: Int) {
        if (level in VERBOSE..ERROR) {
            minLogLevel = level
        }
    }
    
    /**
     * Format a tag by combining the prefix with the specific component
     */
    private fun formatTag(component: String): String {
        return "$TAG_PREFIX.$component"
    }
    
    /**
     * Log at VERBOSE level
     */
    fun v(component: String, message: String) {
        if (loggingEnabled && VERBOSE >= minLogLevel) {
            Log.v(formatTag(component), message)
        }
    }
    
    /**
     * Log at DEBUG level
     */
    fun d(component: String, message: String) {
        if (loggingEnabled && DEBUG >= minLogLevel) {
            Log.d(formatTag(component), message)
        }
    }
    
    /**
     * Log at INFO level
     */
    fun i(component: String, message: String) {
        if (loggingEnabled && INFO >= minLogLevel) {
            Log.i(formatTag(component), message)
        }
    }
    
    /**
     * Log at WARN level
     */
    fun w(component: String, message: String) {
        if (loggingEnabled && WARN >= minLogLevel) {
            Log.w(formatTag(component), message)
        }
    }
    
    /**
     * Log at WARN level with exception
     */
    fun w(component: String, message: String, throwable: Throwable) {
        if (loggingEnabled && WARN >= minLogLevel) {
            Log.w(formatTag(component), message, throwable)
        }
    }
    
    /**
     * Log at ERROR level
     */
    fun e(component: String, message: String) {
        if (loggingEnabled && ERROR >= minLogLevel) {
            Log.e(formatTag(component), message)
        }
    }
    
    /**
     * Log at ERROR level with exception
     */
    fun e(component: String, message: String, throwable: Throwable) {
        if (loggingEnabled && ERROR >= minLogLevel) {
            Log.e(formatTag(component), message, throwable)
        }
    }
    
    /**
     * Log method entry with parameters
     */
    fun methodEntry(component: String, methodName: String, vararg params: Pair<String, Any?>) {
        if (loggingEnabled && DEBUG >= minLogLevel) {
            val paramString = params.joinToString(", ") { "${it.first}=${it.second}" }
            Log.d(formatTag(component), "→ $methodName($paramString)")
        }
    }
    
    /**
     * Log method exit with result
     */
    fun methodExit(component: String, methodName: String, result: Any? = null) {
        if (loggingEnabled && DEBUG >= minLogLevel) {
            val resultString = result?.let { " = $it" } ?: ""
            Log.d(formatTag(component), "← $methodName$resultString")
        }
    }
    
    /**
     * Log file operations
     */
    fun file(component: String, operation: String, path: String, size: Long = -1) {
        if (loggingEnabled && DEBUG >= minLogLevel) {
            val sizeInfo = if (size >= 0) " (${formatFileSize(size)})" else ""
            Log.d(formatTag(component), "FILE $operation: $path$sizeInfo")
        }
    }
    
    /**
     * Format file size in human-readable format
     */
    private fun formatFileSize(size: Long): String {
        return when {
            size < 1024 -> "$size B"
            size < 1024 * 1024 -> String.format(Locale.US, "%.2f KB", size / 1024.0)
            size < 1024 * 1024 * 1024 -> String.format(Locale.US, "%.2f MB", size / (1024.0 * 1024.0))
            else -> String.format(Locale.US, "%.2f GB", size / (1024.0 * 1024.0 * 1024.0))
        }
    }
    
    /**
     * Log a separator line for visual grouping of log events
     */
    fun separator(component: String) {
        if (loggingEnabled && DEBUG >= minLogLevel) {
            Log.d(formatTag(component), "----------------------------------------")
        }
    }
    
    /**
     * Log state changes
     */
    fun state(component: String, name: String, oldValue: Any?, newValue: Any?) {
        if (loggingEnabled && DEBUG >= minLogLevel) {
            Log.d(formatTag(component), "STATE $name: $oldValue → $newValue")
        }
    }
    
    /**
     * Log media operations (recording start/stop/pause/resume)
     */
    fun media(component: String, operation: String, details: String = "") {
        if (loggingEnabled && INFO >= minLogLevel) {
            val detailsInfo = if (details.isNotEmpty()) " ($details)" else ""
            Log.i(formatTag(component), "MEDIA $operation$detailsInfo")
        }
    }
    
    /**
     * Log permissions
     */
    fun permission(component: String, permission: String, status: String) {
        if (loggingEnabled && INFO >= minLogLevel) {
            Log.i(formatTag(component), "PERMISSION $permission: $status")
        }
    }
    
    /**
     * Log broadcast events
     */
    fun broadcast(component: String, action: String, extras: Map<String, Any?>? = null) {
        if (loggingEnabled && DEBUG >= minLogLevel) {
            val extrasStr = extras?.let { 
                " - extras: " + extras.entries.joinToString(", ") { "${it.key}=${it.value}" } 
            } ?: ""
            Log.d(formatTag(component), "BROADCAST $action$extrasStr")
        }
    }
    
    /**
     * Log service lifecycle events
     */
    fun service(component: String, lifecycle: String, details: String = "") {
        if (loggingEnabled && DEBUG >= minLogLevel) {
            val detailsInfo = if (details.isNotEmpty()) " - $details" else ""
            Log.d(formatTag(component), "SERVICE $lifecycle$detailsInfo")
        }
    }
}