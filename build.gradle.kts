plugins {
    alias(libs.plugins.android.application) apply false
}

// Build output outside the iCloud-synced Documents folder (iCloud's "name 2" duplicates break dexing).
allprojects {
    layout.buildDirectory.set(file("${System.getProperty("user.home")}/.gradle-builds/AutoBridge/${project.name}"))
}
