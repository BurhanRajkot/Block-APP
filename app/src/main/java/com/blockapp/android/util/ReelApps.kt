package com.blockapp.android.util

/**
 * The apps the reel counter watches, and the only ones. ReelTracker ignores every other package
 * outright: no scan, no swipe scoring, no bubble.
 *
 * It used to count in any app, on the shape of the screen alone, and that shape is not unique to
 * short video. A browser's full-screen image viewer, a gallery, or any page that snaps one item
 * at a time looks exactly like a reel pager, and the user saw image browsing in Chrome counted
 * as reels. Precision matters more than catching an unlisted app here, since a wrong count also
 * spends the daily reel limit and can close a viewer that was never a reel. An app missing from
 * this list is a one-line fix; a false count isn't fixable by the user at all.
 *
 * Also used by the onboarding quiz ([QUIZ_CHOICES]).
 */
object ReelApps {

    /** Apps whose short-form video viewer is counted. */
    val KNOWN: Set<String> = setOf(
        "com.instagram.android",
        "com.instagram.lite",
        "com.google.android.youtube",
        "app.revanced.android.youtube", // ReVanced
        "com.vanced.android.youtube", // Vanced
        "com.zhiliaoapp.musically", // TikTok
        "com.zhiliaoapp.musically.go", // TikTok Lite
        "com.ss.android.ugc.trill", // TikTok (Asia)
        "com.ss.android.ugc.aweme", // Douyin
        "com.snapchat.android",
        "com.facebook.katana",
        "com.facebook.lite",
        "com.reddit.frontpage",
        "com.twitter.android",
        "com.linkedin.android",
        "com.pinterest",
        "com.tumblr",
        "in.mohalla.video", // Moj
        "in.mohalla.sharechat", // ShareChat
        "com.eterno.shortvideos", // Josh
        "io.chingari.app", // Chingari
        "com.roposo.android", // Roposo
        "com.next.innovation.takatak", // MX TakaTak
        "video.like", // Likee
        "com.kwai.video", // Kwai
        "co.triller.droid", // Triller
    )

    /**
     * Apps that show photos in the same full-screen, swipe-through viewers as their videos
     * (Reddit's immersive feed, LinkedIn and X media viewers, Pinterest close-ups). In these a reel
     * only counts while a video player is actually on screen (ReelScreenScanner's video check),
     * or swiping through pictures would count exactly like the browser images did.
     */
    val NEEDS_VIDEO_EVIDENCE: Set<String> = setOf(
        "com.reddit.frontpage",
        "com.twitter.android",
        "com.linkedin.android",
        "com.pinterest",
        "com.tumblr",
    )

    /** Display name to the packages it covers, in the order the quiz shows them. */
    val QUIZ_CHOICES: List<Pair<String, List<String>>> = listOf(
        "Instagram" to listOf("com.instagram.android", "com.instagram.lite"),
        "YouTube Shorts" to listOf("com.google.android.youtube"),
        "TikTok" to listOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill"),
        "Snapchat" to listOf("com.snapchat.android"),
        "Facebook" to listOf("com.facebook.katana", "com.facebook.lite"),
        "Reddit" to listOf("com.reddit.frontpage"),
        "LinkedIn" to listOf("com.linkedin.android"),
    )
}
