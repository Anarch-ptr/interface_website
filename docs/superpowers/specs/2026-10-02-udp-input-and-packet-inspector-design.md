# FilamentVision UDP Input 与 Packet Inspector 设计

日期：2026-10-02
状态：待用户审核
范围：`webiste2.0` Backend/Core adapter、REST contract 与 Settings UI；不定义 Raspberry Pi 之外的 RTP/TCP/Bluetooth 协议。

## 1. 目标与非目标

本阶段让 Backend 真正接收 Camera A、Camera B 的 UDP 输入，并让 Settings 中保存和应用的配置实际控制 socket、transport parser、frame reassembly、application framing 与 payload decoder。支持五种 payload：`GRAY8`、`BINARY`、`JPEG`、`RGB888`、`DIAMETER`。

本阶段同时提供旁路 Packet Inspector，读取 Backend 实际收到的 Datagram。Inspector 不参与正常处理链，不写数据库，不保留历史，也不能影响 measurement、alarm、session 或 preview。

本阶段不实现 RTP、TCP、Bluetooth、自动协议猜测，且不允许通过来源顺序猜测 UDP 分片。

## 2. 模块边界

正常路径：

```text
UdpReceiver
  -> TransportDatagramParser
  -> Datagram-scope ApplicationFramingParser
  -> FrameReassembler
       |- SingleDatagramFrameReassembler
       `- StructuredChunkFrameReassembler
  -> Frame-scope ApplicationFramingParser
  -> PayloadDecoder
       |- Gray8Decoder
       |- BinaryDecoder
       |- JpegDecoder
       |- Rgb888Decoder
       `- DiameterDecoder
  -> CameraInput(ImageFrameInput | DirectDiameterInput)
  -> Existing Vision / Fusion / Alarm / Session pipeline
```

`headerScope/footerScope=DATAGRAM` 在 transport payload 进入 reassembler 前执行；`FRAME` 在完整 Frame 重组后执行。两处使用同一个 `ApplicationFramingParser` 及同一配置模型，只是调用边界不同。Structured Transport Header 永远由 `TransportDatagramParser` 解析，不能被 Settings 中的 Application Header/Footer 覆盖。

Packet Inspector 仅旁路观察：

```text
UdpReceiver ---------------------> PacketInspectorTap (raw datagram)
Transport/Application parsers ---> PacketInspectorTap (parsed metadata)
FrameReassembler ----------------> PacketInspectorTap (completed frame metadata)
PayloadDecoder ------------------> PacketInspectorTap (decode result)
```

任何 Inspector 捕获、格式化或 API 错误都被隔离，不回传到正常路径。

`CameraInput` 是纯 Kotlin Core 边界，不包含 UDP、JPEG、Ktor 或 SQLite 类型。`ImageFrameInput` 继续进入现有 `VisionProcessor`；`DirectDiameterInput` 转换为有效的 `CameraMeasurement`（`pixelWidth=0`、`confidence=1.0`），随后进入同一 A/B pairing 与 fusion 路径。现有 image source 通过兼容 adapter 接入，因此 characterization/golden tests 继续作为行为基线。`MonitoringRuntime` 仍然只拥有一个 producer reservation；Camera A/B socket 是这一个 source lifecycle 的子资源，不分别计作 monitoring producer。

## 3. `UdpInputProfile`

每个 Camera 拥有独立、版本化的 profile：

```text
UdpInputProfile
  cameraId: A | B
  enabled: Boolean
  sourceIp: optional IPv4/IPv6 literal
  listenPort: 1..65535
  transportMode: SINGLE_DATAGRAM | STRUCTURED_CHUNKED
  payload: PayloadProfile
  applicationFraming: ApplicationFramingProfile
  transportLimits: TransportLimits
```

两个 enabled profile 的 listen port 第一版必须不同；避免两个 receiver 争用同一 socket。`sourceIp` 为空表示接受任意来源，否则在进入 parser 前严格过滤。Backend 默认绑定 `0.0.0.0`，不把 bind address 暴露为本阶段设置。

配置分为 saved revision 与 applied revision。保存写入 Repository Adapter；应用将指定 revision 原子地编译为 immutable runtime profile。无效 profile 永远不能到达 receiver。

## 4. Transport Mode

### 4.1 `SINGLE_DATAGRAM`

一个 Datagram 产生一个候选 Frame/Sample，由 `SingleDatagramFrameReassembler` 直接完成。Datagram 大小仍受 UDP 有效载荷限制。此模式推荐给 `DIAMETER` 和小型 payload，但 format 不强制 transport mode。

### 4.2 `STRUCTURED_CHUNKED`

每个 Datagram 必须以固定 Structured Transport Header 开头。所有多字节整数使用 Network Byte Order（Big Endian）。第一版 header 固定为 34 bytes：

| Offset | Size | Field | Rule |
|---:|---:|---|---|
| 0 | 4 | magic | ASCII `FVU1` (`46 56 55 31`) |
| 4 | 1 | protocolVersion | 第一版必须为 `1` |
| 5 | 1 | headerLength | 第一版必须为 `34`；为扩展预留 |
| 6 | 1 | cameraId | `1=A`, `2=B` |
| 7 | 1 | flags | 第一版必须为 `0` |
| 8 | 8 | frameId | unsigned monotonic identifier |
| 16 | 2 | chunkIndex | zero-based，必须 `< chunkCount` |
| 18 | 2 | chunkCount | `1..configuredMaxChunkCount` |
| 20 | 8 | timestamp | Unix epoch microseconds |
| 28 | 2 | payloadLength | 当前 chunk payload bytes |
| 30 | 4 | framePayloadBytes | 重组后的 application-framed bytes |

实际 Datagram 长度必须严格等于 `headerLength + payloadLength`。Header cameraId 必须匹配 receiver profile。`framePayloadBytes`、`chunkCount`、`payloadLength` 必须先通过配置和 hard limit 校验，之后才允许建立 reassembly state。

Frame key 为 `(cameraId, source endpoint, frameId)`。chunk 可以乱序到达。重复且内容相同的 chunk 被忽略并计数；相同 index 但内容不同会丢弃整个 frame。完成时，唯一 chunk 的 payload 按 `chunkIndex` 拼接，长度必须等于 `framePayloadBytes`。

## 5. Reassembly 安全边界

每个 Camera 独立限制：

- `maxInFlightFrames`: 默认 4，范围 1..32。
- `maxFrameBytes`: 默认 8 MiB，hard max 32 MiB。
- `frameTimeoutMs`: 默认 1000，范围 100..10000。
- `maxChunkCount`: 默认 4096，hard max 8192。
- `maxChunkPayloadBytes`: 默认 1400，范围 1..60000。
- `maxBufferedBytesPerCamera`: hard max 64 MiB；实际预算取配置计算值与 hard max 的较小值。

实现不得依据外部声明直接分配 `framePayloadBytes` 大小的连续 buffer。先保存通过上限校验的 bounded chunks，完成时再进行一次受限合并。达到 in-flight 或 byte budget 时，按 oldest-incomplete 策略丢弃并记录原因。超时 frame 释放全部 chunk；使用 bounded tombstone 记录近期超时 frameId，以识别 late chunk，不建立无限集合。

## 6. Application Header/Footer

```text
ApplicationFramingProfile
  headerEnabled
  headerFormat: HEX | ASCII
  headerBytes
  headerScope: DATAGRAM | FRAME
  stripHeader
  footerEnabled
  footerFormat: HEX | ASCII
  footerBytes
  footerScope: DATAGRAM | FRAME
  stripFooter
```

HEX 接受由空格分隔或连续的偶数个十六进制字符，显示时规范化为大写空格格式。`AA GG 01` 非法。ASCII 使用 UTF-8 的 ASCII 子集（U+0000..U+007F），不接受非 ASCII 字符。每个 header/footer 最大 64 bytes。

Enabled 时必须匹配；不匹配的 Datagram/Frame 被丢弃，同时 Inspector 仍保留 raw preview。`strip=false` 表示校验但保留这些 bytes 给后续阶段；因此 payload size validation 看到的是实际传给 decoder 的 bytes。DATAGRAM scope 在每个 structured chunk 的 transport payload 上执行，FRAME scope 在完整 frame 上执行。SINGLE_DATAGRAM 仍保持两个明确调用阶段，以维持一致语义。

## 7. Payload Decoder

Decoder 不知道 transport mode，只接收已经完成 transport、reassembly 和 application framing 的 payload。

### `GRAY8`

需要 `width`、`height`。预期长度严格为 `width * height`，每像素 1 byte，并转换为现有 image frame model。

### `BINARY`

需要 `width`、`height`、`binaryPacking`：

- `BYTE_PER_PIXEL`: 严格 `width * height` bytes；0 为黑，非 0 为白。
- `BIT_PACKED`: 严格 `ceil(width * height / 8)` bytes；每 byte 从 MSB 到 LSB，最后 byte 的 padding bits 忽略。

输出为 0/255 的 grayscale image frame。

### `RGB888`

需要 `width`、`height`、`channelOrder=RGB|BGR`。长度严格为 `width * height * 3`，转换为内部统一 image frame。

### `JPEG`

需要 `maxFrameBytes`，可选 `expectedWidth/expectedHeight`。先进行 bounded JPEG decode，再从图像读取实际尺寸；可选尺寸不匹配时拒绝。Inspector 复用 decoder 得到的 metadata，不重复完整 decode。

### `DIAMETER`

```text
valueEncoding: FLOAT32 | FLOAT64 | INT16 | INT32 | ASCII
byteOrder: BIG_ENDIAN | LITTLE_ENDIAN
scale: finite number
offset: finite number
unit: MM | MICROMETER | INCH
```

Binary encoding 要求精确 byte length；ASCII 限制为 128 bytes，trim 后必须是有限十进制/科学计数数值。先计算 `decoded * scale + offset`，再按 unit 归一化成 pipeline 使用的 millimetres。直接 diameter sample 转为对应 Camera 的 measurement evidence，默认 confidence 为 `1.0`；A/B 仍由现有 pairing/fusion 处理，原始 A/B 值继续保留。

## 8. Runtime Apply

Apply 是幂等命令并使用 revision/CAS：

- `enabled/sourceIp/listenPort/transportMode` 改变：安全重启该 Camera receiver 和它的 reassembly state。
- parser、decoder、framing 或 limit 改变：编译新的 immutable processor chain 并原子交换；旧 in-flight frames 被明确丢弃并计数，不把不同配置的 chunks 混为一帧。
- restart bind 失败：恢复上一 applied profile/receiver，返回可诊断错误；saved revision 保留但 applied revision 不前移。
- Camera A/B 各自拥有 socket job；重复 Apply 不创建重复 receiver。
- close/restart/异常路径都释放 socket、reassembly buffer 和 coroutine job。

Apply 可在 monitoring 期间执行；发生 receiver restart 时该 Camera 短暂进入 reconnecting/unavailable，Session 不被隐式结束。若现有 runtime 无法安全热交换，则实现必须先增加明确 input-router ownership，不能让 UI 或 route 直接拥有 receiver。

## 9. 诊断统计

每 Camera 使用 bounded、线程安全的 immutable snapshot：

- packets/datagrams received
- bytes received
- frames started/completed/dropped
- duplicate/out-of-order/missing/late chunks
- reassembly timeouts
- invalid transport headers
- invalid application headers/footers
- invalid payload size
- oversized frames
- decode failures
- last packet time
- last valid frame time

SINGLE_DATAGRAM 的 chunk-only counters 显示为“不适用”，而非伪造数值。

## 10. Packet Inspector

### 10.1 捕获模型

Camera A/B 各有独立 `PacketInspectorTap`。默认 disabled。Settings 打开某 Camera Inspector 时取得一个有期限的 inspection lease；关闭页面或显式 stop 时释放，服务端也在 lease 超时后自动关闭，避免浏览器异常退出造成永久开销。

启用时，每 Camera 只保存：

- 最新 Datagram 的最多 `previewLimit` bytes；默认 128，可请求 64/128/256，Backend hard max 512。
- Datagram metadata、transport parse metadata。
- 最新完成 Frame 的 application framing 和 decode metadata。

不保存完整 Datagram、完整 JPEG/RGB frame 或 packet history。接收线程只做 bounded byte copy 和 immutable metadata replacement；Hex/ASCII formatting 在 REST 请求线程按需生成。

### 10.2 Datagram-level 与 Frame-level

Datagram view 显示 source/listen endpoint、receivedAt、datagram size、raw preview、Transport Header、chunk payload region、transport parse status，以及 structured fields：version、cameraId、frameId、chunk index/count、timestamp、payloadLength、framePayloadBytes。

Frame view 只在 reassembly 完成后更新，显示 Application Header、actual payload、Application Footer、payload format、format-specific size/value/JPEG metadata 与 decode result。Header/Footer mismatch 时 raw view 仍可用，并返回具体错误枚举。

### 10.3 Freeze/Resume 与 Copy

Freeze 是 UI 行为：前端保留当前 immutable snapshot DTO，Backend receiver 和 latest snapshot 继续更新。Resume 重新显示 Backend latest。Copy Hex 复制当前 bounded preview；Copy Debug Info 由当前 DTO 在前端生成，不再次下载 payload。

### 10.4 API

使用独立 REST debug contract，不复用 Preview WebSocket：

```text
POST   /api/v1/devices/{deviceId}/cameras/{cameraId}/packet-inspector/lease
DELETE /api/v1/devices/{deviceId}/cameras/{cameraId}/packet-inspector/lease/{leaseId}
GET    /api/v1/devices/{deviceId}/cameras/{cameraId}/packet-inspector?leaseId=...&previewBytes=128
GET    /api/v1/devices/{deviceId}/cameras/{cameraId}/udp-diagnostics
```

GET 由前端以 2 Hz 轮询；响应包含 snapshot sequence，未变化时可返回 `304`/ETag 或小型 unchanged response。API 从不返回超过 512 raw bytes。

## 11. Settings UI

Camera A/B 使用独立卡片和 draft。普通区域显示：Enabled、Source IP、Listen Port、Transport Mode、Payload Format、Width、Height，以及格式相关必要字段。

`Chunked UDP` 展开 Advanced Transport：protocol version（只读 v1）、recommended chunk payload、frame timeout、max frame bytes、max in-flight frames、max chunk count。`Advanced UDP Framing` 显示 Application Header/Footer，并明确标记它们不是 Transport Header。

字段按 payload format 条件显示：Binary packing、RGB channel order、JPEG max/expected dimensions、Diameter encoding/byte order/scale/offset/unit。UI 给出 transport recommendation，但不强制 format 与 transport mode绑定。

Save 持久化完整 A/B draft；Apply 调用 Backend 并显示 saved/applied revision、receiver restart 或 parser swap 结果。所有校验由前端提供即时反馈，同时 Backend 重新执行同等或更严格的 authoritative validation。

每 Camera 的 Inspect Packet 打开独立调试区域，提供 Raw Datagram/Parsed Frame、64/128/256 bytes、Live Inspect、Freeze/Resume、Copy Hex、Copy Debug Info。Header/Payload/Footer 和 Transport Header/Chunk Payload 使用视觉区域标识，但不改变 bytes。

## 12. 持久化与分层

`UdpInputProfile` 属于 Backend input adapter 配置，不进入纯 Vision Core，也不让 Core 依赖 socket、Ktor 或 SQLite。Application service 只依赖 `UdpInputProfileRepository` port；SQLite 实现位于 persistence adapter。REST DTO 与 domain profile 分离并做完整 mapping/validation。

Core 只接收统一的 image frame 或 direct diameter evidence。Transport/JPEG/packet API 不进入 `:core`。这保持未来替换 SQLite/PostgreSQL、UDP/RTP/TCP 时不修改 Vision algorithm。

## 13. 错误模型

至少包含：

```text
INVALID_TRANSPORT_HEADER
UNSUPPORTED_PROTOCOL_VERSION
CAMERA_ID_MISMATCH
INVALID_CHUNK_INDEX
INVALID_CHUNK_COUNT
INVALID_CHUNK_LENGTH
FRAME_INCOMPLETE
REASSEMBLY_TIMEOUT
DUPLICATE_CHUNK_CONFLICT
FRAME_TOO_LARGE
HEADER_MISMATCH
FOOTER_MISMATCH
PAYLOAD_TOO_SHORT
PAYLOAD_TOO_LARGE
INVALID_GRAY8_SIZE
INVALID_RGB888_SIZE
INVALID_BINARY_SIZE
JPEG_DECODE_FAILED
INVALID_DIAMETER_VALUE
UNSUPPORTED_FORMAT
MALFORMED_HEADER_CONFIG
UNKNOWN
```

外部数据错误只丢弃对应 Datagram/Frame 并计数；不能使 receiver job、Runtime 或 Backend process 崩溃。

## 14. TDD 与验证

实现顺序严格为：测试 RED、最小 GREEN、相关回归、diff review。至少覆盖用户列出的 decoder/framing/Inspector 案例，并增加：

- Structured Header Big Endian round-trip、错误 magic/version/camera/length。
- chunk 乱序、重复、冲突、缺失、交错 frame、late chunk、timeout。
- max in-flight、max bytes、max chunks、oversized declaration 不分配。
- DATAGRAM 与 FRAME scope 在两种 transport mode 下的明确边界。
- Apply parser-only 原子交换、receiver restart、bind rollback、重复 Apply 不重复 job。
- Camera A/B socket、reassembly、statistics、Inspector 完全隔离。
- Inspector disabled 时不生成 Hex 字符串且正常 pipeline 行为等价。
- REST preview 上限、lease expiry、Freeze/Resume 前端行为。

最终用真实 UDP sender 集成测试：合法 GRAY8 structured chunks（包含乱序）、错误 Application Header、DIAMETER single datagram；验证 Backend measurement/diagnostics 与 Settings Raw/Parsed Inspector。持续验证 reliable measurement/alarm/session event 不因 Inspector 或 preview 拥塞丢失。

## 15. 回滚边界

每层独立提交：profile/validation；transport parser；reassemblers；application framing；decoders；runtime ownership/apply；persistence/API；Inspector backend；Settings UI；integration gate。任何层失败可回滚到上一层，不修改已经通过的 Vision/Fusion golden baseline。Simulator 保留为测试 fixture，但生产 UDP path 不依赖它。
