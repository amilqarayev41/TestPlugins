# Turkish-Repo 🇹🇷

CloudStream repository for Turkish and international movie, cartoon, and TV series streaming providers.

---

## 🚀 How to Add to CloudStream

You can add this repository to CloudStream using either the **Shortcode** or the **Full Repository URL**.

### Method 1: Using Shortcode (Recommended)

1. In CloudStream, navigate to **Settings** ⚙️ → **Extensions**.
2. Tap **Add Repository** (+).
3. In the repository URL/shortcode field, type:
   ```text
   turkishrepo
   ```
4. Tap **Download** / **Add**.

---

### Method 2: Using Full Repository URL

If you prefer to enter the full URL directly:

```text
https://raw.githubusercontent.com/amilqarayev41/Turkish-Repo/builds/repo.json
```

1. In CloudStream, navigate to **Settings** ⚙️ → **Extensions**.
2. Tap **Add Repository** (+).
3. Paste the URL above and tap **Download** / **Add**.

---

## 📦 Included Plugins

| Plugin | Type | Description |
| :--- | :--- | :--- |
| **Dizilla** | Series, Anime, Cartoons | Turkish and international TV series, cartoons, and anime. |
| **ElisWorld** | Cartoons, Animation | Cartoon network, adult animations, and animated movies with multi-language subtitle and download support. |
| **FullHDFilmizlesene** | Movies | Turkish dubbed (TR Dublaj) and subtitled (TR Altyazı) movies with RapidVid & multi-server support. |
| **HDFilmCehennemi** | Movies | Turkish dubbed and subtitled movies with direct streaming and download support. |

---

## 🛠️ Building & Development

Built with the [CloudStream Gradle Plugin](https://github.com/recloudstream/gradle).

```bash
# Build all plugins and generate plugins.json
./gradlew make makePluginsJson
```

Artifacts and releases are automatically built and published via GitHub Actions to the `builds` branch.
