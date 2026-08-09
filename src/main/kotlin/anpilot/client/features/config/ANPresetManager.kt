package anpilot.client.features.config

import anpilot.client.api.module.ANModuleCategory
import anpilot.client.bootstrap.ANServiceRegistry
import anpilot.client.features.module.ANBaseModule
import anpilot.client.features.utility.AtomicFileWriter
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File

object ANPresetManager {
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val presetsDir = File("anpilot/presets").also { if (!it.exists()) it.mkdirs() }
    var currentPresetName: String = "default"
        private set

    fun listPresets(): List<String> {
        val files = presetsDir.listFiles { _, name -> name.endsWith(".json") } ?: return listOf("default")
        val list = files.map { it.nameWithoutExtension }.toMutableList()
        if (!list.contains("default")) list.add(0, "default")
        return list
    }

    private fun getAllModules(): List<ANBaseModule> {
        return ANModuleCategory.entries.flatMap { 
            ANServiceRegistry.runtime.moduleManager.modules(it) 
        }.filterIsInstance<ANBaseModule>()
    }

    fun savePreset(name: String = currentPresetName) {
        val presetFile = File(presetsDir, "$name.json")
        val json = JsonObject()
        val modulesJson = JsonObject()

        getAllModules().forEach { module ->
            val modJson = JsonObject()
            modJson.addProperty("enabled", module.enabled)
            modJson.addProperty("bind", module.getBind().displayName)

            val settingsJson = JsonObject()
            module.getSettings().forEach { setting ->
                settingsJson.addProperty(setting.name, setting.value.toString())
            }
            modJson.add("settings", settingsJson)
            modulesJson.add(module.name, modJson)
        }

        json.add("modules", modulesJson)
        AtomicFileWriter.writeString(presetFile, gson.toJson(json))
        currentPresetName = name
    }

    fun loadPreset(name: String): Boolean {
        val presetFile = File(presetsDir, "$name.json")
        if (!presetFile.exists()) return false

        try {
            val content = presetFile.readText(Charsets.UTF_8)
            val json = JsonParser.parseString(content).asJsonObject
            val modulesJson = json.getAsJsonObject("modules") ?: return false

            getAllModules().forEach { module ->
                val modJson = modulesJson.getAsJsonObject(module.name)
                if (modJson != null) {
                    if (modJson.has("enabled")) {
                        val shouldEnable = modJson.get("enabled").asBoolean
                        if (module.enabled != shouldEnable) {
                            module.toggle()
                        }
                    }
                }
            }
            currentPresetName = name
            return true
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }
}
