plugins {
    id(MyPlugins.kotlin)
    id(Plugins.Kotlin.serialization)
}
dependencies {
    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlin.serialization.json)
    implementation(libs.kotlin.datetime)
    implementation(libs.kotlin.coroutines.core)
    api(libs.okio.okio)
    api(libs.okhttp.okhttp)
    api(libs.m3u8Parser)
    api(libs.ffmpeg)
    api(variantOf(libs.ffmpeg) { classifier("windows-x86_64") })

    implementation(project(":shared:utils"))
}