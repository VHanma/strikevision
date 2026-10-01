from pathlib import Path

root = Path('qpsi_project')
path = root / 'app/src/main/java/com/aether/qpsicontact/MainActivity.kt'
text = path.read_text()

text = text.replace(
    'import androidx.lifecycle.compose.collectAsStateWithLifecycle\n',
    'import androidx.lifecycle.compose.collectAsStateWithLifecycle\n'
    'import androidx.core.view.WindowCompat\n'
    'import androidx.core.view.WindowInsetsCompat\n'
    'import androidx.core.view.WindowInsetsControllerCompat\n',
    1,
)

old_effect = '''    DisposableEffect(running) {
        val activity = context as? ComponentActivity
        val previous = activity?.window?.attributes?.screenBrightness ?: -1f
        if (running && activity != null) {
            activity.window.attributes = activity.window.attributes.apply { screenBrightness = 1f }
        }
        onDispose {
            if (activity != null) {
                activity.window.attributes = activity.window.attributes.apply { screenBrightness = previous }
            }
        }
    }
'''
new_effect = '''    DisposableEffect(running) {
        val activity = context as? ComponentActivity
        val previousBrightness = activity?.window?.attributes?.screenBrightness ?: -1f
        if (activity != null) {
            val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
            if (running) {
                activity.window.attributes = activity.window.attributes.apply { screenBrightness = 1f }
                WindowCompat.setDecorFitsSystemWindows(activity.window, false)
                controller.hide(WindowInsetsCompat.Type.systemBars())
                controller.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
        onDispose {
            if (activity != null) {
                activity.window.attributes = activity.window.attributes.apply {
                    screenBrightness = previousBrightness
                }
                WindowCompat.setDecorFitsSystemWindows(activity.window, true)
                WindowCompat.getInsetsController(activity.window, activity.window.decorView)
                    .show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }
'''
if old_effect not in text:
    raise SystemExit('brightness effect not found')
text = text.replace(old_effect, new_effect, 1)

old_menu = '''        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
'''
new_menu = '''        if (!running) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
'''
if old_menu not in text:
    raise SystemExit('menu start not found')
text = text.replace(old_menu, new_menu, 1)

old_end = '''            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ContactControls(
'''
new_end = '''                Spacer(Modifier.height(24.dp))
            }
        }

        if (running) {
            Text(
                text = "TRANSMITTING  •  ${formatClock(elapsedMs / 1000L)}",
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.Black,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 28.dp),
            )
            Button(
                onClick = { stopSession() },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 22.dp, vertical = 24.dp)
                    .height(70.dp),
                shape = RoundedCornerShape(24.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFFF3F5F),
                    contentColor = Color.White,
                ),
            ) {
                Text(
                    "STOP + SAVE SESSION",
                    fontWeight = FontWeight.Black,
                    fontSize = 17.sp,
                    letterSpacing = 1.sp,
                )
            }
        }
    }
}

@Composable
private fun ContactControls(
'''
if old_end not in text:
    raise SystemExit('menu end not found')
text = text.replace(old_end, new_end, 1)
path.write_text(text)

build = root / 'app/build.gradle.kts'
b = build.read_text()
b = b.replace('versionCode = 1', 'versionCode = 2', 1)
b = b.replace('versionName = "1.0.0"', 'versionName = "1.0.1-fullscreen"', 1)
build.write_text(b)
