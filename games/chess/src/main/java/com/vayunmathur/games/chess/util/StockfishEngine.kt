package com.vayunmathur.games.chess.util

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import com.vayunmathur.games.chess.data.Board
import java.io.File

object StockfishEngine {
    val inputChannel = Channel<String>(Channel.UNLIMITED)
    val outputChannel = Channel<String>(Channel.UNLIMITED)
    private var engineStarted = false

    enum class Difficulty(val depth: Int, val skill: Int) {
        BEGINNER(8, 0),
        INTERMEDIATE(8, 5),
        ADVANCED(8, 12),
        GRANDMASTER(8, 20)
    }

    var difficulty: Difficulty = Difficulty.BEGINNER

    private external fun nativeInit()
    private external fun nativeSendCommand(command: String)
    private external fun nativeSetOutputCallback(callback: OutputCallback)

    interface OutputCallback {
        fun onOutput(line: String)
    }

    suspend fun nextMove(board: Board) {
        inputChannel.send("position fen ${board.toFen()}")
        inputChannel.send("setoption name Skill Level value ${difficulty.skill}")
        inputChannel.send("go depth ${difficulty.depth}")
    }

    fun start(context: Context) {
        if (engineStarted) return
        System.loadLibrary("stockfish")
        engineStarted = true

        nativeSetOutputCallback(object : OutputCallback {
            override fun onOutput(line: String) {
                Log.d("StockfishEngine", "Stockfish output: $line")
                outputChannel.trySend(line)
            }
        })

        nativeInit()

        val nnuePath = copyAssetToFiles(context, "nn-71d6d32cb962.nnue")

        CoroutineScope(Dispatchers.IO).launch {
            for (cmd in inputChannel) {
                Log.d("StockfishEngine", "Stockfish Input: $cmd")
                nativeSendCommand(cmd)
            }
        }

        CoroutineScope(Dispatchers.IO).launch {
            inputChannel.send("uci")
            inputChannel.send("setoption name EvalFile value $nnuePath")
            inputChannel.send("isready")
        }
    }

    private fun copyAssetToFiles(context: Context, fileName: String): String {
        val destFile = File(context.filesDir, fileName)
        if (!destFile.exists()) {
            context.assets.open(fileName).use { input ->
                destFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }
        return destFile.absolutePath
    }
}
