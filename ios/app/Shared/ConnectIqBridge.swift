import Foundation
import UIKit
import ConnectIQ
import AapsShared

/// Garmin Connect IQ on the iPhone.
///
/// The Kotlin plugin decides when a glucose message goes out and what it contains. This class only
/// talks to the Connect IQ SDK: picking the watches, keeping the list the SDK forgets, and sending
/// the dictionary the watch apps already understand.
///
/// Device selection comes back as a URL (`aaps-ciq://…` or `aaps2-ciq://…`). SwiftUI hands that URL
/// to [handleOpenUrl]. The scheme is the one in Info.plist (`AAPSConnectIqUrlScheme`), so the two
/// app targets do not steal each other's callback.
///
/// ## Pure contracts (no SDK objects)
///
/// Device list JSON, the text Kotlin stores under `garmin_connectiq_devices`. Key order does not
/// matter. Each object is:
///
/// `{ "uuid": "<NSUUID.uuidString>", "modelName": "", "friendlyName": "", "partNumber": "" }`
///
/// `uuid` is uppercase, with dashes. A missing string is `""`. Kotlin's `ConnectIqDeviceStore`
/// accepts that shape in any key order.
///
/// Send results are `IQSendMessageResult.rawValue` as an index into `sendResults` below, which is
/// the same order as Kotlin `ConnectIqSendResult.ordinal` (SDK 1.8.0 header). Device status uses
/// `IQDeviceStatus.rawValue` the same way, and `deviceCharacteristicsDiscovered` is the extra name
/// `READY` (not a raw value). The `token` string passed into `send` is echoed unchanged on
/// `sendFinished`, so a late completion can be told apart from the attempt that replaced it.
final class SwiftConnectIqCompanion: NSObject, ConnectIqCompanion, IQUIOverrideDelegate, IQDeviceEventDelegate, IQAppMessageDelegate {

    private let urlScheme: String
    private let lock = NSLock()
    private var callback: ConnectIqCallback?
    private var pendingDevices: String?
    private var appIds: [String] = []
    private var knownDevices: [IQDevice] = []
    private var installTitle = "Garmin Connect"
    private var installMessage = "AAPS needs Garmin Connect to send glucose to the watch. Open the App Store to install it?"
    private var installAccept = "Install"
    private var installDecline = "Not now"

    /// SDK 1.8.0 `IQSendMessageResult`, in header order. Names are the ones the Kotlin side matches.
    private static let sendResults = [
        "SUCCESS",
        "FAILURE_UNKNOWN",
        "FAILURE_INTERNAL_ERROR",
        "FAILURE_DEVICE_NOT_AVAILABLE",
        "FAILURE_APP_NOT_FOUND",
        "FAILURE_DEVICE_IS_BUSY",
        "FAILURE_UNSUPPORTED_TYPE",
        "FAILURE_INSUFFICIENT_MEMORY",
        "FAILURE_TIMEOUT",
        "FAILURE_MAX_RETRIES",
        "FAILURE_PROMPT_NOT_DISPLAYED",
        "FAILURE_APP_ALREADY_RUNNING",
    ]

    /// SDK 1.8.0 `IQDeviceStatus`, in header order.
    private static let deviceStatuses = [
        "INVALID",
        "BLUETOOTH_NOT_READY",
        "NOT_FOUND",
        "NOT_CONNECTED",
        "CONNECTED",
    ]

    /// The SDK headers carry no nullability, so Swift sees `ConnectIQ!`. The shared instance always
    /// exists; this unwraps it once instead of at every call.
    private static var sdk: ConnectIQ { ConnectIQ.sharedInstance() }

    override init() {
        let fromPlist = Bundle.main.object(forInfoDictionaryKey: "AAPSConnectIqUrlScheme") as? String
        urlScheme = (fromPlist?.isEmpty == false) ? fromPlist! : "aaps-ciq"
        super.init()
        // The two-argument form. A restore identifier would give the SDK its own CoreBluetooth
        // central; this app already restores the sensor and the pump, and the push goes out while
        // those wakes have the process up.
        Self.sdk.initialize(withUrlScheme: urlScheme, uiOverrideDelegate: self)
    }

    func needsToInstallConnectMobile() {
        // The SDK asks us to decide. Opening the store here would leave the phone without a choice.
        onMain { self.presentInstallPrompt() }
    }

    func setInstallPrompt(title: String, message: String, accept: String, decline: String) {
        lock.lock()
        installTitle = title
        installMessage = message
        installAccept = accept
        installDecline = decline
        lock.unlock()
    }

    func reregisterDevice(deviceUuid: String) {
        lock.lock()
        let device = knownDevices.first {
            $0.uuid.uuidString.caseInsensitiveCompare(deviceUuid) == .orderedSame
        }
        lock.unlock()
        guard let device else { return }
        onMain {
            let connectIq = Self.sdk
            connectIq.unregister(forDeviceEvents: device, delegate: self)
            connectIq.register(forDeviceEvents: device, delegate: self)
        }
    }

    func start(callback: ConnectIqCallback, appIdsCsv: String) {
        lock.lock()
        self.callback = callback
        appIds = appIdsCsv.split(separator: ",").map { String($0) }.filter { !$0.isEmpty }
        let pending = pendingDevices
        pendingDevices = nil
        lock.unlock()
        if let pending {
            callback.devicesReplaced(encoded: pending)
        }
    }

    func showDeviceSelection() {
        onMain {
            Self.sdk.showDeviceSelection()
        }
    }

    func handleOpenUrl(url: String) -> Bool {
        guard let parsed = URL(string: url),
              parsed.scheme?.caseInsensitiveCompare(urlScheme) == .orderedSame else {
            return false
        }
        onMain {
            let raw: [Any] = Self.sdk.parseDeviceSelectionResponse(from: parsed) ?? []
            let parsedDevices = raw.compactMap { $0 as? IQDevice }
            let encoded = Self.encode(parsedDevices)
            self.register(parsedDevices)
            self.lock.lock()
            let callback = self.callback
            if callback == nil {
                self.pendingDevices = encoded
            }
            self.lock.unlock()
            callback?.devicesReplaced(encoded: encoded)
        }
        return true
    }

    func restoreDevices(encoded: String) {
        onMain {
            self.register(Self.devices(from: encoded))
        }
    }

    func send(
        deviceUuid: String,
        modelName: String,
        friendlyName: String,
        partNumber: String,
        appId: String,
        payloadJson: String,
        token: String
    ) {
        onMain {
            guard let deviceId = UUID(uuidString: deviceUuid),
                  let appUuid = Self.uuid(fromHex: appId),
                  let payload = Self.object(fromJson: payloadJson) else {
                self.finish(deviceUuid: deviceUuid, appId: appId, result: "FAILURE_UNSUPPORTED_TYPE", token: token)
                return
            }
            let device = IQDevice(
                id: deviceId,
                modelName: modelName,
                friendlyName: friendlyName,
                partNumber: partNumber
            )
            // The store id is not in the Android map. The application id is what the watch matches;
            // the same value is passed as the store id so the SDK has the UUID it requires.
            let app = IQApp(uuid: appUuid, store: appUuid, device: device)
            Self.sdk.sendMessage(payload, to: app, progress: { _, _ in }, completion: { result in
                let index = Int(result.rawValue)
                let name = (index >= 0 && index < Self.sendResults.count) ? Self.sendResults[index] : "FAILURE_UNKNOWN"
                self.finish(deviceUuid: deviceUuid, appId: appId, result: name, token: token)
            })
        }
    }

    func stop() {
        lock.lock()
        callback = nil
        pendingDevices = nil
        let devices = knownDevices
        let ids = appIds
        knownDevices = []
        appIds = []
        lock.unlock()
        onMain {
            self.drop(devices, appIds: ids)
            let connectIq = Self.sdk
            connectIq.unregister(forAllDeviceEvents: self)
            connectIq.unregister(forAllAppMessages: self)
        }
    }

    func deviceStatusChanged(_ device: IQDevice, status: IQDeviceStatus) {
        let index = Int(status.rawValue)
        let name = (index >= 0 && index < Self.deviceStatuses.count) ? Self.deviceStatuses[index] : "UNKNOWN"
        callback?.deviceStatus(deviceUuid: device.uuid.uuidString, status: name)
    }

    func deviceCharacteristicsDiscovered(_ device: IQDevice) {
        callback?.deviceStatus(deviceUuid: device.uuid.uuidString, status: "READY")
        lock.lock()
        let ids = appIds
        lock.unlock()
        registerApps(on: device, appIds: ids)
    }

    func receivedMessage(_ message: Any, from app: IQApp) {
        let json = Self.jsonText(message)
        callback?.messageReceived(
            deviceUuid: app.device.uuid.uuidString,
            appId: Self.hex(from: app.uuid),
            payloadJson: json
        )
    }

    private func finish(deviceUuid: String, appId: String, result: String, token: String) {
        callback?.sendFinished(deviceUuid: deviceUuid, appId: appId, result: result, token: token)
    }

    /// Unregisters anything that is no longer selected, then registers the new list. Registering
    /// without the unregister stacks a second delegate on the next start.
    private func register(_ devices: [IQDevice]) {
        lock.lock()
        let previous = knownDevices
        let ids = appIds
        let alive = callback != nil
        lock.unlock()
        guard alive else { return }
        let removed = previous.filter { old in !devices.contains { $0.uuid == old.uuid } }
        drop(removed, appIds: ids)
        let connectIq = Self.sdk
        for device in devices {
            connectIq.unregister(forDeviceEvents: device, delegate: self)
            unregisterApps(on: device, appIds: ids)
            connectIq.register(forDeviceEvents: device, delegate: self)
            registerApps(on: device, appIds: ids)
        }
        // Same lock as stop(). A stop that landed during the SDK calls wins: the list stays empty.
        lock.lock()
        if callback != nil {
            knownDevices = devices
        }
        lock.unlock()
    }

    private func drop(_ devices: [IQDevice], appIds: [String]) {
        let connectIq = Self.sdk
        for device in devices {
            connectIq.unregister(forDeviceEvents: device, delegate: self)
            unregisterApps(on: device, appIds: appIds)
        }
    }

    private func unregisterApps(on device: IQDevice, appIds: [String]) {
        let connectIq = Self.sdk
        for appId in appIds {
            guard let appUuid = Self.uuid(fromHex: appId) else { continue }
            let app = IQApp(uuid: appUuid, store: appUuid, device: device)
            connectIq.unregister(forAppMessages: app, delegate: self)
        }
    }

    /// Listens to each watch app and asks whether it is installed. Sending waits on that for apps
    /// we already know are missing; an unknown app is still tried once.
    private func registerApps(on device: IQDevice, appIds: [String]) {
        let connectIq = Self.sdk
        for appId in appIds {
            guard let appUuid = Self.uuid(fromHex: appId) else { continue }
            let app = IQApp(uuid: appUuid, store: appUuid, device: device)
            connectIq.register(forAppMessages: app, delegate: self)
            let echoedAppId = appId
            let deviceUuid = device.uuid.uuidString
            connectIq.getAppStatus(app) { status in
                guard let status else { return }
                self.callback?.appStatus(
                    deviceUuid: deviceUuid,
                    appId: echoedAppId,
                    installed: status.isInstalled,
                    version: Int32(status.version)
                )
            }
        }
    }

    private func presentInstallPrompt() {
        lock.lock()
        let title = installTitle
        let message = installMessage
        let accept = installAccept
        let decline = installDecline
        lock.unlock()
        let alert = UIAlertController(title: title, message: message, preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: decline, style: .cancel))
        alert.addAction(UIAlertAction(title: accept, style: .default) { _ in
            Self.sdk.showAppStoreForConnectMobile()
        })
        guard let presenter = Self.topController() else { return }
        presenter.present(alert, animated: true)
    }

    private static func topController() -> UIViewController? {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        let window = scenes.flatMap(\.windows).first { $0.isKeyWindow }
        var controller = window?.rootViewController
        while let presented = controller?.presentedViewController {
            controller = presented
        }
        return controller
    }

    private func onMain(_ block: @escaping () -> Void) {
        if Thread.isMainThread {
            block()
        } else {
            DispatchQueue.main.async(execute: block)
        }
    }

    private static func encode(_ devices: [IQDevice]) -> String {
        let rows: [[String: String]] = devices.map { device in
            [
                "uuid": device.uuid.uuidString,
                "modelName": device.modelName ?? "",
                "friendlyName": device.friendlyName ?? "",
                "partNumber": device.partNumber ?? "",
            ]
        }
        guard JSONSerialization.isValidJSONObject(rows),
              let data = try? JSONSerialization.data(withJSONObject: rows),
              let text = String(data: data, encoding: .utf8) else {
            return "[]"
        }
        return text
    }

    private static func devices(from encoded: String) -> [IQDevice] {
        guard let data = encoded.data(using: .utf8),
              let rows = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]] else {
            return []
        }
        return rows.compactMap { row -> IQDevice? in
            guard let uuidText = row["uuid"] as? String, let uuid = UUID(uuidString: uuidText) else { return nil }
            return IQDevice(
                id: uuid,
                modelName: row["modelName"] as? String ?? "",
                friendlyName: row["friendlyName"] as? String ?? "",
                partNumber: row["partNumber"] as? String ?? ""
            )
        }
    }

    /// `C9E90EE7E6924829A8B45E7DAFFF5CB4` or the dashed form, to a UUID.
    private static func uuid(fromHex hex: String) -> UUID? {
        let clean = hex.replacingOccurrences(of: "-", with: "").uppercased()
        guard clean.count == 32 else { return nil }
        let dashed = "\(clean.prefix(8))-\(clean.dropFirst(8).prefix(4))-\(clean.dropFirst(12).prefix(4))-\(clean.dropFirst(16).prefix(4))-\(clean.dropFirst(20))"
        return UUID(uuidString: dashed)
    }

    /// Back to the 32 hex characters Android uses, so a message from the watch names the same app.
    private static func hex(from uuid: UUID) -> String {
        uuid.uuidString.replacingOccurrences(of: "-", with: "")
    }

    private static func object(fromJson text: String) -> Any? {
        guard let data = text.data(using: .utf8) else { return nil }
        return try? JSONSerialization.jsonObject(with: data)
    }

    private static func jsonText(_ message: Any) -> String {
        if JSONSerialization.isValidJSONObject(message),
           let data = try? JSONSerialization.data(withJSONObject: message),
           let text = String(data: data, encoding: .utf8) {
            return text
        }
        return String(describing: message)
    }
}
