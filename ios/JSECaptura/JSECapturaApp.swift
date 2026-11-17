import SwiftUI
import ReplayKit
import AVKit
import PhotosUI
import UniformTypeIdentifiers

@main
struct JSECapturaApp: App {
    @StateObject private var bib = Biblioteca.compartida
    @Environment(\.scenePhase) private var fase

    var body: some Scene {
        WindowGroup {
            Raiz().environmentObject(bib).preferredColorScheme(.dark)
        }
        .onChange(of: fase) { f in
            if f == .active { Task { await Importador.importarTransmisiones(bib); await Sincronizador.ejecutar(bib) } }
        }
    }
}

struct Raiz: View {
    var body: some View {
        TabView {
            GrabarVista().tabItem { Label("Grabar", systemImage: "record.circle") }
            BibliotecaVista().tabItem { Label("Biblioteca", systemImage: "film.stack") }
            NasVista().tabItem { Label("NAS", systemImage: "externaldrive.connected.to.line.below") }
            AjustesVista().tabItem { Label("Ajustes", systemImage: "gearshape") }
        }
        // Versión arriba a la derecha en todas las pantallas.
        .overlay(alignment: .topTrailing) {
            Text("v\(Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "?")")
                .font(.caption2.monospacedDigit()).foregroundStyle(.secondary).padding(.trailing, 10).padding(.top, 2).allowsHitTesting(false)
        }
    }
}

// MARK: - Importar lo que grabó la extensión de transmisión

enum Importador {
    static var grupo: URL? { FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: "group.mx.jse.captura")?.appendingPathComponent("Grabando", isDirectory: true) }

    /// Importa cada MP4 terminado (.listo). Si la extensión se cerró de golpe (sin .listo) y el archivo lleva >2 min sin cambiar,
    /// se intenta recuperar: el MP4 está fragmentado cada 5 s, así que lo grabado hasta el último fragmento se puede leer.
    @MainActor
    static func importarTransmisiones(_ bib: Biblioteca) async {
        guard let dir = grupo, let lista = try? FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: [.contentModificationDateKey]) else { return }
        for json in lista where json.pathExtension == "json" {
            let id = json.deletingPathExtension().lastPathComponent
            let mp4 = dir.appendingPathComponent("\(id).mp4"), listo = dir.appendingPathComponent("\(id).listo")
            let terminado = FileManager.default.fileExists(atPath: listo.path)
            let mod = (try? mp4.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? .distantPast
            guard terminado || Date().timeIntervalSince(mod) > 120 else { continue }
            let s = (try? JSONSerialization.jsonObject(with: Data(contentsOf: json))) as? [String: Any] ?? [:]
            let limpiar = { [json, mp4, listo] in for u in [json, mp4, listo] { try? FileManager.default.removeItem(at: u) } }
            guard FileManager.default.fileExists(atPath: mp4.path) else { limpiar(); continue }
            let creadoLocal = s["creadoLocal"] as? String ?? Biblioteca.fechaLocal()
            let nombre = "\(creadoLocal.replacingOccurrences(of: ":", with: ".").replacingOccurrences(of: "T", with: " ")) [\(id.prefix(8))].mp4"
            let final = Biblioteca.videos.appendingPathComponent(nombre)
            do {
                // Se reescribe con faststart (moov al inicio) para que abra rápido en VLC/mpv y en Fotos JSE.
                try await Medios.normalizar(mp4, final) { _ in }
                let v = await Medios.validar(final)
                guard v.valido else { throw NSError(domain: "JSE", code: 6, userInfo: [NSLocalizedDescriptionKey: v.motivo ?? "video inválido"]) }
                var g = Grabacion(id: id, titulo: "Grabación \(creadoLocal.replacingOccurrences(of: "T", with: " ").prefix(16))", creado: s["creado"] as? String ?? ISO8601DateFormatter().string(from: Date()),
                                  creadoLocal: creadoLocal, archivo: nombre, duracion: v.duracion, tamano: tamano(final),
                                  origen: (s["origen"] as? String ?? "iPhone") + (terminado ? "" : " · recuperada tras un cierre inesperado"))
                if UserDefaults.standard.string(forKey: "direccion")?.isEmpty ?? true { g.destinos["nas"] = Destino(estado: "no_configurado"); g.destinos["fotosJse"] = Destino(estado: "no_configurado") }
                await Medios.miniatura(final, g.miniatura)
                bib.agregar(g)
                limpiar()
            } catch {
                try? FileManager.default.removeItem(at: final)
                bib.aviso = "No se pudo importar una grabación (\(error.localizedDescription)). Quedó en la carpeta Grabando del contenedor; se reintentará."
            }
        }
    }

    static func tamano(_ u: URL) -> Int64 { (try? FileManager.default.attributesOfItem(atPath: u.path)[.size] as? NSNumber)?.int64Value ?? 0 }

    /// Importa un video de Fotos o de Archivos (p. ej. una grabación hecha con el botón Grabar del Centro de control).
    @MainActor
    static func importarArchivo(_ origen: URL, _ bib: Biblioteca, progreso: @escaping (Double) -> Void) async throws {
        let id = UUID().uuidString.lowercased(), creadoLocal = Biblioteca.fechaLocal()
        let nombre = "\(creadoLocal.replacingOccurrences(of: ":", with: ".").replacingOccurrences(of: "T", with: " ")) importado [\(id.prefix(8))].mp4"
        let final = Biblioteca.videos.appendingPathComponent(nombre)
        try await Medios.normalizar(origen, final, progreso: progreso)
        let v = await Medios.validar(final)
        guard v.valido else { try? FileManager.default.removeItem(at: final); throw NSError(domain: "JSE", code: 7, userInfo: [NSLocalizedDescriptionKey: v.motivo ?? "video inválido"]) }
        var g = Grabacion(id: id, titulo: origen.deletingPathExtension().lastPathComponent, creado: ISO8601DateFormatter().string(from: Date()), creadoLocal: creadoLocal,
                          archivo: nombre, duracion: v.duracion, tamano: tamano(final), origen: "iPhone · importado")
        if UserDefaults.standard.string(forKey: "direccion")?.isEmpty ?? true { g.destinos["nas"] = Destino(estado: "no_configurado"); g.destinos["fotosJse"] = Destino(estado: "no_configurado") }
        await Medios.miniatura(final, g.miniatura)
        bib.agregar(g)
    }
}

/// Video elegido en Fotos, copiado a un archivo temporal.
struct VideoTransferible: Transferable {
    let url: URL
    static var transferRepresentation: some TransferRepresentation {
        FileRepresentation(contentType: .movie) { SentTransferredFile($0.url) } importing: { r in
            let destino = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + "." + r.file.pathExtension)
            try FileManager.default.copyItem(at: r.file, to: destino)
            return Self(url: destino)
        }
    }
}

// MARK: - Grabar

/// Botón oficial del sistema (RPSystemBroadcastPickerView) preseleccionando la extensión de JSE Captura.
struct BotonTransmision: UIViewRepresentable {
    func makeUIView(context: Context) -> RPSystemBroadcastPickerView {
        let v = RPSystemBroadcastPickerView(frame: CGRect(x: 0, y: 0, width: 96, height: 96))
        v.preferredExtension = "mx.jse.captura.Transmision"
        v.showsMicrophoneButton = true
        if let b = v.subviews.compactMap({ $0 as? UIButton }).first {
            b.imageView?.tintColor = .systemRed
            b.setImage(UIImage(systemName: "record.circle.fill", withConfiguration: UIImage.SymbolConfiguration(pointSize: 72)), for: .normal)
        }
        return v
    }
    func updateUIView(_ v: RPSystemBroadcastPickerView, context: Context) {}
}

struct GrabarVista: View {
    @EnvironmentObject var bib: Biblioteca
    @State private var fotos: PhotosPickerItem?
    @State private var archivos = false
    @State private var estado: String?
    @State private var progreso: Double?

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 18) {
                    BotonTransmision().frame(width: 96, height: 96)
                    Text("Toca el botón, elige «JSE Captura» y «Iniciar transmisión».\nGraba toda la pantalla aunque cambies de app; la barra o la burbuja roja de arriba indica que está grabando.")
                        .multilineTextAlignment(.center).font(.callout).foregroundStyle(.secondary)
                    GroupBox("Para detener") {
                        Text("Toca la burbuja/barra roja de la parte superior → Detener, o abre el Centro de control y toca el botón de grabar. Al volver a JSE Captura el MP4 aparece en la Biblioteca.")
                            .font(.footnote).frame(maxWidth: .infinity, alignment: .leading)
                    }
                    GroupBox("Desde el Centro de control") {
                        Text("Mantén pulsado el botón Grabar pantalla → elige JSE Captura → Iniciar transmisión. Si usaste la grabación normal de iOS (va a Fotos), impórtala abajo.")
                            .font(.footnote).frame(maxWidth: .infinity, alignment: .leading)
                    }
                    HStack {
                        PhotosPicker("Importar de Fotos", selection: $fotos, matching: .videos).buttonStyle(.bordered)
                        Button("Importar de Archivos") { archivos = true }.buttonStyle(.bordered)
                    }
                    if let progreso { ProgressView(value: progreso) }
                    if let estado { Text(estado).font(.footnote) }
                    if let a = bib.aviso { Text(a).font(.footnote).foregroundStyle(.orange) }
                }.padding()
            }
            .navigationTitle("JSE Captura")
            .onChange(of: fotos) { item in
                guard let item else { return }
                Task {
                    defer { fotos = nil }
                    guard let v = try? await item.loadTransferable(type: VideoTransferible.self) else { estado = "No se pudo leer el video de Fotos."; return }
                    await importar(v.url, temporal: true)
                }
            }
            .fileImporter(isPresented: $archivos, allowedContentTypes: [.movie, .mpeg4Movie, .quickTimeMovie]) { r in
                if case .success(let u) = r { Task { let ok = u.startAccessingSecurityScopedResource(); defer { if ok { u.stopAccessingSecurityScopedResource() } }; await importar(u, temporal: false) } }
            }
        }
    }

    @MainActor func importar(_ u: URL, temporal: Bool) async {
        estado = "Convirtiendo a MP4 H.264…"; progreso = 0
        do {
            try await Importador.importarArchivo(u, bib) { p in Task { @MainActor in progreso = p } }
            estado = "Listo: agregado a la Biblioteca."
            await Sincronizador.ejecutar(bib)
        } catch { estado = "No se pudo importar: \(error.localizedDescription)" }
        progreso = nil
        if temporal { try? FileManager.default.removeItem(at: u) }
    }
}

// MARK: - Biblioteca

struct BibliotecaVista: View {
    @EnvironmentObject var bib: Biblioteca
    @State private var q = ""
    @State private var pendientes = false
    @State private var favoritos = false
    @State private var sincronizando = false

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Toggle("Solo pendientes", isOn: $pendientes)
                    Toggle("Solo favoritos", isOn: $favoritos)
                }
                ForEach(bib.filtrar(q, pendientes: pendientes, favoritos: favoritos)) { g in
                    NavigationLink(value: g.id) { Fila(g: g) }
                }
                if bib.items.isEmpty { Text("Aún no hay grabaciones.").foregroundStyle(.secondary) }
            }
            .searchable(text: $q, prompt: "Buscar por título, notas o etiquetas")
            .navigationTitle("Biblioteca")
            .navigationDestination(for: String.self) { ReproductorVista(id: $0) }
            .toolbar {
                Button { Task { sincronizando = true; await Sincronizador.ejecutar(bib, forzar: true); sincronizando = false } } label: {
                    if sincronizando { ProgressView() } else { Label("Sincronizar ahora", systemImage: "arrow.triangle.2.circlepath") }
                }
            }
            .refreshable { await Importador.importarTransmisiones(bib); await Sincronizador.ejecutar(bib, forzar: true) }
        }
    }
}

struct Fila: View {
    let g: Grabacion
    var body: some View {
        HStack(spacing: 12) {
            AsyncImage(url: g.miniatura) { $0.resizable().scaledToFill() } placeholder: { Color.gray.opacity(0.3) }
                .frame(width: 88, height: 56).clipShape(RoundedRectangle(cornerRadius: 6))
            VStack(alignment: .leading, spacing: 3) {
                Text((g.favorito ? "★ " : "") + g.titulo).font(.subheadline.bold()).lineLimit(1)
                Text("\(Formato.duracion(g.duracion)) · \(Formato.tamano(g.tamano)) · \(g.creadoLocal.replacingOccurrences(of: "T", with: " ").prefix(16))").font(.caption).foregroundStyle(.secondary)
                HStack(spacing: 6) {
                    Chip("iPhone", g.destinos["dispositivo"]); Chip("NAS", g.destinos["nas"]); Chip("Fotos JSE", g.destinos["fotosJse"])
                }
            }
        }
    }
}

struct Chip: View {
    let n: String; let d: Destino?
    init(_ n: String, _ d: Destino?) { self.n = n; self.d = d }
    var color: Color {
        switch d?.estado { case "guardado", "visible": return .green; case "copiando", "exportado": return .blue; case "requiere_atencion": return .orange; case "pendiente": return .yellow; default: return .gray }
    }
    var body: some View {
        Text("\(n): \(d?.texto ?? "—")").font(.caption2).padding(.horizontal, 6).padding(.vertical, 2)
            .background(color.opacity(0.2)).foregroundStyle(color).clipShape(Capsule())
    }
}

enum Formato {
    static func duracion(_ s: Double) -> String { let t = Int(s.rounded()); return t >= 3600 ? String(format: "%d:%02d:%02d", t / 3600, t / 60 % 60, t % 60) : String(format: "%d:%02d", t / 60, t % 60) }
    static func tamano(_ b: Int64) -> String { ByteCountFormatter.string(fromByteCount: b, countStyle: .file) }
}

// MARK: - Reproductor, marcas, edición, recorte, borrado

struct ReproductorVista: View {
    @EnvironmentObject var bib: Biblioteca
    @Environment(\.dismiss) var cerrar
    let id: String
    @State private var reproductor: AVPlayer?
    @State private var g: Grabacion?
    @State private var etiquetas = ""
    @State private var ini = 0.0
    @State private var fin = 0.0
    @State private var aviso: String?
    @State private var confirmarLocal = false
    @State private var confirmarNas = false

    var ahora: Double { reproductor.map { CMTimeGetSeconds($0.currentTime()) }.flatMap { $0.isFinite ? $0 : nil } ?? 0 }

    var body: some View {
        Form {
            if let r = reproductor { Section { VideoPlayer(player: r).frame(height: 240) } }
            else { Section { Text(g?.destinos["nas"]?.estado == "guardado" ? "Este video ya no está en el iPhone; ábrelo desde la pestaña NAS." : "Archivo no disponible.").foregroundStyle(.secondary) } }
            if g != nil {
                Section("Datos") {
                    TextField("Título", text: Binding(get: { g!.titulo }, set: { g!.titulo = $0 }))
                    TextField("Notas", text: Binding(get: { g!.notas }, set: { g!.notas = $0 }), axis: .vertical)
                    TextField("Etiquetas, separadas por coma", text: $etiquetas)
                    Toggle("Favorito", isOn: Binding(get: { g!.favorito }, set: { g!.favorito = $0 }))
                    Button("Guardar cambios") { guardar() }
                }
                Section("Marcas") {
                    Button("⚑ Marcar aquí") { g!.marcas.append(Marca(t: ahora)); g!.marcas.sort { $0.t < $1.t }; guardar() }
                    ForEach(g!.marcas, id: \.self) { m in
                        Button("⚑ \(Formato.duracion(m.t))\(m.nota.isEmpty ? "" : " · \(m.nota)")") { reproductor?.seek(to: CMTime(seconds: m.t, preferredTimescale: 600)); reproductor?.play() }
                    }.onDelete { g!.marcas.remove(atOffsets: $0); guardar() }
                }
                Section("✂ Recortar (crea un MP4 nuevo)") {
                    HStack { Text("Inicio \(Formato.duracion(ini))"); Spacer(); Button("← aquí") { ini = ahora } }
                    HStack { Text("Fin \(Formato.duracion(fin))"); Spacer(); Button("← aquí") { fin = ahora } }
                    Button("▶ Vista previa") {
                        reproductor?.seek(to: CMTime(seconds: ini, preferredTimescale: 600)); reproductor?.play()
                        DispatchQueue.main.asyncAfter(deadline: .now() + max(0, fin - ini)) { reproductor?.pause() }
                    }
                    Button("Guardar recorte") { Task { await recortar() } }
                }
                Section("Dónde está") {
                    ForEach([("iPhone", "dispositivo"), ("NAS", "nas"), ("Fotos JSE", "fotosJse"), ("Fotos del iPhone", "galeria")], id: \.1) { n, k in
                        if let d = g!.destinos[k] {
                            VStack(alignment: .leading) {
                                Text("\(n): \(d.texto)")
                                if let r = d.ruta { Text(r).font(.caption).foregroundStyle(.secondary) }
                                if let m = d.motivo { Text(m).font(.caption).foregroundStyle(.orange) }
                            }
                        }
                    }
                    Text("\(Formato.duracion(g!.duracion)) · \(Formato.tamano(g!.tamano)) · \(g!.origen)").font(.caption)
                    if let u = g!.url { ShareLink("Compartir / abrir con VLC…", item: u) }
                    Button("Guardar en Fotos del iPhone") { Task { if let u = g!.url { g!.destinos["galeria"] = await Sincronizador.guardarEnFotos(u); guardar() } } }
                }
                Section {
                    Button("Borrar del iPhone…", role: .destructive) { confirmarLocal = true }.disabled(g!.url == nil)
                    Button("Borrar del NAS…", role: .destructive) { confirmarNas = true }.disabled(g!.destinos["nas"]?.estado != "guardado")
                }
                if let aviso { Section { Text(aviso).font(.footnote) } }
            }
        }
        .navigationTitle(g?.titulo ?? "")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear {
            g = bib.items.first { $0.id == id }
            etiquetas = g?.etiquetas.joined(separator: ", ") ?? ""
            fin = g?.duracion ?? 0
            if let u = g?.url, FileManager.default.fileExists(atPath: u.path) { reproductor = AVPlayer(url: u) }
        }
        .onDisappear { reproductor?.pause() }
        .confirmationDialog("Borrar del iPhone", isPresented: $confirmarLocal, titleVisibility: .visible) {
            Button("Borrar del iPhone", role: .destructive) { bib.borrarLocal(id); cerrar() }
        } message: {
            Text(g?.destinos["nas"]?.estado == "guardado" ? "Se borra solo la copia de este iPhone. La del NAS y la de Fotos no se tocan." : "¡Ojo! Todavía NO está en el NAS: es la única copia de JSE Captura.")
        }
        .confirmationDialog("Borrar del NAS", isPresented: $confirmarNas, titleVisibility: .visible) {
            Button("Borrar del NAS", role: .destructive) { Task { await borrarNas() } }
        } message: {
            Text("Se borra \(g?.destinos["nas"]?.ruta ?? "el archivo") del NAS (y de Fotos JSE si comparten carpeta). La copia del iPhone no se toca.")
        }
    }

    func guardar() {
        guard var x = g else { return }
        x.etiquetas = etiquetas.split(separator: ",").map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }
        if x.destinos["nas"]?.estado == "guardado" { x.metaPendiente = true }
        g = x; bib.actualizar(x)
        Task { await Sincronizador.ejecutar(bib) }
    }

    func recortar() async {
        guard let x = g, let u = x.url, fin > ini, ini >= 0, fin <= x.duracion + 0.1 else { aviso = "Rango de recorte inválido."; return }
        aviso = "Creando el recorte…"
        let nid = UUID().uuidString.lowercased()
        let nombre = u.deletingPathExtension().lastPathComponent + " recorte \(nid.prefix(4)).mp4"
        let destino = Biblioteca.videos.appendingPathComponent(nombre)
        do {
            try await Medios.recortar(u, destino, ini, fin)
            let v = await Medios.validar(destino)
            guard v.valido else { throw NSError(domain: "JSE", code: 8, userInfo: [NSLocalizedDescriptionKey: v.motivo ?? "video inválido"]) }
            var n = Grabacion(id: nid, titulo: "\(x.titulo) (recorte)", creado: ISO8601DateFormatter().string(from: Date()), creadoLocal: Biblioteca.fechaLocal(),
                              archivo: nombre, duracion: v.duracion, tamano: Importador.tamano(destino), origen: "Recorte de \(x.titulo)")
            n.etiquetas = x.etiquetas; n.recorteDe = x.id
            n.marcas = x.marcas.filter { $0.t >= ini && $0.t <= fin }.map { Marca(t: $0.t - ini, nota: $0.nota) }
            if UserDefaults.standard.string(forKey: "direccion")?.isEmpty ?? true { n.destinos["nas"] = Destino(estado: "no_configurado"); n.destinos["fotosJse"] = Destino(estado: "no_configurado") }
            await Medios.miniatura(destino, n.miniatura)
            bib.agregar(n)
            aviso = "Listo: «\(n.titulo)» (\(Formato.duracion(n.duracion))). El original no cambió."
            await Sincronizador.ejecutar(bib)
        } catch { try? FileManager.default.removeItem(at: destino); aviso = "No se pudo recortar: \(error.localizedDescription)" }
    }

    func borrarNas() async {
        guard var x = g, let d = UserDefaults.standard.string(forKey: "direccion") else { return }
        do {
            _ = try await ClienteNas(d).json("DELETE", "/api/v1/grabaciones/\(x.id)")
            x.destinos["nas"] = Destino(estado: "no_configurado", motivo: "Borrado del NAS por ti. «Sincronizar ahora» lo vuelve a copiar.")
            x.destinos["fotosJse"] = Destino(estado: "no_configurado")
            g = x; bib.actualizar(x)
        } catch { aviso = "No se pudo borrar del NAS: \(error.localizedDescription)" }
    }
}

// MARK: - Catálogo del NAS (solo con token válido)

struct NasVista: View {
    @State private var items: [[String: Any]] = []
    @State private var error: String?
    @State private var cargando = false

    var body: some View {
        NavigationStack {
            List {
                if let error { Text(error).foregroundStyle(.orange) }
                ForEach(items.indices, id: \.self) { i in
                    let x = items[i]
                    Button {
                        Task { await abrir(x["id"] as? String ?? "") }
                    } label: {
                        VStack(alignment: .leading) {
                            Text(x["titulo"] as? String ?? "").bold()
                            Text("\(Formato.duracion((x["duracion"] as? NSNumber)?.doubleValue ?? 0)) · \(x["rutaRelativa"] as? String ?? "")").font(.caption).foregroundStyle(.secondary)
                        }
                    }
                }
            }
            .navigationTitle("En el NAS")
            .toolbar { if cargando { ProgressView() } }
            .task { await cargar() }
            .refreshable { await cargar() }
        }
    }

    func cargar() async {
        guard let d = UserDefaults.standard.string(forKey: "direccion"), !d.isEmpty, Llavero.leer() != nil else { error = "Vincula el NAS en Ajustes."; return }
        cargando = true; defer { cargando = false }
        do { items = try await ClienteNas(d).json("GET", "/api/v1/grabaciones?limite=500")["elementos"] as? [[String: Any]] ?? []; error = nil }
        catch { self.error = error.localizedDescription }
    }

    /// Pide al NAS un enlace firmado temporal y lo abre en el reproductor del sistema (o cópialo a VLC).
    func abrir(_ id: String) async {
        guard let d = UserDefaults.standard.string(forKey: "direccion") else { return }
        do {
            let c = ClienteNas(d)
            let r = try await c.json("GET", "/api/v1/grabaciones/\(id)/enlace")
            if let ruta = r["ruta"] as? String, let u = URL(string: ruta, relativeTo: c.base) { await UIApplication.shared.open(u) }
        } catch { self.error = error.localizedDescription }
    }
}

// MARK: - Ajustes

struct AjustesVista: View {
    @EnvironmentObject var bib: Biblioteca
    @AppStorage("direccion") private var direccion = ""
    @AppStorage("carpeta") private var carpeta = ""
    @AppStorage("galeria") private var galeria = false
    @State private var usuario = "jse-captura"
    @State private var contrasena = ""
    @State private var estado: String?
    @State private var vinculado = Llavero.leer() != nil

    var body: some View {
        NavigationStack {
            Form {
                Section("NAS") {
                    TextField("Dirección (ej. 192.168.1.50:8790)", text: $direccion).keyboardType(.URL).textInputAutocapitalization(.never).autocorrectionDisabled()
                    if vinculado {
                        Label("Vinculado", systemImage: "checkmark.seal").foregroundStyle(.green)
                        Button("Probar conexión") { Task { await probar() } }
                        Button("Desvincular este iPhone", role: .destructive) { Task { await desvincular() } }
                    } else {
                        TextField("Usuario", text: $usuario).textInputAutocapitalization(.never)
                        SecureField("Contraseña del servicio", text: $contrasena)
                        Button("Vincular") { Task { await vincular() } }.disabled(direccion.isEmpty || contrasena.isEmpty)
                    }
                    TextField("Subcarpeta en el NAS (opcional)", text: $carpeta).textInputAutocapitalization(.never)
                    if let estado { Text(estado).font(.footnote) }
                }
                Section("Fotos del iPhone") {
                    Toggle("Guardar cada grabación también en Fotos", isOn: $galeria)
                    Text("Usa el permiso «Solo agregar»: JSE Captura no lee tu fototeca.").font(.caption).foregroundStyle(.secondary)
                }
                Section("Calidad") {
                    Text("Hasta 1080p a 30 cuadros, H.264 + AAC. Los MP4 abren en VLC, mpv y cualquier reproductor.").font(.caption)
                }
            }
            .navigationTitle("Ajustes")
        }
    }

    func vincular() async {
        do {
            _ = try await ClienteNas(direccion, token: nil).iniciarSesion(usuario, contrasena)
            contrasena = ""; vinculado = true; estado = "Listo. Las grabaciones pendientes se copiarán ahora."
            for i in bib.items.indices where bib.items[i].destinos["nas"]?.estado == "no_configurado" && bib.items[i].url != nil {
                bib.items[i].destinos["nas"] = Destino(); bib.items[i].destinos["fotosJse"] = Destino()
            }
            bib.guardar()
            await Sincronizador.ejecutar(bib, forzar: true)
        } catch { estado = "No se pudo vincular: \(error.localizedDescription)" }
    }

    func probar() async {
        do {
            let r = try await ClienteNas(direccion).json("GET", "/api/v1/prueba")
            let libre = (r["espacioLibre"] as? NSNumber)?.int64Value
            estado = "Conexión correcta" + (libre.map { " · libre en el NAS: \(Formato.tamano($0))" } ?? "")
        } catch { estado = error.localizedDescription }
    }

    func desvincular() async {
        _ = try? await ClienteNas(direccion).json("DELETE", "/api/v1/sesion")
        Llavero.guardar(nil); vinculado = false; estado = "Este iPhone ya no tiene acceso al NAS."
    }
}
