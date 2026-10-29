package dev.alder

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextClock
import android.widget.TextView
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

internal fun episodeDate(value: String?): String? {
    val day = value?.take(10)?.takeIf { it.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) } ?: return null
    val source = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { isLenient = false; timeZone = TimeZone.getTimeZone("UTC") }
    val parsed = runCatching { source.parse(day) }.getOrNull() ?: return null
    return SimpleDateFormat("d MMM yyyy", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(parsed)
}

// A title stays in place over its art; episodes and related titles open over the same scene.
class DetailsActivity : Screen() {
    private val type by lazy { intent.getStringExtra("type") ?: "movie" }
    private val metaId by lazy { intent.getStringExtra("id")!! }
    private val name by lazy { intent.getStringExtra("name") ?: "" }
    private val poster by lazy { intent.getStringExtra("poster") }
    private val initialArt by lazy { intent.getStringExtra("background") }

    private lateinit var key: String
    override val backdrop get() = heroArt
    private lateinit var root: FrameLayout
    private lateinit var hero: FrameLayout
    private lateinit var heroArt: ImageView
    private lateinit var guide: FrameLayout
    private lateinit var guideRows: LinearLayout
    private lateinit var seasonStrip: LinearLayout
    private var seasonButtons = emptyMap<Int, TextView>()
    private lateinit var previewImage: ImageView
    private lateinit var previewTitle: TextView
    private lateinit var previewFacts: TextView
    private lateinit var previewAbout: TextView
    private lateinit var previewProgress: FrameLayout
    private lateinit var similarOverlay: FrameLayout
    private var similarFirst: View? = null
    private lateinit var buttonRow: LinearLayout
    private val castChips = mutableListOf<View>()
    private lateinit var filmographyOverlay: FrameLayout
    private var filmographyOpen = false
    private var filmographyFirst: View? = null
    private var lastFocusedActor: View? = null
    private lateinit var listButton: TextView
    private lateinit var playButton: TextView
    private lateinit var infoButton: TextView
    private lateinit var infoOverlay: FrameLayout
    private var guideOpen = false
    private var similarOpen = false
    private var infoOpen = false
    private val infoCastCards = mutableListOf<View>()
    private var storyScrollView: ScrollView? = null
    private var richCast: List<CastMember> = emptyList()
    private var castStripSlot: FrameLayout? = null
    private var selectedSeason = 1
    private var episodeRows = emptyList<View>()
    private var meta: Meta? = null
    private var item: JSONObject? = null // the title's library item: progress and whether it's in My List
    private var itemChangedHere = false
    private var similarRequested = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        key = authKey ?: return finish()
        val artWidth = resources.displayMetrics.widthPixels
        heroArt = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0f }
        hero = FrameLayout(this).apply { clipChildren = false }
        root = FrameLayout(this).apply {
            clipChildren = false
            addView(hero, FrameLayout.LayoutParams(-1, -1))
            addView(TextClock(this@DetailsActivity).apply {
                format12Hour = "h:mm a"
                format24Hour = "HH:mm"
                textSize = SMALL
                setTextColor(TEXT_2)
                typeface = text("", SMALL).typeface
            }, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
                topMargin = dp(36); rightMargin = dp(PAD)
            })
            val (loading, loadingText) = loadingOverlay("Finding sources…")
            loadingView = loading
            loadingStatus = loadingText
            addView(loadingView, FrameLayout.LayoutParams(-1, -1))
        }
        setWindowed(root, heroArt)

        (initialArt ?: poster)?.let { Images.load(it, heroArt, artWidth, urgent = true) { heroArt.alpha = 1f; refrost() } }

        fun show(m: Meta) {
            meta = m
            val art = m.background ?: initialArt ?: poster
            if (heroArt.tag != art) Images.load(art, heroArt, artWidth, urgent = true) {
                heroArt.animate().alpha(1f).setDuration(250).start()
                refrost()
            }
            richCast = m.castMembers
            showHero(m)
            if (type == "series" && m.videos.isNotEmpty()) buildGuide(m.videos)
            if (type == "movie" && !similarRequested) { similarRequested = true; loadSimilar() }
            loadRichCast()
        }
        background({
            (Stremio.savedAddons(key) ?: Stremio.addons(key)).savedMeta(type, metaId) to Stremio.savedLibrary(key)?.firstOrNull { it.optString("_id") == metaId }
        }) { result ->
            result.getOrNull()?.let { (saved, libraryItem) ->
                if (!itemChangedHere && item == null) item = libraryItem
                if (meta == null) saved?.let(::show)
            }
        }
        background({ Stremio.addons(key).meta(type, metaId, together = true) }) { result ->
            val fresh = result.getOrNull()
            if (fresh != null) show(fresh)
            else if (meta == null) showHero(null)
        }
        background({ Stremio.library(key, listOf(metaId)).firstOrNull() }) { result ->
            if (!itemChangedHere) {
                item = result.getOrNull() ?: item
                if (meta != null) showHero(meta)
            }
        }
    }

    // ---------- title and watch window ----------

    private fun showHero(m: Meta?) {
        hero.removeAllViews()
        val title = text(m?.name ?: name, SERIF_HERO, serif = true).onArt().apply {
            maxLines = 2; ellipsize = TextUtils.TruncateAt.END; gravity = Gravity.CENTER_VERTICAL
            // The box is a fixed height: at large font sizes a two-line name shrinks to fit instead of clipping.
            setAutoSizeTextTypeUniformWithConfiguration(20, SERIF_HERO.toInt(), 1, android.util.TypedValue.COMPLEX_UNIT_SP)
        }
        val titleArea = FrameLayout(this).apply {
            addView(title, FrameLayout.LayoutParams(-1, -1))
            if (m != null) {
                val logo = ImageView(this@DetailsActivity).apply {
                    scaleType = ImageView.ScaleType.FIT_START
                    visibility = View.GONE
                }
                addView(logo, FrameLayout.LayoutParams(dp(260), -1))
                Images.load(m.logo ?: Images.logoFor(m.id), logo, dp(520), urgent = true, failed = {
                    title.visibility = View.VISIBLE; logo.visibility = View.GONE
                }) {
                    title.visibility = View.GONE; logo.visibility = View.VISIBLE
                }
            }
        }
        val stack = column(Gravity.START).apply { addView(titleArea, LinearLayout.LayoutParams(-1, dp(75))) }
        if (m == null) {
            val error = failed("Couldn't load this title's details.") { recreate() }
            stack.addView(error.marginTop(16))
            error.post { error.getChildAt(2).requestFocus() }
        } else {
            val length = if (type == "series") m.videos.map { it.season }.filter { it > 0 }.distinct().size
                .takeIf { it > 0 }?.let(::seasons) else m.runtime
            val facts = listOfNotNull(m.year, m.genres.firstOrNull(), length).joinToString(DOT)
            val factRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            factRow.addView(text(facts, SMALL, color = TEXT_2).onArt().apply {
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            })
            m.rating?.let { rating ->
                factRow.addView(text("·", SMALL, color = TEXT_2), wrap().apply { marginStart = dp(7); marginEnd = dp(7) })
                factRow.addView(text("IMDb", 9f, bold = true, color = BASE).apply {
                    setPadding(dp(4), dp(1), dp(4), dp(1)); background = rounded(TUNGSTEN, 3)
                })
                factRow.addView(text(rating, SMALL, color = TEXT_2).onArt(), wrap().apply { marginStart = dp(6) })
            }
            stack.addView(factRow, wrap().apply { topMargin = dp(12) })
            m.description?.takeIf { it.isNotBlank() }?.let {
                stack.addView(text(it, BODY, color = TEXT).onArt().apply {
                    maxLines = 3; ellipsize = TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(dp(470), -2).apply { topMargin = dp(8) })
            }
            val initialCast = richCast.ifEmpty { m.castMembers }
            buildCastChips(initialCast)?.let { castStrip ->
                val slot = FrameLayout(this).apply {
                    clipChildren = false
                    clipToPadding = false
                    addView(castStrip)
                }
                castStripSlot = slot
                stack.addView(slot, wrap().apply { topMargin = dp(10) })
            }
            addWatch(m, stack)
        }
        hero.addView(stack, FrameLayout.LayoutParams(dp(HERO_W), -2, Gravity.BOTTOM or Gravity.START).apply {
            leftMargin = dp(PAD); bottomMargin = dp(118)
        })
        val hasEpisodes = type == "series" && m?.videos?.isNotEmpty() == true
        if (hasEpisodes || ::similarOverlay.isInitialized) {
            hero.addView(caps(if (hasEpisodes) "▾  Episodes" else "▾  More like this", TEXT_2).onArt().apply {
                isFocusable = true
                setOnClickListener { if (hasEpisodes) showGuide() else showSimilar() }
                rowFocus(rest = { null })
            }, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply {
                leftMargin = dp(PAD); bottomMargin = dp(28)
            })
        }
    }

    /** Where you are, then the main action and three small glass controls. */
    private fun addWatch(m: Meta, stack: LinearLayout) {
        val target = playTarget(m)
        val episode = target?.second
        // Under a minute in is not started: a title opened and closed at 0:00 offers Play, not Resume.
        val resumeAt = (item?.let { Library.resumeAt(it, target?.first ?: metaId) } ?: 0L).takeIf { it >= STARTED } ?: 0L
        val duration = item?.optJSONObject("state")?.optLong("duration") ?: 0L
        val remaining = if (resumeAt > 0 && duration > resumeAt) runtime("${(duration - resumeAt) / 60_000} min") + " left" else null
        val where = listOfNotNull(episode?.let { "S${it.season} E${it.episode}" }, episode?.title?.takeIf { it.isNotBlank() }, remaining).joinToString(DOT)
        if (where.isNotEmpty() && resumeAt > 0) stack.addView(text(where, SMALL, bold = true).onArt().apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }.marginTop(16))
        if (remaining != null) stack.addView(progressBar(resumeAt.toFloat() / duration), LinearLayout.LayoutParams(dp(240), dp(3)).apply { topMargin = dp(8) })

        playButton = pill(if (resumeAt > 0) "Resume" else "Play", R.drawable.ic_play).apply {
            setOnClickListener { openSources(target?.first ?: metaId, startOver = false) }
            // Held OK: every source, to choose one by hand.
            setOnLongClickListener { openSources(target?.first ?: metaId, startOver = false, pick = true); true }
        }
        val startOver = if (resumeAt > 0) iconButton("↺", "Start over") {
            openSources(target?.first ?: metaId, startOver = true)
        } else null
        infoButton = iconButton("ⓘ", "More information") { showInfo(focusCast = false) }
        listButton = iconButton(if (Library.inList(item)) "✓" else "+", "My List") { toggleList() }
        buttonRow = LinearLayout(this).apply {
            clipChildren = false
            addView(playButton, wrap().apply { marginEnd = dp(14) })
            startOver?.let { addView(it, LinearLayout.LayoutParams(dp(32), dp(32)).apply { marginEnd = dp(8) }) }
            addView(listButton, LinearLayout.LayoutParams(dp(32), dp(32)).apply { marginEnd = dp(8) })
            addView(infoButton)
        }
        stack.addView(buttonRow, wrap().apply { topMargin = dp(16) })
        playButton.post { if (!guideOpen && !infoOpen && !similarOpen && !filmographyOpen) playButton.requestFocus() }
    }

    private fun iconButton(symbol: String, description: String, action: () -> Unit) = text(symbol, 18f).apply {
        gravity = Gravity.CENTER
        contentDescription = description
        rowFocus(16, rest = { glass(16) })
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(dp(32), dp(32))
    }

    /** What Play opens: the episode Continue Watching points at, or the first episode, or the movie itself. */
    private fun playTarget(m: Meta?): Pair<String, Video?>? {
        val videos = m?.videos.orEmpty()
        if (videos.isEmpty()) return metaId to null
        val current = item?.optJSONObject("state")?.text("video_id")
        val video = videos.firstOrNull { it.id == current } ?: videos.firstOrNull { it.season > 0 } ?: videos.first()
        return video.id to video
    }

    private fun toggleList() {
        itemChangedHere = true
        val entry = item ?: Library.newItem(metaId, type, meta?.name ?: name, meta?.poster ?: poster)
        Library.setInList(entry, !Library.inList(item))
        item = entry
        listButton.text = if (Library.inList(entry)) "✓" else "+"
        listButton.contentDescription = if (Library.inList(entry)) "Remove from My List" else "Add to My List"
        val snapshot = JSONObject(entry.toString())
        background({ Stremio.saveLibrary(key, listOf(snapshot)) }) {}
    }

    // ---------- glass and episode guide ----------

    private fun glassPane() = FrameLayout(this).apply {
        background = glassOver(this, 14, shade = PANE_SHADE)
        clipToOutline = true
        outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) =
                outline.setRoundRect(0, 0, view.width, view.height, dp(14).toFloat())
        }
    }

    private fun buildGuide(videos: List<Video>) {
        if (::guide.isInitialized) root.removeView(guide)
        guideOpen = false
        val x = dp(PAD)
        val top = dp(128)
        val width = resources.displayMetrics.widthPixels - x * 2
        val height = resources.displayMetrics.heightPixels - top - dp(27)
        val largeText = resources.configuration.fontScale > 1.15f
        guide = FrameLayout(this).apply { visibility = View.GONE }
        val smallLogo = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_START }
        guide.addView(smallLogo, FrameLayout.LayoutParams(dp(150), dp(42)).apply {
            leftMargin = x; topMargin = dp(27)
        })
        val fallback = text(meta?.name ?: name, SERIF_TITLE, serif = true).onArt().apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END; visibility = View.GONE
        }
        guide.addView(fallback, FrameLayout.LayoutParams(dp(250), -2).apply {
            leftMargin = x; topMargin = dp(28)
        })
        Images.load(meta?.logo ?: Images.logoFor(metaId), smallLogo, dp(300), urgent = true, failed = {
            smallLogo.visibility = View.GONE; fallback.visibility = View.VISIBLE
        })

        seasonStrip = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        // Room around the chips for the focused one to grow into: flush with the strip, its left edge was cut flat.
        val room = dp(8)
        guide.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(room, room, room, room)
            clipToPadding = false
            addView(seasonStrip)
        }, FrameLayout.LayoutParams(width + 2 * room, dp(32) + 2 * room).apply { leftMargin = x - room; topMargin = dp(88) - room })

        val pane = glassPane()
        guideRows = column(Gravity.START)
        pane.addView(ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            // The row past the panel's foot fades out instead of running into the edge.
            isVerticalFadingEdgeEnabled = true
            setFadingEdgeLength(dp(40))
            addView(guideRows)
        }, FrameLayout.LayoutParams(dp(380), height - dp(28)).apply {
            leftMargin = dp(14); topMargin = dp(14)
        })
        val previewX = dp(414)
        val previewWidth = width - previewX - dp(14)
        previewImage = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = rounded(BASE, 8)
            clipToOutline = true
        }
        pane.addView(previewImage, FrameLayout.LayoutParams(previewWidth, dp(226)).apply {
            leftMargin = previewX; topMargin = dp(14)
        })
        previewProgress = FrameLayout(this)
        pane.addView(previewProgress, FrameLayout.LayoutParams(previewWidth - dp(20), dp(3)).apply {
            leftMargin = previewX + dp(10); topMargin = dp(229)
        })
        previewTitle = text("", 22f, serif = true).onArt().apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }
        pane.addView(previewTitle, FrameLayout.LayoutParams(previewWidth, -2).apply {
            leftMargin = previewX; topMargin = dp(251)
        })
        previewFacts = text("", SMALL, color = TEXT_2).onArt().apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }
        pane.addView(previewFacts, FrameLayout.LayoutParams(previewWidth, -2).apply {
            leftMargin = previewX; topMargin = dp(if (largeText) 285 else 280)
        })
        previewAbout = text("", SMALL, color = TEXT).onArt().apply {
            maxLines = if (largeText) 2 else 3
            ellipsize = TextUtils.TruncateAt.END
        }
        pane.addView(previewAbout, FrameLayout.LayoutParams(previewWidth, -2).apply {
            leftMargin = previewX; topMargin = dp(if (largeText) 313 else 304)
        })
        guide.addView(pane, FrameLayout.LayoutParams(width, height).apply {
            leftMargin = x; topMargin = top
        })
        root.addView(guide, FrameLayout.LayoutParams(-1, -1))
        selectedSeason = playTarget(meta)?.second?.season ?: videos.first().season
        fillGuide()
    }

    private fun fillGuide() {
        val videos = meta?.videos.orEmpty()
        val seasons = videos.map { it.season }.distinct()
        seasonStrip.removeAllViews()
        seasonButtons = seasons.associateWith { season ->
            chip(if (season == selectedSeason) {
                if (season == 0) "Specials" else "Season $season"
            } else if (season == 0) "Specials" else season.toString(), season == selectedSeason).apply {
                setOnClickListener {
                    selectedSeason = season
                    fillGuide()
                    seasonButtons[season]?.requestFocus()
                }
                seasonStrip.addView(this, LinearLayout.LayoutParams(-2, dp(24)).apply {
                    if (seasonStrip.childCount > 0) marginStart = dp(8)
                })
            }
        }
        guideRows.removeAllViews()
        val watched = Library.watched(item, videos)
        val currentId = playTarget(meta)?.first
        episodeRows = videos.filter { it.season == selectedSeason }.map { video ->
            val row = FrameLayout(this).apply { tag = video.id }
            val number = text("%02d".format(video.episode), BODY, color = TEXT_3)
            row.addView(number, FrameLayout.LayoutParams(dp(32), -2, Gravity.CENTER_VERTICAL).apply {
                leftMargin = dp(12)
            })
            val resumeAt = item?.let { Library.resumeAt(it, video.id) } ?: 0L
            val duration = item?.optJSONObject("state")?.takeIf { it.text("video_id") == video.id }
                ?.optLong("duration") ?: 0L
            val status = when {
                video.id in watched -> "✓"
                resumeAt > 0 && duration > resumeAt -> "${(duration - resumeAt) / 60_000} min left"
                else -> meta?.runtime.orEmpty()
            }
            row.addView(text(video.title.ifBlank { "Episode ${video.episode}" }, BODY,
                color = if (video.id in watched) TEXT_2 else TEXT).apply {
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            }, FrameLayout.LayoutParams(dp(225), -2, Gravity.CENTER_VERTICAL).apply { leftMargin = dp(46) })
            if (status.isNotEmpty()) row.addView(text(status, SMALL,
                color = if (video.id in watched) TUNGSTEN else TEXT_2).apply { gravity = Gravity.END },
                FrameLayout.LayoutParams(dp(98), -2, Gravity.CENTER_VERTICAL or Gravity.END).apply {
                    rightMargin = dp(12)
                })
            row.rowFocus(9) { focused -> if (focused) updatePreview(video) }
            row.setOnClickListener {
                val next = videos.getOrNull(videos.indexOf(video) + 1)?.id
                openSources(video.id, startOver = false, next = next, label = "S${video.season} E${video.episode}")
            }
            row.setOnLongClickListener {
                val next = videos.getOrNull(videos.indexOf(video) + 1)?.id
                openSources(video.id, startOver = false, next = next,
                    label = "S${video.season} E${video.episode}", pick = true)
                true
            }
            guideRows.addView(row, LinearLayout.LayoutParams(-1, dp(45)))
            row
        }
        videos.firstOrNull { it.id == currentId && it.season == selectedSeason }?.let(::updatePreview)
            ?: videos.firstOrNull { it.season == selectedSeason }?.let(::updatePreview)
    }

    private fun updatePreview(video: Video) {
        val blur = Settings.blurThumbnails(this) && video.id !in Library.watched(item, meta?.videos.orEmpty())
        Images.load(video.thumbnail, previewImage, dp(870), orElse = meta?.background, look = if (blur) Images.blurred else null)
        previewTitle.text = video.title.ifBlank { "Episode ${video.episode}" }
        previewFacts.text = listOfNotNull("S${video.season} E${video.episode}", meta?.runtime,
            episodeDate(video.released)).joinToString(DOT)
        previewAbout.text = video.overview.orEmpty()
        previewAbout.visibility = if (video.overview.isNullOrBlank()) View.GONE else View.VISIBLE
        previewProgress.removeAllViews()
        val state = item?.optJSONObject("state")?.takeIf { it.text("video_id") == video.id }
        val resumeAt = item?.let { Library.resumeAt(it, video.id) } ?: 0L
        val duration = state?.optLong("duration") ?: 0L
        if (resumeAt > 0 && duration > resumeAt) {
            previewProgress.addView(progressBar(resumeAt.toFloat() / duration),
                FrameLayout.LayoutParams(-1, -1))
        }
    }

    private fun showGuide() {
        if (!::guide.isInitialized || guideOpen) return
        guideOpen = true
        hero.visibility = View.INVISIBLE
        guide.visibility = View.VISIBLE
        guide.alpha = 0f
        guide.translationY = dp(28).toFloat()
        guide.animate().alpha(1f).translationY(0f).setDuration(260).start()
        // The film steps back behind the guide, so the chips and panel read over a bright backdrop.
        heroArt.animate().alpha(GUIDE_ART).setDuration(260).withEndAction { refrost() }.start()
        leftShade(false) // the episode list sits on glass: the art can run to the left edge as it does on the right
        val currentId = playTarget(meta)?.first
        (episodeRows.firstOrNull { it.tag == currentId } ?: episodeRows.firstOrNull())?.requestFocus()
    }

    private fun hideGuide() {
        guideOpen = false
        heroArt.animate().alpha(1f).setDuration(220).withEndAction { refrost() }.start()
        leftShade(true)
        guide.animate().alpha(0f).translationY(dp(24).toFloat()).setDuration(220).withEndAction {
            guide.visibility = View.GONE
            hero.visibility = View.VISIBLE
            if (::playButton.isInitialized) playButton.requestFocus()
        }.start()
    }

    /**
     * The whole story and complete cast, with circular portraits above actor and character names.
     * Remote navigates between the story text and cast cards, with OK on any actor revealing their filmography.
     */
    private fun showInfo(focusCast: Boolean = false) {
        val m = meta ?: return
        if (::infoOverlay.isInitialized) root.removeView(infoOverlay)
        infoOpen = true
        hero.visibility = View.INVISIBLE
        infoCastCards.clear()
        storyScrollView = null

        infoOverlay = FrameLayout(this)
        infoOverlay.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(0xEB0E0C0A.toInt(), 0xD90E0C0A.toInt(), 0x990E0C0A.toInt()))
        }, FrameLayout.LayoutParams(-1, -1))

        val facts = listOfNotNull(m.year, m.runtime,
            m.genres.take(2).joinToString(" / ").ifEmpty { null }, m.rating?.let { "IMDb $it" }).joinToString(DOT)

        val content = column(Gravity.START).apply {
            clipChildren = false
            clipToPadding = false
        }

        content.addView(text(m.name, SERIF_TITLE, bold = true, serif = true).onArt().apply {
            maxLines = 2; ellipsize = TextUtils.TruncateAt.END
        })
        content.addView(text(facts, SMALL, color = TEXT).onArt().apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }.marginTop(6))

        m.description?.takeIf { it.isNotBlank() }?.let { desc ->
            content.addView(text("The story", SMALL, bold = true, color = TEXT).onArt().marginTop(14))
            val story = ScrollView(this).apply {
                isVerticalScrollBarEnabled = false
                isFocusable = true
                addView(text(desc, BODY, color = TEXT).onArt())
            }
            storyScrollView = story
            content.addView(story, LinearLayout.LayoutParams(dp(HERO_W), dp(110)).apply { topMargin = dp(6) })
        }

        val fullCast = richCast.ifEmpty { m.castMembers }
        if (fullCast.isNotEmpty()) {
            content.addView(caps("Cast", TEXT_2).onArt().marginTop(14))
            val castRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                clipChildren = false
                clipToPadding = false
            }

            val avatarSize = dp(56)
            val ringBox = avatarSize + dp(6)

            fullCast.forEachIndexed { index, actor ->
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    isFocusable = true
                    clipChildren = false
                    clipToPadding = false
                    setPadding(dp(4), dp(4), dp(4), dp(6))
                }

                val avatarContainer = FrameLayout(this).apply { clipChildren = false }

                val ring = View(this).apply {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(0x00000000)
                        setStroke(dp(2), TUNGSTEN)
                    }
                    visibility = View.INVISIBLE
                }
                avatarContainer.addView(ring, FrameLayout.LayoutParams(ringBox, ringBox, Gravity.CENTER))

                val avatarFrame = FrameLayout(this).apply {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(0x33F3D9B1.toInt())
                    }
                    clipToOutline = true
                    outlineProvider = object : android.view.ViewOutlineProvider() {
                        override fun getOutline(view: View, outline: android.graphics.Outline) =
                            outline.setOval(0, 0, view.width, view.height)
                    }
                }
                val initials = text(actor.initials, 15f, bold = true, color = 0xFFF3D9B1.toInt(), serif = true).apply {
                    gravity = Gravity.CENTER
                }
                avatarFrame.addView(initials, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))

                if (!actor.photo.isNullOrBlank()) {
                    val photoView = ImageView(this@DetailsActivity).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        visibility = View.GONE
                    }
                    avatarFrame.addView(photoView, FrameLayout.LayoutParams(-1, -1))
                    Images.load(actor.photo, photoView, avatarSize, failed = {
                        photoView.visibility = View.GONE
                        initials.visibility = View.VISIBLE
                    }) {
                        photoView.visibility = View.VISIBLE
                        initials.visibility = View.GONE
                    }
                }
                avatarContainer.addView(avatarFrame, FrameLayout.LayoutParams(avatarSize, avatarSize, Gravity.CENTER))

                card.addView(avatarContainer, wrap())

                val nameView = text(actor.name, SMALL, bold = true, color = TEXT).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    textAlignment = View.TEXT_ALIGNMENT_CENTER
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                }
                card.addView(nameView, LinearLayout.LayoutParams(dp(82), -2).apply { topMargin = dp(6) })

                val charView = if (!actor.character.isNullOrBlank()) {
                    text(actor.character, 11f, color = TEXT_2).apply {
                        gravity = Gravity.CENTER_HORIZONTAL
                        textAlignment = View.TEXT_ALIGNMENT_CENTER
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                    }
                } else null
                charView?.let { card.addView(it, LinearLayout.LayoutParams(dp(82), -2).apply { topMargin = dp(2) }) }

                card.setOnFocusChangeListener { _, focused ->
                    card.animate().scaleX(if (focused) 1.08f else 1f).scaleY(if (focused) 1.08f else 1f).setDuration(120).start()
                    ring.visibility = if (focused) View.VISIBLE else View.INVISIBLE
                    nameView.setTextColor(if (focused) 0xFFFBF8F5.toInt() else TEXT)
                    charView?.setTextColor(if (focused) TEXT else TEXT_2)
                }

                card.setOnClickListener {
                    showFilmography(actor, card)
                }

                infoCastCards.add(card)
                castRow.addView(card, LinearLayout.LayoutParams(-2, -2).apply {
                    if (index > 0) marginStart = dp(12)
                })
            }

            val castScroll = HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                clipChildren = false
                clipToPadding = false
                addView(castRow)
            }
            content.addView(castScroll, LinearLayout.LayoutParams(-1, dp(128)).apply { topMargin = dp(6) })
        }

        infoOverlay.addView(content, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM or Gravity.START).apply {
            leftMargin = dp(PAD); rightMargin = dp(PAD); bottomMargin = dp(34)
        })
        root.addView(infoOverlay, FrameLayout.LayoutParams(-1, -1))

        if (focusCast && infoCastCards.isNotEmpty()) {
            infoCastCards.first().requestFocus()
        } else {
            storyScrollView?.requestFocus() ?: infoCastCards.firstOrNull()?.requestFocus()
        }
    }

    private fun hideInfo() {
        infoOpen = false
        if (::infoOverlay.isInitialized) root.removeView(infoOverlay)
        hero.visibility = View.VISIBLE
        infoButton.requestFocus()
    }

    private fun loadSimilar() {
        background({
            Stremio.addons(key).firstNotNullOfOrNull { addon ->
                runCatching { addon.similar(type, metaId) }.getOrNull()?.takeIf { it.isNotEmpty() }
            }.orEmpty()
        }) { result ->
            result.getOrNull()?.let { titles ->
                titles.asSequence().filter { it.id != metaId && it.type == type }
                    .distinctBy { it.id }.take(12).toList().takeIf { it.isNotEmpty() }
                    ?.let { showSimilar("More like this", it) }
            }
        }
    }

    /** Movies reveal one Home-sized poster row without moving the title off the screen. */
    private fun showSimilar(heading: String, titles: List<Meta>) {
        if (::similarOverlay.isInitialized) root.removeView(similarOverlay)
        similarOverlay = FrameLayout(this).apply { visibility = View.GONE }
        similarOverlay.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(0xF70E0C0A.toInt(), 0xA60E0C0A.toInt(), 0x000E0C0A))
        }, FrameLayout.LayoutParams(-1, dp(280), Gravity.BOTTOM))
        val content = column(Gravity.START)
        content.addView(caps(heading).onArt(), LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = dp(12)
        })
        val row = LinearLayout(this).apply { clipChildren = false; clipToPadding = false }
        similarFirst = null
        titles.forEachIndexed { index, title ->
            val card = FrameLayout(this).apply {
                background = rounded(BASE, 6)
                outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: android.graphics.Outline) =
                        outline.setRoundRect(0, 0, view.width, view.height, dp(6).toFloat())
                }
                clipToOutline = true
                val image = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
                addView(image, FrameLayout.LayoutParams(-1, -1))
                val fallback = text(title.name, SMALL, bold = true).apply {
                    maxLines = 3; ellipsize = TextUtils.TruncateAt.END
                    setPadding(dp(8), 0, dp(8), dp(8)); visibility = View.GONE
                }
                addView(fallback, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
                cardFocus(6)
                setOnClickListener {
                    startActivity(Intent(this@DetailsActivity, DetailsActivity::class.java)
                        .putExtra("type", title.type).putExtra("id", title.id).putExtra("name", title.name)
                        .putExtra("poster", title.poster).putExtra("background", title.background))
                }
                Images.load(title.poster ?: title.background, image, dp(POSTER_W), failed = {
                    fallback.visibility = View.VISIBLE
                })
            }
            if (similarFirst == null) similarFirst = card
            row.addView(card, LinearLayout.LayoutParams(dp(POSTER_W), dp(POSTER_H)).apply {
                if (index > 0) marginStart = dp(CARD_GAP)
            })
        }
        content.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipChildren = false
            clipToPadding = false
            addView(row)
        }, LinearLayout.LayoutParams(-1, dp(POSTER_H + 8)))
        similarOverlay.addView(content, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
            leftMargin = dp(PAD); rightMargin = dp(PAD); bottomMargin = dp(38)
        })
        root.addView(similarOverlay, FrameLayout.LayoutParams(-1, -1))
        showHero(meta)
    }

    private fun showSimilar() {
        if (!::similarOverlay.isInitialized || similarOpen) return
        similarOpen = true
        hero.visibility = View.INVISIBLE
        similarOverlay.visibility = View.VISIBLE
        similarOverlay.alpha = 0f
        similarOverlay.translationY = dp(20).toFloat()
        similarOverlay.animate().alpha(1f).translationY(0f).setDuration(220).start()
        similarFirst?.requestFocus()
    }

    private fun hideSimilar() {
        similarOpen = false
        similarOverlay.animate().alpha(0f).translationY(dp(20).toFloat()).setDuration(180).withEndAction {
            similarOverlay.visibility = View.GONE
            hero.visibility = View.VISIBLE
            playButton.requestFocus()
        }.start()
    }

    private fun loadRichCast() {
        background({
            Tmdb.cast(metaId, type)
        }) { result ->
            val enhanced = result.getOrNull().orEmpty()
            if (enhanced.isNotEmpty()) {
                richCast = enhanced
                castStripSlot?.let { slot ->
                    slot.removeAllViews()
                    buildCastChips(richCast)?.let { slot.addView(it) }
                }
                if (infoOpen) {
                    showInfo(focusCast = currentFocus in infoCastCards)
                }
            }
        }
    }

    private fun buildCastChips(allMembers: List<CastMember>): View? {
        val members = allMembers.take(4)
        if (members.isEmpty()) return null
        castChips.clear()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        val room = dp(6)
        val scrollView = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipChildren = false
            clipToPadding = false
            setPadding(room, room, room, room)
            addView(container)
        }

        members.forEachIndexed { index, actor ->
            val chip = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isFocusable = true
                minimumHeight = dp(28)
                setPadding(dp(8), dp(4), dp(12), dp(4))
                background = glass(14)

                val avatarSize = dp(20)
                val avatarFrame = FrameLayout(context).apply {
                    background = rounded(0x33F3D9B1.toInt(), 10)
                    clipToOutline = true
                    outlineProvider = object : android.view.ViewOutlineProvider() {
                        override fun getOutline(view: View, outline: android.graphics.Outline) =
                            outline.setRoundRect(0, 0, view.width, view.height, dp(10).toFloat())
                    }
                }
                val initialsText = text(actor.initials, 9f, bold = true, color = TUNGSTEN, serif = true).apply {
                    gravity = Gravity.CENTER
                }
                avatarFrame.addView(initialsText, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))

                if (!actor.photo.isNullOrBlank()) {
                    val photoView = ImageView(this@DetailsActivity).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        visibility = View.GONE
                    }
                    avatarFrame.addView(photoView, FrameLayout.LayoutParams(-1, -1))
                    Images.load(actor.photo, photoView, avatarSize, failed = {
                        photoView.visibility = View.GONE
                        initialsText.visibility = View.VISIBLE
                    }) {
                        photoView.visibility = View.VISIBLE
                        initialsText.visibility = View.GONE
                    }
                }
                addView(avatarFrame, LinearLayout.LayoutParams(avatarSize, avatarSize).apply { marginEnd = dp(8) })

                val nameView = text(actor.name, SMALL, color = TEXT_2)
                addView(nameView, wrap())

                setOnFocusChangeListener { v, has ->
                    v.background = glass(14, has)
                    v.grow(has, CHIP_GROW).start()
                    nameView.setTextColor(if (has) TEXT else TEXT_2)
                }
                setOnClickListener { showFilmography(actor, this) }
            }
            castChips.add(chip)
            container.addView(chip, LinearLayout.LayoutParams(-2, -2).apply {
                if (index > 0) marginStart = dp(8)
            })
        }

        val totalCastCount = (richCast.ifEmpty { allMembers }).size
        if (totalCastCount > 4) {
            val moreChip = chip("+${totalCastCount - 4} more").apply {
                setOnClickListener { showInfo(focusCast = true) }
            }
            castChips.add(moreChip)
            container.addView(moreChip, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
        }

        return FrameLayout(this).apply {
            clipChildren = false
            clipToPadding = false
            addView(scrollView, FrameLayout.LayoutParams(-2, -2).apply {
                leftMargin = -room; topMargin = -room; bottomMargin = -room
            })
        }
    }

    private fun showFilmography(actor: CastMember, originView: View) {
        lastFocusedActor = originView
        background({
            Stremio.addons(key).firstNotNullOfOrNull { addon ->
                runCatching { addon.search(actor.name) }.getOrNull()?.takeIf { it.isNotEmpty() }
            }.orEmpty()
        }) { result ->
            val titles = result.getOrNull()?.filter { it.id != metaId }?.distinctBy { it.id }?.take(16).orEmpty()
            if (titles.isEmpty()) {
                if (!infoOpen) showInfo(focusCast = true)
                return@background
            }
            buildFilmographyOverlay(actor.name, titles)
            openFilmography()
        }
    }

    private fun buildFilmographyOverlay(heading: String, titles: List<Meta>) {
        if (::filmographyOverlay.isInitialized) root.removeView(filmographyOverlay)
        filmographyOverlay = FrameLayout(this).apply { visibility = View.GONE }
        filmographyOverlay.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(0xF70E0C0A.toInt(), 0xA60E0C0A.toInt(), 0x000E0C0A))
        }, FrameLayout.LayoutParams(-1, dp(280), Gravity.BOTTOM))
        val content = column(Gravity.START)
        content.addView(caps(heading).onArt(), LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = dp(12)
        })
        val row = LinearLayout(this).apply { clipChildren = false; clipToPadding = false }
        filmographyFirst = null
        titles.forEachIndexed { index, title ->
            val card = FrameLayout(this).apply {
                background = rounded(BASE, 6)
                outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: android.graphics.Outline) =
                        outline.setRoundRect(0, 0, view.width, view.height, dp(6).toFloat())
                }
                clipToOutline = true
                val image = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
                addView(image, FrameLayout.LayoutParams(-1, -1))
                val fallback = text(title.name, SMALL, bold = true).apply {
                    maxLines = 3; ellipsize = TextUtils.TruncateAt.END
                    setPadding(dp(8), 0, dp(8), dp(8)); visibility = View.GONE
                }
                addView(fallback, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
                cardFocus(6)
                setOnClickListener {
                    startActivity(Intent(this@DetailsActivity, DetailsActivity::class.java)
                        .putExtra("type", title.type).putExtra("id", title.id).putExtra("name", title.name)
                        .putExtra("poster", title.poster).putExtra("background", title.background))
                }
                Images.load(title.poster ?: title.background, image, dp(POSTER_W), failed = {
                    fallback.visibility = View.VISIBLE
                })
            }
            if (filmographyFirst == null) filmographyFirst = card
            row.addView(card, LinearLayout.LayoutParams(dp(POSTER_W), dp(POSTER_H)).apply {
                if (index > 0) marginStart = dp(CARD_GAP)
            })
        }
        content.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipChildren = false
            clipToPadding = false
            addView(row)
        }, LinearLayout.LayoutParams(-1, dp(POSTER_H + 8)))
        filmographyOverlay.addView(content, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
            leftMargin = dp(PAD); rightMargin = dp(PAD); bottomMargin = dp(38)
        })
        root.addView(filmographyOverlay, FrameLayout.LayoutParams(-1, -1))
    }

    private fun openFilmography() {
        if (!::filmographyOverlay.isInitialized || filmographyOpen) return
        filmographyOpen = true
        if (infoOpen) {
            infoOverlay.visibility = View.INVISIBLE
        } else {
            hero.visibility = View.INVISIBLE
        }
        filmographyOverlay.visibility = View.VISIBLE
        filmographyOverlay.alpha = 0f
        filmographyOverlay.translationY = dp(20).toFloat()
        filmographyOverlay.animate().alpha(1f).translationY(0f).setDuration(220).start()
        filmographyFirst?.requestFocus()
    }

    private fun hideFilmography() {
        if (!filmographyOpen) return
        filmographyOpen = false
        filmographyOverlay.animate().alpha(0f).translationY(dp(20).toFloat()).setDuration(180).withEndAction {
            filmographyOverlay.visibility = View.GONE
            if (infoOpen) {
                infoOverlay.visibility = View.VISIBLE
            } else {
                hero.visibility = View.VISIBLE
            }
            (lastFocusedActor ?: if (infoOpen) infoOverlay else playButton).requestFocus()
        }.start()
    }

    // ---------- shared ----------

    private var resolvingStreams: Cancellable? = null
    private lateinit var loadingView: FrameLayout
    private lateinit var loadingStatus: TextView

    private fun cancelStreamResolution() {
        resolvingStreams?.cancel()
        resolvingStreams = null
        if (::loadingView.isInitialized) loadingView.visibility = View.GONE
    }

    private fun openSources(videoId: String, startOver: Boolean, next: String? = null, label: String? = null, pick: Boolean = false) {
        if (resolvingStreams != null) return
        val m = meta
        val nextId = next ?: m?.videos?.let { vs -> vs.getOrNull(vs.indexOfFirst { it.id == videoId } + 1)?.id }
        val video = m?.videos?.firstOrNull { it.id == videoId }
        val short = label ?: video?.let { "S${it.season} E${it.episode}" }
        val intent = Intent(this, StreamsActivity::class.java)
            .putExtra("type", type).putExtra("id", metaId).putExtra("name", m?.name ?: name).putExtra("poster", m?.poster ?: poster)
            .putExtra("videoId", videoId).putExtra("nextVideoId", nextId).putExtra("startOver", startOver).putExtra("pick", pick)
            .putExtra("background", m?.background)
            .putExtra("label", listOfNotNull(m?.name ?: name, short).joinToString("  ·  "))
            // For the sources page: the title's logo, the episode's still and "S1 E3 · OutKast".
            .putExtra("logo", m?.logo).putExtra("still", video?.thumbnail).putExtra("runtime", m?.runtime)
            .putExtra("blurStill", Settings.blurThumbnails(this) && video != null && video.id !in Library.watched(item, m?.videos.orEmpty()))
            .putExtra("episode", video?.let { v -> listOfNotNull("S${v.season} E${v.episode}", v.title.takeIf { it.isNotBlank() }).joinToString("  ·  ") })

        if (pick || Settings.alwaysList(this)) {
            startActivity(intent)
            return
        }

        loadingStatus.text = "Finding sources…"
        loadingView.visibility = View.VISIBLE
        loadingView.bringToFront()

        resolvingStreams = StreamsResolver.resolve(
            context = this,
            type = type,
            videoId = videoId,
            metaId = metaId,
            authKey = authKey,
            onReady = { best ->
                cancelStreamResolution()
                startActivity(Intent(this, PlayerActivity::class.java).putExtras(intent)
                    .putExtra("sources", org.json.JSONArray(best.map { it.toJson() }).toString()))
            },
            onFallback = {
                cancelStreamResolution()
                startActivity(intent)
            }
        )
    }

    override fun onPause() {
        cancelStreamResolution()
        super.onPause()
    }

    // Back from the player: progress, the resume point and My List may have changed.
    override fun onRestart() {
        super.onRestart()
        background({ runCatching { Stremio.library(key, listOf(metaId)).firstOrNull() }.getOrNull() }) { result ->
            item = result.getOrNull() ?: item
            val wasGuide = guideOpen
            if (infoOpen) { root.removeView(infoOverlay); infoOpen = false }
            hero.visibility = View.VISIBLE
            showHero(meta)
            meta?.videos?.takeIf { type == "series" && it.isNotEmpty() }?.let(::buildGuide)
            if (wasGuide) showGuide()
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (event.keyCode == KeyEvent.KEYCODE_BACK && resolvingStreams != null) {
                cancelStreamResolution()
                return true
            }
            if (guideOpen) {
                val focused = currentFocus
                if (focused in seasonButtons.values) when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_DOWN -> { episodeRows.firstOrNull()?.requestFocus(); return true }
                    KeyEvent.KEYCODE_DPAD_UP -> return true
                }
                val at = episodeRows.indexOf(focused)
                if (at >= 0) when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> return true
                    KeyEvent.KEYCODE_DPAD_UP -> if (at == 0) { seasonButtons[selectedSeason]?.requestFocus(); return true }
                    KeyEvent.KEYCODE_DPAD_DOWN -> if (at == episodeRows.lastIndex) return true
                }
            } else if (filmographyOpen && event.keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                hideFilmography()
                return true
            } else if (similarOpen && event.keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                hideSimilar()
                return true
            } else if (infoOpen && !filmographyOpen) {
                if (event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    if (currentFocus === storyScrollView && infoCastCards.isNotEmpty()) {
                        infoCastCards.first().requestFocus()
                        return true
                    }
                } else if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                    if (currentFocus?.let { v -> infoCastCards.any { it === v || isInside(v, it) } } == true) {
                        storyScrollView?.requestFocus()
                        return true
                    }
                }
            } else if (!infoOpen && !similarOpen && !filmographyOpen && currentFocus?.let { v -> castChips.any { it === v || isInside(v, it) } } == true) {
                if (event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    playButton.requestFocus()
                    return true
                } else if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                    return true
                }
            } else if (!infoOpen && !similarOpen && !filmographyOpen && castChips.isNotEmpty() && event.keyCode == KeyEvent.KEYCODE_DPAD_UP &&
                currentFocus?.let { it === playButton || (::buttonRow.isInitialized && isInside(it, buttonRow)) } == true) {
                castChips.firstOrNull()?.requestFocus()
                return true
            } else if (!infoOpen && !similarOpen && !filmographyOpen && event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN &&
                currentFocus?.let { isInside(it, hero) } == true &&
                (if (type == "series") ::guide.isInitialized else ::similarOverlay.isInitialized)) {
                if (type == "series") showGuide() else showSimilar()
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onBackPressed() {
        when {
            filmographyOpen -> hideFilmography()
            infoOpen -> hideInfo()
            guideOpen -> hideGuide()
            similarOpen -> hideSimilar()
            else -> super.onBackPressed()
        }
    }

    private companion object {
        const val PANE_SHADE = 0.55f // the episode panel's frost, darkened to the mock's smoked glass
        const val GUIDE_ART = 0.6f // the film's brightness behind the episode guide
        const val STARTED = 60_000L // ms watched before a title counts as started
    }
}
