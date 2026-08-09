pluginManagement {
	repositories {
		maven {
			name = "Architectury"
			url = uri("https://maven.architectury.dev/")
		}
		maven {
			name = "MinecraftForge"
			url = uri("https://maven.minecraftforge.net/")
		}
		maven {
			name = "Fabric"
			url = uri("https://maven.fabricmc.net/")
		}
		mavenCentral()
		gradlePluginPortal()
	}

	plugins {
		id("dev.architectury.loom") version "1.7.435"
	}
}

rootProject.name = "anpilotclient"
