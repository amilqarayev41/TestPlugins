// FullHDFilmizlesene plugin
dependencies {
    "compileOnly"("org.jspecify:jspecify:1.0.0")
}

// Use an integer for version numbers
version = 1

cloudstream {
    description = "FullHDFilmizlesene"
    authors = listOf("Amil")

    /**
    * Status int as one of the following:
    * 0: Down
    * 1: Ok
    * 2: Slow
    * 3: Beta-only
    **/
    status = 1

    tvTypes = listOf("Movie")

    language = "tr"

    iconUrl = "https://raw.githubusercontent.com/amilqarayev41/Turkish-Repo/builds/FullHDFilmizlesene.png"
}
