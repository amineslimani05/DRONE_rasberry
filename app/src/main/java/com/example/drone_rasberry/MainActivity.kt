package com.example.drone_rasberry

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.drone_rasberry.ui.theme.DRONE_rasberryTheme
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DRONE_rasberryTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    InterfaceUtilisateurDrone()
                }
            }
        }
    }
}

@Composable
fun InterfaceUtilisateurDrone(modeleVue: DroneViewModel = viewModel()) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // --- EN-TÊTE ET STATUT ---
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "CONTRÔLEUR DRONE",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            
            Card(
                modifier = Modifier.padding(top = 8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (modeleVue.estArme) Color(0xFFE8F5E9) else Color(0xFFFFEBEE)
                )
            ) {
                Text(
                    text = if (modeleVue.estArme) "● SYSTÈME ARMÉ" else "○ SYSTÈME DÉSARMÉ",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    color = if (modeleVue.estArme) Color(0xFF2E7D32) else Color(0xFFC62828),
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // --- VISUALISATION UDP (Ce que voit la Raspberry) ---
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color.Black),
            shape = RoundedCornerShape(8.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = "FLUX UDP SORTANT (JSON) :",
                    color = Color.Green,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = modeleVue.dernierMessageJson,
                    color = Color.White,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 16.sp
                )
            }
        }

        // --- COMMANDES PRINCIPALES ---
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = { modeleVue.armerLeDrone() },
                modifier = Modifier.weight(1f).height(50.dp),
                enabled = !modeleVue.estArme,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("ARMER", fontWeight = FontWeight.Bold)
            }
            
            Button(
                onClick = { modeleVue.desarmerLeDrone() },
                modifier = Modifier.weight(1f).height(50.dp),
                enabled = modeleVue.estArme,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF757575)),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("DÉSARMER", fontWeight = FontWeight.Bold)
            }
        }

        // --- JOYSTICK ---
        Box(
            modifier = Modifier.weight(1f),
            contentAlignment = Alignment.Center
        ) {
            ComposantJoystick(
                auDeplacement = { x, y -> modeleVue.mettreAJourDeplacement(x, y) }
            )
        }

        // --- ARRÊT D'URGENCE ---
        Button(
            onClick = { modeleVue.arretUrgence() },
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color.Red),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("ARRÊT D'URGENCE", fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
        }
    }
}

@Composable
fun ComposantJoystick(auDeplacement: (Float, Float) -> Unit) {
    val tailleJoystick = 200.dp
    val rayonManche = 40.dp
    var positionManche by remember { mutableStateOf(Offset.Zero) }

    Box(
        modifier = Modifier
            .size(tailleJoystick)
            .clip(CircleShape)
            .background(Brush.radialGradient(listOf(Color(0xFFF5F5F5), Color(0xFFD6D6D6)))),
        contentAlignment = Alignment.Center
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDrag = { modification, vecteurDeplacement ->
                            modification.consume()
                            val nouvelleX = positionManche.x + vecteurDeplacement.x
                            val nouvelleY = positionManche.y + vecteurDeplacement.y
                            val distance = sqrt(nouvelleX * nouvelleX + nouvelleY * nouvelleY)
                            val rayonMax = size.width / 2
                            
                            if (distance <= rayonMax) {
                                positionManche = Offset(nouvelleX, nouvelleY)
                            } else {
                                val angle = atan2(nouvelleY, nouvelleX)
                                positionManche = Offset(cos(angle) * rayonMax, sin(angle) * rayonMax)
                            }
                            auDeplacement(positionManche.x / rayonMax, -(positionManche.y / rayonMax))
                        },
                        onDragEnd = {
                            positionManche = Offset.Zero
                            auDeplacement(0f, 0f)
                        }
                    )
                }
        ) {
            val centre = Offset(size.width / 2, size.height / 2)
            drawCircle(Color.LightGray, radius = size.width / 2, center = centre, style = Stroke(2f))
            drawCircle(
                color = Color(0xFF1976D2),
                radius = rayonManche.toPx(),
                center = Offset(centre.x + positionManche.x, centre.y + positionManche.y)
            )
        }
    }
}
