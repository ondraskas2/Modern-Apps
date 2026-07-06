package com.vayunmathur.watch.watch.ui

import android.app.Activity
import android.os.Bundle
import com.vayunmathur.watch.watch.service.ExerciseService

/**
 * Transparent (Theme.NoDisplay) trampoline that lets the Tile drive the exercise
 * session: Tiles can only fire a [android.content.Intent] (LaunchAction), not
 * call a Service, so PAUSE/RESUME/STOP buttons launch this activity, which
 * forwards to [ExerciseService] and immediately finishes.
 */
class ExerciseControlActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        when (intent?.getStringExtra(EXTRA_ACTION)) {
            ACTION_PAUSE -> ExerciseService.pause(this)
            ACTION_RESUME -> ExerciseService.resume(this)
            ACTION_STOP -> ExerciseService.stop(this)
        }
        finish()
    }

    companion object {
        const val EXTRA_ACTION = "action"
        const val ACTION_PAUSE = "PAUSE"
        const val ACTION_RESUME = "RESUME"
        const val ACTION_STOP = "STOP"
    }
}
