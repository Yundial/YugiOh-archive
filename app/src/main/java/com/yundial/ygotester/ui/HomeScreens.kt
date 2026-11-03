package com.yundial.ygotester.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Style
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yundial.ygotester.data.CardRepository
import com.yundial.ygotester.data.DeckStore
import kotlinx.coroutines.launch

@Composable
fun SetupScreen(firstRun: Boolean, onDone: () -> Unit, onBack: (() -> Unit)?) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var latest by remember { mutableStateOf(CardRepository.latestData) }
    var running by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    var progress by remember { mutableFloatStateOf(0f) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize()) {
        TopBar(if (firstRun) "Benvenuto" else "Aggiorna dati carte", if (running) null else onBack)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("YGO Tester", style = MaterialTheme.typography.headlineMedium, color = Gold, fontWeight = FontWeight.Bold)
            Text(
                "Per funzionare l'app scarica una volta sola:\n" +
                    "• il database delle regole e degli effetti (EDOPro, circa 8 MB)\n" +
                    "• nomi e testi delle carte in italiano (YGOPRODeck, circa 20 MB)\n\n" +
                    "Gli script degli effetti e le immagini si scaricano quando servono e restano salvati sul telefono.",
                style = MaterialTheme.typography.bodyMedium
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = latest, onCheckedChange = { latest = it }, enabled = !running)
                Column {
                    Text("Usa la versione più recente delle carte", style = MaterialTheme.typography.bodyMedium)
                    Text("Include le uscite più nuove, ma potrebbe non essere compatibile con il motore dell'app. " +
                        "Lascia spento se non ti serve.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }
            }
            if (running) {
                Text(msg)
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            }
            error?.let { Text(it, color = OppColor) }
            Button(
                enabled = !running,
                onClick = {
                    running = true; error = null
                    scope.launch {
                        val err = CardRepository.downloadAll(ctx, latest) { m, f -> msg = m; progress = f }
                        running = false
                        if (err == null) onDone() else error = err
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (CardRepository.isReady()) "Scarica di nuovo" else "Scarica i dati") }
            if (!firstRun && !running && CardRepository.isReady()) {
                OutlinedButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Lascia così") }
            }
        }
    }
}

@Composable
fun HomeScreen(onPlay: () -> Unit, onDecks: () -> Unit, onUpdate: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(32.dp))
        Text("YGO Tester", fontSize = 36.sp, fontWeight = FontWeight.Bold, color = Gold)
        Text("Prova i tuoi mazzi contro un avversario virtuale", textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium, color = Color.LightGray)
        Spacer(Modifier.height(24.dp))
        HomeButton(Icons.Default.PlayArrow, "Gioca una partita di prova", "Scegli mazzo, difficoltà e avversario", onPlay)
        HomeButton(Icons.Default.Style, "I miei mazzi", "${DeckStore.decks.size} mazzi in archivio", onDecks)
        HomeButton(Icons.Default.CloudDownload, "Dati delle carte", "Aggiorna database e testi", onUpdate)
    }
}

@Composable
private fun HomeButton(icon: ImageVector, title: String, sub: String, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = PanelLight)
    ) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = Gold, modifier = Modifier.size(32.dp))
            Column(Modifier.padding(start = 16.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(sub, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            }
        }
    }
}
