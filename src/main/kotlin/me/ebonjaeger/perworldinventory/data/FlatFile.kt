package me.ebonjaeger.perworldinventory.data

import com.google.common.cache.Cache
import com.google.common.cache.CacheBuilder
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import me.ebonjaeger.perworldinventory.ConsoleLogger
import me.ebonjaeger.perworldinventory.PerWorldInventory
import me.ebonjaeger.perworldinventory.serialization.LocationSerializer
import me.ebonjaeger.perworldinventory.serialization.PlayerSerializer
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.entity.Player
import java.io.File
import java.io.FileReader
import java.io.FileWriter
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.TimeUnit

class FlatFile(private val plugin: PerWorldInventory,
               private val serializer: PlayerSerializer,
               cacheExpires: Long,
               maxCacheSize: Long) : DataSource
{

    private val profileCache: Cache<ProfileKey, JsonObject> = CacheBuilder.newBuilder()
            .expireAfterAccess(cacheExpires, TimeUnit.MINUTES)
            .maximumSize(maxCacheSize)
            .build()

    override fun savePlayer(key: ProfileKey, player: PlayerProfile)
    {
        val file = getFile(key)
        ConsoleLogger.debug("Saving data for player '${player.name}' in file '${file.path}'")

        try
        {
            createFileIfNotExists(file)
        } catch (ex: IOException)
        {
            if (ex !is FileAlreadyExistsException)
            {
                ConsoleLogger.severe("Error creating file '${file.path}':", ex)
                return
            }
        }

        ConsoleLogger.debug("Writing player data for player '${player.name}' to file")
        val data = serializer.serialize(player)
        try
        {
            FileWriter(file).use { it.write(Gson().toJson(data)) }
        } catch (ex: IOException)
        {
            ConsoleLogger.severe("Could not write data to file '$file':", ex)
        }
    }

    override fun saveLogout(player: PlayerProfile)
    {
        val dir = File(plugin.DATA_DIRECTORY, player.uuid.toString())
        val file = File(dir, "last-logout.json")

        try
        {
            createFileIfNotExists(file)
            val data = LocationSerializer.serialize(player.location)
            FileWriter(file).use { it.write(Gson().toJson(data)) }
        } catch (ex: IOException)
        {
            if (ex !is FileAlreadyExistsException)
            {
                ConsoleLogger.severe("Error writing logout location for '${player.name}':", ex)
            }
        }
    }

    override fun saveLocation(player: Player, location: Location)
    {
        val dir = File(plugin.DATA_DIRECTORY, player.uniqueId.toString())
        val file = File(dir, "last-locations.json")

        try
        {
            createFileIfNotExists(file)
            val data = LocationSerializer.serialize(location)
            val key = location.world.name

            // Get any existing data
            JsonReader(FileReader(file)).use {
                val parser = JsonParser()
                val root = parser.parse(it).asJsonObject
                val locations = if (root.has("locations"))
                {
                    root["locations"].asJsonObject
                } else
                {
                    JsonObject()
                }

                // If a location for this world already exists, remove it.
                if (locations.has(key))
                {
                    locations.remove(key)
                }

                // Write the latest data to disk
                locations.add(key, data)
                root.add("locations", locations)
                FileWriter(file).use { it.write(Gson().toJson(root)) }
            }
        } catch (ex: IOException)
        {
            if (ex !is FileAlreadyExistsException)
            {
                ConsoleLogger.severe("Error writing last location for '${player.name}':", ex)
            }
        }
    }

    // TODO: Find a better way of doing this
    override fun getPlayer(key: ProfileKey, player: Player): JsonObject?
    {
        // Check for cached data first
        val cached = profileCache.getIfPresent(key)
        if (cached != null)
        {
            return cached
        }

        val file = getFile(key)

        // If the file does not exist, the player hasn't been to this group before
        if (!file.exists())
        {
            return null
        }

        JsonReader(FileReader(file)).use {
            val parser = JsonParser()
            val json = parser.parse(it).asJsonObject

            profileCache.put(key, json)

            return json
        }
    }

    override fun getLogout(player: Player): Location?
    {
        val dir = File(plugin.DATA_DIRECTORY, player.uniqueId.toString())
        val file = File(dir, "last-logout.json")

        // This player is likely logging in for the first time
        if (!file.exists())
        {
            return null
        }

        JsonReader(FileReader(file)).use {
            val parser = JsonParser()
            val data = parser.parse(it).asJsonObject

            return LocationSerializer.deserialize(data)
        }
    }

    override fun getLocation(player: Player, world: String): Location?
    {
        val dir = File(plugin.DATA_DIRECTORY, player.uniqueId.toString())
        val file = File(dir, "last-locations.json")

        // Clearly they haven't visited any other worlds yet
        if (!file.exists())
        {
            return null
        }

        JsonReader(FileReader(file)).use {
            val parser = JsonParser()
            val root = parser.parse(it).asJsonObject
            if (!root.has("locations"))
            {
                // Somehow the file exists, but still no locations
                return null
            }

            val locations = root["locations"].asJsonObject
            return if (locations.has(world))
            {
                LocationSerializer.deserialize(locations["world"].asJsonObject)
            } else
            {
                // They haven't been to this world before, so no data
                null
            }
        }
    }

    /**
     * Creates the given file if it doesn't exist.
     *
     * @param file The file to create if necessary
     * @return The given file (allows inline use)
     * @throws IOException If file could not be created
     */
    @Throws(IOException::class)
    private fun createFileIfNotExists(file: File): File
    {
        if (!file.exists())
        {
            if (!file.parentFile.exists())
            {
                Files.createDirectories(file.parentFile.toPath())
            }

            Files.createFile(file.toPath())
        }

        return file
    }

    /**
     * Get the data file for a player.
     *
     * @param key The [ProfileKey] to get the right file
     * @return The data file to read from or write to
     */
    private fun getFile(key: ProfileKey): File
    {
        val dir = File(plugin.DATA_DIRECTORY, key.uuid.toString())
        return when(key.gameMode)
        {
            GameMode.ADVENTURE -> File(dir, key.groupName + "_adventure.json")
            GameMode.CREATIVE -> File(dir, key.groupName + "_creative.json")
            GameMode.SPECTATOR -> File(dir, key.groupName + "_spectator.json")
            GameMode.SURVIVAL -> File(dir, key.groupName + ".json")
        }
    }
}