package com.johansouplis.kanban

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.johansouplis.kanban.ui.KanbanApp
import com.johansouplis.kanban.ui.KanbanTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            KanbanTheme {
                KanbanApp()
            }
        }
    }
}
