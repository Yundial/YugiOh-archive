package com.yundial.ygotester.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import com.yundial.ygotester.data.CardRepository
import com.yundial.ygotester.data.Net
import com.yundial.ygotester.data.Texts

val Gold = Color(0xFFE0B03C)
val Ink = Color(0xFF14121C)
val Panel = Color(0xFF1F1B2B)
val PanelLight = Color(0xFF2B2640)
val MineColor = Color(0xFF4FA3FF)
val OppColor = Color(0xFFFF6B6B)

private val Scheme = darkColorScheme(
    primary = Gold,
    onPrimary = Color(0xFF231A00),
    secondary = Color(0xFF9F8CFF),
    background = Ink,
    surface = Panel,
    surfaceVariant = PanelLight,
    onBackground = Color(0xFFEDE7F6),
    onSurface = Color(0xFFEDE7F6),
)

@Composable
fun YgoTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, content = content)
}

/** Barra in alto con titolo e tasto indietro */
@Composable
fun TopBar(title: String, onBack: (() -> Unit)?, actions: @Composable () -> Unit = {}) {
    Surface(color = Panel) {
        Row(
            Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Indietro") }
            } else Spacer(Modifier.width(12.dp))
            Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f))
            actions()
        }
    }
}

/** Retro della carta */
@Composable
fun CardBack(modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(3.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF6B3E1F), Color(0xFF3A1F0C))))
            .border(1.dp, Color(0xFF8C5A2B), RoundedCornerShape(3.dp)),
        contentAlignment = Alignment.Center
    ) {
        Box(Modifier.fillMaxSize(0.45f).clip(RoundedCornerShape(50)).background(Color(0xFF2A160A)))
    }
}

private fun imageId(code: Int): Int {
    val c = CardRepository.get(code) ?: return code
    return if (c.alias != 0 && kotlin.math.abs(c.alias - c.id) < 20) c.alias else c.id
}

/** Immagine della carta (scaricata da YGOPRODeck e tenuta in cache). code = 0 → carta coperta */
@Composable
fun CardImage(code: Int, modifier: Modifier = Modifier, big: Boolean = false) {
    if (code == 0) {
        CardBack(modifier)
        return
    }
    val id = imageId(code)
    SubcomposeAsyncImage(
        model = if (big) Net.imageBig(id) else Net.imageSmall(id),
        contentDescription = CardRepository.name(code),
        modifier = modifier.clip(RoundedCornerShape(3.dp)),
        contentScale = ContentScale.Crop,
        loading = { Box(Modifier.fillMaxSize().background(PanelLight)) },
        error = { NameTile(code) },
    )
}

@Composable
private fun NameTile(code: Int) {
    val c = CardRepository.get(code)
    val bg = when {
        c == null -> PanelLight
        c.isSpell -> Color(0xFF1D7A6C)
        c.isTrap -> Color(0xFF8E2C6B)
        c.isExtra -> Color(0xFF4B3A7A)
        else -> Color(0xFF8A5A22)
    }
    Box(Modifier.fillMaxSize().background(bg).padding(2.dp), contentAlignment = Alignment.Center) {
        Text(CardRepository.name(code), fontSize = 8.sp, lineHeight = 9.sp, textAlign = TextAlign.Center, color = Color.White,
            maxLines = 5, overflow = TextOverflow.Ellipsis)
    }
}

/** Scheda dettagliata della carta */
@Composable
fun CardDetailDialog(code: Int, onDismiss: () -> Unit, extraButtons: @Composable () -> Unit = {}) {
    val c = CardRepository.get(code)
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Chiudi") } },
        dismissButton = extraButtons,
        title = { Text(c?.name ?: "Carta", fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CardImage(code, Modifier.width(170.dp).aspectRatio(0.686f), big = true)
                }
                if (c != null) {
                    if (c.name != c.nameEn) Text(c.nameEn, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    Text(Texts.typeLine(c), style = MaterialTheme.typography.labelMedium, color = Gold)
                    val stat = Texts.statLine(c)
                    if (stat.isNotEmpty()) Text(stat, style = MaterialTheme.typography.labelMedium)
                    Text(c.desc, style = MaterialTheme.typography.bodyMedium)
                    Text("ID ${c.id}", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                } else {
                    Text("Carta non presente nel database.")
                }
            }
        }
    )
}
