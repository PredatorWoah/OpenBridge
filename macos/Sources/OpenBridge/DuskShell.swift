import SwiftUI
import AppKit
import BridgeCore

/// Dusk, the original layout: a sidebar with the phone, a toolbar with the folder trail, the files, and the transfer
/// bar at the bottom.
struct DuskBrowser: View {
    @ObservedObject var model: BrowserModel
    var mirror: () -> Void
    @Binding var dropping: Bool
    /// While typing in search, keys like Space and Return go to the field, not to shortcuts.
    @FocusState private var searching: Bool

    var body: some View {
        HStack(spacing: 0) {
            Sidebar(model: model, mirror: mirror)
            VStack(spacing: 0) {
                Toolbar(model: model, searching: $searching)
                FolderContent(model: model, look: .dusk, dropping: $dropping)
                BrowserError(model: model)
                if model.transfer != nil {
                    TransferBar(model: model).padding(.horizontal, 16).padding(.bottom, 16)
                        .transition(.move(edge: .bottom).combined(with: .opacity))
                }
            }
            .animation(.spring(response: 0.5, dampingFraction: 0.75), value: model.transfer != nil)
        }
        .modifier(BrowserChrome(model: model, searching: searching))
    }
}

private struct Sidebar: View {
    @ObservedObject var model: BrowserModel
    var mirror: () -> Void
    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("DEVICES").font(.system(size: 11, weight: .semibold)).tracking(1.4).foregroundStyle(Palette.muted)
                .padding(.horizontal, 8).padding(.bottom, 4)
            HStack(spacing: 10) {
                ArchMark(size: 26)
                VStack(alignment: .leading, spacing: 1) {
                    Text(model.deviceName).font(.system(size: 13.5, weight: .semibold)).lineLimit(1)
                    Text(ConnectText.transport(model)).font(.system(size: 11.5)).foregroundStyle(.white.opacity(0.75))
                }
            }
            .padding(10).frame(maxWidth: .infinity, alignment: .leading)
            .background(LinearGradient(colors: [Palette.violet.opacity(0.45), Palette.pink.opacity(0.3)], startPoint: .leading, endPoint: .trailing),
                        in: RoundedRectangle(cornerRadius: 10, style: .continuous))
            Button { model.showBrowser = false } label: { Label("Devices", systemImage: "chevron.left") }
                .buttonStyle(.plain).foregroundStyle(Palette.muted).font(.system(size: 12.5)).padding(10)
            if model.clipboardAvailable {
                Button { model.showTexts = true } label: { Label("Texts", systemImage: "text.bubble") }
                    .buttonStyle(.plain).font(.system(size: 13, weight: .semibold)).padding(10)
                    .help("Send texts to the phone, and see what it sent")
            }
            if model.overUSB || model.fastUSB {
                Button(action: mirror) { Label("Mirror screen", systemImage: "rectangle.on.rectangle") }
                    .buttonStyle(.plain).font(.system(size: 13, weight: .semibold)).padding(10)
                    .help("Show and control the phone's screen on your Mac (needs USB debugging)")
            }
            Spacer()
            Button("Disconnect", action: model.disconnect).buttonStyle(GhostButton()).padding(8)
        }
        .padding(.top, 44).padding(.horizontal, 10).padding(.bottom, 10)
        .frame(width: 220)
        .background(Color.white.opacity(0.04))
        .overlay(alignment: .trailing) { Rectangle().fill(.white.opacity(0.08)).frame(width: 1) }
    }
}

/// One row when everything fits; when the window is narrower, the controls move to a second row instead of
/// squeezing the path and buttons until their text breaks.
private struct Toolbar: View {
    @ObservedObject var model: BrowserModel
    var searching: FocusState<Bool>.Binding
    var body: some View {
        ViewThatFits(in: .horizontal) {
            HStack(spacing: 8) {
                navigation(compact: false)
                Spacer(minLength: 8)
                controls
                DownloadButton(model: model)
            }
            VStack(alignment: .leading, spacing: 8) {
                HStack(spacing: 8) {
                    navigation(compact: true)
                    Spacer(minLength: 8)
                    DownloadButton(model: model)
                }
                HStack(spacing: 8) { controls; Spacer(minLength: 0) }
            }
        }
        .padding(.horizontal, 16).padding(.top, 40).padding(.bottom, 10)
        .overlay(alignment: .bottom) { Rectangle().fill(.white.opacity(0.08)).frame(height: 1) }
    }

    private func navigation(compact: Bool) -> some View {
        HStack(spacing: 8) {
            SelectAllBox(model: model)
            UpButton(model: model)
            ResultsButton(model: model)
            PathBar(model: model, compact: compact)
        }
    }

    private var controls: some View {
        HStack(spacing: 8) {
            SearchBox(model: model, searching: searching)
            MediaToggle(model: model)
            ViewToggle(model: model)
            SortMenu(model: model)
            MoreMenu(model: model)
        }
    }
}

/// Clickable folder trail: "vivo X300 Pro › Internal shared storage › DCIM". Deep paths keep the first and last two
/// folders and put the rest behind "…". Names never break mid-word; `compact` lets long ones shorten with an ellipsis.
private struct PathBar: View {
    @ObservedObject var model: BrowserModel
    var compact: Bool

    var body: some View {
        let path = Array(model.path.enumerated())
        let hidden = path.count > 3 ? Array(path[1..<(path.count - 2)]) : []
        let shown = hidden.isEmpty ? path : [path[0]] + path.suffix(2)
        HStack(spacing: 4) {
            ForEach(shown, id: \.offset) { index, crumb in
                if index > 0 { chevron }
                if index == path.count - 2 && !hidden.isEmpty {
                    Menu {
                        ForEach(hidden, id: \.offset) { hiddenIndex, folder in Button(folder.name) { model.go(to: hiddenIndex) } }
                    } label: { Text("…").font(.system(size: 14, weight: .medium)).foregroundStyle(Palette.muted) }
                        .menuStyle(.borderlessButton).menuIndicator(.hidden).fixedSize().help("Folders in between")
                        .accessibilityLabel("Folders in between")
                    chevron
                }
                crumbButton(index, crumb.name, last: index == path.count - 1)
            }
        }
    }

    private var chevron: some View {
        Image(systemName: "chevron.right").font(.system(size: 10, weight: .bold)).foregroundStyle(Palette.muted).accessibilityHidden(true)
    }

    @ViewBuilder private func crumbButton(_ index: Int, _ name: String, last: Bool) -> some View {
        let button = Button(name) { model.go(to: index) }
            .buttonStyle(.plain)
            .font(.system(size: 14, weight: last ? .bold : .medium))
            .foregroundStyle(last ? .white : Palette.muted)
            .lineLimit(1)
        if compact { button.truncationMode(.middle).layoutPriority(last ? 1 : 0) } else { button.fixedSize() }
    }
}

/// Dusk's connect screen: the sun arch, the state of the connection, and cards for what to do next.
struct DuskConnect: View {
    @ObservedObject var model: BrowserModel
    @Binding var wifi: Bool
    @Binding var openAutomatically: Bool
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        GeometryReader { geometry in
            ZStack(alignment: .top) {
                Hero(active: true, connected: model.connected, centerY: 0.27, radius: 62, startScanning: true, label: ConnectText.title(model))
                    .allowsHitTesting(false)
                    .ignoresSafeArea()
                VStack(spacing: 6) {
                    Spacer().frame(height: geometry.size.height * 0.27 + 104)
                    Text(ConnectText.title(model)).font(.system(size: 24, weight: .bold, design: .rounded))
                    Text(ConnectText.subtitle(model)).foregroundStyle(Palette.muted).multilineTextAlignment(.center).modifier(Selectable())
                    if model.connected {
                        DeviceCard(model: model, openAutomatically: $openAutomatically)
                            .padding(.top, 16)
                            .transition(.move(edge: .bottom).combined(with: .opacity))
                    } else {
                        if let phone = model.usbPairable {
                            USBPairingCard(model: model, phone: phone).padding(.top, 16)
                        } else if model.usbNeedsAllow {
                            HintCard(symbol: "cable.connector", text: ConnectText.allowFastUSB,
                                     action: "Use Fast USB", busy: model.busy) { model.connectFastUSB(asking: true) }
                                .padding(.top, 16)
                        }
                        if model.wifiIsolated {
                            HintCard(symbol: "wifi.exclamationmark", text: ConnectText.isolated)
                                .padding(.top, 12)
                        }
                        if !model.saved.isEmpty {
                            SavedPhonesCard(model: model, discovery: model.discovery).padding(.top, 16)
                        }
                        if let error = model.error {
                            ErrorCard(message: error, busy: model.busy, retry: { if model.overUSB { model.retryUSB() } else { wifi = true } })
                                .padding(.top, 12)
                                .transition(.opacity)
                        }
                    }
                    Spacer()
                    PairOverWiFiLink(model: model, wifi: $wifi)
                        .padding(.bottom, 22)
                }
                .padding(.horizontal, 24)
                .frame(maxWidth: .infinity)
                .animation(reduceMotion ? nil : .spring(response: 0.6, dampingFraction: 0.68), value: model.connected)
            }
        }
    }
}
