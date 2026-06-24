// Host-only JVM tool (NOT shipped in any app). Downloads Thunderbird's public
// holiday .ics files and converts them to the calendar app's bundled JSON
// assets. Run: ./gradlew :tools:holidaygen:run
plugins {
    application
}

application {
    mainClass.set("HolidayGen")
}

// Emit assets relative to the repo root.
tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
}
