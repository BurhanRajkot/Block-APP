package com.blockapp.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.blockapp.android.ui.theme.Ember
import com.blockapp.android.ui.theme.EmberWash
import com.blockapp.android.util.ReelApps
import com.blockapp.android.util.ReelPlan
import kotlinx.coroutines.delay
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The onboarding quiz: five questions about the user's scrolling, then the plan ReelPlan builds
 * from them, which the user can start or skip. Shown on first launch (MainActivity checks
 * [ReelPlan.quizSeen]) and again from the plan screen to rebuild a plan.
 *
 * Starting a plan here can only ever tighten what is already in force (see ReelPlan's class doc),
 * so retaking the quiz with gentler answers is not a way to switch reels back on today.
 *
 * Answers live in rememberSaveable state, so a rotation or a trip to Settings mid-quiz keeps them.
 */
@Composable
fun QuizScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    var step by rememberSaveable { mutableIntStateOf(0) }
    var apps by rememberSaveable { mutableStateOf(setOf<String>()) }
    var reelsIndex by rememberSaveable { mutableIntStateOf(-1) }
    var minutesIndex by rememberSaveable { mutableIntStateOf(-1) }
    var moments by rememberSaveable { mutableStateOf(setOf<Int>()) }
    var goalIndex by rememberSaveable { mutableIntStateOf(-1) }

    fun answers() = ReelPlan.Answers(
        apps = apps,
        reelsPerDay = REEL_CHOICES.getOrNull(reelsIndex)?.second,
        minutesPerDay = MINUTE_CHOICES.getOrNull(minutesIndex)?.second ?: 60,
        moments = moments.mapNotNull { ReelPlan.Moment.entries.getOrNull(it) }.toSet(),
        goal = ReelPlan.Goal.entries.getOrElse(goalIndex) { ReelPlan.Goal.CUT_DOWN },
    )

    fun skip() {
        ReelPlan.markQuizSeen(context)
        onDone()
    }

    // Back walks back a question, and only leaves from the intro. The plan reveal goes back to
    // the last question rather than through the "building" pause.
    BackHandler(enabled = step > 0) { step = if (step == STEP_PLAN) STEP_GOAL else step - 1 }

    val canContinue = when (step) {
        STEP_APPS -> apps.isNotEmpty()
        STEP_REELS -> reelsIndex >= 0
        STEP_MINUTES -> minutesIndex >= 0
        STEP_MOMENTS -> moments.isNotEmpty()
        STEP_GOAL -> goalIndex >= 0
        else -> true
    }

    Column(Modifier.fillMaxSize()) {
        if (step in STEP_APPS..STEP_GOAL) {
            QuizTopBar(
                progress = step / STEP_GOAL.toFloat(),
                onBack = { step-- },
                onSkip = ::skip,
            )
        }
        AnimatedContent(
            targetState = step,
            transitionSpec = {
                val forward = targetState > initialState
                (slideInHorizontally(tween(300)) { if (forward) it / 3 else -it / 3 } + fadeIn(tween(220)))
                    .togetherWith(slideOutHorizontally(tween(300)) { if (forward) -it / 6 else it / 6 } + fadeOut(tween(150)))
            },
            modifier = Modifier.weight(1f),
            label = "quiz",
        ) { current ->
            when (current) {
                STEP_INTRO -> Intro(onStart = { step = STEP_APPS }, onSkip = ::skip)
                STEP_APPS -> Question(
                    title = "Where do the reels get you?",
                    subtitle = "Pick every app you lose time to. Short videos are counted in all the big ones either way.",
                ) {
                    (ReelApps.QUIZ_CHOICES.map { it.first } + OTHER_APP).forEach { name ->
                        ChoiceCard(
                            title = name,
                            selected = name in apps,
                            onClick = { apps = if (name in apps) apps - name else apps + name },
                        )
                    }
                }
                STEP_REELS -> Question(
                    title = "How many reels on a normal day?",
                    subtitle = "A guess is fine. Most people guess low, and the plan allows for that.",
                ) {
                    REEL_CHOICES.forEachIndexed { index, (label, _) ->
                        ChoiceCard(label, selected = reelsIndex == index, onClick = { reelsIndex = index })
                    }
                }
                STEP_MINUTES -> Question(
                    title = "And how long in those apps?",
                    subtitle = "Roughly, per day. Your real screen time shows up once usage access is on.",
                ) {
                    MINUTE_CHOICES.forEachIndexed { index, (label, _) ->
                        ChoiceCard(label, selected = minutesIndex == index, onClick = { minutesIndex = index })
                    }
                }
                STEP_MOMENTS -> Question(
                    title = "When is it hardest to stop?",
                    subtitle = "Pick all that apply. Mornings and late nights can become reel-free hours.",
                ) {
                    ReelPlan.Moment.entries.forEach { moment ->
                        val i = moment.ordinal
                        ChoiceCard(
                            title = moment.title,
                            selected = i in moments,
                            onClick = { moments = if (i in moments) moments - i else moments + i },
                        )
                    }
                }
                STEP_GOAL -> Question(
                    title = "What do you want?",
                    subtitle = "You can rebuild the plan any time. It can only get stricter on the day, though.",
                ) {
                    ReelPlan.Goal.entries.forEachIndexed { index, goal ->
                        ChoiceCard(
                            title = goal.title,
                            subtitle = goal.blurb,
                            selected = goalIndex == index,
                            onClick = { goalIndex = index },
                        )
                    }
                }
                STEP_BUILDING -> Building(onFinished = { step = STEP_PLAN })
                else -> PlanReveal(
                    answers = answers(),
                    onStart = { plan, quiet ->
                        ReelPlan.start(context, answers(), plan, quiet)
                        onDone()
                    },
                    onSkip = ::skip,
                )
            }
        }
        if (step in STEP_APPS..STEP_GOAL) {
            PrimaryButton(
                text = if (step == STEP_GOAL) "Build my plan" else "Continue",
                onClick = { step++ },
                enabled = canContinue,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = ScreenPadding, vertical = 16.dp),
            )
        }
    }
}

@Composable
private fun QuizTopBar(progress: Float, onBack: () -> Unit, onSkip: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 8.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
        }
        Meter(progress, MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f), height = 6.dp)
        TextButton(onClick = onSkip) { Text("Skip") }
    }
}

@Composable
private fun Intro(onStart: () -> Unit, onSkip: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = ScreenPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(40.dp))
        ReelStackArt(height = 240.dp)
        Spacer(Modifier.height(32.dp))
        Text(
            "Let's get your evenings back",
            style = MaterialTheme.typography.headlineLarge,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "Five quick questions about how you scroll. You get a plan that brings your reels " +
                "down a step at a time, and that follows you down when you beat it.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.weight(1f))
        Spacer(Modifier.height(28.dp))
        PrimaryButton("Start", onClick = onStart, modifier = Modifier.fillMaxWidth())
        TextButton(onClick = onSkip, modifier = Modifier.padding(vertical = 8.dp)) {
            Text("Skip for now")
        }
    }
}

@Composable
private fun Question(title: String, subtitle: String, choices: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = ScreenPadding),
    ) {
        Spacer(Modifier.height(20.dp))
        Text(title, style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { choices() }
        Spacer(Modifier.height(16.dp))
    }
}

/**
 * A short pause with the plan's art while "building". The plan is computed instantly; the pause
 * is there so the reveal reads as the answer to the questions rather than as the next page.
 */
@Composable
private fun Building(onFinished: () -> Unit) {
    var line by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        repeat(BUILD_LINES.size) {
            line = it
            delay(BUILD_LINE_MS)
        }
        onFinished()
    }
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = ScreenPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        StepsArt(height = 140.dp)
        Spacer(Modifier.height(28.dp))
        AnimatedContent(targetState = line, label = "building") { index ->
            Text(
                BUILD_LINES[index],
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun PlanReveal(
    answers: ReelPlan.Answers,
    onStart: (ReelPlan.Plan, ReelPlan.QuietHours?) -> Unit,
    onSkip: () -> Unit,
) {
    val plan = remember(answers) { ReelPlan.build(answers) }
    val suggestedQuiet = remember(answers) { ReelPlan.suggestedQuietHours(answers.moments) }
    var useQuiet by rememberSaveable { mutableStateOf(true) }
    val goalDay = plan.startDay.plusWeeks((plan.weeks - 1).toLong())
    // A reel plus the scroll to the next is about 20 seconds.
    val hoursBackPerWeek = (plan.baseline - plan.targetLimit) * REEL_SECONDS * 7 / 3600f

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = ScreenPadding),
    ) {
        Spacer(Modifier.height(28.dp))
        Eyebrow("Your plan", color = Ember)
        Spacer(Modifier.height(6.dp))
        Text(plan.goal.title, style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(20.dp))

        Panel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Eyebrow("From today")
                    Spacer(Modifier.height(4.dp))
                    Text("${plan.startLimit}", style = MaterialTheme.typography.displayMedium)
                    Text(
                        "reels a day, down from about ${plan.baseline}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Box(
                    Modifier
                        .clip(RoundedCornerShape(18.dp))
                        .background(MaterialTheme.colorScheme.primary)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Text(
                        if (plan.targetLimit == 0) "Goal: 0" else "Goal: ${plan.targetLimit}",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
            Spacer(Modifier.height(18.dp))
            BarChart(
                values = plan.steps.map { it.toLong().coerceAtLeast(0L) },
                color = Ember,
                highlight = 0,
                labels = List(plan.steps.size) { "W${it + 1}" },
                chartHeight = 100.dp,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "Reaches the goal by ${goalDay.format(DateTimeFormatter.ofPattern("d MMMM", Locale.getDefault()))}. " +
                    "If you watch fewer than planned for a few days, the limit follows you down early.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (suggestedQuiet != null) {
            Spacer(Modifier.height(12.dp))
            Panel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconTile(Icons.Outlined.Bedtime, tint = Ember, background = EmberWash)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Reel-free hours", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${suggestedQuiet.label()}, every day",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = useQuiet,
                        onCheckedChange = { useQuiet = it },
                        colors = SwitchDefaults.colors(checkedTrackColor = MaterialTheme.colorScheme.primary),
                    )
                }
            }
        }

        if (hoursBackPerWeek >= 1f) {
            Spacer(Modifier.height(12.dp))
            Panel {
                Text(
                    "About ${hoursBackPerWeek.toInt()} hours a week back",
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "at the goal, compared with ${plan.baseline} reels a day.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(24.dp))
        PrimaryButton(
            "Start my plan",
            onClick = { onStart(plan, suggestedQuiet.takeIf { useQuiet }) },
            modifier = Modifier.fillMaxWidth(),
        )
        TextButton(onClick = onSkip, modifier = Modifier.align(Alignment.CenterHorizontally).padding(vertical = 8.dp)) {
            Text("Not now")
        }
        Spacer(Modifier.height(8.dp))
    }
}

private const val STEP_INTRO = 0
private const val STEP_APPS = 1
private const val STEP_REELS = 2
private const val STEP_MINUTES = 3
private const val STEP_MOMENTS = 4
private const val STEP_GOAL = 5
private const val STEP_BUILDING = 6
private const val STEP_PLAN = 7

private const val OTHER_APP = "Something else"

/** Label and the estimate it stands for. Null is "no idea": ReelPlan estimates from time spent. */
private val REEL_CHOICES: List<Pair<String, Int?>> = listOf(
    "Under 30" to 20,
    "30 to 100" to 65,
    "100 to 200" to 150,
    "200 to 400" to 300,
    "More than 400" to 500,
    "No idea" to null,
)

private val MINUTE_CHOICES: List<Pair<String, Int>> = listOf(
    "Under 30 minutes" to 20,
    "30 minutes to an hour" to 45,
    "1 to 2 hours" to 90,
    "2 to 4 hours" to 180,
    "More than 4 hours" to 300,
)

private val BUILD_LINES = listOf(
    "Looking at how you scroll…",
    "Setting your first step…",
    "Picking your reel-free hours…",
)

/** Long enough to read each line, short enough that three of them don't feel like a wait. */
private const val BUILD_LINE_MS = 700L

/** One reel plus the swipe to the next, for the "hours back" estimate. */
private const val REEL_SECONDS = 20f
