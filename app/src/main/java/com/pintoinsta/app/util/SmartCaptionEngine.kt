package com.pintoinsta.app.util

import java.util.Locale

/**
 * Intelligent Caption & Hashtag Engine for CrossAIStudio.
 * Transforms raw Pinterest titles and keywords into engaging, algorithm-optimized Instagram posts.
 * Generates tailored niche hashtags and supports posting them directly as the first comment.
 */
object SmartCaptionEngine {

    data class PreparedCaptionResult(
        val mainCaption: String,
        val firstComment: String? = null,
        val hashtagsUsed: List<String> = emptyList()
    )

    private val NICHE_HASHTAGS: Map<String, List<String>> = mapOf(
        "aesthetic" to listOf(
            "#aesthetic", "#aesthetics", "#vibe", "#mood", "#aestheticfeed",
            "#aestheticvideos", "#moodboard", "#pinterestinspired", "#pinterestaesthetic",
            "#visualsoflife", "#vibes", "#explorepage"
        ),
        "interior" to listOf(
            "#interiordesign", "#homedecor", "#interiorinspo", "#homedesign",
            "#homeinspiration", "#interiordecor", "#architecture", "#roomdecor",
            "#modernhome", "#dreamhome", "#decorinspiration", "#minimalisthome"
        ),
        "fashion" to listOf(
            "#outfitinspo", "#fashionstyle", "#styleinspiration", "#ootd",
            "#outfitideas", "#streetstyle", "#aestheticoutfit", "#fashioninspo",
            "#fitcheck", "#styleinspo", "#pinterestfashion", "#aestheticfashion"
        ),
        "fitness" to listOf(
            "#fitnessmotivation", "#workoutroutine", "#fitnessgoals", "#gymmotivation",
            "#healthylifestyle", "#fitspo", "#training", "#workoutinspo",
            "#wellness", "#gymlife", "#bodygoals"
        ),
        "food" to listOf(
            "#foodporn", "#foodie", "#recipeideas", "#homecooking",
            "#foodstagram", "#instafood", "#yummy", "#healthyrecipes",
            "#comfortfood", "#culinary", "#tasty"
        ),
        "luxury" to listOf(
            "#luxurylifestyle", "#luxury", "#billionaire", "#successmindset",
            "#wealth", "#mindset", "#motivation", "#luxurylife",
            "#millionairemindset", "#elegance", "#dreamlife"
        ),
        "travel" to listOf(
            "#travelgram", "#wanderlust", "#travelphotography", "#explorepage",
            "#naturephotography", "#beautifuldestinations", "#travelinspo",
            "#adventuretime", "#bucketlist", "#roamtheplanet"
        ),
        "quotes" to listOf(
            "#quotesdaily", "#quoteoftheday", "#deepquotes", "#relatablequotes",
            "#lifequotes", "#mindsetquotes", "#motivationalquotes",
            "#inspirationalquotes", "#positivevibes", "#wisdom"
        ),
        "tech" to listOf(
            "#techgadgets", "#setupinspiration", "#desksetup", "#workspace",
            "#technology", "#gamingsetup", "#minimalsetup", "#cleansetup",
            "#techlife", "#gadgetlover"
        ),
        "art" to listOf(
            "#artreels", "#artoftheday", "#illustration", "#creative",
            "#digitalart", "#sketch", "#designinspiration", "#artistsoninstagram",
            "#contemporaryart", "#visualart"
        ),
        "wallpaper" to listOf(
            "#wallpaper", "#wallpapers", "#aestheticwallpaper", "#phonewallpaper",
            "#lockscreen", "#hdwallpapers", "#backgrounds", "#visualart"
        )
    )

    private val GENERAL_DISCOVERY_TAGS = listOf(
        "#explorepage", "#fyp", "#viral", "#trending", "#instadaily", "#reelsinstagram"
    )

    /**
     * Prepares an optimized caption and optional first comment block.
     *
     * @param rawTitle The title of the pin (or fallback text).
     * @param description Pin description if available.
     * @param searchTerm The search keyword used to discover the pin.
     * @param isSmartCaptionEnabled Whether to format hooks, prompts, and hashtags.
     * @param isFirstCommentEnabled If true, hashtags are moved to first comment.
     * @param isCreditAttributionEnabled If true, appends standard credit disclaimer.
     */
    fun buildCaption(
        rawTitle: String?,
        description: String? = null,
        searchTerm: String? = null,
        isSmartCaptionEnabled: Boolean = true,
        isFirstCommentEnabled: Boolean = true,
        isCreditAttributionEnabled: Boolean = true
    ): PreparedCaptionResult {
        val baseText = rawTitle?.takeIf { it.isNotBlank() }
            ?: description?.takeIf { it.isNotBlank() }
            ?: searchTerm?.takeIf { it.isNotBlank() }
            ?: "Daily Inspiration"

        val cleanedBase = InstagramCaptionUtils.cleanAndFormatCaption(baseText)

        if (!isSmartCaptionEnabled) {
            // Raw caption mode
            val finalCaption = if (isCreditAttributionEnabled) {
                appendCreditFooter(cleanedBase)
            } else {
                cleanedBase
            }
            return PreparedCaptionResult(mainCaption = finalCaption, firstComment = null)
        }

        // 1. Build Hook / Headline
        val hookTitle = formatHookTitle(cleanedBase)

        // 2. Build Call-To-Action (CTA) Prompt
        val ctaPrompt = selectCallToAction(cleanedBase, searchTerm)

        // 3. Generate Targeted Hashtags
        val hashtags = generateHashtags(cleanedBase, description, searchTerm)
        val hashtagString = hashtags.joinToString(" ")

        // 4. Build Main Caption Body
        val sb = StringBuilder()
        sb.append(hookTitle)
        sb.append("\n\n")
        sb.append(ctaPrompt)

        if (isCreditAttributionEnabled) {
            sb.append("\n\n")
            sb.append("──────────────\n")
            sb.append("📸 Source: Pinterest | Credit to respective owner\n")
            sb.append("✉️ DM for credit or removal")
        }

        return if (isFirstCommentEnabled && hashtagString.isNotBlank()) {
            // Hashtags go in the first comment for maximum aesthetic appeal & SEO
            PreparedCaptionResult(
                mainCaption = InstagramCaptionUtils.cleanAndFormatCaption(sb.toString()),
                firstComment = hashtagString,
                hashtagsUsed = hashtags
            )
        } else {
            // Hashtags appended to the main caption
            if (hashtagString.isNotBlank()) {
                sb.append("\n\n.\n.\n.\n")
                sb.append(hashtagString)
            }
            PreparedCaptionResult(
                mainCaption = InstagramCaptionUtils.cleanAndFormatCaption(sb.toString()),
                firstComment = null,
                hashtagsUsed = hashtags
            )
        }
    }

    private fun formatHookTitle(text: String): String {
        // Strip out existing hashtags from the title
        val withoutTags = text.replace(Regex("#[A-Za-z0-9_]+"), "").trim()
        val capitalized = withoutTags.split(" ").joinToString(" ") { word ->
            word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
        }
        return "✨ $capitalized ✨"
    }

    private fun selectCallToAction(title: String, searchTerm: String?): String {
        val combined = "$title ${searchTerm.orEmpty()}".lowercase(Locale.ROOT)
        return when {
            combined.contains("quote") || combined.contains("mindset") || combined.contains("motivation") ->
                "💭 Drop a \"100\" if this resonates with you. Save for daily reminder! 📌"
            combined.contains("recipe") || combined.contains("food") ->
                "🍽️ Tag someone who needs to make this! Save this recipe for later 📌"
            combined.contains("outfit") || combined.contains("fashion") || combined.contains("style") ->
                "Rate this look from 1-10 in the comments below! 👇 Save for outfit inspo 📌"
            combined.contains("decor") || combined.contains("home") || combined.contains("room") || combined.contains("interior") ->
                "Would you live here? Yes or No? 👇 Double tap & save for your dream home inspo! 📌"
            combined.contains("workout") || combined.contains("gym") || combined.contains("fitness") ->
                "🔥 Save this workout for your next session & tag your workout partner! 💪"
            else ->
                "Save this post for later inspiration & share with a friend who needs to see this! 📌"
        }
    }

    private fun appendCreditFooter(text: String): String {
        return "$text\n\n──────────────\n📸 Source: Pinterest | Credit to respective owner\n✉️ DM for credit or removal"
    }

    /**
     * Generates 15-25 targeted hashtags based on title, description, and keywords.
     */
    fun generateHashtags(title: String, description: String?, searchTerm: String?): List<String> {
        val combined = "$title ${description.orEmpty()} ${searchTerm.orEmpty()}".lowercase(Locale.ROOT)
        val selectedTags = linkedSetOf<String>()

        // 1. Detect Niche matches
        var nicheFound = false
        for ((nicheKey, tags) in NICHE_HASHTAGS) {
            if (combined.contains(nicheKey)) {
                selectedTags.addAll(tags.shuffled().take(8))
                nicheFound = true
            }
        }

        // Secondary keyword scan if direct niche key didn't trigger
        if (!nicheFound) {
            when {
                combined.contains("kitchen") || combined.contains("bedroom") || combined.contains("living") || combined.contains("house") ->
                    selectedTags.addAll(NICHE_HASHTAGS["interior"] ?: emptyList())
                combined.contains("dress") || combined.contains("wear") || combined.contains("shoes") || combined.contains("cloth") ->
                    selectedTags.addAll(NICHE_HASHTAGS["fashion"] ?: emptyList())
                combined.contains("healthy") || combined.contains("diet") || combined.contains("cook") ->
                    selectedTags.addAll(NICHE_HASHTAGS["food"] ?: emptyList())
                combined.contains("money") || combined.contains("car") || combined.contains("business") ->
                    selectedTags.addAll(NICHE_HASHTAGS["luxury"] ?: emptyList())
                combined.contains("nature") || combined.contains("beach") || combined.contains("mountain") || combined.contains("trip") ->
                    selectedTags.addAll(NICHE_HASHTAGS["travel"] ?: emptyList())
                else ->
                    selectedTags.addAll(NICHE_HASHTAGS["aesthetic"] ?: emptyList())
            }
        }

        // 2. Generate dynamic tags from search term
        if (!searchTerm.isNullOrBlank()) {
            val cleanKw = searchTerm.replace(Regex("[^a-zA-Z0-9 ]"), "").trim()
            val words = cleanKw.split(Regex("\\s+")).filter { it.length > 2 }
            if (words.isNotEmpty()) {
                val fullCompound = words.joinToString("")
                selectedTags.add("#$fullCompound")
                selectedTags.add("#${fullCompound}inspo")
                for (w in words) {
                    selectedTags.add("#$w")
                }
            }
        }

        // 3. Add general high-reach discovery tags
        selectedTags.addAll(GENERAL_DISCOVERY_TAGS)

        // Return between 15 and 25 deduplicated hashtags
        return selectedTags.filter { it.startsWith("#") && it.length > 2 }.take(25)
    }
}

