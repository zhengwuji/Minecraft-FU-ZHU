package net.fabricmc.api

interface ModInitializer {
    fun onInitialize()
}

interface ClientModInitializer {
    fun onInitializeClient()
}

@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
annotation class Environment(val value: EnvType)

enum class EnvType {
    CLIENT,
    SERVER
}
