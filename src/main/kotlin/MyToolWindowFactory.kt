package com.example

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JPanel

class MyToolWindowFactory : ToolWindowFactory {
    override fun shouldBeAvailable(project: Project) = true

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val myToolWindow = ReasonableVulkanToolWindow(project, toolWindow.disposable)
        val content = ContentFactory.getInstance().createContent(myToolWindow.getContent(), null, false)
        toolWindow.contentManager.addContent(content)
    }

    class ReasonableVulkanToolWindow(private val project: Project, parentDisposable: Disposable? = null) : Disposable {
        private val assetListModel = DefaultListModel<String>()
        private val activityListModel = DefaultListModel<String>()

        private val serverStatusLabel = JBLabel()
        private val playStateLabel = JBLabel()

        private val playButton = JButton("Play")
        private val pauseButton = JButton("Pause")
        private val stopButton = JButton("Stop")

        private val service = ReasonableVulkanService.getInstance()

        private val mainPanel = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            border = JBUI.Borders.empty(8)

            service.ensureStarted()

            fun updateStatus() {
                val running = service.serverRunning
                val port = service.serverPort
                serverStatusLabel.text = if (running) {
                    "<html><b>Bridge Server:</b> <font color='green'>Running</font> (Port: $port)</html>"
                } else {
                    "<html><b>Bridge Server:</b> <font color='red'>Stopped</font></html>"
                }

                val isPlaying = service.isPlaying
                val isPaused = service.isPaused

                playStateLabel.text = when {
                    !isPlaying -> "<html><b>Engine State:</b> <font color='gray'>STOPPED</font></html>"
                    isPaused -> "<html><b>Engine State:</b> <font color='orange'>PAUSED</font></html>"
                    else -> "<html><b>Engine State:</b> <font color='green'>PLAYING</font></html>"
                }

                playButton.text = if (isPlaying && !isPaused) "Playing" else "Play"
                pauseButton.text = if (isPaused) "Resume" else "Pause"
                pauseButton.isEnabled = isPlaying
                stopButton.isEnabled = isPlaying

                assetListModel.clear()
                for (file in service.openedFilesHistory) {
                    assetListModel.addElement(file)
                }

                activityListModel.clear()
                for (entry in service.activityHistory) {
                    activityListModel.addElement(entry)
                }
            }

            // Top Server Header Panel
            val topHeaderPanel = JPanel(BorderLayout()).apply {
                add(serverStatusLabel, BorderLayout.WEST)

                val serverButtons = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
                    val restartButton = JButton("Restart Server").apply {
                        addActionListener {
                            service.restartServer()
                            updateStatus()
                        }
                    }
                    val refreshButton = JButton("Refresh").apply {
                        addActionListener {
                            updateStatus()
                        }
                    }
                    add(restartButton)
                    add(refreshButton)
                }
                add(serverButtons, BorderLayout.EAST)
            }

            // Engine Controls Panel
            val controlPanel = JPanel(BorderLayout()).apply {
                border = JBUI.Borders.empty(6, 0)

                val statusBox = JPanel(FlowLayout(FlowLayout.LEFT, 0, 2)).apply {
                    add(playStateLabel)
                }

                val actionsPanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 4)).apply {
                    playButton.apply {
                        addActionListener {
                            service.play()
                            updateStatus()
                        }
                    }
                    pauseButton.apply {
                        addActionListener {
                            service.togglePause()
                            updateStatus()
                        }
                    }
                    stopButton.apply {
                        addActionListener {
                            service.stop()
                            updateStatus()
                        }
                    }

                    add(playButton)
                    add(pauseButton)
                    add(stopButton)
                }

                add(statusBox, BorderLayout.NORTH)
                add(actionsPanel, BorderLayout.CENTER)
            }

            val topContainer = JPanel(BorderLayout()).apply {
                add(topHeaderPanel, BorderLayout.NORTH)
                add(controlPanel, BorderLayout.CENTER)
            }

            add(topContainer, BorderLayout.NORTH)

            // Tabs for History and Opened Assets
            val tabbedPane = JBTabbedPane()

            // Tab 1: Activity Log
            val activityList = JBList(activityListModel)
            val activityTab = JPanel(BorderLayout()).apply {
                border = JBUI.Borders.emptyTop(4)
                add(JBScrollPane(activityList), BorderLayout.CENTER)

                val bottomBar = JPanel(FlowLayout(FlowLayout.RIGHT)).apply {
                    val clearActivityBtn = JButton("Clear Log").apply {
                        addActionListener {
                            service.activityHistory.clear()
                            updateStatus()
                        }
                    }
                    add(clearActivityBtn)
                }
                add(bottomBar, BorderLayout.SOUTH)
            }
            tabbedPane.addTab("Activity Log", activityTab)

            // Tab 2: Opened Assets
            val assetList = JBList(assetListModel).apply {
                addListSelectionListener {
                    val selected = selectedValue
                    if (selected != null && !it.valueIsAdjusting) {
                        service.openFileInIde(selected)
                    }
                }
            }
            val assetTab = JPanel(BorderLayout()).apply {
                border = JBUI.Borders.emptyTop(4)
                add(JBScrollPane(assetList), BorderLayout.CENTER)

                val bottomBar = JPanel(FlowLayout(FlowLayout.RIGHT)).apply {
                    val clearAssetsBtn = JButton("Clear Assets").apply {
                        addActionListener {
                            service.openedFilesHistory.clear()
                            updateStatus()
                        }
                    }
                    add(clearAssetsBtn)
                }
                add(bottomBar, BorderLayout.SOUTH)
            }
            tabbedPane.addTab("Opened Assets", assetTab)

            add(tabbedPane, BorderLayout.CENTER)

            // Register state listener
            service.addStateChangeListener {
                updateStatus()
            }

            updateStatus()
        }

        init {
            if (parentDisposable != null) {
                Disposer.register(parentDisposable, this)
            }
        }

        fun getContent(): JBPanel<*> = mainPanel

        override fun dispose() {
            // Cleanup on tool window disposal
        }
    }
}
