import SwiftUI

@main
struct BrewApp: App {
    @StateObject private var app = AppState()

    var body: some Scene {
        WindowGroup {
            RootView().environmentObject(app).tint(Brand.tanDark).preferredColorScheme(.light)
        }
    }
}

/// The native skeleton: tabs, navigation, cart, banner pager, pull to refresh, toast with Undo — plain SwiftUI.
/// The content (banners, categories, product cards, lists, the product page) is Remote Compose from the server:
/// the same documents the Android app plays.
struct RootView: View {
    @EnvironmentObject var app: AppState

    var body: some View {
        NavigationStack(path: $app.path) {
            TabView(selection: $app.tab) {
                HomeView().tabItem { Label("Home", systemImage: "house.fill") }.tag(0)
                MenuTab()
                    .tabItem { Label("Menu", systemImage: "list.bullet") }.tag(1)
                ListTab(
                    ids: Catalog.drinks.map(\.id).filter { app.favorites.contains($0) },
                    empty: "No favourites yet. Tap the heart on any drink."
                )
                .tabItem { Label("Favorites", systemImage: "heart.fill") }.tag(2)
                LabView().tabItem { Label("Lab", systemImage: "sparkles") }.tag(4)
                ProfileView().tabItem { Label("Profile", systemImage: "person.fill") }.tag(3)
            }
            .navigationTitle(["", "Menu", "Favorites", "Profile", "Lab"][app.tab])
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) { CartButton() }
            }
            .navigationDestination(for: Route.self) { route in
                switch route {
                case .details(let id): DetailsView(id: id)
                case .cart: CartView()
                }
            }
        }
        .overlay(alignment: .bottom) { ToastView().padding(.bottom, 70) }
    }
}

struct CartButton: View {
    @EnvironmentObject var app: AppState
    var body: some View {
        Button {
            app.path.append(.cart)
        } label: {
            // The badge stays inside the button's bounds: iOS 26 toolbar buttons clip anything outside.
            Image(systemName: "cart.fill").foregroundStyle(Brand.ink)
                .padding(.trailing, 8).padding(.top, 6)
                .overlay(alignment: .topTrailing) {
                    if app.cartCount > 0 {
                        Text("\(app.cartCount)").font(.caption2.bold()).foregroundStyle(.white)
                            .padding(.horizontal, 5).padding(.vertical, 1)
                            .background(Capsule().fill(Brand.tanDark))
                    }
                }
        }
        .accessibilityLabel("Cart, \(app.cartCount) items")
    }
}

/// "Added to cart" with Undo, like any shopping app.
struct ToastView: View {
    @EnvironmentObject var app: AppState
    var body: some View {
        if let t = app.toast {
            HStack {
                Text(t.text).foregroundStyle(.white).font(.subheadline)
                Spacer()
                if let line = t.undo {
                    Button("Undo") { app.undo(line) }.font(.subheadline.bold()).foregroundStyle(Brand.butter)
                }
            }
            .padding(.horizontal, 16).padding(.vertical, 14)
            .background(RoundedRectangle(cornerRadius: 12).fill(Color(white: 0.18)))
            .padding(.horizontal, 12)
            .transition(.move(edge: .bottom).combined(with: .opacity))
            .task(id: t.id) {
                try? await Task.sleep(nanoseconds: 4_000_000_000)
                if app.toast?.id == t.id { withAnimation { app.toast = nil } }
            }
        }
    }
}
