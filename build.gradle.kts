plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.20"
    id("org.jetbrains.intellij.platform") version "2.10.2"
}

group = "com.johnxy84.shopify"
version = "1.1"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

// Read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
dependencies {
    intellijPlatform {
        rustRover("2025.3.5")
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)

        // Add plugin dependencies for compilation here, example:
        // bundledPlugin("com.intellij.java")
    }
    implementation("com.graphql-java:graphql-java:21.5")
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("junit:junit:4.13.2")
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "242"
        }

        changeNotes = """
            <h3>1.1</h3>
            <ul>
                <li>Improved startup performance — filesystem scan and parser initialization now run in the background, eliminating the "plugin might be slowing things down" warning</li>
                <li>Added metaobject cost support — <code>metaobject</code> (1 point) and <code>field(key:)</code> access (3 points) are now calculated per Shopify's updated documentation</li>
                <li>Compact status bar — icon with cost number replaces verbose text; full details on hover and click</li>
                <li>Analysis result caching — skips redundant parsing when document content hasn't changed</li>
                <li>Fixed fragment cycle detection — circular fragment references no longer cause a stack overflow</li>
                <li>Fixed timer and listener cleanup on disposal</li>
            </ul>
        """.trimIndent()
    }
    pluginVerification {
        ides {
            create("IU", "2024.2.6") {
                // No extra configuration
            }
        }
    }
}

tasks {
    // Set the JVM compatibility versions
    withType<JavaCompile> {
        sourceCompatibility = "21"
        targetCompatibility = "21"
    }
    withType<Test> {
        useJUnitPlatform()
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}
