pluginManagement {
    includeBuild("build-logic")
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "hermes-android"

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

include(":app")

include(":core:model")
include(":core:net")
include(":core:gateway")
include(":core:chat")
include(":core:database")
include(":core:data")
include(":core:designsystem")
include(":core:ui")
include(":core:push")
include(":core:voice")

include(":feature:connect")
include(":feature:sessions")
include(":feature:chat")
include(":feature:notifications")
include(":feature:cron")
include(":feature:settings")
include(":feature:tasks")
include(":feature:bots")

include(":testing:fakegateway")
