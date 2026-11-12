import Foundation
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
final class SwiftConnectIqCompanion: NSObject, ConnectIqCompanion, IQUIOverrideDelegate, IQDeviceEventDelegate, IQAppMessageDelegate {

    private let urlScheme: String
    private let lock = NSLock()
    private var callback: ConnectIqCallback?
    private var pendingDevices: String?
    private var appIds: [String] = []

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

    override init() {
        let fromPlist = Bundle.main.object(forInfoDictionaryKey: "AAPSConnectIqUrlScheme") as? String
        urlScheme = (fromPlist?.isEmpty == false) ? fromPlist! : "aaps-ciq"
        super.init()
        // The two-argument form. A restore identifier would give the SDK its own CoreBluetooth
        // central; this app already restores the sensor and the pump, and the push goes out while
        // those wakes have the process up.
        ConnectIQ.sharedInstance().initialize(withUrlScheme: urlScheme, uiOverrideDelegate: self)
    }

    func needsToInstallConnectMobile() {
        ConnectIQ.sharedInstance().showAppStoreForConnectMobile()
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
            ConnectIQ.sharedInstance().showConnectIQDeviceSelection()
        }
    }

    func handleOpenUrl(url: String) -> Bool {
        guard let parsed = URL(string: url),
              parsed.scheme?.caseInsensitiveCompare(urlScheme) == .orderedSame else {
            return false
        }
        onMain {
            let raw = ConnectIQ.sharedInstance().parseDeviceSelectionResponse(from: parsed) as? [Any] ?? []
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
        payloadJson: String
    ) {
        onMain {
            guard let deviceId = UUID(uuidString: deviceUuid),
                  let appUuid = Self.uuid(fromHex: appId),
                  let payload = Self.object(fromJson: payloadJson) else {
                self.finish(deviceUuid: deviceUuid, appId: appId, result: "FAILURE_UNSUPPORTED_TYPE")
                return
            }
            let device = IQDevice.device(
                withId: deviceId,
                modelName: modelName,
                friendlyName: friendlyName,
                partNumber: partNumber
            )
            // The store id is not in the Android map. The application id is what the watch matches;
            // the same value is passed as the store id so the SDK has the UUID it requires.
            let app = IQApp.app(withUUID: appUuid, storeUuid: appUuid, device: device)
            ConnectIQ.sharedInstance().sendMessage(payload, to: app, progress: { _, _ in }, completion: { result in
                let index = Int(result.rawValue)
                let name = (index >= 0 && index < Self.sendResults.count) ? Self.sendResults[index] : "FAILURE_UNKNOWN"
                self.finish(deviceUuid: deviceUuid, appId: appId, result: name)
            })
        }
    }

    func deviceStatusChanged(_ device: IQDevice, status: IQDeviceStatus) {
        let index = Int(status.rawValue)
        let name = (index >= 0 && index < Self.deviceStatuses.count) ? Self.deviceStatuses[index] : "UNKNOWN"
        callback?.deviceStatus(deviceUuid: device.uuid.uuidString, status: name)
    }

    func deviceCharacteristicsDiscovered(_ device: IQDevice) {
        callback?.deviceStatus(deviceUuid: device.uuid.uuidString, status: "READY")
        registerApps(on: device)
    }

    func receivedMessage(_ message: Any, from app: IQApp) {
        let json = Self.jsonText(message)
        callback?.messageReceived(
            deviceUuid: app.device.uuid.uuidString,
            appId: Self.hex(from: app.uuid),
            payloadJson: json
        )
    }

    private func finish(deviceUuid: String, appId: String, result: String) {
        callback?.sendFinished(deviceUuid: deviceUuid, appId: appId, result: result)
    }

    private func register(_ devices: [IQDevice]) {
        let connectIq = ConnectIQ.sharedInstance()
        for device in devices {
            connectIq.register(forDeviceEvents: device, delegate: self)
            registerApps(on: device)
        }
    }

    /// Listens to each watch app and asks whether it is installed. Sending waits on that for apps
    /// we already know are missing; an unknown app is still tried once.
    private func registerApps(on device: IQDevice) {
        let connectIq = ConnectIQ.sharedInstance()
        for appId in appIds {
            guard let appUuid = Self.uuid(fromHex: appId) else { continue }
            let app = IQApp.app(withUUID: appUuid, storeUuid: appUuid, device: device)
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
        return rows.compactMap { row in
            guard let uuidText = row["uuid"] as? String, let uuid = UUID(uuidString: uuidText) else { return nil }
            return IQDevice.device(
                withId: uuid,
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
