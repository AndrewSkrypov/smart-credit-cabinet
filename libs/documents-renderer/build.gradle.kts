plugins { id("cabinet.library-conventions") }
dependencies {
    api(project(":libs:documents-model"))
    implementation("org.springframework:spring-context")
    implementation(libs.pdfbox) { exclude(group = "commons-logging", module = "commons-logging") }
    implementation(libs.poi.ooxml)
}
