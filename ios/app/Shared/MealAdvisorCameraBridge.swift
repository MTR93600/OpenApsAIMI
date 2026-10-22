import AVFoundation
import AapsShared
import UIKit

/// The Kotlin interface keeps AVFoundation and UIKit out of shared Compose code. This provider
/// returns a real custom-preview controller and forwards captured JPEG data to Kotlin.
final class SwiftMealAdvisorCameraFactory: NSObject, IosMealAdvisorCameraFactory {

    func create(
        permissionDeniedMessage: String,
        cameraNotReadyMessage: String,
        onCaptured: @escaping (Data) -> Void,
        onError: @escaping (String) -> Void
    ) -> any IosMealAdvisorCameraController {
        SwiftMealAdvisorCameraController(
            permissionDeniedMessage: permissionDeniedMessage,
            cameraNotReadyMessage: cameraNotReadyMessage,
            onCaptured: onCaptured,
            onError: onError
        )
    }
}

private final class SwiftMealAdvisorCameraController: NSObject, IosMealAdvisorCameraController, AVCapturePhotoCaptureDelegate {

    let viewController: UIViewController

    private let session = AVCaptureSession()
    private let photoOutput = AVCapturePhotoOutput()
    private let permissionDeniedMessage: String
    private let cameraNotReadyMessage: String
    private let onCaptured: (Data) -> Void
    private let onError: (String) -> Void
    private let sessionQueue = DispatchQueue(label: "app.aaps.meal-advisor.camera")
    private var configured = false
    private var disposed = false

    init(
        permissionDeniedMessage: String,
        cameraNotReadyMessage: String,
        onCaptured: @escaping (Data) -> Void,
        onError: @escaping (String) -> Void
    ) {
        self.permissionDeniedMessage = permissionDeniedMessage
        self.cameraNotReadyMessage = cameraNotReadyMessage
        self.onCaptured = onCaptured
        self.onError = onError
        self.viewController = MealAdvisorPreviewController(session: session)
        super.init()
        requestPermissionAndStart()
    }

    func capture() {
        sessionQueue.async { [weak self] in
            guard let self, !disposed, configured, session.isRunning else {
                DispatchQueue.main.async { [weak self] in
                    guard let self, !disposed else { return }
                    onError(cameraNotReadyMessage)
                }
                return
            }
            photoOutput.capturePhoto(
                with: AVCapturePhotoSettings(),
                delegate: self
            )
        }
    }

    func dispose() {
        sessionQueue.async { [weak self] in
            guard let self else { return }
            disposed = true
            if session.isRunning {
                session.stopRunning()
            }
        }
    }

    private func requestPermissionAndStart() {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            configureAndStart()
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .video) { [weak self] granted in
                guard let self else { return }
                if granted {
                    configureAndStart()
                } else {
                    reportError(permissionDeniedMessage)
                }
            }
        case .denied, .restricted:
            reportError(permissionDeniedMessage)
        @unknown default:
            reportError(cameraNotReadyMessage)
        }
    }

    private func configureAndStart() {
        sessionQueue.async { [weak self] in
            guard let self, !disposed else { return }
            if !configured {
                session.beginConfiguration()
                session.sessionPreset = .photo
                guard
                    let camera = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back),
                    let input = try? AVCaptureDeviceInput(device: camera),
                    session.canAddInput(input),
                    session.canAddOutput(photoOutput)
                else {
                    session.commitConfiguration()
                    reportError(cameraNotReadyMessage)
                    return
                }
                session.addInput(input)
                session.addOutput(photoOutput)
                session.commitConfiguration()
                configured = true
            }
            session.startRunning()
        }
    }

    private func reportError(_ message: String) {
        DispatchQueue.main.async { [weak self] in
            guard let self, !disposed else { return }
            onError(message)
        }
    }

    private final class MealAdvisorPreviewController: UIViewController {

        private let previewLayer: AVCaptureVideoPreviewLayer

        init(session: AVCaptureSession) {
            previewLayer = AVCaptureVideoPreviewLayer(session: session)
            super.init(nibName: nil, bundle: nil)
            view.backgroundColor = .black
            previewLayer.videoGravity = .resizeAspectFill
            view.layer.addSublayer(previewLayer)
        }

        required init?(coder: NSCoder) {
            return nil
        }

        override func viewDidLayoutSubviews() {
            super.viewDidLayoutSubviews()
            previewLayer.frame = view.bounds
        }
    }

    func photoOutput(
        _ output: AVCapturePhotoOutput,
        didFinishProcessingPhoto photo: AVCapturePhoto,
        error: Error?
    ) {
        if error != nil {
            reportError(cameraNotReadyMessage)
            return
        }
        guard let jpeg = photo.fileDataRepresentation(), !jpeg.isEmpty else {
            reportError(cameraNotReadyMessage)
            return
        }
        DispatchQueue.main.async { [weak self] in
            guard let self, !disposed else { return }
            onCaptured(jpeg)
        }
    }
}
