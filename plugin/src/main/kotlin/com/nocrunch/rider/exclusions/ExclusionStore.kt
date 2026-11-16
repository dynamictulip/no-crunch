package com.nocrunch.rider.exclusions

import com.google.gson.GsonBuilder
import com.google.gson.JsonParseException
import com.intellij.openapi.diagnostic.logger
import com.nocrunch.rider.engine.ExclusionRuleDto
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** Reads and writes `<Solution>.nocrunch.user` next to the solution file. */
class ExclusionStore(val file: Path) {
    private data class FileModel(val version: Int = 1, val excluded: List<ExclusionRuleDto> = emptyList())

    private val gson = GsonBuilder().setPrettyPrinting().serializeNulls().create()

    fun load(): List<ExclusionRuleDto> {
        if (!Files.exists(file)) return emptyList()
        return try {
            gson.fromJson(Files.readString(file), FileModel::class.java)?.excluded.orEmpty()
                .filter { it.project.isNotBlank() }
        } catch (e: JsonParseException) {
            LOG.warn("Ignoring unreadable exclusions file $file", e)
            emptyList()
        } catch (e: IOException) {
            LOG.warn("Ignoring unreadable exclusions file $file", e)
            emptyList()
        }
    }

    fun save(rules: List<ExclusionRuleDto>) = synchronized(lockFor(file)) {
        Files.createDirectories(file.parent)
        val tmp = Files.createTempFile(file.parent, file.fileName.toString(), ".tmp")
        try {
            Files.writeString(tmp, gson.toJson(FileModel(1, rules)) + "\n")
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    companion object {
        private val LOG = logger<ExclusionStore>()
        private val locks = java.util.concurrent.ConcurrentHashMap<Path, Any>()
        private fun lockFor(file: Path): Any = locks.computeIfAbsent(file.toAbsolutePath().normalize()) { Any() }

        fun forSolution(solutionFile: Path): ExclusionStore {
            val name = solutionFile.fileName.toString().substringBeforeLast('.')
            return ExclusionStore(solutionFile.resolveSibling("$name.nocrunch.user"))
        }
    }
}
