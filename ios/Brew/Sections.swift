import RcComposePlayerSwiftUI
import SwiftUI

/// Where a section's bytes came from: the backend, or the copy bundled in the app.
enum Origin { case live, bundled }

struct Section {
    let data: Data
    let origin: Origin
}

extension String {
    /// A value for a URL query (ids come from documents; never paste them into a URL raw).
    var urlQueryValue: String {
        addingPercentEncoding(withAllowedCharacters: .alphanumerics.union(CharacterSet(charactersIn: ",-_"))) ?? ""
    }
}

/**
 Loads one section ("hero?i=0", "favorites", "details?id=caramel" …) from the backend — the very same bytes the
 Android app gets. Offline it falls back to the copy in the app bundle (written by go run ./cmd/demo render).
 On the main actor: several sections load at once, and all of them use the cache and the warning flag.
 */
@MainActor
enum SectionSource {
    static let baseURL = URL(string: "http://127.0.0.1:8080/sections/")!
    static var cache: [String: Section] = [:]
    /// Anything bigger is refused (the player's own limit is 32 MB unpacked).
    static let maxBytes = 32 * 1024 * 1024

    /**
     A basic check before the player sees the bytes: a Remote Compose document starts with its HEADER operation
     (opcode 0) and is not absurdly small or large. The Android app runs a full structural check (DocumentValidator,
     which also catches truncation); there is no Swift port of it yet.
     */
    static func looksValid(_ data: Data) -> Bool { data.count >= 16 && data.count <= maxBytes && data.first == 0 }

    /// [bundledOnly]: sections only the app bundle has (the Lab's shader documents are not served by the backend).
    static func load(_ path: String, bundledOnly: Bool = false) async -> Section? {
        if !bundledOnly, let s = await download(path) {
            cache[path] = s
            return s
        }
        // Cancelled (the view moved on to another section): no fallback, the caller drops the result anyway.
        if Task.isCancelled { return nil }
        let name = path.replacingOccurrences(of: "?", with: "_").replacingOccurrences(of: "=", with: "-")
            .replacingOccurrences(of: ",", with: "+")
        if let url = Bundle.main.url(forResource: name, withExtension: "rc", subdirectory: "sections"),
           let data = try? Data(contentsOf: url), looksValid(data) {
            let s = Section(data: data, origin: .bundled)
            cache[path] = s
            return s
        }
        return nil
    }

    private static func download(_ path: String) async -> Section? {
        guard let url = URL(string: path, relativeTo: baseURL) else { return nil }
        var req = URLRequest(url: url)
        req.timeoutInterval = 2
        req.cachePolicy = .reloadIgnoringLocalCacheData
        do {
            let (data, resp) = try await URLSession.shared.data(for: req)
            let code = (resp as? HTTPURLResponse)?.statusCode ?? 0
            if warned { warned = false; print("Brew: backend reachable again at \(baseURL.absoluteString)") }
            guard code == 200 else {
                print("Brew: the backend answered HTTP \(code) for /sections/\(path); using the bundled copy.")
                return nil
            }
            guard looksValid(data) else {
                print("Brew: rejected /sections/\(path) from the backend (\(data.count) bytes, not a document); using the bundled copy.")
                return nil
            }
            return Section(data: data, origin: .live)
        } catch is CancellationError {
            return nil
        } catch let error as URLError where error.code == .cancelled {
            return nil
        } catch {
            unreachable(error.localizedDescription)
            return nil
        }
    }

    private static var warned = false

    /// Printed once when the backend can't be reached: what happened and how to fix it (Xcode's console).
    private static func unreachable(_ reason: String) {
        guard !warned else { return }
        warned = true
        print("""
        Brew: backend not reachable at \(baseURL.absoluteString) (\(reason)).
        The app keeps working with the copies bundled in the app (Brew/Resources/sections).
        To get live sections from the server:
          1. Start it:  cd backend && go run ./cmd/demo serve   (the first start builds the JSON converter, about a minute)
          2. The simulator reaches it at 127.0.0.1:8080 directly. A real iPhone needs the computer's IP:
             start it with  go run ./cmd/demo serve -lan  and set SectionSource.baseURL to http://<your-computer-ip>:8080/sections/
          3. Check on the computer:  curl http://127.0.0.1:8080/sections/promo.json
        Then pull to refresh on Home.
        """)
    }

    /// Which banners the carousel shows, in order: the server picks them from its pool, a new random choice on every
    /// request (every pull to refresh), as a JSON array of indices, e.g. [3,0,4] → hero?i=3, hero?i=0, hero?i=4.
    /// Untrusted input: non-negative distinct ints only, at most 6. Offline: the two banners bundled in the app.
    static func bannerIDs() async -> [Int] {
        let offline = [0, 1]
        var req = URLRequest(url: URL(string: "banners", relativeTo: baseURL)!)
        req.timeoutInterval = 2
        req.cachePolicy = .reloadIgnoringLocalCacheData
        guard let (data, resp) = try? await URLSession.shared.data(for: req),
              (resp as? HTTPURLResponse)?.statusCode == 200,
              let ids = try? JSONDecoder().decode([Int].self, from: data)
        else { return offline }
        var seen = Set<Int>()
        let valid = ids.filter { (0..<100).contains($0) && seen.insert($0).inserted }.prefix(6)
        return valid.isEmpty ? offline : Array(valid)
    }
}

/// Keeps one player controller per section so the app can push named colours into it.
@MainActor
final class SectionModel: ObservableObject {
    @Published var section: Section?
    let controller = RemoteComposePlayerController()
    /// The path and pull-to-refresh the current section was loaded for: either changing means load again.
    private var loadedKey: String?
    private var pushTask: Task<Void, Never>?

    func load(_ path: String, refresh: Int, fresh: Bool, bundledOnly: Bool) async {
        let key = "\(path)#\(refresh)"
        if !fresh, loadedKey == key, section != nil { return }
        if !fresh, let cached = SectionSource.cache[path], loadedKey?.hasPrefix("\(path)#") != true { section = cached }
        let loaded = await SectionSource.load(path, bundledOnly: bundledOnly)
        // A newer load (another path, another refresh) replaced this one while it waited: keep the newer result.
        guard !Task.isCancelled else { return }
        if let loaded { section = loaded }
        loadedKey = key
    }

    /// The controller only accepts a value once the document is attached and it knows the name, so retry briefly.
    /// A newer push cancels the one still retrying.
    func push(_ colors: [String: UInt32]) {
        pushTask?.cancel()
        pushTask = Task { @MainActor in
            for _ in 0..<20 {
                var all = true
                for (name, argb) in colors where controller.names.contains("USER:\(name)") {
                    if !controller.setColor(argb, for: name) { all = false }
                }
                if all && !controller.names.isEmpty { return }
                try? await Task.sleep(nanoseconds: 100_000_000)
                if Task.isCancelled { return }
            }
        }
    }
}

/// A Remote Compose section inside a SwiftUI layout, with a quiet placeholder until the first copy arrives.
struct RemoteSection: View {
    let path: String
    let height: CGFloat
    var refresh = 0
    var fresh = false
    var bundledOnly = false
    @EnvironmentObject var app: AppState
    @StateObject private var model = SectionModel()

    var body: some View {
        Group {
            if let s = model.section {
                RemoteComposePlayerView(
                    data: s.data,
                    controller: model.controller,
                    configuration: .init(theme: .light, compatibility: .compatible, background: .transparent),
                    onEvent: { event in
                        switch event {
                        case .namedAction(let name, .float(let v)): app.value(name, v)
                        case .namedAction(let name, _): app.handle(name)
                        default: break
                        }
                    },
                    onError: { print("Brew player error \(path): \($0)") }
                )
                .onAppear { model.push(app.namedColors) }
                .onChange(of: s.data) { _, _ in model.push(app.namedColors) }
            } else {
                RoundedRectangle(cornerRadius: 24).fill(Color(argb: 0xFFF6F1ED)).padding(.horizontal, 16)
            }
        }
        .frame(height: height)
        .task(id: "\(path)#\(refresh)") { await model.load(path, refresh: refresh, fresh: fresh, bundledOnly: bundledOnly) }
        .onChange(of: app.favorites) { _, _ in model.push(app.namedColors) }
        .onChange(of: app.category) { _, _ in model.push(app.namedColors) }
    }
}
