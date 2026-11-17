import Foundation
import AVFoundation
import UIKit

struct Destino: Codable, Equatable {
    var estado: String = "pendiente"   // pendiente | copiando | guardado | exportado | visible | requiere_atencion | no_configurado
    var progreso: Double = 0
    var motivo: String? = nil
    var codigo: String? = nil
    var ruta: String? = nil
    static let textos = ["pendiente": "Pendiente", "copiando": "Copiando", "guardado": "Guardado", "exportado": "Exportado a la carpeta",
                         "visible": "Visible en Fotos JSE", "requiere_atencion": "Requiere atención", "no_configurado": "Sin configurar", "borrado": "Borrado"]
    var texto: String { (Destino.textos[estado] ?? estado) + (estado == "copiando" ? " \(Int(progreso * 100))%" : "") }
}

struct Marca: Codable, Hashable { var t: Double; var nota: String = "" }

struct Grabacion: Codable, Identifiable, Equatable {
    let id: String
    var titulo: String
    let creado: String
    let creadoLocal: String
    var archivo: String?          // nombre dentro de Documents/Videos
    var duracion: Double
    var tamano: Int64
    var origen: String
    var notas = ""
    var etiquetas: [String] = []
    var favorito = false
    var marcas: [Marca] = []
    var recorteDe: String? = nil
    var sha256: String? = nil
    var metaPendiente = false
    var destinos: [String: Destino] = ["dispositivo": Destino(estado: "guardado"), "nas": Destino(), "fotosJse": Destino(), "galeria": Destino(estado: "no_configurado")]

    var url: URL? { archivo.map { Biblioteca.videos.appendingPathComponent($0) } }
    var miniatura: URL { Biblioteca.miniaturas.appendingPathComponent("\(id).jpg") }
}

/// Biblioteca local en Documents (visible en la app Archivos → En mi iPhone → JSE Captura).
@MainActor
final class Biblioteca: ObservableObject {
    static let compartida = Biblioteca()
    static let documentos = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
    static let videos = documentos.appendingPathComponent("Videos", isDirectory: true)
    static let miniaturas = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0].appendingPathComponent("Miniaturas", isDirectory: true)
    private let archivo = documentos.appendingPathComponent("biblioteca.json")
    @Published var items: [Grabacion] = []
    @Published var aviso: String?

    init() {
        try? FileManager.default.createDirectory(at: Self.videos, withIntermediateDirectories: true)
        try? FileManager.default.createDirectory(at: Self.miniaturas, withIntermediateDirectories: true)
        if let d = try? Data(contentsOf: archivo), let l = try? JSONDecoder().decode([Grabacion].self, from: d) { items = l }
        for i in items.indices { for (k, v) in items[i].destinos where v.estado == "copiando" { items[i].destinos[k]?.estado = "pendiente" } }
    }

    func guardar() {
        if let d = try? JSONEncoder().encode(items) { try? d.write(to: archivo, options: .atomic) }
    }

    func agregar(_ g: Grabacion) { items.removeAll { $0.id == g.id }; items.append(g); guardar() }
    func actualizar(_ g: Grabacion) { if let i = items.firstIndex(where: { $0.id == g.id }) { items[i] = g; guardar() } }

    func filtrar(_ q: String, pendientes: Bool, favoritos: Bool) -> [Grabacion] {
        let t = q.lowercased().trimmingCharacters(in: .whitespaces)
        return items.filter { g in
            (!pendientes || g.destinos.values.contains { ["pendiente", "copiando", "requiere_atencion"].contains($0.estado) }) &&
            (!favoritos || g.favorito) &&
            (t.isEmpty || ([g.titulo, g.notas] + g.etiquetas).joined(separator: " ").lowercased().contains(t))
        }.sorted { $0.creado > $1.creado }
    }

    /// Borra solo la copia del iPhone; la del NAS y la de Fotos no se tocan.
    func borrarLocal(_ id: String) {
        guard var g = items.first(where: { $0.id == id }) else { return }
        if let u = g.url { try? FileManager.default.removeItem(at: u) }
        if g.destinos["nas"]?.estado == "guardado" { g.archivo = nil; g.destinos["dispositivo"] = Destino(estado: "borrado"); actualizar(g) }
        else { items.removeAll { $0.id == id }; guardar() }
    }

    static func fechaLocal(_ d: Date = Date()) -> String {
        let f = DateFormatter(); f.dateFormat = "yyyy-MM-dd'T'HH:mm:ss"; f.locale = Locale(identifier: "en_US_POSIX"); return f.string(from: d)
    }
}

enum Medios {
    struct Info { let duracion: Double; let valido: Bool; let motivo: String? }

    /// Pista de video y duración válida antes de agregar a la biblioteca.
    static func validar(_ url: URL) async -> Info {
        let a = AVURLAsset(url: url)
        guard let pistas = try? await a.loadTracks(withMediaType: .video), !pistas.isEmpty else { return Info(duracion: 0, valido: false, motivo: "no tiene pista de video") }
        let d = (try? await a.load(.duration)).map(CMTimeGetSeconds) ?? 0
        return d > 0 ? Info(duracion: d, valido: true, motivo: nil) : Info(duracion: 0, valido: false, motivo: "duración inválida")
    }

    static func miniatura(_ video: URL, _ destino: URL) async {
        let g = AVAssetImageGenerator(asset: AVURLAsset(url: video)); g.appliesPreferredTrackTransform = true; g.maximumSize = CGSize(width: 480, height: 480)
        if let (img, _) = try? await g.image(at: CMTime(seconds: 1, preferredTimescale: 600)), let d = UIImage(cgImage: img).jpegData(compressionQuality: 0.8) { try? d.write(to: destino) }
    }

    /// Normaliza a MP4 H.264/AAC. Si ya es H.264 se copia sin recodificar (passthrough); HEVC/ProRes se convierte en el dispositivo.
    static func normalizar(_ origen: URL, _ destino: URL, progreso: @escaping (Double) -> Void) async throws {
        let a = AVURLAsset(url: origen)
        let pistas = try await a.loadTracks(withMediaType: .video)
        guard let v = pistas.first else { throw NSError(domain: "JSE", code: 1, userInfo: [NSLocalizedDescriptionKey: "El archivo no tiene video"]) }
        let fmt = try await v.load(.formatDescriptions).first
        let esH264 = fmt.map { CMFormatDescriptionGetMediaSubType($0) == kCMVideoCodecType_H264 } ?? false
        let preset = esH264 ? AVAssetExportPresetPassthrough : AVAssetExportPreset1920x1080
        guard let s = AVAssetExportSession(asset: a, presetName: preset) else { throw NSError(domain: "JSE", code: 2, userInfo: [NSLocalizedDescriptionKey: "No se puede convertir este video"]) }
        try? FileManager.default.removeItem(at: destino)
        s.outputURL = destino; s.outputFileType = .mp4; s.shouldOptimizeForNetworkUse = true
        let t = Task { while !Task.isCancelled { progreso(Double(s.progress)); try? await Task.sleep(nanoseconds: 300_000_000) } }
        await s.export()
        t.cancel()
        if s.status != .completed { throw s.error ?? NSError(domain: "JSE", code: 3, userInfo: [NSLocalizedDescriptionKey: "La conversión falló"]) }
    }

    /// Recorte: MP4 nuevo; el original no cambia.
    static func recortar(_ origen: URL, _ destino: URL, _ ini: Double, _ fin: Double) async throws {
        guard let s = AVAssetExportSession(asset: AVURLAsset(url: origen), presetName: AVAssetExportPresetHighestQuality) else { throw NSError(domain: "JSE", code: 4) }
        s.outputURL = destino; s.outputFileType = .mp4; s.shouldOptimizeForNetworkUse = true
        s.timeRange = CMTimeRange(start: CMTime(seconds: ini, preferredTimescale: 600), end: CMTime(seconds: fin, preferredTimescale: 600))
        await s.export()
        if s.status != .completed { throw s.error ?? NSError(domain: "JSE", code: 5) }
    }
}
