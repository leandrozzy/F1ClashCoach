package com.leandro.f1clashcoach.ui

import android.Manifest
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Newspaper
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.IntentCompat
import com.leandro.f1clashcoach.CoachApp
import com.leandro.f1clashcoach.capture.CoachAccessibilityService
import com.leandro.f1clashcoach.capture.CoachCaptureService
import com.leandro.f1clashcoach.capture.LiveState

enum class Tab(val label: String, val icon: ImageVector) {
    HOME("Início", Icons.Filled.Home),
    GARAGE("Garagem", Icons.Filled.DirectionsCar),
    EVOLVE("Evoluir", Icons.AutoMirrored.Filled.TrendingUp),
    RACE("Corrida", Icons.Filled.Flag),
    CLUB("Clube", Icons.Filled.Groups),
}

enum class SubScreen(val title: String) { NEWS("Notícias e guias"), SETTINGS("Ajustes"), AI("Coach IA") }

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()
    private val openAiTab = mutableStateOf(false)
    private val a11yGuide = mutableStateOf(false)

    private val projectionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val data = res.data
        if (res.resultCode == RESULT_OK && data != null) {
            CoachCaptureService.start(this, res.resultCode, data)
            launchF1Clash()
        } else {
            Toast.makeText(this, "Sem a permissão de captura o Coach não consegue ler a tela.", Toast.LENGTH_LONG).show()
        }
    }

    private val askNotif = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val notifLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { requestProjection() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleShare(intent)
        // Avisos no celular (caixote, eventos acabando, clube): pede a permissão uma vez.
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            askNotif.launch(Manifest.permission.POST_NOTIFICATIONS)
        setContent {
            CoachTheme {
                AppRoot(vm, openAiTab = openAiTab.value, onAiTabOpened = { openAiTab.value = false },
                    onStartCoach = ::startCoach, onStopCoach = { CoachCaptureService.stop(this) }, onOpenGame = ::launchF1Clash,
                    onOpenUrl = { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it))) })
                if (a11yGuide.value) AccessibilityGuide(
                    onOpenAccessibility = { a11yGuide.value = false; startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                    onOpenAppInfo = { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) },
                    onDismiss = { a11yGuide.value = false },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    /** Print compartilhado da galeria → vai direto para o Coach IA. */
    private fun handleShare(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND) {
            IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let {
                vm.pendingImage.value = it
                openAiTab.value = true
            }
        }
    }

    private fun startCoach() {
        // Android 11+: leitura pela acessibilidade (igual ao OSM Coach), sem pedir gravação de tela toda vez.
        if (CoachAccessibilityService.supported) {
            if (CoachAccessibilityService.isEnabled(this)) launchF1Clash() else a11yGuide.value = true
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Permita \"Sobrepor a outros apps\" para o F1 Clash Coach e toque em Iniciar de novo.", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        if (Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) else requestProjection()
    }

    private fun requestProjection() {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        projectionLauncher.launch(mpm.createScreenCaptureIntent())
    }

    private fun launchF1Clash() {
        val i = packageManager.getLaunchIntentForPackage(CoachApp.F1_CLASH_PACKAGE)
        if (i != null) startActivity(i)
        else startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=${CoachApp.F1_CLASH_PACKAGE}")))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(
    vm: MainViewModel,
    openAiTab: Boolean,
    onAiTabOpened: () -> Unit,
    onStartCoach: () -> Unit,
    onStopCoach: () -> Unit,
    onOpenGame: () -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
    var sub by rememberSaveable { mutableStateOf<SubScreen?>(null) }
    val toast by vm.toast.collectAsState()
    val ctx = androidx.compose.ui.platform.LocalContext.current

    LaunchedEffect(openAiTab) { if (openAiTab) { sub = SubScreen.AI; onAiTabOpened() } }
    LaunchedEffect(toast) { toast?.let { Toast.makeText(ctx, it, Toast.LENGTH_LONG).show(); vm.consumeToast() } }
    BackHandler(enabled = sub != null || tab != Tab.HOME) { if (sub != null) sub = null else tab = Tab.HOME }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(sub?.title ?: if (tab == Tab.HOME) "🏁 F1 Clash Coach" else tab.label, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) },
                colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(containerColor = Navy),
                navigationIcon = {
                    if (sub != null) IconButton(onClick = { sub = null }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Voltar") }
                },
                actions = {
                    IconButton(onClick = { sub = SubScreen.AI }) { Icon(Icons.Filled.AutoAwesome, "Perguntar à IA") }
                    IconButton(onClick = { sub = SubScreen.NEWS }) { Icon(Icons.Filled.Newspaper, "Notícias") }
                    IconButton(onClick = { sub = SubScreen.SETTINGS }) { Icon(Icons.Filled.Settings, "Ajustes") }
                },
            )
        },
        bottomBar = {
            NavigationBar(containerColor = Navy) {
                Tab.entries.forEach { t ->
                    NavigationBarItem(selected = sub == null && tab == t, onClick = { tab = t; sub = null },
                        icon = { Icon(t.icon, t.label) }, label = { Text(t.label, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) },
                        colors = androidx.compose.material3.NavigationBarItemDefaults.colors(
                            selectedIconColor = Ink, selectedTextColor = Yellow, indicatorColor = Yellow,
                            unselectedIconColor = Muted, unselectedTextColor = Muted,
                        ))
                }
            }
        },
    ) { padding ->
        val m = Modifier.padding(padding)
        val goAi = { sub = SubScreen.AI }
        when (sub) {
            SubScreen.NEWS -> NewsScreen(vm, m, onOpenUrl, goAi)
            SubScreen.SETTINGS -> SettingsScreen(vm, m)
            SubScreen.AI -> AiScreen(vm, m, onOpenUrl)
            null -> when (tab) {
                Tab.HOME -> HomeScreen(vm, m, LiveState.status.collectAsState().value, onStartCoach, onStopCoach, onOpenGame, { tab = it; sub = null }, onOpenUrl,
                    onOpenSettings = { sub = SubScreen.SETTINGS }, onOpenClub = { tab = Tab.CLUB; sub = null }, goAi = goAi)
                Tab.GARAGE -> GarageScreen(vm, m)
                Tab.RACE -> RaceScreen(vm, m, goAi)
                Tab.EVOLVE -> EvolveScreen(vm, m, goAi)
                Tab.CLUB -> ClubScreen(vm, m, goAi)
            }
        }
    }
}

@Composable
fun AccessibilityGuide(onOpenAccessibility: () -> Unit, onOpenAppInfo: () -> Unit, onDismiss: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ligar a leitura do jogo") },
        text = {
            androidx.compose.foundation.layout.Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                SetupStep(1, false, "Abra a Acessibilidade", "Toque no botão abaixo.", null) {}
                SetupStep(2, false, "Apps instalados", "Pode se chamar \"Serviços baixados\" ou \"Apps baixados\".", null) {}
                SetupStep(3, false, "F1 Clash Coach — leitura do jogo", "Ative a chave e confirme em Permitir.", null) {}
                Text("Se o Android disser \"Configuração restrita\": toque em Info do app › menu ⋮ › Permitir configurações restritas e tente de novo.",
                    fontSize = 12.sp, color = Warn)
                Text("Depois é só abrir o F1 Clash: o painel aparece sozinho e some quando você sai do jogo.", fontSize = 12.sp, color = Muted)
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = onOpenAccessibility) { Text("Abrir Acessibilidade") } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onOpenAppInfo) { Text("Info do app") } },
    )
}
