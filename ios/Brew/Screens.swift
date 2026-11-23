import SwiftUI

struct HomeView: View {
    @EnvironmentObject var app: AppState
    @State private var refresh = 0

    var body: some View {
        ScrollView {
            VStack(spacing: 0) {
                VStack(alignment: .leading, spacing: 0) {
                    Text("Good morning").font(.subheadline).foregroundStyle(Brand.muted)
                    Text("Brew · Yerevan").font(.title2.weight(.semibold)).foregroundStyle(Brand.ink)
                }
                .frame(maxWidth: .infinity, alignment: .leading).padding(.horizontal, 20)
                Banners(refresh: refresh)
                Header(title: "Categories") {
                    app.category = nil
                    app.tab = 1
                }
                RemoteSection(path: "categories", height: Heights.categories, refresh: refresh)
                Header(title: "Favorites") { app.tab = 2 }
                RemoteSection(path: "favorites", height: Heights.favorites, refresh: refresh)
                Header(title: "For you") {
                    app.category = nil
                    app.tab = 1
                }
                RemoteSection(path: "for-you", height: Heights.forYou, refresh: refresh)
                // Written by the backend as JSON (backend/promo.json), converted to a binary document on the server.
                RemoteSection(path: "promo", height: Heights.promo, refresh: refresh).padding(.top, 20)
                Text("Sections: Remote Compose from the server · player: rc-players (iOS)")
                    .font(.caption2).foregroundStyle(Brand.muted).padding(.top, 20).padding(.bottom, 16)
            }
        }
        .refreshable {
            refresh += 1
            try? await Task.sleep(nanoseconds: 500_000_000)
        }
    }
}

/// Native paging carousel (swipe, auto-advance, dots) whose pages are Remote Compose banners from the server. The
/// server also decides which banners there are: on every pull to refresh a new random choice from its pool.
struct Banners: View {
    let refresh: Int
    @State private var ids = [0, 1]
    @State private var page = 0
    private var count: Int { ids.count }

    var body: some View {
        VStack(spacing: 12) {
            TabView(selection: $page) {
                ForEach(Array(ids.enumerated()), id: \.offset) { i, id in
                    RemoteSection(path: "hero?i=\(id)", height: Heights.hero, refresh: refresh).tag(i)
                }
            }
            .tabViewStyle(.page(indexDisplayMode: .never))
            .frame(height: Heights.hero)
            HStack(spacing: 6) {
                ForEach(0..<count, id: \.self) { i in
                    Capsule().fill(i == page ? Brand.tanDark : Brand.tanSoft)
                        .frame(width: i == page ? 22 : 8, height: 8)
                }
            }
            .animation(.easeInOut(duration: 0.25), value: page)
            .padding(.bottom, 4)
        }
        .padding(.top, 8)
        .task(id: refresh) {
            ids = await SectionSource.bannerIDs()
            page = min(page, ids.count - 1)
        }
        .task(id: count) {
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 4_000_000_000)
                withAnimation(.easeInOut(duration: 0.4)) { page = (page + 1) % max(count, 1) }
            }
        }
    }
}

struct Header: View {
    let title: String
    var action = "See all"
    var onAction: () -> Void = {}
    var body: some View {
        HStack {
            Text(title).font(.title2.weight(.semibold)).foregroundStyle(Brand.ink)
            Spacer()
            Button(action, action: onAction).foregroundStyle(Brand.tanDark)
        }
        .padding(.horizontal, 20).padding(.top, 20).padding(.bottom, 8)
    }
}

/// Menu: native filter chips (All + categories) over one Remote Compose list of the matching products.
struct MenuTab: View {
    @EnvironmentObject var app: AppState
    var body: some View {
        let ids = Catalog.drinks.filter { app.category == nil || $0.category == app.category }.map(\.id)
        VStack(alignment: .leading, spacing: 4) {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    chip(nil, "All")
                    ForEach(Catalog.categories, id: \.key) { c in chip(c.key, c.label) }
                }
                .padding(.horizontal, 16).padding(.vertical, 8)
            }
            Text("\(ids.count) item\(ids.count == 1 ? "" : "s")").font(.footnote).foregroundStyle(Brand.muted).padding(.leading, 20)
            ListTab(ids: ids, empty: "Nothing here yet.")
        }
    }

    private func chip(_ key: String?, _ label: String) -> some View {
        let on = app.category == key
        return Button {
            app.category = key
        } label: {
            Text(label).font(.subheadline.weight(on ? .semibold : .regular)).foregroundStyle(Brand.ink)
                .padding(.horizontal, 14).padding(.vertical, 8)
                .background(Capsule().fill(on ? Brand.tanSoft : Color.white))
                .overlay(Capsule().stroke(on ? Color.clear : Brand.muted.opacity(0.4)))
        }
        .buttonStyle(.plain)
    }
}

/// Menu and Favorites: the app's own list, one Remote Compose row per product ("row?id=…"). The app decides which
/// ids it shows; every row is a fixed document, so the server builds them all ahead of time.
struct ListTab: View {
    let ids: [String]
    let empty: String
    var body: some View {
        if ids.isEmpty {
            VStack(spacing: 16) {
                Image(systemName: "heart.fill").font(.system(size: 64)).foregroundStyle(Brand.tanSoft)
                Text(empty).foregroundStyle(Brand.muted).multilineTextAlignment(.center)
            }
            .padding(32).frame(maxWidth: .infinity, maxHeight: .infinity)
        } else {
            ScrollView {
                LazyVStack(spacing: 0) {
                    ForEach(ids, id: \.self) { id in
                        RemoteSection(path: "row?id=\(id.urlQueryValue)", height: Heights.row)
                    }
                }
                .padding(.top, 12)
            }
        }
    }
}

struct ProfileView: View {
    @EnvironmentObject var app: AppState
    var body: some View {
        VStack(spacing: 8) {
            Text("☕").font(.system(size: 44)).frame(width: 96, height: 96).background(Circle().fill(Brand.tanSoft))
            Text("DevFest Yerevan").font(.title2.weight(.semibold)).foregroundStyle(Brand.ink).padding(.top, 8)
            Text("\(app.favorites.count) favourites · \(app.cartCount) in cart").foregroundStyle(Brand.muted)
            Text("This tab is plain SwiftUI. Home, Menu, Favorites and the product page are Remote Compose sections — the same bytes the Android app plays.")
                .font(.footnote).foregroundStyle(Brand.muted).multilineTextAlignment(.center).padding(.top, 24)
            Spacer()
        }
        .padding(24)
    }
}

struct DetailsView: View {
    let id: String
    @EnvironmentObject var app: AppState

    var body: some View {
        ScrollView {
            // The whole product page is one Remote Compose document: options and quantity live inside it; the total below is priced by the app.
            RemoteSection(path: "details?id=\(id.urlQueryValue)", height: Heights.details(id), fresh: true)
        }
        .background(alignment: .top) { Brand.tan.frame(height: 400).ignoresSafeArea() }
        .toolbarBackground(Brand.tan, for: .navigationBar)
        .toolbarBackground(.visible, for: .navigationBar)
        .safeAreaInset(edge: .bottom) {
            HStack {
                VStack(alignment: .leading, spacing: 0) {
                    Text("Total").font(.subheadline).foregroundStyle(Brand.muted)
                    Text(price(cents: app.pageTotalCents(id))).font(.title.bold()).foregroundStyle(Brand.ink)
                }
                Spacer()
                Button {
                    app.order(id)
                } label: {
                    Text("Add to cart").font(.headline).foregroundStyle(.white)
                        .frame(width: 180, height: 56).background(Capsule().fill(Brand.tanDark))
                }
            }
            .padding(.horizontal, 24).padding(.vertical, 12)
            .background(.white.shadow(.drop(color: .black.opacity(0.08), radius: 8, y: -2)))
        }
    }
}

/// The cart is plain SwiftUI: it is app state, not server UI.
struct CartView: View {
    @EnvironmentObject var app: AppState

    var body: some View {
        Group {
            if app.cart.isEmpty {
                VStack(spacing: 16) {
                    Image(systemName: "cart.fill").font(.system(size: 64)).foregroundStyle(Brand.tanSoft)
                    Text("Your cart is empty").foregroundStyle(Brand.muted)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                List {
                    ForEach(app.cart) { line in
                        let d = Catalog.drink(line.drinkId)
                        HStack(spacing: 14) {
                            Text(d.emoji).font(.system(size: 32)).frame(width: 64, height: 64)
                                .background(RoundedRectangle(cornerRadius: 16).fill(Color(argb: d.tile)))
                            VStack(alignment: .leading, spacing: 2) {
                                Text(d.name).font(.headline).foregroundStyle(Brand.ink)
                                if !line.note.isEmpty { Text(line.note).font(.caption).foregroundStyle(Brand.muted) }
                                Text("\(line.qty) × \(price(cents: line.unitCents))").font(.caption).foregroundStyle(Brand.muted)
                            }
                            Spacer()
                            Text(price(cents: line.unitCents * line.qty)).font(.subheadline.weight(.semibold)).foregroundStyle(Brand.ink)
                        }
                    }
                    .onDelete { app.cart.remove(atOffsets: $0) }
                }
                .listStyle(.plain)
                .safeAreaInset(edge: .bottom) {
                    HStack {
                        VStack(alignment: .leading, spacing: 0) {
                            Text("Total").font(.subheadline).foregroundStyle(Brand.muted)
                            Text(price(cents: app.cartTotalCents)).font(.title.bold()).foregroundStyle(Brand.ink)
                        }
                        Spacer()
                        Button {
                            let n = app.cartCount
                            app.cart.removeAll()
                            if !app.path.isEmpty { app.path.removeLast() }
                            UINotificationFeedbackGenerator().notificationOccurred(.success)
                            app.toast = Toast(text: "Order placed — \(n) item\(n == 1 ? "" : "s"). Thank you!", undo: nil)
                        } label: {
                            Text("Checkout").font(.headline).foregroundStyle(.white)
                                .frame(width: 180, height: 56).background(Capsule().fill(Brand.tanDark))
                        }
                    }
                    .padding(.horizontal, 24).padding(.vertical, 12).background(.white)
                }
            }
        }
        .navigationTitle("Cart")
    }
}

/// The Lab: the Android app's GPU-shader documents (AGSL), played by rc-players on the iPhone — to see whether and how
/// they work here. The bytes are the very same files the Android app builds (bundled: lab_i-N.rc; the backend doesn't
/// serve them). Note: rc-players runs shaders without an allow-list; the androidx player needs one (setShaderControl).
struct LabView: View {
    /// Titles and heights generated from shared/…/brew/lab/LabDocuments.kt (Catalog.generated.swift).
    private let cards = LabCards.all
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                ForEach(Array(cards.enumerated()), id: \.offset) { i, card in
                    VStack(alignment: .leading, spacing: 6) {
                        RemoteSection(path: "lab?i=\(i)", height: card.height, bundledOnly: true)
                            .clipShape(RoundedRectangle(cornerRadius: 24))
                        Text("\(card.title) · rc-players").font(.caption.monospaced()).foregroundStyle(.gray).padding(.leading, 6)
                    }
                }
            }
            .padding(16)
        }
        .background(Color(red: 0.043, green: 0.043, blue: 0.059))
    }
}
