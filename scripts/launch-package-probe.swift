// Launch only the supplied development app using LaunchServices and an isolated profile.
// Window-server detection is not a physical input/compositor or cold-cache measurement.
import AppKit
import CoreGraphics
import Foundation

guard CommandLine.arguments.count == 5 else {
    fputs("Usage: launch-package-probe APP PROFILE JAVA_SOURCE OUTPUT_JSON\n", stderr)
    exit(2)
}
let appURL = URL(fileURLWithPath: CommandLine.arguments[1]).standardizedFileURL
let profile = URL(fileURLWithPath: CommandLine.arguments[2]).standardizedFileURL
let source = URL(fileURLWithPath: CommandLine.arguments[3]).standardizedFileURL
let output = URL(fileURLWithPath: CommandLine.arguments[4]).standardizedFileURL
guard FileManager.default.fileExists(atPath: appURL.appendingPathComponent("Contents/MacOS/TurboJ").path),
      FileManager.default.fileExists(atPath: source.path) else {
    fputs("Application or owned Java fixture missing\n", stderr); exit(2)
}
let configuration = NSWorkspace.OpenConfiguration()
configuration.createsNewApplicationInstance = true
configuration.activates = true
configuration.arguments = [source.path]
configuration.environment = ["JAVA_TOOL_OPTIONS": "-Dturboj.config=\(profile.path)",
                             "PATH": "/usr/bin:/bin:/usr/sbin:/sbin"]
let started = DispatchTime.now().uptimeNanoseconds
var process: NSRunningApplication? = nil
var failure: Error? = nil
NSWorkspace.shared.openApplication(at: appURL, configuration: configuration) { app, error in
    process = app; failure = error
}
let deadline = Date().addingTimeInterval(45)
var detected: [String: Any]? = nil
while Date() < deadline && failure == nil {
    RunLoop.current.run(until: Date().addingTimeInterval(0.02))
    guard let app = process else { continue }
    if app.isTerminated { break }
    guard let windows = CGWindowListCopyWindowInfo([.optionOnScreenOnly, .excludeDesktopElements], kCGNullWindowID) as? [[String: Any]] else { continue }
    for window in windows {
        guard (window[kCGWindowOwnerPID as String] as? Int) == Int(app.processIdentifier),
              let bounds = window[kCGWindowBounds as String] as? [String: Any],
              let width = bounds["Width"] as? Double, let height = bounds["Height"] as? Double,
              width >= 600 && height >= 400 else { continue }
        detected = ["pid": Int(app.processIdentifier), "width": width, "height": height,
                    "launchToWindowServerMs": Double(DispatchTime.now().uptimeNanoseconds - started) / 1_000_000,
                    "app": appURL.path, "profile": profile.path,
                    "source": source.path, "createdAt": ISO8601DateFormatter().string(from: Date()),
                    "boundary": "LaunchServices dispatch to owned app's on-screen window in CGWindowList; 20 ms polling. Warm OS caches, new JVM. Does not prove source analysis or final compositor presentation."]
        break
    }
    if detected != nil { break }
}
guard let result = detected else {
    process?.terminate()
    fputs("Owned application window not observed: \(String(describing: failure))\n", stderr)
    exit(1)
}
try FileManager.default.createDirectory(at: output.deletingLastPathComponent(), withIntermediateDirectories: true)
try JSONSerialization.data(withJSONObject: result, options: [.prettyPrinted, .sortedKeys]).write(to: output)
print("Owned app PID \(result["pid"]!), evidence \(output.path)")
