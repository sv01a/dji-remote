// Root build configuration for dji-osmo-watch
// Target device: Huawei Watch 4 Pro (HarmonyOS Android-compatibility layer)
// NOTE: No external dependencies. Only Kotlin stdlib + Android framework APIs,
// to stay Wear-OS-free / Play-Services-free per project requirements.
plugins {
    id("com.android.application") version "8.10.1" apply false
    id("org.jetbrains.kotlin.android") version "2.1.20" apply false
}
