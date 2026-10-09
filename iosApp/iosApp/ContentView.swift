import SwiftUI
import UIKit
import KysheonApp

/// Thin bridge to the shared Compose UI: no logic lives here.
struct ContentView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
