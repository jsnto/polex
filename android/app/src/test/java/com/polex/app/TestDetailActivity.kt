package com.polex.app

import androidx.activity.ComponentActivity

/** Test-only activity (declared in the unit-test manifest) that the Compose
 *  UI tests launch; it provides the back-invoked dispatcher the screen's
 *  [androidx.activity.compose.BackHandler] needs. Never shipped. */
class TestDetailActivity : ComponentActivity()
