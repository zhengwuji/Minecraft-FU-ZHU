import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
	id("dev.architectury.loom") version "1.7.435"
	`maven-publish`
	id("org.jetbrains.kotlin.jvm") version "1.9.23"
}

version = providers.gradleProperty("mod_version").get()
group = providers.gradleProperty("maven_group").get()

loom {
	forge {
		mixinConfig("anpilotclient.mixins.json")
	}
}

repositories {
	mavenCentral()
	maven {
		name = "Architectury"
		url = uri("https://maven.architectury.dev/")
	}
	maven {
		name = "MinecraftForge"
		url = uri("https://maven.minecraftforge.net/")
	}
	maven {
		name = "KotlinForForge"
		url = uri("https://thedarkcolour.github.io/KotlinForForge/")
	}
	maven {
		name = "Modrinth"
		url = uri("https://api.modrinth.com/maven")
	}
}

dependencies {
	minecraft("com.mojang:minecraft:1.20.1")
	mappings(loom.officialMojangMappings())

	"forge"("net.minecraftforge:forge:1.20.1-47.1.3")

	implementation("org.jetbrains.kotlin:kotlin-stdlib:1.9.23")
	implementation("org.jetbrains.kotlin:kotlin-reflect:1.9.23")
	implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
}

tasks.processResources {
	val version = version
	inputs.property("version", version)

	filesMatching("META-INF/mods.toml") {
		expand("version" to version)
	}
}

tasks.withType<JavaCompile>().configureEach {
	options.release = 17
}

kotlin {
	compilerOptions {
		jvmTarget = JvmTarget.JVM_17
	}
}

java {
	withSourcesJar()
	sourceCompatibility = JavaVersion.VERSION_17
	targetCompatibility = JavaVersion.VERSION_17
}

tasks.jar {
	val projectName = project.name
	inputs.property("projectName", projectName)
	duplicatesStrategy = DuplicatesStrategy.EXCLUDE

	from({
		configurations.runtimeClasspath.get()
			.filter { it.name.contains("kotlin") }
			.map { if (it.isDirectory) it else zipTree(it) }
	}) {
		exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
	}

	from("LICENSE") {
		rename { "${it}_$projectName" }
	}
}

publishing {
	publications {
		register<MavenPublication>("mavenJava") {
			from(components["java"])
		}
	}
}
