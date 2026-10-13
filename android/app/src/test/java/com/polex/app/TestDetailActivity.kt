package com.polex.app

import androidx.activity.ComponentActivity

/** Test-only host activity for the Compose UI tests; it provides the
 *  back-invoked dispatcher the screen's [androidx.activity.compose.BackHandler]
 *  needs. Registered with Robolectric's ShadowPackageManager by
 *  [TestApplication]; never shipped (test source set only, not in the
 *  production manifest). */
class TestDetailActivity : ComponentActivity()
