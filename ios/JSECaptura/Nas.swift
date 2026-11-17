import Foundation
import Security
import CryptoKit
import Photos
import UIKit

struct ErrorNas: LocalizedError {
    let estado: Int; let codigo: String; let mensaje: String; var datos: [String: Any] = [:]
    var errorDescription: String? { mensaje }
    var requiereUsuario: Bool { ["credenciales_invalidas", "archivo_demasiado_grande", "sin_espacio", "carpeta_invalida", "video_invalido", "conflicto_id", "certificado"].contains(codigo) }
}

/// Token del NAS en el Llavero de iOS (solo este dispositivo, disponible tras el primer desbloqueo).
enum Llavero {
    static let cuenta = "jse-captura-token"
    static func guardar(_ t: String?) {
        let q: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrAccount as String: cuenta]
        SecItemDelete(q as CFDictionary)
        guard let t else { return }
        var n = q; n[kSecValueData as String] = Data(t.utf8); n[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        SecItemAdd(n as CFDictionary, nil)
    }
    static func leer() -> String? {
        let q: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrAccount as String: cuenta, kSecReturnData as String: true]
        var r: AnyObject?
        return SecItemCopyMatching(q as CFDictionary, &r) == errSecSuccess ? (r as? Data).flatMap { String(data: $0, encoding: .utf8) } : nil
    }
}

final class ClienteNas {
    let base: URL
    var token: String?
    init(_ direccion: String, token: String? = Llavero.leer()) {
        var d = direccion.trimmingCharacters(in: .whitespaces)
        if !d.contains("://") { d = "http://" + d }
        if d.hasPrefix("http://"), URL(string: d)?.port == nil { d += ":8790" }
        base = URL(string: d) ?? URL(string: "http://127.0.0.1:8790")!
        self.token = token
    }

    func json(_ metodo: String, _ ruta: String, _ cuerpo: [String: Any]? = nil, timeout: TimeInterval = 30, datos: Data? = nil, cabeceras: [String: String] = [:]) async throws -> [String: Any] {
        var r = URLRequest(url: URL(string: ruta, relativeTo: base)!, timeoutInterval: timeout)
        r.httpMethod = metodo
        r.setValue("application/json", forHTTPHeaderField: "Accept")
        if let token { r.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization") }
        cabeceras.forEach { r.setValue($1, forHTTPHeaderField: $0) }
        if let cuerpo { r.httpBody = try JSONSerialization.data(withJSONObject: cuerpo); r.setValue("application/json", forHTTPHeaderField: "Content-Type") }
        if let datos { r.httpBody = datos; r.setValue("application/offset+octet-stream", forHTTPHeaderField: "Content-Type") }
        let (d, resp): (Data, URLResponse)
        do { (d, resp) = try await URLSession.shared.data(for: r) }
        catch let e as URLError {
            switch e.code {
            case .cannotConnectToHost, .cannotFindHost: throw ErrorNas(estado: 0, codigo: "ECONNREFUSED", mensaje: "El NAS no responde. ¿Estás en el Wi-Fi de casa o con Tailscale encendido?")
            case .timedOut: throw ErrorNas(estado: 0, codigo: "ETIMEDOUT", mensaje: "El NAS tardó demasiado en responder.")
            case .serverCertificateUntrusted, .secureConnectionFailed: throw ErrorNas(estado: 0, codigo: "certificado", mensaje: "El certificado HTTPS del NAS no es de confianza en este iPhone (ver guía, sección HTTPS).")
            default: throw ErrorNas(estado: 0, codigo: "red", mensaje: "Error de red: \(e.localizedDescription)")
            }
        }
        let codigo = (resp as? HTTPURLResponse)?.statusCode ?? 0
        let j = (try? JSONSerialization.jsonObject(with: d)) as? [String: Any] ?? [:]
        if codigo >= 400 { throw ErrorNas(estado: codigo, codigo: j["error"] as? String ?? "http_\(codigo)", mensaje: j["mensaje"] as? String ?? "Error HTTP \(codigo)", datos: j) }
        return j
    }

    func iniciarSesion(_ u: String, _ p: String) async throws -> [String: Any] {
        let j = try await json("POST", "/api/v1/sesion", ["usuario": u, "contrasena": p, "equipo": "\(UIDevice.current.name) (iOS)"])
        token = j["token"] as? String; Llavero.guardar(token); return j
    }

    /// Subida reanudable en trozos de 8 MB (protocolo en docs/protocolo.md).
    func subir(_ url: URL, meta: [String: Any], progreso: @escaping (Double) -> Void) async throws -> [String: Any] {
        let total = (try FileManager.default.attributesOfItem(atPath: url.path)[.size] as? NSNumber)?.int64Value ?? 0
        var m = meta; m["tamano"] = total
        var r = try await json("POST", "/api/v1/subidas", m)
        let h = try FileHandle(forReadingFrom: url); defer { try? h.close() }
        let id = meta["id"] as! String
        while (r["estado"] as? String) != "guardado" {
            let desde = (r["desplazamiento"] as? NSNumber)?.int64Value ?? 0
            progreso(Double(desde) / Double(max(total, 1)))
            try h.seek(toOffset: UInt64(desde))
            let trozo = h.readData(ofLength: 8 * 1024 * 1024)
            do { r = try await json("PATCH", "/api/v1/subidas/\(id)", timeout: 120, datos: trozo, cabeceras: ["Upload-Offset": String(desde)]) }
            catch let e as ErrorNas where e.codigo == "desplazamiento_incorrecto" { r = ["estado": "incompleta", "desplazamiento": e.datos["desplazamiento"] ?? 0] }
        }
        progreso(1)
        return r
    }
}

enum Sincronizador {
    static func sha256(_ url: URL) throws -> String {
        let h = try FileHandle(forReadingFrom: url); defer { try? h.close() }
        var s = SHA256()
        while case let d = h.readData(ofLength: 1 << 20), !d.isEmpty { s.update(data: d) }
        return s.finalize().map { String(format: "%02x", $0) }.joined()
    }

    @MainActor
    static func ejecutar(_ bib: Biblioteca, forzar: Bool = false) async {
        let aj = UserDefaults.standard
        let direccion = aj.string(forKey: "direccion") ?? ""
        for var g in bib.items.sorted(by: { $0.creado < $1.creado }) {
            if aj.bool(forKey: "galeria"), ["pendiente", "no_configurado"].contains(g.destinos["galeria"]?.estado ?? ""), let u = g.url {
                g.destinos["galeria"] = await guardarEnFotos(u); bib.actualizar(g)
            }
            guard !direccion.isEmpty, Llavero.leer() != nil else { continue }
            if forzar, g.destinos["nas"]?.estado == "requiere_atencion" || g.destinos["nas"]?.estado == "no_configurado" { g.destinos["nas"]?.estado = "pendiente" }
            if g.destinos["nas"]?.estado == "pendiente", let u = g.url {
                g.destinos["nas"] = Destino(estado: "copiando"); bib.actualizar(g)
                do {
                    if g.sha256 == nil { g.sha256 = try sha256(u) }
                    let meta: [String: Any] = ["id": g.id, "sha256": g.sha256!, "titulo": g.titulo, "carpeta": aj.string(forKey: "carpeta") ?? "",
                        "creado": g.creado, "creadoLocal": g.creadoLocal, "duracion": g.duracion, "notas": g.notas, "etiquetas": g.etiquetas, "favorito": g.favorito,
                        "marcas": g.marcas.map { ["t": $0.t, "nota": $0.nota] }, "recorteDe": g.recorteDe as Any,
                        "origen": ["plataforma": "ios", "equipo": UIDevice.current.model, "modo": g.origen]]
                    let id = g.id
                    let r = try await ClienteNas(direccion).subir(u, meta: meta) { p in
                        Task { @MainActor in if let i = bib.items.firstIndex(where: { $0.id == id }) { bib.items[i].destinos["nas"]?.progreso = p } }
                    }
                    let rem = r["grabacion"] as? [String: Any] ?? [:]
                    g.destinos["nas"] = Destino(estado: "guardado", progreso: 1, ruta: rem["rutaRelativa"] as? String)
                    let f = rem["fotosJse"] as? [String: Any] ?? [:]
                    g.destinos["fotosJse"] = Destino(estado: f["estado"] as? String ?? "no_configurado", motivo: f["motivo"] as? String)
                } catch let e as ErrorNas {
                    g.destinos["nas"] = Destino(estado: e.requiereUsuario ? "requiere_atencion" : "pendiente", motivo: e.mensaje, codigo: e.codigo)
                } catch {
                    g.destinos["nas"] = Destino(estado: "pendiente", motivo: error.localizedDescription)
                }
                bib.actualizar(g)
            }
            if g.destinos["nas"]?.estado == "guardado", g.metaPendiente {
                if (try? await ClienteNas(direccion).json("PATCH", "/api/v1/grabaciones/\(g.id)", ["titulo": g.titulo, "notas": g.notas, "etiquetas": g.etiquetas, "favorito": g.favorito, "marcas": g.marcas.map { ["t": $0.t, "nota": $0.nota] }])) != nil {
                    g.metaPendiente = false; bib.actualizar(g)
                }
            }
            if g.destinos["nas"]?.estado == "guardado", g.destinos["fotosJse"]?.estado == "exportado",
               let f = try? await ClienteNas(direccion).json("POST", "/api/v1/grabaciones/\(g.id)/fotos-jse", timeout: 200) {
                g.destinos["fotosJse"] = Destino(estado: f["estado"] as? String ?? "exportado", motivo: f["motivo"] as? String); bib.actualizar(g)
            }
        }
    }

    /// Fotos: permiso de solo agregar (no lee tu fototeca).
    static func guardarEnFotos(_ url: URL) async -> Destino {
        let s = await PHPhotoLibrary.requestAuthorization(for: .addOnly)
        guard s == .authorized || s == .limited else { return Destino(estado: "requiere_atencion", motivo: "Sin permiso para agregar a Fotos. Actívalo en Ajustes → JSE Captura → Fotos → Solo agregar.") }
        do {
            try await PHPhotoLibrary.shared().performChanges { PHAssetCreationRequest.creationRequestForAssetFromVideo(atFileURL: url) }
            return Destino(estado: "guardado", ruta: "Fotos")
        } catch { return Destino(estado: "requiere_atencion", motivo: "No se pudo guardar en Fotos: \(error.localizedDescription)") }
    }
}
