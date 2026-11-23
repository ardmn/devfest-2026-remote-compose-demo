import SwiftUI
import UIKit

/// Palette shared with the documents (Palette is generated from shared/…/brew/remote/BrewTheme.kt).
enum Brand {
    static let tan = Color(argb: Palette.tan)
    static let tanDark = Color(argb: Palette.tanDark)
    static let tanSoft = Color(argb: Palette.tanSoft)
    static let ink = Color(argb: Palette.ink)
    static let muted = Color(argb: Palette.muted)
    static let butter = Color(argb: Palette.butter)
    static let green = Color(argb: Palette.green)
    static let clearARGB: UInt32 = 0x00000000
}

extension Color {
    init(argb: UInt32) {
        self.init(
            .sRGB,
            red: Double((argb >> 16) & 0xFF) / 255, green: Double((argb >> 8) & 0xFF) / 255,
            blue: Double(argb & 0xFF) / 255, opacity: Double((argb >> 24) & 0xFF) / 255)
    }
}

// The data — drinks, prices, categories, toppings, heights — is in Catalog.generated.swift, written by
// go run ./cmd/demo render from shared/…/brew/remote (the same Kotlin the documents are made from). Only logic here.
extension Catalog {
    /// For the app's own, known ids.
    static func drink(_ id: String) -> Drink { find(id) ?? drinks[0] }
    /// For ids that come from a document: nil when unknown — the action is ignored.
    static func find(_ id: String) -> Drink? { drinks.first { $0.id == id } }
    static func isCategory(_ key: String) -> Bool { categories.contains { $0.key == key } }

    /// The price of one item in cents, from the catalog and the reported options — never from the document.
    static func unitCents(_ d: Drink, large: Bool, toppings mask: Int) -> Int {
        guard d.hasOptions else { return d.priceCents }
        let extras = toppings.indices.filter { mask & (1 << $0) != 0 }.reduce(0) { $0 + toppings[$1].cents }
        return d.priceCents + (large ? largeCents : 0) + extras
    }
}

extension Heights {
    static func details(_ id: String) -> CGFloat { Catalog.drink(id).detailsHeight }
}

func price(cents: Int) -> String { "$\(cents / 100).\(String(format: "%02d", cents % 100))" }

struct CartLine: Identifiable, Equatable {
    let drinkId: String, qty: Int, unitCents: Int, note: String
    var id: String { "\(drinkId)/\(note)/\(unitCents)" }
}

struct Toast: Identifiable, Equatable {
    let id = UUID()
    let text: String
    let undo: CartLine?
}

/// The app's own state. Documents never own it: they ask through host actions (add_to_cart:<id>,
/// toggle_favorite:<id>, category:<key>, open_details:<id>) and show it through named colours the app pushes.
@MainActor
final class AppState: ObservableObject {
    @Published var cart: [CartLine] = []
    @Published var favorites: Set<String> = ["caramel", "cookie"]
    @Published var category: String?
    @Published var tab = 0
    @Published var path: [Route] = []
    @Published var toast: Toast?

    // The options the product page reported last. The page's document does its own arithmetic for display; the price
    // the app charges is worked out here from the catalog — a document is untrusted input.
    @Published var pageQty = 1
    @Published var pageLarge = false
    @Published var pageToppings = 0

    var cartCount: Int { cart.reduce(0) { $0 + $1.qty } }
    var cartTotalCents: Int { cart.reduce(0) { $0 + $1.unitCents * $1.qty } }

    func pageTotalCents(_ id: String) -> Int {
        guard let d = Catalog.find(id) else { return 0 }
        return Catalog.unitCents(d, large: pageLarge, toppings: pageToppings) * pageQty
    }

    func add(_ id: String, qty: Int, unitCents: Int, note: String, text: String) {
        if let i = cart.firstIndex(where: { $0.drinkId == id && $0.note == note && $0.unitCents == unitCents }) {
            cart[i] = CartLine(drinkId: id, qty: cart[i].qty + qty, unitCents: unitCents, note: note)
        } else {
            cart.append(CartLine(drinkId: id, qty: qty, unitCents: unitCents, note: note))
        }
        UINotificationFeedbackGenerator().notificationOccurred(.success)
        toast = Toast(text: text, undo: CartLine(drinkId: id, qty: qty, unitCents: unitCents, note: note))
    }

    func undo(_ line: CartLine) {
        guard let i = cart.firstIndex(where: { $0.drinkId == line.drinkId && $0.note == line.note && $0.unitCents == line.unitCents })
        else { return }
        let left = cart[i].qty - line.qty
        if left > 0 { cart[i] = CartLine(drinkId: line.drinkId, qty: left, unitCents: line.unitCents, note: line.note) } else { cart.remove(at: i) }
        toast = nil
    }

    /// Host actions from any section. Untrusted input: unknown ids and categories are logged and ignored.
    func handle(_ name: String) {
        let parts = name.split(separator: ":", maxSplits: 1).map(String.init)
        let verb = parts.first ?? ""
        let arg = parts.count > 1 ? parts[1] : ""
        print("Brew action \(name)")
        if verb == "category" {
            guard Catalog.isCategory(arg) else { return print("Brew: ignored \(name): unknown category") }
            // Home's category circles are shortcuts into the Menu tab, filtered.
            category = arg
            path.removeAll()
            tab = 1
            return
        }
        guard let d = Catalog.find(arg) else { return print("Brew: ignored \(name): unknown action or id") }
        switch verb {
        case "open_details":
            pageQty = 1
            pageLarge = false
            pageToppings = 0
            path.append(.details(d.id))
        case "add_to_cart":
            add(d.id, qty: 1, unitCents: Catalog.unitCents(d, large: false, toppings: 0), note: "", text: "\(d.name) added to cart")
        case "toggle_favorite":
            if favorites.contains(d.id) { favorites.remove(d.id) } else { favorites.insert(d.id) }
        default:
            print("Brew: ignored \(name): unknown action")
        }
    }

    /// Named Float values the product page sends after every change. Untrusted: finite only, clamped.
    func value(_ name: String, _ v: Float) {
        guard v.isFinite else { return }
        switch name {
        case "qty": pageQty = Int(min(max(v, 1), 9))
        case "large": pageLarge = v > 0.5
        case "toppings": pageToppings = Int(min(max(v, 0), Float((1 << Catalog.toppings.count) - 1)))
        default: break
        }
    }

    func order(_ id: String) {
        guard let d = Catalog.find(id) else { return }
        let qty = pageQty
        var note: [String] = []
        if d.hasOptions {
            note.append(pageLarge ? "Large" : "Regular")
            note += Catalog.toppings.indices.filter { pageToppings & (1 << $0) != 0 }.map { Catalog.toppings[$0].name }
        }
        if !path.isEmpty { path.removeLast() }
        add(
            d.id, qty: qty, unitCents: Catalog.unitCents(d, large: pageLarge, toppings: pageToppings),
            note: note.joined(separator: " · "), text: "\(qty) × \(d.name) added to cart")
    }

    /// The named colours every section reads (USER:fav_<id>, USER:favline_<id>, USER:cat_<key>, USER:catline_<key>).
    var namedColors: [String: UInt32] {
        var m: [String: UInt32] = [:]
        for d in Catalog.drinks {
            let fav = favorites.contains(d.id)
            m["fav_\(d.id)"] = fav ? Palette.red : Brand.clearARGB
            m["favline_\(d.id)"] = fav ? Brand.clearARGB : Palette.ink
        }
        // Home's category circles are shortcuts, never highlighted (the Menu tab's chips show the filter).
        for c in Catalog.categories {
            m["cat_\(c.key)"] = Palette.tanSoft
            m["catline_\(c.key)"] = Brand.clearARGB
        }
        return m
    }
}

enum Route: Hashable {
    case details(String)
    case cart
}
