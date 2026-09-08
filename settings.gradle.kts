rootProject.name = "ses-scenario-server"

include(
    "modules:core-ses",
    "modules:core-devs",
    "modules:template",
    "modules:dialogue",
    "modules:llm",
    "modules:scenario",
    "modules:persistence",
    "modules:api",
    "app",
)
