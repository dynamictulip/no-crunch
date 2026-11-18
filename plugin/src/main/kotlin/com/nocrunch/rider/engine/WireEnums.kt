package com.nocrunch.rider.engine

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.TypeAdapter
import com.google.gson.TypeAdapterFactory
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter

/**
 * An enum the engine sends as a camelCase string ([wire]). A value this plugin doesn't know (from a newer engine)
 * reads as the constant marked [isFallback] instead of failing the whole message.
 */
interface WireEnum {
    val wire: String
    val isFallback: Boolean
}

/** A test's state. Mirrors the engine's TestState; [UNKNOWN] stands in for a state a newer engine added. */
enum class TestState(override val wire: String, override val isFallback: Boolean = false) : WireEnum {
    DISCOVERED("discovered"),
    QUEUED("queued"),
    RUNNING("running"),
    PASSED("passed"),
    FAILED("failed"),
    SKIPPED("skipped"),
    EXCLUDED("excluded"),
    STALE("stale"),
    UNKNOWN("unknown", isFallback = true);

    /** A finished result: passed, failed or skipped. */
    val isOutcome: Boolean get() = this == PASSED || this == FAILED || this == SKIPPED

    /** Counted as "○ pending": waiting for a (new) result. Skipped and excluded tests aren't. */
    val isPending: Boolean get() = this == QUEUED || this == RUNNING || this == STALE || this == DISCOVERED
}

/** Whether a result came from continuous testing or a run the user asked for. Unknown values read as [AUTO]. */
enum class RunKind(override val wire: String, override val isFallback: Boolean = false) : WireEnum {
    AUTO("auto", isFallback = true),
    MANUAL("manual"),
}

/**
 * A project's build. The engine sends succeeded, failed, blocked or cancelled when a build finishes; [BUILDING] is the
 * plugin's own, from when the engine says a build started.
 */
enum class BuildStatus(override val wire: String, override val isFallback: Boolean = false) : WireEnum {
    BUILDING("building"),
    SUCCEEDED("succeeded"),
    FAILED("failed"),
    BLOCKED("blocked"),
    CANCELLED("cancelled"),
    UNKNOWN("unknown", isFallback = true);

    /** The build failed, or a project it needs failed to build. */
    val isBroken: Boolean get() = this == FAILED || this == BLOCKED
}

/** How a project's test run ended. */
enum class RunStatus(override val wire: String, override val isFallback: Boolean = false) : WireEnum {
    COMPLETED("completed"),
    CANCELLED("cancelled"),
    CRASHED("crashed"),
    UNSUPPORTED_MTP_VERSION("unsupportedMtpVersion"),
    UNSUPPORTED_NOT_MTP("unsupportedNotMtp"),
    UNKNOWN("unknown", isFallback = true);

    /** The project can't be run: not on Microsoft.Testing.Platform, or an MTP version the engine doesn't speak. */
    val isUnsupported: Boolean get() = this == UNSUPPORTED_MTP_VERSION || this == UNSUPPORTED_NOT_MTP
}

/**
 * What the engine is doing. The engine sends idle, syncing, building, testing and paused; [STARTING], [STOPPED] and
 * [ERROR] are the plugin's own.
 */
enum class EngineState(override val wire: String, override val isFallback: Boolean = false) : WireEnum {
    STARTING("starting"),
    SYNCING("syncing"),
    BUILDING("building"),
    TESTING("testing"),
    IDLE("idle"),
    PAUSED("paused"),
    ERROR("error"),
    STOPPED("stopped"),
    UNKNOWN("unknown", isFallback = true);

    /** Working through changes: syncing the mirror, building or running tests. */
    val isUpdating: Boolean get() = this == SYNCING || this == BUILDING || this == TESTING

    /** Starting up or working through changes. */
    val isBusy: Boolean get() = this == STARTING || isUpdating
}

/** Reads and writes every [WireEnum] by its wire name; unknown names read as the enum's fallback constant. */
object WireEnumAdapterFactory : TypeAdapterFactory {
    override fun <T> create(gson: Gson, type: TypeToken<T>): TypeAdapter<T>? {
        val raw = type.rawType
        if (!raw.isEnum || !WireEnum::class.java.isAssignableFrom(raw)) return null
        val constants = raw.enumConstants.map { it as WireEnum }
        val fallback = constants.single { it.isFallback }
        @Suppress("UNCHECKED_CAST")
        return Adapter(constants.associateBy { it.wire }, fallback) as TypeAdapter<T>
    }

    private class Adapter(private val byWire: Map<String, WireEnum>, private val fallback: WireEnum) : TypeAdapter<WireEnum>() {
        override fun write(out: JsonWriter, value: WireEnum?) {
            if (value == null) out.nullValue() else out.value(value.wire)
        }

        override fun read(input: JsonReader): WireEnum? {
            if (input.peek() == JsonToken.NULL) {
                input.nextNull()
                return null
            }
            return byWire[input.nextString()] ?: fallback
        }
    }
}

/** The Gson setup for the engine protocol. */
object EngineJson {
    val gson: Gson = GsonBuilder().serializeNulls().registerTypeAdapterFactory(WireEnumAdapterFactory).create()
}
