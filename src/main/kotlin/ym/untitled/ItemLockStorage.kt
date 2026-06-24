package ym.untitled

import org.bukkit.configuration.file.YamlConfiguration
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

class ItemLockStorage(
    private val plugin: ItemLockPlugin,
    private val executor: Executor
) {

    private val records = ConcurrentHashMap<String, BindingRecord>()
    private val saveQueued = AtomicBoolean(false)
    private val dataPath = plugin.dataFolder.toPath().resolve("item-lock-data.yml")

    fun loadAsync(): CompletableFuture<Void> {
        return CompletableFuture.runAsync({
            Files.createDirectories(dataPath.parent)
            if (Files.notExists(dataPath)) {
                Files.write(dataPath, "items: {}\n".toByteArray(Charsets.UTF_8))
            }
            val yaml = YamlConfiguration.loadConfiguration(dataPath.toFile())
            val section = yaml.getConfigurationSection("items") ?: return@runAsync
            for (itemId in section.getKeys(false)) {
                val path = "items.$itemId"
                val ownerText = yaml.getString("$path.owner-uuid") ?: continue
                val ownerUuid = runCatching { UUID.fromString(ownerText) }.getOrNull() ?: continue
                records[itemId] = BindingRecord(
                    itemId = itemId,
                    ownerUuid = ownerUuid,
                    ownerName = yaml.getString("$path.owner-name", "Unknown") ?: "Unknown",
                    material = yaml.getString("$path.material", "UNKNOWN") ?: "UNKNOWN",
                    boundAt = yaml.getLong("$path.bound-at", 0L),
                    updatedAt = yaml.getLong("$path.updated-at", 0L)
                )
            }
        }, executor)
    }

    fun recordBind(itemId: String, ownerUuid: UUID, ownerName: String, material: String, boundAt: Long) {
        records[itemId] = BindingRecord(
            itemId = itemId,
            ownerUuid = ownerUuid,
            ownerName = ownerName,
            material = material,
            boundAt = boundAt,
            updatedAt = System.currentTimeMillis()
        )
        queueSave()
    }

    fun recordUnbind(itemId: String) {
        records.remove(itemId)
        queueSave()
    }

    fun find(itemId: String): BindingRecord? = records[itemId]

    fun recordCount(): Int = records.size

    fun flushAsync(): CompletableFuture<Void> {
        val snapshot = records.values.toList()
        saveQueued.set(false)
        return CompletableFuture.runAsync({ writeSnapshot(snapshot) }, executor)
    }

    private fun queueSave() {
        if (!saveQueued.compareAndSet(false, true)) {
            return
        }
        CompletableFuture.runAsync({
            try {
                Thread.sleep(250L)
                writeSnapshot(records.values.toList())
            } finally {
                saveQueued.set(false)
            }
        }, executor)
    }

    private fun writeSnapshot(snapshot: List<BindingRecord>) {
        Files.createDirectories(dataPath.parent)
        val yaml = YamlConfiguration()
        for (record in snapshot) {
            val path = "items.${record.itemId}"
            yaml.set("$path.owner-uuid", record.ownerUuid.toString())
            yaml.set("$path.owner-name", record.ownerName)
            yaml.set("$path.material", record.material)
            yaml.set("$path.bound-at", record.boundAt)
            yaml.set("$path.updated-at", record.updatedAt)
        }
        yaml.save(dataPath.toFile())
    }

    data class BindingRecord(
        val itemId: String,
        val ownerUuid: UUID,
        val ownerName: String,
        val material: String,
        val boundAt: Long,
        val updatedAt: Long
    )
}
