plugins {
    alias(libs.plugins.fabric.loom)
}

base {
    archivesName = project.property("archives_base_name") as String
    version = libs.versions.mod.version.get()
    group = project.property("maven_group") as String
}

repositories {
    maven {
        name = "fabric"
        url = uri("https://maven.fabricmc.net/")
    }
    maven {
        name = "meteor-maven"
        url = uri("https://maven.meteordev.org/releases")
    }
    maven {
        name = "meteor-maven-snapshots"
        url = uri("https://maven.meteordev.org/snapshots")
    }
}

dependencies {
    // Fabric
    minecraft(libs.minecraft)
    implementation(libs.fabric.loader)

    // Meteor
    implementation(libs.meteor.client)
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(libs.versions.jdk.get().toInt())
    }
}

tasks {
    processResources {
        val propertyMap = mapOf(
            "version" to project.version,
            "minecraft_version" to "~" + libs.versions.minecraft.get(),
            "jdk_version" to libs.versions.jdk.get()
        )

        inputs.properties(propertyMap)
        filteringCharset = "UTF-8"

        filesMatching("fabric.mod.json") {
            expand(propertyMap)
        }
    }

    withType<JavaCompile> {
        options.encoding = "UTF-8"
    }
}
