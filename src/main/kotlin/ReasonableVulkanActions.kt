package com.example

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileEditor.FileDocumentManager

class ReasonableVulkanPlayAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        ReasonableVulkanService.getInstance().play()
    }

    override fun update(e: AnActionEvent) {
        val service = ReasonableVulkanService.getInstance()
        val isPlaying = service.isPlaying
        val isPaused = service.isPaused
        e.presentation.isVisible = true
        e.presentation.isEnabled = true
        e.presentation.text = if (isPlaying && !isPaused) "Playing Engine" else "Play Engine"
        e.presentation.description = if (isPlaying && !isPaused) "ReasonableVulkan engine is currently playing" else "Start or resume play mode in ReasonableVulkan"
        e.presentation.icon = AllIcons.Actions.Execute
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}

class ReasonableVulkanPauseAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        ReasonableVulkanService.getInstance().togglePause()
    }

    override fun update(e: AnActionEvent) {
        val service = ReasonableVulkanService.getInstance()
        val isPlaying = service.isPlaying
        val isPaused = service.isPaused
        e.presentation.isVisible = true
        e.presentation.isEnabled = isPlaying
        e.presentation.text = if (isPaused) "Resume Engine" else "Pause Engine"
        e.presentation.description = if (isPaused) "Resume play mode in ReasonableVulkan" else "Pause play mode in ReasonableVulkan"
        e.presentation.icon = if (isPaused) AllIcons.Actions.Resume else AllIcons.Actions.Pause
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}

class ReasonableVulkanStopAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        ReasonableVulkanService.getInstance().stop()
    }

    override fun update(e: AnActionEvent) {
        val service = ReasonableVulkanService.getInstance()
        val isPlaying = service.isPlaying
        e.presentation.isVisible = true
        e.presentation.isEnabled = isPlaying
        e.presentation.text = "Stop Engine"
        e.presentation.description = "Stop play mode in ReasonableVulkan"
        e.presentation.icon = AllIcons.Actions.Suspend
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}

class ReasonableVulkanTogglePlayAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        ReasonableVulkanService.getInstance().togglePlay()
    }

    override fun update(e: AnActionEvent) {
        val service = ReasonableVulkanService.getInstance()
        val isPlaying = service.isPlaying
        e.presentation.isVisible = true
        e.presentation.isEnabled = true
        e.presentation.text = if (isPlaying) "Stop Engine" else "Play Engine"
        e.presentation.description = if (isPlaying) "Stop play mode in ReasonableVulkan" else "Start play mode in ReasonableVulkan"
        e.presentation.icon = if (isPlaying) AllIcons.Actions.Suspend else AllIcons.Actions.Execute
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}
