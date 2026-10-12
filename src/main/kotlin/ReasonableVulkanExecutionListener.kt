package com.example

import com.intellij.execution.ExecutionListener
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment

class ReasonableVulkanExecutionListener : ExecutionListener {
    override fun processStarted(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler) {
        try {
            val configName = env.runProfile.name
            if (isEngineProcess(configName)) {
                ReasonableVulkanService.getInstance().play()
            }
        } catch (_: Exception) {}
    }

    override fun processTerminated(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler, exitCode: Int) {
        try {
            val configName = env.runProfile.name
            val service = ReasonableVulkanService.getInstance()
            if (service.isPlaying && isEngineProcess(configName)) {
                service.stop()
            }
        } catch (_: Exception) {}
    }

    private fun isEngineProcess(name: String): Boolean {
        if (name.isBlank()) return true
        return name.contains("ReasonableVulkan", ignoreCase = true) ||
                name.contains("Engine", ignoreCase = true) ||
                name.contains("Game", ignoreCase = true) ||
                name.contains("Play", ignoreCase = true) ||
                name.contains("App", ignoreCase = true) ||
                name.contains("Main", ignoreCase = true) ||
                name.contains("Debug", ignoreCase = true) ||
                name.contains("Release", ignoreCase = true) ||
                name.contains("Run", ignoreCase = true)
    }
}
