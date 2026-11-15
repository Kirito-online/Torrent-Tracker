package com.example.data.parser

import com.example.domain.model.HtmlSelectorConfig
import com.example.domain.model.JsonMappingConfig
import com.example.domain.model.SourceType
import com.example.domain.model.TorrentSource

data class DirectorySourceEntry(
  val id: String,
  val name: String,
  val description: String,
  val categoryHint: String,
  val baseUrl: String,
  val searchUrlTemplate: String,
  val feedUrl: String,
  val sourceType: SourceType,
  val htmlSelectors: HtmlSelectorConfig = HtmlSelectorConfig(),
  val jsonMapping: JsonMappingConfig = JsonMappingConfig(),
  val keywords: String = ""
) {
  fun toTorrentSource(): TorrentSource = TorrentSource(
    id = id,
    name = name,
    baseUrl = baseUrl,
    searchUrlTemplate = searchUrlTemplate,
    feedUrl = feedUrl,
    sourceType = sourceType,
    enabled = true,
    isBuiltIn = false,
    htmlSelectors = htmlSelectors,
    jsonMapping = jsonMapping
  )
}

object SourceDirectory {

  val catalog: List<DirectorySourceEntry> = listOf(
    DirectorySourceEntry(
      id = "builtin_tpb_apibay",
      name = "The Pirate Bay",
      description = "Fast JSON API powered by apibay for movies, TV shows, music, games, and software.",
      categoryHint = "General · Movies · TV · Games",
      baseUrl = "https://thepiratebay.org",
      searchUrlTemplate = "https://apibay.org/q.php?q={query}",
      feedUrl = "https://apibay.org/precompiled/data_top100_all.json",
      sourceType = SourceType.JSON_API,
      jsonMapping = JsonMappingConfig(
        itemsPath = "",
        titleField = "name",
        identifierField = "id",
        detailsUrlTemplate = "https://thepiratebay.org/description.php?id={id}",
        infoHashField = "info_hash",
        sizeField = "size",
        seedersField = "seeders",
        leechersField = "leechers",
        dateField = "added",
        uploaderField = "username",
        categoryField = "category"
      ),
      keywords = "tpb piratebay apibay top100 popular famous"
    ),
    DirectorySourceEntry(
      id = "builtin_1337x",
      name = "1337x",
      description = "Popular general torrent index covering movies, series, games, music, and applications.",
      categoryHint = "General · Movies · TV · Games",
      baseUrl = "https://1337x.to",
      searchUrlTemplate = "https://1337x.to/search/{query}/{page}/",
      feedUrl = "https://1337x.to/trending",
      sourceType = SourceType.HTML_SELECTOR,
      htmlSelectors = HtmlSelectorConfig(
        itemContainer = "table.table-list tbody tr",
        title = "td.name a:nth-of-type(2)",
        detailsLink = "td.name a:nth-of-type(2)",
        fileSize = "td.size",
        seeders = "td.seeds",
        leechers = "td.leeches",
        uploadDate = "td.coll-date",
        uploader = "td.uploader a"
      ),
      keywords = "1337 leet 1337xto popular famous"
    ),
    DirectorySourceEntry(
      id = "builtin_yts_api",
      name = "YTS Movies",
      description = "Dedicated high-definition movie torrent index (720p, 1080p, 2160p 4K) via official JSON API.",
      categoryHint = "Movies · 1080p · 4K",
      baseUrl = "https://yts.mx",
      searchUrlTemplate = "https://yts.mx/api/v2/list_movies.json?query_term={query}&page={page}&limit=25&sort_by=seeds",
      feedUrl = "https://yts.mx/api/v2/list_movies.json?sort_by=download_count&limit=25&page={page}",
      sourceType = SourceType.JSON_API,
      jsonMapping = JsonMappingConfig(
        itemsPath = "data.movies",
        titleField = "title_long",
        identifierField = "id",
        imageField = "medium_cover_image",
        dateField = "date_uploaded_unix",
        categoryField = "genres",
        yearField = "year",
        languageField = "language",
        descriptionField = "synopsis"
      ),
      keywords = "yts yify movies films hd 4k"
    ),
    DirectorySourceEntry(
      id = "builtin_nyaa_rss",
      name = "Nyaa Anime",
      description = "Leading anime, manga, and East Asian media torrent tracker with native RSS search.",
      categoryHint = "Anime · Manga · Audio",
      baseUrl = "https://nyaa.si",
      searchUrlTemplate = "https://nyaa.si/?page=rss&q={query}",
      feedUrl = "https://nyaa.si/?page=rss",
      sourceType = SourceType.RSS_ATOM,
      keywords = "nyaa anime subsplease erai manga fansub"
    ),
    DirectorySourceEntry(
      id = "builtin_eztv_html",
      name = "EZTV Shows",
      description = "Well-known TV series torrent index with episode metadata and direct magnet links.",
      categoryHint = "TV Shows · Episodes",
      baseUrl = "https://eztvx.to",
      searchUrlTemplate = "https://eztvx.to/search/{query}",
      feedUrl = "https://eztvx.to/",
      sourceType = SourceType.HTML_SELECTOR,
      htmlSelectors = HtmlSelectorConfig(
        itemContainer = "tr.forum_header_border",
        title = "a.epinfo",
        detailsLink = "a.epinfo",
        magnetLink = "a.magnet",
        torrentFileLink = "a.download_1, a[href*=.torrent]",
        fileSize = "td:nth-child(4)",
        seeders = "td:nth-child(6)",
        uploadDate = "td:nth-child(5)"
      ),
      keywords = "eztv eztvx tv series episodes shows"
    ),
    DirectorySourceEntry(
      id = "dir_torrentgalaxy",
      name = "TorrentGalaxy",
      description = "Popular community torrent tracker for movies, TV shows, documentaries, and games.",
      categoryHint = "General · Movies · TV",
      baseUrl = "https://torrentgalaxy.one",
      searchUrlTemplate = "https://torrentgalaxy.one/get-posts/keywords:{query}",
      feedUrl = "https://torrentgalaxy.one/rss",
      sourceType = SourceType.HTML_SELECTOR,
      htmlSelectors = HtmlSelectorConfig(
        itemContainer = "div.tgxtablerow, tr.tgxtablerow",
        title = "a[href*=/torrent/], a.txlight",
        detailsLink = "a[href*=/torrent/]",
        magnetLink = "a[href^=magnet:]",
        fileSize = "span.badge, td:contains(GB), td:contains(MB)",
        seeders = "font[color=green], span[title=Seeders]",
        leechers = "font[color=#ff0000], span[title=Leechers]",
        uploader = "a[href*=/profile/]"
      ),
      keywords = "tgx torrentgalaxy galaxy movies tv"
    ),
    DirectorySourceEntry(
      id = "dir_solidtorrents",
      name = "SolidTorrents",
      description = "Fast DHT torrent search engine with clean JSON API and magnet link metadata.",
      categoryHint = "General · DHT Search",
      baseUrl = "https://solidtorrents.to",
      searchUrlTemplate = "https://solidtorrents.to/api/v1/search?q={query}&page={page}",
      feedUrl = "https://solidtorrents.to/api/v1/search?q=1080p&sort=seeders",
      sourceType = SourceType.JSON_API,
      jsonMapping = JsonMappingConfig(
        itemsPath = "results",
        titleField = "title",
        identifierField = "_id",
        detailsUrlTemplate = "https://solidtorrents.to/torrents/{id}",
        magnetField = "magnet",
        infoHashField = "infohash",
        sizeField = "size",
        seedersField = "swarm.seeders",
        leechersField = "swarm.leechers",
        dateField = "imported",
        categoryField = "category"
      ),
      keywords = "solidtorrents dht json api search"
    ),
    DirectorySourceEntry(
      id = "dir_limetorrents",
      name = "LimeTorrents RSS",
      description = "Long-running general torrent directory offering direct RSS search feeds.",
      categoryHint = "General · RSS Search",
      baseUrl = "https://www.limetorrents.lol",
      searchUrlTemplate = "https://www.limetorrents.lol/searchrss/{query}/",
      feedUrl = "https://www.limetorrents.lol/rss/",
      sourceType = SourceType.RSS_ATOM,
      keywords = "limetorrents lime rss general"
    ),
    DirectorySourceEntry(
      id = "dir_bitsearch",
      name = "BitSearch",
      description = "Clean DHT torrent meta-search engine with magnet links, file sizes, and swarm counts.",
      categoryHint = "General · DHT Meta-Search",
      baseUrl = "https://bitsearch.to",
      searchUrlTemplate = "https://bitsearch.to/search?q={query}&page={page}",
      feedUrl = "https://bitsearch.to/trending",
      sourceType = SourceType.HTML_SELECTOR,
      htmlSelectors = HtmlSelectorConfig(
        itemContainer = "li.search-result, div.search-result",
        title = "h5.title a, a[href*=/torrents/]",
        detailsLink = "h5.title a, a[href*=/torrents/]",
        magnetLink = "a[href^=magnet:]",
        torrentFileLink = "a[href*=.torrent]",
        fileSize = "div.stats div:nth-child(2)",
        seeders = "div.stats div:nth-child(3)",
        leechers = "div.stats div:nth-child(4)"
      ),
      keywords = "bitsearch dht magnet search"
    ),
    DirectorySourceEntry(
      id = "dir_knaben",
      name = "Knaben Database",
      description = "Multi-indexer torrent search aggregator indexing TPB, 1337x, RuTracker, and Nyaa.",
      categoryHint = "Meta-Search · Multi-Index",
      baseUrl = "https://knaben.eu",
      searchUrlTemplate = "https://knaben.eu/search/{query}/0/{page}/seeders",
      feedUrl = "https://knaben.eu/browse/0/1/seeders",
      sourceType = SourceType.HTML_SELECTOR,
      htmlSelectors = HtmlSelectorConfig(
        itemContainer = "tr.text-nowrap, table tbody tr",
        title = "td:nth-child(2) a",
        detailsLink = "td:nth-child(2) a",
        magnetLink = "a[href^=magnet:]",
        fileSize = "td:nth-child(3)",
        uploadDate = "td:nth-child(4)",
        seeders = "td:nth-child(5)",
        leechers = "td:nth-child(6)"
      ),
      keywords = "knaben aggregator meta search rutracker"
    ),
    DirectorySourceEntry(
      id = "dir_fitgirl_repacks",
      name = "FitGirl Repacks RSS",
      description = "Popular PC game repacks tracker with magnet-linked release announcements via RSS.",
      categoryHint = "Games · PC Repacks",
      baseUrl = "https://fitgirl-repacks.site",
      searchUrlTemplate = "https://fitgirl-repacks.site/?s={query}&feed=rss2",
      feedUrl = "https://fitgirl-repacks.site/feed/",
      sourceType = SourceType.RSS_ATOM,
      keywords = "fitgirl games repacks pc gaming"
    ),
    DirectorySourceEntry(
      id = "dir_ext_torrents",
      name = "EXT Torrents",
      description = "Modern multi-category torrent index with movies, TV, games, and software.",
      categoryHint = "General · Movies · TV",
      baseUrl = "https://ext.to",
      searchUrlTemplate = "https://ext.to/search/?q={query}&page={page}",
      feedUrl = "https://ext.to/latest/",
      sourceType = SourceType.HTML_SELECTOR,
      htmlSelectors = HtmlSelectorConfig(
        itemContainer = "table.search-table tbody tr, table tbody tr",
        title = "a.torrent-title-link, td:nth-child(1) a[href*=-]",
        detailsLink = "a.torrent-title-link, td:nth-child(1) a[href*=-]",
        magnetLink = "a[href^=magnet:]",
        fileSize = "td:nth-child(2)",
        seeders = "td:nth-child(4)",
        leechers = "td:nth-child(5)"
      ),
      keywords = "ext exttorrents movies tv"
    ),
    DirectorySourceEntry(
      id = "dir_watchsomuch",
      name = "WatchSoMuch",
      description = "Curated HD movies and TV shows torrent catalog with poster artwork.",
      categoryHint = "Movies · TV Shows",
      baseUrl = "https://watchsomuch.com",
      searchUrlTemplate = "https://watchsomuch.com/Movies/{query}",
      feedUrl = "https://watchsomuch.com/",
      sourceType = SourceType.HTML_SELECTOR,
      htmlSelectors = HtmlSelectorConfig(
        itemContainer = "div.movie, div.item, div[class*=movie], a:has(img)",
        title = "h2, h3, .title, .name, img[alt], a[title]",
        detailsLink = "a[href]",
        magnetLink = "a[href^=magnet:]",
        coverImage = "img"
      ),
      keywords = "watchsomuch wsm movies tv hd"
    ),
    DirectorySourceEntry(
      id = "builtin_academic_torrents",
      name = "Academic Torrents RSS",
      description = "Open-access scientific datasets, research papers, and educational video courses.",
      categoryHint = "Research · Datasets",
      baseUrl = "https://academictorrents.com",
      searchUrlTemplate = "https://academictorrents.com/collection.rss",
      feedUrl = "https://academictorrents.com/collection.rss",
      sourceType = SourceType.RSS_ATOM,
      keywords = "academic science datasets papers ml"
    ),
    DirectorySourceEntry(
      id = "builtin_fosstorrents_rss",
      name = "FOSS Torrents RSS",
      description = "Free and open-source operating system distributions and Linux ISO releases.",
      categoryHint = "Software · Linux",
      baseUrl = "https://fosstorrents.com",
      searchUrlTemplate = "https://fosstorrents.com/feed/",
      feedUrl = "https://fosstorrents.com/feed/",
      sourceType = SourceType.RSS_ATOM,
      keywords = "foss linux iso ubuntu fedora"
    ),
    DirectorySourceEntry(
      id = "builtin_linuxtracker_rss",
      name = "LinuxTracker RSS",
      description = "Community-maintained RSS feed for Linux and BSD distribution ISO torrents.",
      categoryHint = "Software · Linux",
      baseUrl = "https://linuxtracker.org",
      searchUrlTemplate = "https://linuxtracker.org/rss.php",
      feedUrl = "https://linuxtracker.org/rss.php",
      sourceType = SourceType.RSS_ATOM,
      keywords = "linuxtracker bsd distro iso"
    ),
    DirectorySourceEntry(
      id = "dir_arch_linux_releases",
      name = "Arch Linux Releases RSS",
      description = "Official Arch Linux ISO release announcements and torrent download feed.",
      categoryHint = "Software · Linux",
      baseUrl = "https://archlinux.org",
      searchUrlTemplate = "https://archlinux.org/feeds/releases/",
      feedUrl = "https://archlinux.org/feeds/releases/",
      sourceType = SourceType.RSS_ATOM,
      keywords = "archlinux iso official"
    ),
    DirectorySourceEntry(
      id = "dir_distrowatch_torrents",
      name = "DistroWatch Torrents RSS",
      description = "Aggregated RSS feed tracking new Linux/BSD distribution torrent releases.",
      categoryHint = "Software · Linux",
      baseUrl = "https://distrowatch.com",
      searchUrlTemplate = "https://distrowatch.com/news/torrents.xml",
      feedUrl = "https://distrowatch.com/news/torrents.xml",
      sourceType = SourceType.RSS_ATOM,
      keywords = "distrowatch linux bsd rss"
    ),
    DirectorySourceEntry(
      id = "dir_debian_cd_torrents",
      name = "Debian Official CD/DVD Torrents",
      description = "Official Debian installation image torrent directory parsed via HTML selectors.",
      categoryHint = "Software · Linux",
      baseUrl = "https://cdimage.debian.org",
      searchUrlTemplate = "https://cdimage.debian.org/debian-cd/current/amd64/bt-cd/",
      feedUrl = "",
      sourceType = SourceType.HTML_SELECTOR,
      htmlSelectors = HtmlSelectorConfig(
        itemContainer = "tr:has(a[href$=.torrent])",
        title = "a[href$=.torrent]",
        detailsLink = "a[href$=.torrent]",
        torrentFileLink = "a[href$=.torrent]",
        fileSize = "td:nth-child(4)",
        uploadDate = "td:nth-child(3)"
      ),
      keywords = "debian iso cdimage official"
    ),
    DirectorySourceEntry(
      id = "dir_ubuntu_releases_html",
      name = "Ubuntu Official Releases",
      description = "Official Ubuntu Desktop & Server LTS release torrent index.",
      categoryHint = "Software · Linux",
      baseUrl = "https://releases.ubuntu.com",
      searchUrlTemplate = "https://releases.ubuntu.com/",
      feedUrl = "",
      sourceType = SourceType.HTML_SELECTOR,
      htmlSelectors = HtmlSelectorConfig(
        itemContainer = "tr:has(a[href$=.torrent])",
        title = "a[href$=.torrent]",
        detailsLink = "a[href$=.torrent]",
        torrentFileLink = "a[href$=.torrent]",
        fileSize = "td:nth-child(4)",
        uploadDate = "td:nth-child(3)"
      ),
      keywords = "ubuntu lts iso official"
    )
  )

  fun search(query: String): List<DirectorySourceEntry> {
    val q = query.trim()
    if (q.isBlank()) return catalog
    val tokens = q.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
    return catalog.filter { entry ->
      val searchable = "${entry.name} ${entry.description} ${entry.categoryHint} ${entry.baseUrl} ${entry.sourceType.label} ${entry.keywords}".lowercase()
      tokens.all { searchable.contains(it) }
    }
  }
}
