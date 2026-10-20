import SwiftUI
import BridgeCore

// Checkboxes on every row and tile, and one for the whole folder, drawn in the look: Dusk a rounded square that
// fills with the sunset, Aurora a mint square that glows, Tide an aqua circle like a bubble.

enum CheckState { case off, on, mixed }

/// How a checkbox looks; no behaviour of its own.
struct CheckMark: View {
    let state: CheckState
    var size: CGFloat = 16
    /// On a picture (grid tiles): an empty box gets a dark backing so it shows on light photos too.
    var onPicture = false

    var body: some View {
        let look: Look = Palette.look
        ZStack {
            if look == .tide {
                Circle().fill(fill(look))
                Circle().strokeBorder(stroke(look), lineWidth: 1.4)
            } else {
                let radius: CGFloat = look == .aurora ? size * 0.32 : size * 0.26
                RoundedRectangle(cornerRadius: radius, style: .continuous).fill(fill(look))
                RoundedRectangle(cornerRadius: radius, style: .continuous).strokeBorder(stroke(look), lineWidth: 1.4)
            }
            if state != .off {
                Image(systemName: state == .on ? "checkmark" : "minus")
                    .font(.system(size: size * 0.6, weight: .heavy))
                    .foregroundStyle(markColor(look))
            }
        }
        .frame(width: size, height: size)
        .shadow(color: glow(look), radius: look == .aurora ? 6 : 3)
        .animation(.easeOut(duration: 0.12), value: state)
    }

    private var lit: Bool { state != .off }

    private func fill(_ look: Look) -> AnyShapeStyle {
        guard lit else { return AnyShapeStyle(Color.black.opacity(onPicture ? 0.35 : 0.12)) }
        let colors = look.colors
        switch look {
        case .dusk:
            return AnyShapeStyle(LinearGradient(colors: [colors.pink.color(), colors.amber.color()], startPoint: .topLeading, endPoint: .bottomTrailing))
        case .aurora:
            return AnyShapeStyle(colors.amber.color(0.9))
        case .tide:
            return AnyShapeStyle(LinearGradient(colors: [colors.sun.color(), colors.amber.color()], startPoint: .top, endPoint: .bottom))
        }
    }

    private func stroke(_ look: Look) -> Color {
        let colors = look.colors
        if lit { return look == .dusk ? colors.sun.color(0.9) : colors.cream.color(0.7) }
        return look == .dusk ? colors.amber.color(0.65) : colors.amber.color(0.55)
    }

    private func markColor(_ look: Look) -> Color {
        look == .dusk ? Color.white : look.colors.night.color()
    }

    private func glow(_ look: Look) -> Color {
        guard lit else { return .clear }
        return look.colors.amber.color(look == .aurora ? 0.8 : 0.45)
    }
}

/// A row's or tile's checkbox: adds or removes the item from the selection without touching the rest.
struct RowCheckbox: View {
    let name: String
    let selected: Bool
    var onPicture = false
    let toggle: () -> Void

    var body: some View {
        Button(action: toggle) {
            CheckMark(state: selected ? .on : .off, onPicture: onPicture)
                .padding(4)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .help(selected ? "Deselect" : "Select")
        .accessibilityLabel("Select \(name)")
        .accessibilityValue(selected ? "Selected" : "Not selected")
    }
}

/// Select all or none of what's shown in the folder; a dash when only some are selected.
struct SelectAllBox: View {
    @ObservedObject var model: BrowserModel

    var body: some View {
        let state: CheckState = model.allSelectedState
        if model.results == nil && !model.sorted.isEmpty {
            Button(action: model.toggleAll) {
                CheckMark(state: state, size: 17).padding(5).contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .help(state == .on ? "Select none" : "Select all")
            .accessibilityLabel(state == .on ? "Select none" : "Select all")
            .accessibilityValue(state == .on ? "All selected" : state == .mixed ? "Some selected" : "None selected")
        }
    }
}
