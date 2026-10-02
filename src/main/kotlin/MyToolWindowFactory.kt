package com.example

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
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
        val myToolWindow = ReasonableVulkanToolWindow(project)
        val content = ContentFactory.getInstance().createContent(myToolWindow.getContent(), null, false)
        toolWindow.contentManager.addContent(content)
    }

    class ReasonableVulkanToolWindow(private val project: Project) {
        private val listModel = DefaultListModel<String>()
        private val statusLabel = JBLabel()
        private val mainPanel = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            border = JBUI.Borders.empty(8)

            val service = ReasonableVulkanService.getInstance()
            service.ensureStarted()

            fun updateStatus() {
                val running = service.serverRunning
                val port = service.serverPort
                statusLabel.text = if (running) {
                    "<html><b>Bridge Server:</b> <font color='green'>Running</font> (Port: $port)</html>"
                } else {
                    "<html><b>Bridge Server:</b> <font color='red'>Stopped</font></html>"
                }

                listModel.clear()
                for (file in service.openedFilesHistory) {
                    listModel.addElement(file)
                }
            }

            val topPanel = JPanel(BorderLayout()).apply {
                add(statusLabel, BorderLayout.WEST)

                val buttonPanel = JPanel(FlowLayout(FlowLayout.RIGHT)).apply {
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
                    val clearButton = JButton("Clear List").apply {
                        addActionListener {
                            service.openedFilesHistory.clear()
                            updateStatus()
                        }
                    }
                    add(restartButton)
                    add(refreshButton)
                    add(clearButton)
                }
                add(buttonPanel, BorderLayout.EAST)
            }

            add(topPanel, BorderLayout.NORTH)

            val historyLabel = JBLabel("Recently Opened Assets from Engine:")
            val fileList = JBList(listModel).apply {
                addListSelectionListener {
                    val selected = selectedValue
                    if (selected != null && !it.valueIsAdjusting) {
                        service.openFileInIde(selected)
                    }
                }
            }

            val listPanel = JPanel(BorderLayout()).apply {
                border = JBUI.Borders.emptyTop(8)
                add(historyLabel, BorderLayout.NORTH)
                add(JBScrollPane(fileList), BorderLayout.CENTER)
            }

            add(listPanel, BorderLayout.CENTER)

            updateStatus()
        }

        fun getContent(): JBPanel<JBPanel<*>> = mainPanel
    }
}
