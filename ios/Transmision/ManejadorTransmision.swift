import ReplayKit
import AVFoundation

/// Extensión de transmisión de ReplayKit: el sistema le entrega la pantalla completa (y el audio) aunque cambies de app.
/// Se inicia desde el botón de JSE Captura o desde el Centro de control (mantener pulsado Grabar → JSE Captura).
/// Escribe un MP4 H.264/AAC en el contenedor compartido; la app lo importa a su biblioteca al abrirse.
final class ManejadorTransmision: RPBroadcastSampleHandler {
    private var escritor: AVAssetWriter?
    private var entradaVideo: AVAssetWriterInput?
    private var entradaAudio: AVAssetWriterInput?
    private var entradaMic: AVAssetWriterInput?
    private var iniciado = false
    private var destino: URL?
    private var id = UUID().uuidString.lowercased()
    private let cola = DispatchQueue(label: "jse.transmision")

    static var grupo: URL { FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: "group.mx.jse.captura")! }

    override func broadcastStarted(withSetupInfo setupInfo: [String: NSObject]?) {
        let dir = Self.grupo.appendingPathComponent("Grabando", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        destino = dir.appendingPathComponent("\(id).mp4")
        let sesion: [String: Any] = ["id": id, "creado": ISO8601DateFormatter().string(from: Date()), "creadoLocal": Self.fechaLocal(), "origen": "iPhone · pantalla completa (ReplayKit)"]
        if let d = try? JSONSerialization.data(withJSONObject: sesion) { try? d.write(to: dir.appendingPathComponent("\(id).json")) }
    }

    static func fechaLocal() -> String {
        let f = DateFormatter(); f.dateFormat = "yyyy-MM-dd'T'HH:mm:ss"; f.locale = Locale(identifier: "en_US_POSIX"); return f.string(from: Date())
    }

    private func preparar(con muestra: CMSampleBuffer) {
        guard let destino, let img = CMSampleBufferGetImageBuffer(muestra) else { return }
        let w = CVPixelBufferGetWidth(img), h = CVPixelBufferGetHeight(img)
        // Hasta 1080p conservando la relación de aspecto (múltiplos de 16).
        let largo = Double(max(w, h)), corto = Double(min(w, h))
        let escala = min(1.0, min(1920 / largo, 1080 / corto))
        func al16(_ v: Double) -> Int { max(16, Int(v * escala / 16) * 16) }
        let (ow, oh) = w >= h ? (al16(largo), al16(corto)) : (al16(corto), al16(largo))
        guard let e = try? AVAssetWriter(outputURL: destino, fileType: .mp4) else { return }
        // Fragmentos cada 5 s: si la extensión se cierra de golpe, lo grabado hasta el último fragmento se puede recuperar.
        e.movieFragmentInterval = CMTime(seconds: 5, preferredTimescale: 600)
        e.shouldOptimizeForNetworkUse = true
        let v = AVAssetWriterInput(mediaType: .video, outputSettings: [
            AVVideoCodecKey: AVVideoCodecType.h264, AVVideoWidthKey: ow, AVVideoHeightKey: oh,
            AVVideoScalingModeKey: AVVideoScalingModeResizeAspect,
            AVVideoCompressionPropertiesKey: [AVVideoAverageBitRateKey: 8_000_000, AVVideoMaxKeyFrameIntervalKey: 30, AVVideoProfileLevelKey: AVVideoProfileLevelH264HighAutoLevel, AVVideoExpectedSourceFrameRateKey: 30],
        ])
        v.expectsMediaDataInRealTime = true
        let a = AVAssetWriterInput(mediaType: .audio, outputSettings: [AVFormatIDKey: kAudioFormatMPEG4AAC, AVNumberOfChannelsKey: 2, AVSampleRateKey: 44100, AVEncoderBitRateKey: 128_000])
        a.expectsMediaDataInRealTime = true
        // El micrófono va en una segunda pista AAC (VLC/mpv permiten elegirla); ReplayKit no lo mezcla con el audio de las apps.
        let m = AVAssetWriterInput(mediaType: .audio, outputSettings: [AVFormatIDKey: kAudioFormatMPEG4AAC, AVNumberOfChannelsKey: 1, AVSampleRateKey: 44100, AVEncoderBitRateKey: 96_000])
        m.expectsMediaDataInRealTime = true
        if e.canAdd(v) { e.add(v) }
        if e.canAdd(a) { e.add(a) }
        if e.canAdd(m) { e.add(m); entradaMic = m }
        e.startWriting()
        e.startSession(atSourceTime: CMSampleBufferGetPresentationTimeStamp(muestra))
        escritor = e; entradaVideo = v; entradaAudio = a; iniciado = true
    }

    override func processSampleBuffer(_ muestra: CMSampleBuffer, with tipo: RPSampleBufferType) {
        cola.sync {
            switch tipo {
            case .video:
                if !iniciado { preparar(con: muestra) }
                if let v = entradaVideo, v.isReadyForMoreMediaData { v.append(muestra) }
            case .audioApp:
                // Las apps que bloquean la captura (DRM, llamadas) llegan en silencio: es una restricción del sistema.
                if iniciado, let a = entradaAudio, a.isReadyForMoreMediaData { a.append(muestra) }
            case .audioMic:
                // Solo llega si activaste el micrófono en el selector del sistema.
                if iniciado, let m = entradaMic, m.isReadyForMoreMediaData { m.append(muestra) }
            @unknown default: break
            }
        }
    }

    override func broadcastFinished() {
        let grupo = DispatchGroup()
        cola.sync {
            guard let e = escritor else { return }
            entradaVideo?.markAsFinished(); entradaAudio?.markAsFinished(); entradaMic?.markAsFinished()
            grupo.enter()
            e.finishWriting { grupo.leave() }
        }
        _ = grupo.wait(timeout: .now() + 10)
        // Avisar a la app: archivo listo para importar.
        let listo = Self.grupo.appendingPathComponent("Grabando/\(id).listo")
        FileManager.default.createFile(atPath: listo.path, contents: Data())
    }
}
