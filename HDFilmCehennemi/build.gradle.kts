// HDFilmCehennemi plugin
dependencies {
    "compileOnly"("org.jspecify:jspecify:1.0.0")
}

// Use an integer for version numbers
version = 1

cloudstream {
    description = "HDFilmCehennemi"
    authors = listOf("Antigravity")

    /**
    * Status int as one of the following:
    * 0: Down
    * 1: Ok
    * 2: Slow
    * 3: Beta-only
    **/
    status = 1

    tvTypes = listOf("Movie", "TvSeries")

    language = "tr"

    iconUrl = "https://www.hdfilmcehennemi.nl/dist/images/logo-light.svg"
}
