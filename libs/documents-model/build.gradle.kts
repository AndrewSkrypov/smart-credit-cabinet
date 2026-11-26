plugins { id("cabinet.library-conventions") }
dependencies { api(project(":libs:loan-math")); testImplementation("org.jetbrains.kotlin:kotlin-test-junit5") }
