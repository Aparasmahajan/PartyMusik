package io.mahajan.partymusik

import androidx.compose.ui.window.ComposeUIViewController
import io.mahajan.partymusik.ui.App
import platform.UIKit.UIViewController

/** Hosted by SwiftUI through UIViewControllerRepresentable (iosApp/iosApp/ComposeView.swift). */
fun MainViewController(core: PartyMusikCore): UIViewController = ComposeUIViewController { App(core) }
