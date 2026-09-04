import Foundation
import CoreAudio
import AudioToolbox
import AVFoundation
import Accelerate

// MARK: - Errors

enum HarnessError: Error, CustomStringConvertible {
    case infrastructure(String)
    case product(String)
    
    var description: String {
        switch self {
        case .infrastructure(let msg): return "[INFRASTRUCTURE ERROR] \(msg)"
        case .product(let msg): return "[PRODUCT ERROR] \(msg)"
        }
    }
    
    var classification: String {
        switch self {
        case .infrastructure: return "infrastructure"
        case .product: return "product"
        }
    }
}

// MARK: - DSP Helpers

func rms(_ x: [Float]) -> Float {
    guard !x.isEmpty else { return 0 }
    var square: Float = 0
    vDSP_svesq(x, 1, &square, vDSP_Length(x.count))
    return sqrt(square / Float(x.count))
}

func peakAbsolute(_ x: [Float]) -> Float {
    guard !x.isEmpty else { return 0 }
    var maxVal: Float = 0
    vDSP_maxmgv(x, 1, &maxVal, vDSP_Length(x.count))
    return maxVal
}

func normalizedCorrelation(_ sample: [Float], _ reference: [Float], at offset: Int) -> Float {
    guard offset >= 0, offset + sample.count <= reference.count, !sample.isEmpty else { return -1 }
    var dot: Float = 0, sampleEnergy: Float = 0, referenceEnergy: Float = 0
    sample.withUnsafeBufferPointer { sBuf in
        reference.withUnsafeBufferPointer { rBuf in
            let rPtr = rBuf.baseAddress! + offset
            vDSP_dotpr(sBuf.baseAddress!, 1, rPtr, 1, &dot, vDSP_Length(sample.count))
            vDSP_svesq(sBuf.baseAddress!, 1, &sampleEnergy, vDSP_Length(sample.count))
            vDSP_svesq(rPtr, 1, &referenceEnergy, vDSP_Length(sample.count))
        }
    }
    let denominator = sqrt(sampleEnergy * referenceEnergy)
    return denominator > 0 ? dot / denominator : 0
}

func bestMatch(_ sample: [Float], in reference: [Float], step: Int = 240) -> (offset: Int, correlation: Float) {
    precondition(!sample.isEmpty && sample.count <= reference.count)
    var sampleEnergy: Float = 0
    vDSP_svesq(sample, 1, &sampleEnergy, vDSP_Length(sample.count))
    guard sampleEnergy > 0 else { return (0, 0) }
    
    var best = (0, -Float.infinity)
    let maxOffset = reference.count - sample.count
    let sampleCount = sample.count
    
    sample.withUnsafeBufferPointer { sBuf in
        reference.withUnsafeBufferPointer { rBuf in
            let sPtr = sBuf.baseAddress!
            let rPtr = rBuf.baseAddress!
            
            for offset in Swift.stride(from: 0, through: maxOffset, by: max(1, step)) {
                let rSlicePtr = rPtr + offset
                var dot: Float = 0
                var refEnergy: Float = 0
                vDSP_dotpr(sPtr, 1, rSlicePtr, 1, &dot, vDSP_Length(sampleCount))
                vDSP_svesq(rSlicePtr, 1, &refEnergy, vDSP_Length(sampleCount))
                let denominator = sqrt(sampleEnergy * refEnergy)
                let score = denominator > 0 ? dot / denominator : 0
                if score > best.1 {
                    best = (offset, score)
                }
            }
            let fineStart = max(0, best.0 - step)
            let fineEnd = min(maxOffset, best.0 + step)
            for offset in fineStart ... fineEnd {
                let rSlicePtr = rPtr + offset
                var dot: Float = 0
                var refEnergy: Float = 0
                vDSP_dotpr(sPtr, 1, rSlicePtr, 1, &dot, vDSP_Length(sampleCount))
                vDSP_svesq(rSlicePtr, 1, &refEnergy, vDSP_Length(sampleCount))
                let denominator = sqrt(sampleEnergy * refEnergy)
                let score = denominator > 0 ? dot / denominator : 0
                if score > best.1 {
                    best = (offset, score)
                }
            }
        }
    }
    return best
}

func project(sample: [Float], onto reference: [Float]) -> (projection: [Float], residual: [Float], alpha: Float) {
    precondition(sample.count == reference.count && !sample.isEmpty)
    var dot: Float = 0, refEnergy: Float = 0
    vDSP_dotpr(sample, 1, reference, 1, &dot, vDSP_Length(sample.count))
    vDSP_svesq(reference, 1, &refEnergy, vDSP_Length(reference.count))
    let alpha = refEnergy > 0 ? dot / refEnergy : 0
    var projection = [Float](repeating: 0, count: sample.count)
    var residual = [Float](repeating: 0, count: sample.count)
    var scaledAlpha = alpha
    vDSP_vsmul(reference, 1, &scaledAlpha, &projection, 1, vDSP_Length(sample.count))
    vDSP_vsub(projection, 1, sample, 1, &residual, 1, vDSP_Length(sample.count))
    return (projection, residual, alpha)
}

func residualVarianceFraction(_ residual: [Float], explainedBy secondaryReference: [Float], totalEnergy: Float) -> Float {
    guard totalEnergy > 0, !residual.isEmpty, residual.count == secondaryReference.count else { return 0 }
    var dot: Float = 0, secEnergy: Float = 0
    vDSP_dotpr(residual, 1, secondaryReference, 1, &dot, vDSP_Length(residual.count))
    vDSP_svesq(secondaryReference, 1, &secEnergy, vDSP_Length(secondaryReference.count))
    guard secEnergy > 0 else { return 0 }
    let beta = dot / secEnergy
    let secondaryEnergy = beta * beta * secEnergy
    return secondaryEnergy / totalEnergy
}

func mixInterleavedStereoToMono(_ stereo: [Float]) -> [Float] {
    let frameCount = stereo.count / 2
    guard frameCount > 0 else { return [] }
    var mono = [Float](repeating: 0, count: frameCount)
    stereo.withUnsafeBufferPointer { sBuf in
        let ptr = sBuf.baseAddress!
        for i in 0 ..< frameCount {
            mono[i] = 0.5 * (ptr[i * 2] + ptr[i * 2 + 1])
        }
    }
    return mono
}

// MARK: - Phase Events

struct PhaseEvent: Codable {
    let phase: String
    let epochMs: Int64
    let pid: Int32?
    let trackId: String?
    let positionSeconds: Double?
    let success: Bool?
    let error: String?
}

func parsePhaseEvents(from content: String) -> [PhaseEvent] {
    let lines = content.components(separatedBy: .newlines)
    var events: [PhaseEvent] = []
    let decoder = JSONDecoder()
    for line in lines {
        let trimmed = line.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { continue }
        if let data = trimmed.data(using: .utf8),
           let event = try? decoder.decode(PhaseEvent.self, from: data) {
            events.append(event)
        }
    }
    return events
}

// MARK: - Asset Index & OGG Decoding

func resolveAssetPath(for trackKey: String, in assetsDir: URL) throws -> URL {
    let indexesDir = assetsDir.appendingPathComponent("indexes")
    let fm = FileManager.default
    guard fm.fileExists(atPath: indexesDir.path) else {
        throw HarnessError.infrastructure("Indexes directory not found at: \(indexesDir.path)")
    }
    let indexFiles = try fm.contentsOfDirectory(at: indexesDir, includingPropertiesForKeys: nil)
        .filter { $0.pathExtension == "json" }
    guard !indexFiles.isEmpty else {
        throw HarnessError.infrastructure("No asset index JSON found in: \(indexesDir.path)")
    }
    
    for indexURL in indexFiles {
        let data = try Data(contentsOf: indexURL)
        guard let json = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              let objects = json["objects"] as? [String: [String: Any]] else {
            continue
        }
        if let entry = objects[trackKey], let hash = entry["hash"] as? String {
            let prefix = String(hash.prefix(2))
            let objectPath = assetsDir.appendingPathComponent("objects")
                .appendingPathComponent(prefix)
                .appendingPathComponent(hash)
            if fm.fileExists(atPath: objectPath.path) {
                return objectPath
            } else {
                throw HarnessError.infrastructure("Asset object file missing for \(trackKey) at \(objectPath.path)")
            }
        }
    }
    throw HarnessError.infrastructure("Track key \(trackKey) not found in asset indexes")
}

func decodeAndResampleOGG(url: URL, targetSampleRate: Double) throws -> [Float] {
    let file = try AVAudioFile(forReading: url)
    let srcFormat = file.processingFormat
    let targetFormat = AVAudioFormat(commonFormat: .pcmFormatFloat32,
                                     sampleRate: targetSampleRate,
                                     channels: 1,
                                     interleaved: false)!
    guard let converter = AVAudioConverter(from: srcFormat, to: targetFormat) else {
        throw HarnessError.infrastructure("Failed to create AVAudioConverter from \(srcFormat) to \(targetFormat)")
    }
    
    let srcFrameCount = AVAudioFrameCount(file.length)
    guard let srcBuffer = AVAudioPCMBuffer(pcmFormat: srcFormat, frameCapacity: srcFrameCount) else {
        throw HarnessError.infrastructure("Failed to allocate source buffer for \(url.lastPathComponent)")
    }
    try file.read(into: srcBuffer)
    
    let ratio = targetSampleRate / srcFormat.sampleRate
    let dstFrameCapacity = AVAudioFrameCount(Double(srcBuffer.frameLength) * ratio) + 2048
    guard let dstBuffer = AVAudioPCMBuffer(pcmFormat: targetFormat, frameCapacity: dstFrameCapacity) else {
        throw HarnessError.infrastructure("Failed to allocate destination buffer")
    }
    
    var convError: NSError?
    var consumed = false
    let inputBlock: AVAudioConverterInputBlock = { inNumPackets, outStatus in
        if consumed {
            outStatus.pointee = .noDataNow
            return nil
        }
        consumed = true
        outStatus.pointee = .haveData
        return srcBuffer
    }
    
    converter.convert(to: dstBuffer, error: &convError, withInputFrom: inputBlock)
    if let convError = convError {
        throw HarnessError.infrastructure("AVAudioConverter failed: \(convError)")
    }
    
    guard let channelData = dstBuffer.floatChannelData else {
        throw HarnessError.infrastructure("No channel data in converted buffer")
    }
    let frames = Int(dstBuffer.frameLength)
    let floats = Array(UnsafeBufferPointer(start: channelData[0], count: frames))
    return floats
}

// MARK: - Assertion System & Report

enum AssertionResult {
    case metric(name: String, measured: Double, threshold: Double, op: String, passed: Bool)
    case range(name: String, measured: Double, min: Double, max: Double, passed: Bool)
    
    var name: String {
        switch self {
        case .metric(let name, _, _, _, _): return name
        case .range(let name, _, _, _, _): return name
        }
    }
    
    var passed: Bool {
        switch self {
        case .metric(_, _, _, _, let passed): return passed
        case .range(_, _, _, _, let passed): return passed
        }
    }
}

struct Report: Codable {
    let passed: Bool
    let classification: String // "success", "product", "infrastructure"
    let error: String?
    let assertions: [ReportAssertion]
}

struct ReportAssertion: Codable {
    let name: String
    let measured: Double
    let expected: String
    let passed: Bool
}

class TestReporter {
    var results: [AssertionResult] = []
    
    @discardableResult
    func assertMetric(_ name: String, _ measured: Float, atLeast threshold: Float) -> Bool {
        let m = Double(measured)
        let t = Double(threshold)
        let passed = m >= t
        results.append(.metric(name: name, measured: m, threshold: t, op: ">=", passed: passed))
        let tag = passed ? "[PASS]" : "[FAIL]"
        print("\(tag) \(name): \(String(format: "%.4f", m)) (expected >= \(String(format: "%.4f", t)))")
        fflush(stdout)
        return passed
    }
    
    @discardableResult
    func assertMetric(_ name: String, _ measured: Float, atMost threshold: Float) -> Bool {
        let m = Double(measured)
        let t = Double(threshold)
        let passed = m <= t
        results.append(.metric(name: name, measured: m, threshold: t, op: "<=", passed: passed))
        let tag = passed ? "[PASS]" : "[FAIL]"
        print("\(tag) \(name): \(String(format: "%.6f", m)) (expected <= \(String(format: "%.6f", t)))")
        fflush(stdout)
        return passed
    }
    
    @discardableResult
    func assertRange(_ name: String, _ measured: Double, _ range: ClosedRange<Double>) -> Bool {
        let passed = range.contains(measured)
        results.append(.range(name: name, measured: measured, min: range.lowerBound, max: range.upperBound, passed: passed))
        let tag = passed ? "[PASS]" : "[FAIL]"
        print("\(tag) \(name): \(String(format: "%.3f", measured))s (expected \(range.lowerBound) ... \(range.upperBound)s)")
        fflush(stdout)
        return passed
    }
    
    var allPassed: Bool {
        return !results.isEmpty && results.allSatisfy { $0.passed }
    }
    
    func generateReport(classification: String, error: String? = nil) -> Report {
        let passed = allPassed && error == nil
        let assertionReports = results.map { res -> ReportAssertion in
            switch res {
            case .metric(let name, let measured, let threshold, let op, let p):
                return ReportAssertion(name: name, measured: measured, expected: "\(op) \(threshold)", passed: p)
            case .range(let name, let measured, let min, let max, let p):
                return ReportAssertion(name: name, measured: measured, expected: "\(min) ... \(max)", passed: p)
            }
        }
        return Report(
            passed: passed,
            classification: passed ? "success" : classification,
            error: error,
            assertions: assertionReports
        )
    }
    
    func writeReport(to url: URL, classification: String, error: String? = nil) throws {
        let report = generateReport(classification: classification, error: error)
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        let data = try encoder.encode(report)
        try data.write(to: url)
    }
}

// MARK: - WAV Export

func writeWavFile(url: URL, interleavedSamples: [Float], sampleRate: Double) throws {
    let settings: [String: Any] = [
        AVFormatIDKey: kAudioFormatLinearPCM,
        AVSampleRateKey: sampleRate,
        AVNumberOfChannelsKey: 2,
        AVLinearPCMBitDepthKey: 32,
        AVLinearPCMIsFloatKey: true,
        AVLinearPCMIsBigEndianKey: false,
        AVLinearPCMIsNonInterleaved: false
    ]
    let wavFile = try AVAudioFile(forWriting: url, settings: settings, commonFormat: .pcmFormatFloat32, interleaved: true)
    let frameCount = AVAudioFrameCount(interleavedSamples.count / 2)
    guard frameCount > 0 else { return }
    guard let buffer = AVAudioPCMBuffer(pcmFormat: wavFile.processingFormat, frameCapacity: frameCount) else {
        throw HarnessError.infrastructure("Failed to allocate AVAudioPCMBuffer for writing WAV")
    }
    buffer.frameLength = frameCount
    if let channelData = buffer.floatChannelData {
        interleavedSamples.withUnsafeBufferPointer { sBuf in
            channelData[0].initialize(from: sBuf.baseAddress!, count: interleavedSamples.count)
        }
    }
    try wavFile.write(from: buffer)
}

// MARK: - Process Audio Capture

final class ProcessAudioCapture {
    private var processObject: AudioObjectID = 0
    private var tapID: AudioObjectID = 0
    private var aggregateID: AudioObjectID = 0
    private var ioProcID: AudioDeviceIOProcID?
    private(set) var sampleRate: Float64 = 48000.0
    
    private var lock = os_unfair_lock_s()
    private(set) var stereoSamples: [Float] = []
    private(set) var firstBufferEpochMs: Int64?
    
    func start(targetPID: pid_t) throws {
        // Reserve capacity for ~35 seconds of stereo Float32 @ 48kHz to avoid allocations on real-time thread
        os_unfair_lock_lock(&lock)
        stereoSamples.removeAll(keepingCapacity: true)
        stereoSamples.reserveCapacity(48_000 * 2 * 35)
        firstBufferEpochMs = nil
        os_unfair_lock_unlock(&lock)

        // Retry translating PID for up to 10 seconds in case the sound engine is initializing
        var procObj = AudioObjectID(0)
        var address = AudioObjectPropertyAddress(
            mSelector: kAudioHardwarePropertyTranslatePIDToProcessObject,
            mScope: kAudioObjectPropertyScopeGlobal,
            mElement: kAudioObjectPropertyElementMain
        )
        var pid = targetPID
        var size = UInt32(MemoryLayout<AudioObjectID>.size)
        var status = OSStatus(-1)
        
        let deadline = Date().addingTimeInterval(10.0)
        while Date() < deadline {
            procObj = AudioObjectID(0)
            size = UInt32(MemoryLayout<AudioObjectID>.size)
            status = AudioObjectGetPropertyData(
                AudioObjectID(kAudioObjectSystemObject),
                &address,
                UInt32(MemoryLayout<pid_t>.size),
                &pid,
                &size,
                &procObj
            )
            if status == noErr && procObj != kAudioObjectUnknown && procObj != 0 {
                break
            }
            Thread.sleep(forTimeInterval: 0.1)
        }
        
        guard status == noErr, procObj != kAudioObjectUnknown, procObj != 0 else {
            throw HarnessError.infrastructure("Failed to translate PID \(targetPID) to CoreAudio process object: OSStatus \(status)")
        }
        self.processObject = procObj
        
        // Create Process Tap
        let tapDesc = CATapDescription(stereoMixdownOfProcesses: [procObj])
        var tap = AudioObjectID(0)
        status = AudioHardwareCreateProcessTap(tapDesc, &tap)
        guard status == noErr, tap != 0 else {
            throw HarnessError.infrastructure("Failed to create process tap: OSStatus \(status)")
        }
        self.tapID = tap
        
        // Read Tap UID
        var tapUIDRef: Unmanaged<CFString>?
        var tapUIDSize = UInt32(MemoryLayout<Unmanaged<CFString>?>.size)
        var tapUIDAddress = AudioObjectPropertyAddress(
            mSelector: kAudioTapPropertyUID,
            mScope: kAudioObjectPropertyScopeGlobal,
            mElement: kAudioObjectPropertyElementMain
        )
        status = withUnsafeMutablePointer(to: &tapUIDRef) { ptr in
            AudioObjectGetPropertyData(tap, &tapUIDAddress, 0, nil, &tapUIDSize, ptr)
        }
        guard status == noErr, let tapUID = tapUIDRef?.takeRetainedValue() as String? else {
            AudioHardwareDestroyProcessTap(tap)
            self.tapID = 0
            throw HarnessError.infrastructure("Failed to get process tap UID: OSStatus \(status)")
        }
        
        // Create private Aggregate Device
        let subTap: [String: Any] = [kAudioSubTapUIDKey: tapUID]
        let aggDesc: [String: Any] = [
            kAudioAggregateDeviceNameKey: "CMMProcessAudioTestAggregate",
            kAudioAggregateDeviceUIDKey: UUID().uuidString,
            kAudioAggregateDeviceTapListKey: [subTap],
            kAudioAggregateDeviceIsPrivateKey: 1
        ]
        var agg = AudioObjectID(0)
        status = AudioHardwareCreateAggregateDevice(aggDesc as CFDictionary, &agg)
        guard status == noErr, agg != 0 else {
            AudioHardwareDestroyProcessTap(tap)
            self.tapID = 0
            throw HarnessError.infrastructure("Failed to create aggregate device: OSStatus \(status)")
        }
        self.aggregateID = agg
        
        // Query sample rate
        var nominalRate: Float64 = 48000.0
        var rateSize = UInt32(MemoryLayout<Float64>.size)
        var rateAddress = AudioObjectPropertyAddress(
            mSelector: kAudioDevicePropertyNominalSampleRate,
            mScope: kAudioObjectPropertyScopeGlobal,
            mElement: kAudioObjectPropertyElementMain
        )
        if AudioObjectGetPropertyData(agg, &rateAddress, 0, nil, &rateSize, &nominalRate) == noErr && nominalRate > 0 {
            self.sampleRate = nominalRate
        }
        
        // Register IOProc
        let clientData = Unmanaged.passUnretained(self).toOpaque()
        let ioProc: AudioDeviceIOProc = { device, now, inputData, inputTime, outputData, outputTime, clientData in
            guard let clientData = clientData else { return noErr }
            let capture = Unmanaged<ProcessAudioCapture>.fromOpaque(clientData).takeUnretainedValue()
            capture.processInput(inputData: inputData)
            return noErr
        }
        
        var procID: AudioDeviceIOProcID?
        status = AudioDeviceCreateIOProcID(agg, ioProc, clientData, &procID)
        guard status == noErr, let validProcID = procID else {
            AudioHardwareDestroyAggregateDevice(agg)
            AudioHardwareDestroyProcessTap(tap)
            self.aggregateID = 0
            self.tapID = 0
            throw HarnessError.infrastructure("Failed to create AudioDeviceIOProcID: OSStatus \(status)")
        }
        self.ioProcID = validProcID
        
        status = AudioDeviceStart(agg, validProcID)
        guard status == noErr else {
            AudioDeviceDestroyIOProcID(agg, validProcID)
            AudioHardwareDestroyAggregateDevice(agg)
            AudioHardwareDestroyProcessTap(tap)
            self.ioProcID = nil
            self.aggregateID = 0
            self.tapID = 0
            throw HarnessError.infrastructure("Failed to start AudioDevice: OSStatus \(status)")
        }
    }
    
    func processInput(inputData: UnsafePointer<AudioBufferList>) {
        os_unfair_lock_lock(&lock)
        defer { os_unfair_lock_unlock(&lock) }
        
        let nowMs = Int64(Date().timeIntervalSince1970 * 1000)
        if firstBufferEpochMs == nil {
            firstBufferEpochMs = nowMs
        }
        
        let abl = UnsafeMutableAudioBufferListPointer(UnsafeMutablePointer(mutating: inputData))
        if abl.count == 1 {
            let buf = abl[0]
            if let data = buf.mData {
                let floatPtr = data.assumingMemoryBound(to: Float.self)
                let sampleCount = Int(buf.mDataByteSize) / MemoryLayout<Float>.size
                stereoSamples.append(contentsOf: UnsafeBufferPointer(start: floatPtr, count: sampleCount))
            }
        } else if abl.count >= 2 {
            let b0 = abl[0]
            let b1 = abl[1]
            if let d0 = b0.mData, let d1 = b1.mData {
                let leftPtr = d0.assumingMemoryBound(to: Float.self)
                let rightPtr = d1.assumingMemoryBound(to: Float.self)
                let frames = min(
                    Int(b0.mDataByteSize) / MemoryLayout<Float>.size,
                    Int(b1.mDataByteSize) / MemoryLayout<Float>.size
                )
                for i in 0 ..< frames {
                    stereoSamples.append(leftPtr[i])
                    stereoSamples.append(rightPtr[i])
                }
            }
        }
    }
    
    func stopAndCleanup() {
        os_unfair_lock_lock(&lock)
        let procID = ioProcID
        let agg = aggregateID
        let tap = tapID
        self.ioProcID = nil
        self.aggregateID = 0
        self.tapID = 0
        os_unfair_lock_unlock(&lock)
        
        if let procID = procID, agg != 0 {
            AudioDeviceStop(agg, procID)
            AudioDeviceDestroyIOProcID(agg, procID)
        }
        if agg != 0 {
            AudioHardwareDestroyAggregateDevice(agg)
        }
        if tap != 0 {
            AudioHardwareDestroyProcessTap(tap)
        }
    }
    
    deinit {
        stopAndCleanup()
    }
    
    func getCapturedSamples() -> (samples: [Float], startEpochMs: Int64, sampleRate: Double) {
        os_unfair_lock_lock(&lock)
        defer { os_unfair_lock_unlock(&lock) }
        return (stereoSamples, firstBufferEpochMs ?? 0, Double(sampleRate))
    }
}

// MARK: - Capture Slicing & Assertions

func extractSlice(
    from stereoSamples: [Float],
    captureStartEpochMs: Int64,
    sampleRate: Double,
    windowStartEpochMs: Int64,
    windowEndEpochMs: Int64
) -> [Float]? {
    let startFrame = Int(Double(windowStartEpochMs - captureStartEpochMs) * sampleRate / 1000.0)
    let endFrame = Int(Double(windowEndEpochMs - captureStartEpochMs) * sampleRate / 1000.0)
    guard startFrame >= 0, endFrame > startFrame else { return nil }
    let totalFrames = stereoSamples.count / 2
    guard endFrame <= totalFrames else { return nil }
    let sampleStart = startFrame * 2
    let sampleEnd = endFrame * 2
    let stereoSlice = Array(stereoSamples[sampleStart ..< sampleEnd])
    return mixInterleavedStereoToMono(stereoSlice)
}

func findEvent(in events: [PhaseEvent], matching keywords: [String], fallbackTrack: String? = nil) -> PhaseEvent? {
    for event in events {
        let p = event.phase.uppercased()
        for kw in keywords {
            if p.contains(kw.uppercased()) {
                return event
            }
        }
        if let fb = fallbackTrack, event.trackId == fb {
            return event
        }
    }
    return nil
}

func runCaptureAssertions(
    events: [PhaseEvent],
    stereoSamples: [Float],
    captureStartEpochMs: Int64,
    sampleRate: Double,
    swedenMono: [Float],
    wetHandsMono: [Float],
    reporter: TestReporter
) throws {
    guard let startEvent = findEvent(in: events, matching: ["PLAY_A", "PLAY", "START"], fallbackTrack: "vanilla:sweden") else {
        throw HarnessError.infrastructure("No Sweden play event found in events")
    }
    guard let seekEvent = findEvent(in: events, matching: ["SEEK_APPLIED", "WAIT_SEEK", "SEEK_A", "SEEK"]) else {
        throw HarnessError.infrastructure("No Sweden seek event found in events")
    }
    guard let switchEvent = findEvent(in: events, matching: ["SWITCH_B", "SWITCH"], fallbackTrack: "vanilla:wet_hands") else {
        throw HarnessError.infrastructure("No Wet Hands switch event found in events")
    }
    guard let pauseEvent = findEvent(in: events, matching: ["PAUSE_B", "WAIT_PAUSE", "PAUSE"]) else {
        throw HarnessError.infrastructure("No pause event found in events")
    }
    guard let resumeEvent = findEvent(in: events, matching: ["RESUME_B", "WAIT_RESUME", "RESUME"]) else {
        throw HarnessError.infrastructure("No resume event found in events")
    }
    
    // 1. Sweden start (audio captured 1–3 seconds after Sweden starts)
    guard let startSlice = extractSlice(
        from: stereoSamples,
        captureStartEpochMs: captureStartEpochMs,
        sampleRate: sampleRate,
        windowStartEpochMs: startEvent.epochMs + 1000,
        windowEndEpochMs: startEvent.epochMs + 3000
    ) else {
        throw HarnessError.infrastructure("Insufficient capture data for Sweden start window")
    }
    let startMatch = bestMatch(startSlice, in: swedenMono)
    reporter.assertMetric("sweden.start.correlation", startMatch.correlation, atLeast: 0.85)
    let startOffsetSeconds = Double(startMatch.offset) / sampleRate
    reporter.assertRange("sweden.start.offset", startOffsetSeconds, 0.0 ... 3.5)
    
    // 2. Sweden seek (audio captured 0.5–2.5 seconds after the 60-second seek)
    guard let seekSlice = extractSlice(
        from: stereoSamples,
        captureStartEpochMs: captureStartEpochMs,
        sampleRate: sampleRate,
        windowStartEpochMs: seekEvent.epochMs + 500,
        windowEndEpochMs: seekEvent.epochMs + 2500
    ) else {
        throw HarnessError.infrastructure("Insufficient capture data for Sweden seek window")
    }
    let seekMatch = bestMatch(seekSlice, in: swedenMono)
    reporter.assertMetric("sweden.seek.correlation", seekMatch.correlation, atLeast: 0.85)
    let seekOffsetSeconds = Double(seekMatch.offset) / sampleRate
    reporter.assertRange("sweden.seek.offset", seekOffsetSeconds, 60.0 ... 62.5)
    
    // 3. Wet Hands switch (audio captured 1–3 seconds after switching to Wet Hands)
    guard let switchSlice = extractSlice(
        from: stereoSamples,
        captureStartEpochMs: captureStartEpochMs,
        sampleRate: sampleRate,
        windowStartEpochMs: switchEvent.epochMs + 1000,
        windowEndEpochMs: switchEvent.epochMs + 3000
    ) else {
        throw HarnessError.infrastructure("Insufficient capture data for Wet Hands switch window")
    }
    let switchMatch = bestMatch(switchSlice, in: wetHandsMono)
    reporter.assertMetric("wet_hands.switch.correlation", switchMatch.correlation, atLeast: 0.85)
    
    // Residual variance: project Wet Hands out, check Sweden
    let wetHandsAligned = Array(wetHandsMono[switchMatch.offset ..< switchMatch.offset + switchSlice.count])
    let (_, residual, _) = project(sample: switchSlice, onto: wetHandsAligned)
    let swedenResidualMatch = bestMatch(residual, in: swedenMono)
    let swedenAligned = Array(swedenMono[swedenResidualMatch.offset ..< swedenResidualMatch.offset + residual.count])
    var switchEnergy: Float = 0
    vDSP_svesq(switchSlice, 1, &switchEnergy, vDSP_Length(switchSlice.count))
    let swedenResidualVariance = residualVarianceFraction(residual, explainedBy: swedenAligned, totalEnergy: switchEnergy)
    reporter.assertMetric("sweden.residual.variance", swedenResidualVariance, atMost: 0.03)
    
    // 4. Pause window (settled pause: 0.5s to 1.5s after pause)
    guard let pauseSlice = extractSlice(
        from: stereoSamples,
        captureStartEpochMs: captureStartEpochMs,
        sampleRate: sampleRate,
        windowStartEpochMs: pauseEvent.epochMs + 500,
        windowEndEpochMs: pauseEvent.epochMs + 1500
    ) else {
        throw HarnessError.infrastructure("Insufficient capture data for pause window")
    }
    let pausePeak = peakAbsolute(pauseSlice)
    let pauseRms = rms(pauseSlice)
    reporter.assertMetric("pause.peak", pausePeak, atMost: 0.001)
    reporter.assertMetric("pause.rms", pauseRms, atMost: 0.0005)
    
    // 5. Wet Hands resume (audio captured 0.5–2.5 seconds after resume)
    guard let resumeSlice = extractSlice(
        from: stereoSamples,
        captureStartEpochMs: captureStartEpochMs,
        sampleRate: sampleRate,
        windowStartEpochMs: resumeEvent.epochMs + 500,
        windowEndEpochMs: resumeEvent.epochMs + 2500
    ) else {
        throw HarnessError.infrastructure("Insufficient capture data for Wet Hands resume window")
    }
    let resumeMatch = bestMatch(resumeSlice, in: wetHandsMono)
    reporter.assertMetric("wet_hands.resume.correlation", resumeMatch.correlation, atLeast: 0.85)
    
    // Wet hands resume offset delta
    let prePauseStartMs = max(switchEvent.epochMs + 3000, pauseEvent.epochMs - 1000)
    let prePauseEndMs = pauseEvent.epochMs
    var expectedResumeOffset: Double
    if prePauseEndMs > prePauseStartMs,
       let prePauseSlice = extractSlice(
        from: stereoSamples,
        captureStartEpochMs: captureStartEpochMs,
        sampleRate: sampleRate,
        windowStartEpochMs: prePauseStartMs,
        windowEndEpochMs: prePauseEndMs
       ) {
        let prePauseMatch = bestMatch(prePauseSlice, in: wetHandsMono)
        let elapsedFromPrePauseStartToResumeWindow = 0.5 + Double(prePauseEndMs - prePauseStartMs) / 1000.0
        expectedResumeOffset = Double(prePauseMatch.offset) / sampleRate + elapsedFromPrePauseStartToResumeWindow
    } else {
        let playedBeforePause = Double(pauseEvent.epochMs - (switchEvent.epochMs + 1000)) / 1000.0
        expectedResumeOffset = Double(switchMatch.offset) / sampleRate + playedBeforePause + 0.5
    }
    let actualResumeOffset = Double(resumeMatch.offset) / sampleRate
    let resumeOffsetDelta = Float(abs(actualResumeOffset - expectedResumeOffset))
    reporter.assertMetric("wet_hands.resume.offset_delta", resumeOffsetDelta, atMost: 0.5)
}

// MARK: - Self-Test Suite

func runDspSelfTests() {
    print("[SELF-TEST] Running generated DSP checks...")
    fflush(stdout)
    let rate = 48_000
    let reference = (0 ..< rate * 4).map { i -> Float in
        let t = Double(i) / Double(rate)
        let f = 440.0 + 20.0 * (t / 4.0)
        return Float(sin(2 * Double.pi * f * t))
    }
    let sample = Array(reference[rate ..< rate * 2])
    
    precondition(abs(rms(sample) - 0.707) < 0.01, "RMS of 440Hz sine wave should be ~0.707")
    print("  [PASS] Sine RMS check: \(rms(sample)) ~ 0.707")
    fflush(stdout)
    
    let match = bestMatch(sample, in: reference)
    precondition(match.correlation > 0.99, "Correlation should be > 0.99, got \(match.correlation)")
    precondition(abs(match.offset - rate) <= 240, "Offset delta should be <= 240, got \(abs(match.offset - rate))")
    print("  [PASS] Best match: correlation=\(match.correlation), offset=\(match.offset)")
    fflush(stdout)
    
    let tone2 = (0 ..< rate * 4).map { i -> Float in
        let t = Double(i) / Double(rate)
        let f = 1000.0 + 20.0 * (t / 4.0)
        return Float(sin(2 * Double.pi * f * t))
    }
    let tone2Sample = Array(tone2[rate ..< rate * 2])
    
    // 0% overlap mixture
    let mix0 = tone2Sample
    let proj0 = project(sample: mix0, onto: tone2Sample)
    var energy0: Float = 0
    vDSP_svesq(mix0, 1, &energy0, vDSP_Length(mix0.count))
    let variance0 = residualVarianceFraction(proj0.residual, explainedBy: sample, totalEnergy: energy0)
    precondition(variance0 <= 0.03, "0% overlap should yield residual variance <= 0.03, got \(variance0)")
    print("  [PASS] Residual projection (0% overlap): variance=\(variance0) <= 0.03")
    fflush(stdout)
    
    // 20% overlap mixture
    let mix20 = (0 ..< rate).map { tone2Sample[$0] + 0.2 * sample[$0] }
    let proj20 = project(sample: mix20, onto: tone2Sample)
    var energy20: Float = 0
    vDSP_svesq(mix20, 1, &energy20, vDSP_Length(mix20.count))
    let variance20 = residualVarianceFraction(proj20.residual, explainedBy: sample, totalEnergy: energy20)
    precondition(variance20 > 0.03, "20% overlap should yield residual variance > 0.03, got \(variance20)")
    print("  [PASS] Residual projection (20% overlap): variance=\(variance20) > 0.03 (detected)")
    fflush(stdout)
    
    // Silence checks
    let silent = [Float](repeating: 0.0001, count: rate)
    let peak = peakAbsolute(silent)
    let silentRms = rms(silent)
    precondition(peak < 0.001, "Silence peak should be < 0.001, got \(peak)")
    precondition(silentRms < 0.0005, "Silence RMS should be < 0.0005, got \(silentRms)")
    print("  [PASS] Silence thresholds: peak=\(peak) < 0.001, rms=\(silentRms) < 0.0005")
    fflush(stdout)
}

func runEventParsingSelfTests() {
    print("[SELF-TEST] Testing event parsing...")
    fflush(stdout)
    let sampleJSONL = """
    {"phase":"WAIT_READY","epochMs":1000000,"pid":12345,"trackId":null,"positionSeconds":null,"success":true,"error":null}
    {"phase":"PLAY_A","epochMs":1001000,"pid":null,"trackId":"vanilla:sweden","positionSeconds":0.0,"success":true,"error":null}
    {"phase":"SEEK_A","epochMs":1005000,"pid":null,"trackId":"vanilla:sweden","positionSeconds":60.0,"success":true,"error":null}
    {"phase":"SWITCH_B","epochMs":1010000,"pid":null,"trackId":"vanilla:wet_hands","positionSeconds":0.0,"success":true,"error":null}
    {"phase":"PAUSE_B","epochMs":1015000,"pid":null,"trackId":"vanilla:wet_hands","positionSeconds":null,"success":true,"error":null}
    {"phase":"RESUME_B","epochMs":1020000,"pid":null,"trackId":"vanilla:wet_hands","positionSeconds":null,"success":true,"error":null}
    {"phase":"FINISH","epochMs":1025000,"pid":null,"trackId":null,"positionSeconds":null,"success":true,"error":null}
    """
    let events = parsePhaseEvents(from: sampleJSONL)
    precondition(events.count == 7, "Expected 7 parsed events, got \(events.count)")
    precondition(events[0].pid == 12345, "Expected PID 12345, got \(String(describing: events[0].pid))")
    precondition(events[1].phase == "PLAY_A" && events[1].trackId == "vanilla:sweden")
    precondition(events[2].positionSeconds == 60.0)
    print("  [PASS] Successfully parsed 7 PhaseEvents from JSONL")
    fflush(stdout)
}

func runReportSelfTests() {
    print("[SELF-TEST] Testing synthetic capture assertion evaluation and report generation...")
    fflush(stdout)
    
    let sampleRate = 48000.0
    let rate = 48000
    
    // Synthesize reference tracks
    // Sweden: 70s chirp around 440 Hz
    let swedenMono = (0 ..< rate * 70).map { i -> Float in
        let t = Double(i) / sampleRate
        let f = 440.0 + 10.0 * (t / 70.0)
        return Float(sin(2 * Double.pi * f * t))
    }
    // Wet Hands: 30s chirp around 1000 Hz
    let wetHandsMono = (0 ..< rate * 30).map { i -> Float in
        let t = Double(i) / sampleRate
        let f = 1000.0 + 10.0 * (t / 30.0)
        return Float(sin(2 * Double.pi * f * t))
    }
    
    // Timeline in ms:
    // Base epoch: 10_000
    // Capture starts at epoch 9_000, runs for 25 seconds = 25,000 ms (until 34_000)
    let captureStartEpochMs: Int64 = 9_000
    let totalCaptureSeconds = 25.0
    let totalFrames = Int(totalCaptureSeconds * sampleRate)
    
    // Build interleaved stereo capture buffer
    var monoCapture = [Float](repeating: 0.00005, count: totalFrames) // slight noise floor
    
    let tPlayA: Int64 = 10_000
    let tSeekA: Int64 = 14_000
    let tSwitchB: Int64 = 18_000
    let tPauseB: Int64 = 23_000
    let tResumeB: Int64 = 26_000
    let tFinish: Int64 = 30_000
    
    // 1. Sweden start: window is [tPlayA + 1000, tPlayA + 3000] = [11000, 13000].
    // Insert sweden at offset 1.0s
    let startFrame = Int(Double(11000 - captureStartEpochMs) * sampleRate / 1000.0)
    let startCount = Int(2.0 * sampleRate)
    let swedenStartSlice = Array(swedenMono[rate * 1 ..< rate * 1 + startCount])
    for i in 0 ..< startCount {
        monoCapture[startFrame + i] = swedenStartSlice[i]
    }
    
    // 2. Sweden seek: window is [tSeekA + 500, tSeekA + 2500] = [14500, 16500].
    // Insert sweden at offset 60.5s
    let seekFrame = Int(Double(14500 - captureStartEpochMs) * sampleRate / 1000.0)
    let seekCount = Int(2.0 * sampleRate)
    let swedenSeekOffset = Int(60.5 * sampleRate)
    let swedenSeekSlice = Array(swedenMono[swedenSeekOffset ..< swedenSeekOffset + seekCount])
    for i in 0 ..< seekCount {
        monoCapture[seekFrame + i] = swedenSeekSlice[i]
    }
    
    // 3. Wet hands switch: window is [tSwitchB + 1000, tSwitchB + 3000] = [19000, 21000].
    // Insert wet hands at offset 1.0s
    let switchFrame = Int(Double(19000 - captureStartEpochMs) * sampleRate / 1000.0)
    let switchCount = Int(2.0 * sampleRate)
    let wetHandsSlice = Array(wetHandsMono[rate * 1 ..< rate * 1 + switchCount])
    for i in 0 ..< switchCount {
        monoCapture[switchFrame + i] = wetHandsSlice[i]
    }
    
    // Also insert wet hands leading up to pause (1.0s before pause = [22000, 23000])
    let prePauseFrame = Int(Double(22000 - captureStartEpochMs) * sampleRate / 1000.0)
    let prePauseCount = Int(1.0 * sampleRate)
    let wetHandsPrePause = Array(wetHandsMono[rate * 4 ..< rate * 4 + prePauseCount])
    for i in 0 ..< prePauseCount {
        monoCapture[prePauseFrame + i] = wetHandsPrePause[i]
    }
    
    // 4. Pause window is [tPauseB + 500, tPauseB + 1500] = [23500, 24500].
    // Leave as silence ~0.00005.
    
    // 5. Wet hands resume: window is [tResumeB + 500, tResumeB + 2500] = [26500, 28500].
    // Resumes from pre-pause position (offset 5.0s + 0.5s = 5.5s)
    let resumeFrame = Int(Double(26500 - captureStartEpochMs) * sampleRate / 1000.0)
    let resumeCount = Int(2.0 * sampleRate)
    let resumeOffset = Int(5.5 * sampleRate)
    let wetHandsResumeSlice = Array(wetHandsMono[resumeOffset ..< resumeOffset + resumeCount])
    for i in 0 ..< resumeCount {
        monoCapture[resumeFrame + i] = wetHandsResumeSlice[i]
    }
    
    // Build stereo interleaved capture
    var stereoSamples = [Float](repeating: 0, count: totalFrames * 2)
    for i in 0 ..< totalFrames {
        stereoSamples[i * 2] = monoCapture[i]
        stereoSamples[i * 2 + 1] = monoCapture[i]
    }
    
    let events = [
        PhaseEvent(phase: "WAIT_READY", epochMs: 9_500, pid: 9999, trackId: nil, positionSeconds: nil, success: true, error: nil),
        PhaseEvent(phase: "PLAY_A", epochMs: tPlayA, pid: nil, trackId: "vanilla:sweden", positionSeconds: 0.0, success: true, error: nil),
        PhaseEvent(phase: "SEEK_A", epochMs: tSeekA, pid: nil, trackId: "vanilla:sweden", positionSeconds: 60.0, success: true, error: nil),
        PhaseEvent(phase: "SWITCH_B", epochMs: tSwitchB, pid: nil, trackId: "vanilla:wet_hands", positionSeconds: 0.0, success: true, error: nil),
        PhaseEvent(phase: "PAUSE_B", epochMs: tPauseB, pid: nil, trackId: "vanilla:wet_hands", positionSeconds: nil, success: true, error: nil),
        PhaseEvent(phase: "RESUME_B", epochMs: tResumeB, pid: nil, trackId: "vanilla:wet_hands", positionSeconds: nil, success: true, error: nil),
        PhaseEvent(phase: "FINISH", epochMs: tFinish, pid: nil, trackId: nil, positionSeconds: nil, success: true, error: nil)
    ]
    
    // Positive test
    let reporter = TestReporter()
    do {
        try runCaptureAssertions(
            events: events,
            stereoSamples: stereoSamples,
            captureStartEpochMs: captureStartEpochMs,
            sampleRate: sampleRate,
            swedenMono: swedenMono,
            wetHandsMono: wetHandsMono,
            reporter: reporter
        )
    } catch {
        fatalError("runCaptureAssertions threw unexpected error: \(error)")
    }
    
    precondition(reporter.allPassed, "Expected all 10 assertions to pass in positive self-test")
    precondition(reporter.results.count == 10, "Expected exactly 10 assertions, got \(reporter.results.count)")
    
    let tempDir = URL(fileURLWithPath: NSTemporaryDirectory()).appendingPathComponent(UUID().uuidString)
    try! FileManager.default.createDirectory(at: tempDir, withIntermediateDirectories: true)
    defer { try? FileManager.default.removeItem(at: tempDir) }
    
    let reportURL = tempDir.appendingPathComponent("report.json")
    try! reporter.writeReport(to: reportURL, classification: "success")
    
    let reportData = try! Data(contentsOf: reportURL)
    let decodedReport = try! JSONDecoder().decode(Report.self, from: reportData)
    precondition(decodedReport.passed == true, "Report should show passed == true")
    precondition(decodedReport.classification == "success", "Report should show classification == success")
    precondition(decodedReport.assertions.count == 10, "Report should contain 10 assertions")
    print("  [PASS] Positive test report generated and verified valid JSON")
    fflush(stdout)
    
    // Negative test: regression injection (corrupt seek offset)
    var corruptCapture = stereoSamples
    // Zero out seek slice and fill with audio from offset 5s instead of 60.5s
    let wrongSeekOffset = Int(5.0 * sampleRate)
    let wrongSeekSlice = Array(swedenMono[wrongSeekOffset ..< wrongSeekOffset + seekCount])
    for i in 0 ..< seekCount {
        corruptCapture[(seekFrame + i) * 2] = wrongSeekSlice[i]
        corruptCapture[(seekFrame + i) * 2 + 1] = wrongSeekSlice[i]
    }
    
    let corruptReporter = TestReporter()
    try! runCaptureAssertions(
        events: events,
        stereoSamples: corruptCapture,
        captureStartEpochMs: captureStartEpochMs,
        sampleRate: sampleRate,
        swedenMono: swedenMono,
        wetHandsMono: wetHandsMono,
        reporter: corruptReporter
    )
    
    precondition(!corruptReporter.allPassed, "Corrupt seek should cause assertions to fail")
    let seekOffsetResult = corruptReporter.results.first(where: { $0.name == "sweden.seek.offset" })
    precondition(seekOffsetResult?.passed == false, "sweden.seek.offset should fail in negative test")
    let corruptReport = corruptReporter.generateReport(classification: "product", error: "Seek regression detected")
    precondition(corruptReport.passed == false, "Corrupt report should have passed == false")
    precondition(corruptReport.classification == "product", "Corrupt report should have classification == product")
    print("  [PASS] Negative test caught seek offset regression and classified as 'product'")
    fflush(stdout)
    
    // WAV export test
    let wavURL = tempDir.appendingPathComponent("test_capture.wav")
    try! writeWavFile(url: wavURL, interleavedSamples: Array(stereoSamples.prefix(rate * 2)), sampleRate: sampleRate)
    let writtenWav = try! AVAudioFile(forReading: wavURL)
    precondition(writtenWav.length == AVAudioFramePosition(rate), "WAV file should have 1 second of frames")
    print("  [PASS] WAV export verified with AVAudioFile")
    fflush(stdout)
}

func writeOrUpdateErrorReport(at reportURL: URL, classification: String, errorDescription: String) {
    var existingAssertions: [ReportAssertion] = []
    if let existingData = try? Data(contentsOf: reportURL),
       let existingReport = try? JSONDecoder().decode(Report.self, from: existingData),
       !existingReport.assertions.isEmpty {
        existingAssertions = existingReport.assertions
    }
    
    let report = Report(
        passed: false,
        classification: classification,
        error: errorDescription,
        assertions: existingAssertions
    )
    let encoder = JSONEncoder()
    encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
    if let data = try? encoder.encode(report) {
        try? data.write(to: reportURL)
    }
}

func runReportPreservationSelfTest() {
    print("[SELF-TEST] Testing report preservation under error...")
    fflush(stdout)
    let tempDir = URL(fileURLWithPath: NSTemporaryDirectory()).appendingPathComponent(UUID().uuidString)
    try! FileManager.default.createDirectory(at: tempDir, withIntermediateDirectories: true)
    defer { try? FileManager.default.removeItem(at: tempDir) }
    
    let reportURL = tempDir.appendingPathComponent("report.json")
    let initialReport = Report(
        passed: false,
        classification: "product",
        error: "Product assertion failed",
        assertions: [ReportAssertion(name: "sweden.seek.offset", measured: 5.0, expected: "60.0 ... 62.5", passed: false)]
    )
    let encoder = JSONEncoder()
    encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
    try! encoder.encode(initialReport).write(to: reportURL)
    
    // Call the error report update function
    writeOrUpdateErrorReport(at: reportURL, classification: "product", errorDescription: "Top-level failure")
    
    let data = try! Data(contentsOf: reportURL)
    let updatedReport = try! JSONDecoder().decode(Report.self, from: data)
    precondition(!updatedReport.assertions.isEmpty, "Assertions should be preserved, not overwritten with []")
    precondition(updatedReport.assertions.count == 1, "Expected 1 assertion preserved, got \(updatedReport.assertions.count)")
    precondition(updatedReport.classification == "product")
    precondition(updatedReport.error == "Top-level failure")
    print("  [PASS] Preserved \(updatedReport.assertions.count) assertions in report.json")
    fflush(stdout)
}

func runDeinterleavedAudioBufferListSelfTest() {
    print("[SELF-TEST] Testing deinterleaved AudioBufferList and idempotent cleanup...")
    fflush(stdout)
    
    let capture = ProcessAudioCapture()
    
    // Allocate a 2-buffer AudioBufferList (deinterleaved stereo, 3 frames)
    // Left: [1.0, 2.0, 3.0], Right: [4.0, 5.0, 6.0]
    var leftChannel: [Float] = [1.0, 2.0, 3.0]
    var rightChannel: [Float] = [4.0, 5.0, 6.0]
    
    let ablSize = MemoryLayout<AudioBufferList>.size + MemoryLayout<AudioBuffer>.size
    let rawMem = UnsafeMutableRawPointer.allocate(byteCount: ablSize, alignment: MemoryLayout<AudioBufferList>.alignment)
    defer { rawMem.deallocate() }
    
    let ablPtr = rawMem.bindMemory(to: AudioBufferList.self, capacity: 1)
    ablPtr.pointee.mNumberBuffers = 2
    
    leftChannel.withUnsafeMutableBufferPointer { leftBuf in
        rightChannel.withUnsafeMutableBufferPointer { rightBuf in
            let abl = UnsafeMutableAudioBufferListPointer(ablPtr)
            abl[0] = AudioBuffer(mNumberChannels: 1, mDataByteSize: UInt32(3 * MemoryLayout<Float>.size), mData: leftBuf.baseAddress)
            abl[1] = AudioBuffer(mNumberChannels: 1, mDataByteSize: UInt32(3 * MemoryLayout<Float>.size), mData: rightBuf.baseAddress)
            
            capture.processInput(inputData: UnsafePointer(ablPtr))
        }
    }
    
    let (samples, _, _) = capture.getCapturedSamples()
    let expected: [Float] = [1.0, 4.0, 2.0, 5.0, 3.0, 6.0]
    precondition(samples == expected, "Deinterleaved stereo was not properly interleaved: got \(samples), expected \(expected)")
    print("  [PASS] Deinterleaved AudioBufferList successfully interleaved without out-of-bounds access")
    fflush(stdout)
    
    // Test idempotent stopAndCleanup
    capture.stopAndCleanup()
    capture.stopAndCleanup() // safe duplicate call
    print("  [PASS] stopAndCleanup verified idempotent across multiple invocations")
    fflush(stdout)
}

func isTimeoutExceeded(startTime: Date, timeoutSeconds: TimeInterval, currentTime: Date = Date()) -> Bool {
    return currentTime.timeIntervalSince(startTime) >= timeoutSeconds
}

func runSeparateTimeoutAnchorsSelfTest() {
    print("[SELF-TEST] Testing separate timeout anchors helper...")
    fflush(stdout)
    
    let t0 = Date(timeIntervalSince1970: 1000)
    let pidFoundTime = Date(timeIntervalSince1970: 1020) // 20s later PID arrives
    
    // If scenario monitoring used t0, only 10s would remain
    let wrongRemaining = 30.0 - pidFoundTime.timeIntervalSince(t0)
    precondition(wrongRemaining == 10.0, "Old shared anchor drained scenario budget")
    
    // With separate scenario start anchor at pidFoundTime:
    let scenarioStart = pidFoundTime
    let scenarioCheckTime = Date(timeIntervalSince1970: 1045) // 25s into scenario
    
    precondition(!isTimeoutExceeded(startTime: scenarioStart, timeoutSeconds: 30.0, currentTime: scenarioCheckTime),
                 "Scenario should not time out at 25s under separate anchor")
    let scenarioTimeoutTime = Date(timeIntervalSince1970: 1051) // 31s into scenario
    precondition(isTimeoutExceeded(startTime: scenarioStart, timeoutSeconds: 30.0, currentTime: scenarioTimeoutTime),
                 "Scenario should time out after 30s under separate anchor")
    print("  [PASS] Separate 30-second anchors verified")
    fflush(stdout)
}

func runAllSelfTests() {
    print("=== STARTING AUDIO-TEST SELF-TEST ===")
    fflush(stdout)
    runDspSelfTests()
    runEventParsingSelfTests()
    runReportSelfTests()
    runReportPreservationSelfTest()
    runDeinterleavedAudioBufferListSelfTest()
    runSeparateTimeoutAnchorsSelfTest()
    print("=== ALL SELF-TESTS PASSED ===")
    fflush(stdout)
}

// MARK: - Live Capture & Analysis Mode

func runLiveCaptureAndAnalysis(eventsPath: String, outputDir: String, assetsDir: String) throws {
    let eventsURL = URL(fileURLWithPath: eventsPath)
    let outputURL = URL(fileURLWithPath: outputDir)
    let assetsURL = URL(fileURLWithPath: assetsDir)
    
    let fm = FileManager.default
    try fm.createDirectory(at: outputURL, withIntermediateDirectories: true)
    
    print("[HARNESS] Waiting for Java PID in: \(eventsPath)")
    fflush(stdout)
    
    var targetPID: pid_t?
    var events: [PhaseEvent] = []
    let pidTimeoutSeconds: TimeInterval = 30.0
    let pidWaitStart = Date()
    
    while !isTimeoutExceeded(startTime: pidWaitStart, timeoutSeconds: pidTimeoutSeconds) {
        if fm.fileExists(atPath: eventsURL.path),
           let content = try? String(contentsOf: eventsURL, encoding: .utf8) {
            let parsed = parsePhaseEvents(from: content)
            if let firstWithPid = parsed.first(where: { $0.pid != nil }), let pid = firstWithPid.pid {
                targetPID = pid_t(pid)
                events = parsed
                break
            }
        }
        Thread.sleep(forTimeInterval: 0.1)
    }
    
    guard let pid = targetPID else {
        throw HarnessError.infrastructure("Timeout waiting for Java PID in \(eventsPath) after \(pidTimeoutSeconds)s")
    }
    
    print("[HARNESS] Found Java PID \(pid). Initializing CoreAudio process tap...")
    fflush(stdout)
    
    let capture = ProcessAudioCapture()
    try capture.start(targetPID: pid)
    print("[HARNESS] Process tap active on aggregate device at \(capture.sampleRate) Hz. Capturing...")
    fflush(stdout)
    
    // Clean up tap & aggregate device regardless of how this function exits (idempotent)
    defer {
        capture.stopAndCleanup()
    }
    
    // Monitor events until FINISH, FAILED, or timeout (separate 30-second anchor)
    let scenarioStart = Date()
    let scenarioTimeoutSeconds: TimeInterval = 30.0
    var finished = false
    var failureError: String?
    while !isTimeoutExceeded(startTime: scenarioStart, timeoutSeconds: scenarioTimeoutSeconds) {
        if let content = try? String(contentsOf: eventsURL, encoding: .utf8) {
            events = parsePhaseEvents(from: content)
            if let failEvent = events.first(where: { $0.phase.uppercased().contains("FAIL") || $0.success == false }) {
                failureError = failEvent.error ?? "In-game driver reported phase failure: \(failEvent.phase)"
                break
            }
            if events.contains(where: { $0.phase.uppercased().contains("FINISH") }) {
                finished = true
                break
            }
        }
        Thread.sleep(forTimeInterval: 0.1)
    }
    
    if let failure = failureError {
        throw HarnessError.product("Client driver reported failure: \(failure)")
    }
    
    guard finished else {
        let lastPhase = events.last?.phase ?? "none"
        throw HarnessError.infrastructure("Global timeout (30s) waiting for driver FINISH event. Last phase: \(lastPhase)")
    }
    
    // Settle for 1.5s to allow final buffer delivery
    Thread.sleep(forTimeInterval: 1.5)
    
    let (stereoSamples, captureStartEpochMs, sampleRate) = capture.getCapturedSamples()
    // Explicit early stop/cleanup of CoreAudio capture before file writing and DSP analysis
    capture.stopAndCleanup()
    print("[HARNESS] Capture finished and device stopped. Captured \(stereoSamples.count / 2) stereo frames (\(String(format: "%.2f", Double(stereoSamples.count / 2) / sampleRate))s).")
    fflush(stdout)
    
    // Write capture.wav
    let wavURL = outputURL.appendingPathComponent("capture.wav")
    try writeWavFile(url: wavURL, interleavedSamples: stereoSamples, sampleRate: sampleRate)
    print("[HARNESS] Saved capture WAV to: \(wavURL.path)")
    fflush(stdout)
    
    // Resolve and decode Loom reference tracks
    print("[HARNESS] Resolving Minecraft reference tracks from assets: \(assetsDir)...")
    fflush(stdout)
    let swedenURL = try resolveAssetPath(for: "minecraft/sounds/music/game/sweden.ogg", in: assetsURL)
    let wetHandsURL = try resolveAssetPath(for: "minecraft/sounds/music/game/wet_hands.ogg", in: assetsURL)
    
    print("[HARNESS] Decoding and resampling Sweden from: \(swedenURL.path)")
    fflush(stdout)
    let swedenMono = try decodeAndResampleOGG(url: swedenURL, targetSampleRate: sampleRate)
    
    print("[HARNESS] Decoding and resampling Wet Hands from: \(wetHandsURL.path)")
    fflush(stdout)
    let wetHandsMono = try decodeAndResampleOGG(url: wetHandsURL, targetSampleRate: sampleRate)
    
    // Evaluate assertions
    print("[HARNESS] Evaluating product audio assertions...")
    fflush(stdout)
    let reporter = TestReporter()
    try runCaptureAssertions(
        events: events,
        stereoSamples: stereoSamples,
        captureStartEpochMs: captureStartEpochMs,
        sampleRate: sampleRate,
        swedenMono: swedenMono,
        wetHandsMono: wetHandsMono,
        reporter: reporter
    )
    
    let reportURL = outputURL.appendingPathComponent("report.json")
    let classification = reporter.allPassed ? "success" : "product"
    try reporter.writeReport(to: reportURL, classification: classification)
    print("[HARNESS] Wrote test report to: \(reportURL.path)")
    fflush(stdout)
    
    if !reporter.allPassed {
        throw HarnessError.product("One or more product audio assertions failed")
    }
    
    print("[HARNESS] ALL CLIENT AUDIO ASSERTIONS PASSED!")
    fflush(stdout)
}

// MARK: - Main Entry Point

let rawArgs = CommandLine.arguments
var isSelfTest = false
var eventsPath: String?
var outputDir: String?
var assetsDir: String?

var idx = 1
while idx < rawArgs.count {
    let arg = rawArgs[idx]
    if arg == "--self-test" {
        isSelfTest = true
        idx += 1
    } else if arg == "--events" && idx + 1 < rawArgs.count {
        eventsPath = rawArgs[idx + 1]
        idx += 2
    } else if arg == "--output" && idx + 1 < rawArgs.count {
        outputDir = rawArgs[idx + 1]
        idx += 2
    } else if arg == "--assets" && idx + 1 < rawArgs.count {
        assetsDir = rawArgs[idx + 1]
        idx += 2
    } else {
        fputs("Error: Invalid or unhandled command line argument: \(arg)\n", stderr)
        fputs("Usage:\n  audio-test --self-test\n  audio-test --events <path> --output <dir> --assets <dir>\n", stderr)
        exit(2)
    }
}

if isSelfTest {
    runAllSelfTests()
    exit(0)
} else if let evPath = eventsPath, let outDir = outputDir, let asDir = assetsDir {
    do {
        try runLiveCaptureAndAnalysis(eventsPath: evPath, outputDir: outDir, assetsDir: asDir)
        exit(0)
    } catch let err as HarnessError {
        fputs("\(err.description)\n", stderr)
        let outputURL = URL(fileURLWithPath: outDir)
        let reportURL = outputURL.appendingPathComponent("report.json")
        writeOrUpdateErrorReport(at: reportURL, classification: err.classification, errorDescription: err.description)
        exit(1)
    } catch {
        fputs("[INFRASTRUCTURE ERROR] Unexpected error: \(error)\n", stderr)
        let outputURL = URL(fileURLWithPath: outDir)
        let reportURL = outputURL.appendingPathComponent("report.json")
        writeOrUpdateErrorReport(at: reportURL, classification: "infrastructure", errorDescription: "\(error)")
        exit(1)
    }
} else {
    fputs("Error: Missing required arguments.\n", stderr)
    fputs("Usage:\n  audio-test --self-test\n  audio-test --events <path> --output <dir> --assets <dir>\n", stderr)
    exit(2)
}
