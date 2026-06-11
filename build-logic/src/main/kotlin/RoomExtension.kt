import org.gradle.kotlin.dsl.DependencyHandlerScope

fun DependencyHandlerScope.implementRoom(libs: org.gradle.accessors.dm.LibrariesForLibs) {
    add("implementation", libs.androidx.room.runtime)
    add("implementation", libs.androidx.room.ktx)
    add("ksp", libs.androidx.room.compiler)
}
