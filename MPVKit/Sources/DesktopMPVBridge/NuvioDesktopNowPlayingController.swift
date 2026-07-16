import AppKit
import MediaPlayer

final class NuvioDesktopNowPlayingController {
    private let onPlay: () -> Void
    private let onPause: () -> Void
    private let onTogglePlayPause: () -> Void
    private let onStop: () -> Void
    private let onPrevious: () -> Void
    private let onNext: () -> Void
    private var targets: [(MPRemoteCommand, Any)] = []
    private var isActive = false
    private var lastTitle = ""
    private var lastSubtitle = ""
    private var lastDurationMs: Int64 = -1
    private var lastPositionMs: Int64 = -1
    private var lastIsPlaying = false
    private var lastPlaybackSpeed: Float = -1

    init(
        onPlay: @escaping () -> Void,
        onPause: @escaping () -> Void,
        onTogglePlayPause: @escaping () -> Void,
        onStop: @escaping () -> Void,
        onPrevious: @escaping () -> Void,
        onNext: @escaping () -> Void
    ) {
        self.onPlay = onPlay
        self.onPause = onPause
        self.onTogglePlayPause = onTogglePlayPause
        self.onStop = onStop
        self.onPrevious = onPrevious
        self.onNext = onNext
    }

    func activate() {
        guard !isActive else { return }
        let center = MPRemoteCommandCenter.shared()
        addTarget(center.playCommand) { [weak self] _ in
            DispatchQueue.main.async { self?.onPlay() }
            return .success
        }
        addTarget(center.pauseCommand) { [weak self] _ in
            DispatchQueue.main.async { self?.onPause() }
            return .success
        }
        addTarget(center.togglePlayPauseCommand) { [weak self] _ in
            DispatchQueue.main.async { self?.onTogglePlayPause() }
            return .success
        }
        addTarget(center.stopCommand) { [weak self] _ in
            DispatchQueue.main.async { self?.onStop() }
            return .success
        }
        addTarget(center.previousTrackCommand) { [weak self] _ in
            DispatchQueue.main.async { self?.onPrevious() }
            return .success
        }
        addTarget(center.nextTrackCommand) { [weak self] _ in
            DispatchQueue.main.async { self?.onNext() }
            return .success
        }
        center.playCommand.isEnabled = true
        center.pauseCommand.isEnabled = true
        center.togglePlayPauseCommand.isEnabled = true
        center.stopCommand.isEnabled = true
        center.previousTrackCommand.isEnabled = true
        center.nextTrackCommand.isEnabled = true
        isActive = true
    }

    func update(
        title: String,
        streamTitle: String,
        providerName: String,
        durationMs: Int64,
        positionMs: Int64,
        isPlaying: Bool,
        playbackSpeed: Float
    ) {
        guard isActive else { return }
        let subtitle = [streamTitle, providerName]
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
            .joined(separator: " | ")
        guard title != lastTitle ||
                subtitle != lastSubtitle ||
                durationMs != lastDurationMs ||
                abs(positionMs - lastPositionMs) >= 1_000 ||
                isPlaying != lastIsPlaying ||
                playbackSpeed != lastPlaybackSpeed else { return }

        var info = MPNowPlayingInfoCenter.default().nowPlayingInfo ?? [:]
        info[MPMediaItemPropertyTitle] = title
        info[MPMediaItemPropertyAlbumTitle] = subtitle
        info[MPNowPlayingInfoPropertyMediaType] = MPNowPlayingInfoMediaType.video.rawValue
        info[MPNowPlayingInfoPropertyPlaybackRate] = isPlaying ? max(Double(playbackSpeed), 0.01) : 0.0
        info[MPNowPlayingInfoPropertyElapsedPlaybackTime] = Double(max(positionMs, 0)) / 1_000.0
        if durationMs > 0 {
            info[MPMediaItemPropertyPlaybackDuration] = Double(durationMs) / 1_000.0
        } else {
            info.removeValue(forKey: MPMediaItemPropertyPlaybackDuration)
        }
        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
        MPNowPlayingInfoCenter.default().playbackState = isPlaying ? .playing : .paused
        lastTitle = title
        lastSubtitle = subtitle
        lastDurationMs = durationMs
        lastPositionMs = positionMs
        lastIsPlaying = isPlaying
        lastPlaybackSpeed = playbackSpeed
    }

    func deactivate() {
        guard isActive else { return }
        let center = MPRemoteCommandCenter.shared()
        targets.forEach { command, target in command.removeTarget(target) }
        targets.removeAll()
        center.playCommand.isEnabled = false
        center.pauseCommand.isEnabled = false
        center.togglePlayPauseCommand.isEnabled = false
        center.stopCommand.isEnabled = false
        center.previousTrackCommand.isEnabled = false
        center.nextTrackCommand.isEnabled = false
        MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
        MPNowPlayingInfoCenter.default().playbackState = .stopped
        isActive = false
        lastTitle = ""
        lastSubtitle = ""
        lastDurationMs = -1
        lastPositionMs = -1
        lastIsPlaying = false
        lastPlaybackSpeed = -1
    }

    private func addTarget(
        _ command: MPRemoteCommand,
        handler: @escaping (MPRemoteCommandEvent) -> MPRemoteCommandHandlerStatus
    ) {
        let target = command.addTarget(handler: handler)
        targets.append((command, target))
    }

    deinit {
        deactivate()
    }
}
